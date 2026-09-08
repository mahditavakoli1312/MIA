// .github/scripts/ai-role-review.js
// Managed by MIA — kept byte-for-byte in sync with docs/github/scripts/.
//
// The advisory roles. Detects which roles were tagged (@po / @qc), asks the repo's AI-team model
// to respond from each role's perspective (with the issue as context), then posts one
// reply comment. "@tec" is intentionally NOT handled here — the agent-issue-worker workflow
// picks that up and actually implements + merges a change.
//
// The model may be served by OpenRouter (free tier, the default) or by MiniMax's own platform
// (a paid account, no shared daily cap) — AGENT_PROVIDER says which, and PROVIDERS below holds
// everything that differs between them.
//
// Every reply footers what it cost. Both providers return an OpenAI-shaped `usage` block on
// every response, so advice is accounted for on the issue exactly like TEC's work is.

const commentBody = process.env.COMMENT_BODY || "";
const issueNumber = process.env.ISSUE_NUMBER;
const issueTitle = process.env.ISSUE_TITLE || "";
const issueBody = process.env.ISSUE_BODY || "";
const repo = process.env.REPO; // "owner/name"

// Everything that differs between the services an AGENT_MODEL can live on. Kept as a table so
// the ask/report code below never branches on a provider name.
const PROVIDERS = {
  openrouter: {
    label: "OpenRouter",
    // The keys this run may spend, primary first. The spare is only ever reached after the one
    // before it reports a limit — free OpenRouter keys are capped per day, and a spent key
    // would otherwise turn every @po/@qc mention into an error comment until the quota resets.
    envKeys: ["OPENROUTER_API_KEY", "OPENROUTER_API_KEY_FALLBACK"],
    url: "https://openrouter.ai/api/v1/chat/completions",
    // Full thinking effort — "max" is the top rung OpenRouter forwards, sent explicitly so a
    // change to the endpoint's own default can't quietly downgrade reviews. OpenRouter drops
    // the field for models that don't reason, so an AGENT_MODEL override still works.
    reasoning: { effort: "max", exclude: true },
  },
  minimax: {
    label: "MiniMax",
    // A paid account, so there is no spare-key dance: one key, one attempt.
    envKeys: ["MINIMAX_API_KEY"],
    // Deliberately NOT the plain /v1/chat/completions path MiniMax also serves: that one
    // returns the model's thinking inline, wrapped in <think>…</think> at the top of `content`,
    // and this text is posted verbatim as a GitHub comment. chatcompletion_v2 puts it in a
    // separate `reasoning_content` field that nothing here reads.
    url: "https://api.minimax.io/v1/text/chatcompletion_v2",
    // MiniMax accepts an OpenRouter-style `reasoning` block but ignores it, and thinks by
    // default regardless — so sending one would be noise on every request.
    reasoning: null,
  },
};

const providerId = process.env.AGENT_PROVIDER || "openrouter";
const provider = PROVIDERS[providerId] || PROVIDERS.openrouter;
const keys = provider.envKeys
  .map((name) => (process.env[name] || "").trim())
  .filter((k, i, all) => k && all.indexOf(k) === i);
const model = process.env.AGENT_MODEL || "minimax/minimax-m3:free";
// Free tiers are spelled two ways on OpenRouter: a `:free` suffix, and the stealth models,
// which carry no suffix but still bill nothing. Nothing on a paid provider is ever free.
const isFreeModel = (id) =>
  providerId === "openrouter" && (id.includes(":free") || id.includes("stealth/"));
const githubToken = process.env.GITHUB_TOKEN;

// Each role gets its own "personality" (system prompt). Edit these freely.
const ROLES = {
  "@po": {
    label: "🧭 Product Owner (PO)",
    system:
      "You are a Product Owner. Respond focusing on user value, business priority, scope, " +
      "and crisp acceptance criteria. Call out anything ambiguous or out of scope. Be concise " +
      "and practical. If the reader wants this built, remind them to comment `@tec` so the TEC " +
      "agent implements and merges it.",
  },
  "@qc": {
    label: "✅ QC Team",
    system:
      "You are a QA/QC engineer. Respond focusing on test cases, acceptance criteria, regression " +
      "risks, and what could break. List concrete things to verify as a short checklist. Be concise.",
  },
};

const fmt = (v) => (Number.isFinite(v) ? v : 0).toLocaleString("en-US");

// 429 = rate limited for now, 402 = credit exhausted. Both mean "try the other key"; every
// other status would fail identically on the spare, so it is reported straight away.
const isOutOfQuota = (status) => status === 429 || status === 402;

// Ask the model for one role's response on whichever OpenAI-compatible endpoint `provider`
// names, walking down `keys` for as long as the answer is "you are out of quota".
// Returns the answer plus what it cost — both providers report `usage` on every reply.
async function askAI(system, userText) {
  let lastError;
  for (const key of keys) {
    const res = await fetch(provider.url, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${key}`,
      },
      body: JSON.stringify({
        model,
        // Omitted entirely for providers that have no such block (see PROVIDERS).
        ...(provider.reasoning ? { reasoning: provider.reasoning } : {}),
        // No max_tokens on purpose. An effort level is a *share* of the output budget — "max"
        // is ~95% of it — so the old 800-token cap would have left the reply about 40 tokens
        // to be written in. Brevity is asked for in the role prompts instead.
        messages: [
          { role: "system", content: system },
          { role: "user", content: userText },
        ],
      }),
    });

    const data = await res.json();
    // MiniMax can answer HTTP 200 and still have failed, reporting it in `base_resp`
    // (status_code 0 means success). OpenRouter never sets the field, so absent is fine.
    const baseResp = data?.base_resp;
    const inBodyFailure = baseResp && baseResp.status_code !== 0;
    if (res.ok && !inBodyFailure) {
      return {
        text: (data.choices?.[0]?.message?.content || "").trim(),
        usage: data.usage || null,
      };
    }
    lastError = new Error(
      `${provider.label} error ${res.status}: ${JSON.stringify(data).slice(0, 500)}`
    );
    // An in-body failure is a real error, not a quota wall, so it is never worth another key.
    if (inBodyFailure || !isOutOfQuota(res.status)) throw lastError;
    console.warn(
      `${provider.label} key rate limited (${res.status}) — trying the next key.`
    );
  }
  throw lastError;
}

// A per-role line: what this one answer cost, shown small so it doesn't crowd the advice.
function usageLine(usage) {
  if (!usage) return "";
  const total = usage.total_tokens ?? usage.prompt_tokens + usage.completion_tokens;
  return (
    `\n\n<sub>🧾 ${fmt(total)} tokens ` +
    `(prompt ${fmt(usage.prompt_tokens)} + output ${fmt(usage.completion_tokens)})</sub>`
  );
}

// Post the reply back onto the same issue/PR.
async function postComment(text) {
  await fetch(
    `https://api.github.com/repos/${repo}/issues/${issueNumber}/comments`,
    {
      method: "POST",
      headers: {
        authorization: `Bearer ${githubToken}`,
        accept: "application/vnd.github+json",
      },
      body: JSON.stringify({ body: text }),
    }
  );
}

async function main() {
  if (keys.length === 0) {
    // Name the secret this repo's provider actually needs — telling a MiniMax repo to add an
    // OpenRouter key would send the reader off to fix the wrong thing.
    await postComment(
      `🔑 The AI role bot could not run: this repo is set to ${provider.label} ` +
        `(\`AGENT_PROVIDER=${providerId}\`) but no \`${provider.envKeys[0]}\` secret is set. ` +
        "Add it in the MIA app Settings, or in repo Settings → Secrets and variables → Actions" +
        (providerId === "openrouter"
          ? " (optionally with an `OPENROUTER_API_KEY_FALLBACK` second key for when the first one is rate limited)."
          : ".")
    );
    process.exit(1);
  }

  const tagged = Object.keys(ROLES).filter((tag) => commentBody.includes(tag));
  if (tagged.length === 0) return;

  // Give the model the issue as grounding context, and ask it to match the comment's language
  // (the MIA project is Persian-first, so replies should follow whatever language was used).
  const context =
    `Repository: ${repo}\n` +
    `Issue #${issueNumber}: ${issueTitle}\n` +
    `Issue description:\n"""${issueBody}"""\n\n` +
    `A teammate just commented:\n"""${commentBody}"""\n\n` +
    `Reply from your role's perspective. Answer in the SAME language as the comment ` +
    `(Persian or English).`;

  const sections = [];
  const spend = { tokens: 0, cost: 0, calls: 0 };
  for (const tag of tagged) {
    const { label, system } = ROLES[tag];
    try {
      const { text, usage } = await askAI(system, context);
      sections.push(`### ${label}\n${text}${usageLine(usage)}`);
      if (usage) {
        spend.tokens +=
          usage.total_tokens ?? usage.prompt_tokens + usage.completion_tokens;
        spend.cost += usage.cost || 0;
        spend.calls += 1;
      }
    } catch (err) {
      // Free models are rate/quota capped — degrade cleanly instead of crashing.
      sections.push(`### ${label}\n⚠️ Could not get a response: ${err.message}`);
    }
  }

  const reply =
    "🤖 **AI role responses**\n\n" + sections.join("\n\n---\n\n") + spendFooter(spend);
  await postComment(reply);
}

// The bottom line for the whole reply, mirroring what TEC posts after implementing an issue,
// so one issue's comment thread reads as a single running ledger.
function spendFooter({ tokens, cost, calls }) {
  if (calls === 0) return "";
  const money =
    cost > 0
      ? `$${cost.toFixed(4)}`
      : isFreeModel(model)
        ? "$0.00 (free model)"
        : "$0.00";
  return (
    `\n\n---\n\n🧾 **Spend for this reply** — ${fmt(tokens)} tokens · ` +
    `${money} · \`${model}\``
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
