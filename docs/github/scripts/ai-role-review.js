// .github/scripts/ai-role-review.js
// Managed by MIA — kept byte-for-byte in sync with docs/github/scripts/.
//
// The advisory roles. Detects which roles were tagged (@po / @qc), asks a FREE OpenRouter
// model to respond from each role's perspective (with the issue as context), then posts one
// reply comment. "@tec" is intentionally NOT handled here — the agent-issue-worker workflow
// picks that up and actually implements + merges a change.
//
// Every reply footers what it cost. OpenRouter returns `usage` (tokens + billed cost) on every
// response, so advice is accounted for on the issue exactly like TEC's implementation work is.

const commentBody = process.env.COMMENT_BODY || "";
const issueNumber = process.env.ISSUE_NUMBER;
const issueTitle = process.env.ISSUE_TITLE || "";
const issueBody = process.env.ISSUE_BODY || "";
const repo = process.env.REPO; // "owner/name"
const orKey = process.env.OPENROUTER_API_KEY;
const model = process.env.AGENT_MODEL || "openai/gpt-oss-20b:free";
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

// Ask the model for one role's response via OpenRouter (OpenAI-compatible API).
// Returns the answer plus what it cost — `usage` is always present in an OpenRouter reply.
async function askAI(system, userText) {
  const res = await fetch("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${orKey}`,
    },
    body: JSON.stringify({
      model,
      max_tokens: 800,
      messages: [
        { role: "system", content: system },
        { role: "user", content: userText },
      ],
    }),
  });

  const data = await res.json();
  if (!res.ok) {
    throw new Error(
      `OpenRouter error ${res.status}: ${JSON.stringify(data).slice(0, 500)}`
    );
  }
  return {
    text: (data.choices?.[0]?.message?.content || "").trim(),
    usage: data.usage || null,
  };
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
  if (!orKey) {
    await postComment(
      "🔑 The AI role bot could not run: no `OPENROUTER_API_KEY` secret is set. " +
        "Add it in repo Settings → Secrets and variables → Actions."
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
      : model.includes(":free")
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
