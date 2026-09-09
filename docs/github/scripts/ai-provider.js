// .github/scripts/ai-provider.js
// Managed by MIA — kept byte-for-byte in sync with docs/github/scripts/.
//
// Everything the role scripts share about *talking to a model*: which service an AGENT_MODEL
// lives on, which secret to spend, how to walk down to a spare key when the first one is out of
// quota, and how to post the answer back onto an issue.
//
// It exists because there is now more than one caller. ai-role-review.js (the @po/@qc advisors)
// and decompose-brief.js (the PO decomposing a brief into TEC-sized issues) must agree on all of
// it: a repo pointed at MiniMax has to stay pointed at MiniMax for every role, and a rate-limited
// key has to fail over the same way everywhere. Two copies of this logic would drift.
//
// Nothing here reads the environment at import time — `resolveProvider(env)` does, so a caller
// can be tested with a fake environment.

/** Everything that differs between the services an AGENT_MODEL can live on. */
const PROVIDERS = {
  openrouter: {
    label: "OpenRouter",
    // The keys a run may spend, primary first. The spare is only ever reached after the one
    // before it reports a limit — free OpenRouter keys are capped per day, and a spent key
    // would otherwise turn every mention into an error comment until the quota resets.
    envKeys: ["OPENROUTER_API_KEY", "OPENROUTER_API_KEY_FALLBACK"],
    url: "https://openrouter.ai/api/v1/chat/completions",
    // Full thinking effort — "max" is the top rung OpenRouter forwards, sent explicitly so a
    // change to the endpoint's own default can't quietly downgrade answers. OpenRouter drops
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

const DEFAULT_MODEL = "minimax/minimax-m3:free";

/**
 * The provider, keys and model this run should use, read out of `env`.
 *
 * The model id is passed to the provider's API verbatim — no provider prefix, unlike the TEC
 * workflow, which addresses models through OpenCode.
 */
function resolveProvider(env) {
  const id = env.AGENT_PROVIDER || "openrouter";
  const provider = PROVIDERS[id] || PROVIDERS.openrouter;
  const keys = provider.envKeys
    .map((name) => (env[name] || "").trim())
    .filter((key, i, all) => key && all.indexOf(key) === i);
  const model = env.AGENT_MODEL || DEFAULT_MODEL;
  return {
    id,
    label: provider.label,
    url: provider.url,
    reasoning: provider.reasoning,
    envKeys: provider.envKeys,
    keys,
    model,
    // Free tiers are spelled two ways on OpenRouter: a `:free` suffix, and the stealth models,
    // which carry no suffix but still bill nothing. Nothing on a paid provider is ever free.
    isFree: id === "openrouter" && (model.includes(":free") || model.includes("stealth/")),
  };
}

// 429 = rate limited for now, 402 = credit exhausted. Both mean "try the other key"; every
// other status would fail identically on the spare, so it is reported straight away.
const isOutOfQuota = (status) => status === 429 || status === 402;

/**
 * Asks `ai` (a resolveProvider result) one question, walking down its keys for as long as the
 * answer is "you are out of quota". Returns `{ text, usage }`.
 *
 * A thrown error carries `.quota = true` when every key was rate limited, so a caller can tell
 * "wait for the reset" apart from "this is broken" — the two need different advice on the issue.
 */
async function askAI(ai, { system, user }) {
  let lastError;
  for (const key of ai.keys) {
    const res = await fetch(ai.url, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${key}`,
      },
      body: JSON.stringify({
        model: ai.model,
        // Omitted entirely for providers that have no such block (see PROVIDERS).
        ...(ai.reasoning ? { reasoning: ai.reasoning } : {}),
        // No max_tokens on purpose. An effort level is a *share* of the output budget — "max"
        // is ~95% of it — so a cap would leave the answer almost nothing to be written in.
        // Brevity is asked for in the prompts instead.
        messages: [
          { role: "system", content: system },
          { role: "user", content: user },
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
      `${ai.label} error ${res.status}: ${JSON.stringify(data).slice(0, 500)}`
    );
    // An in-body failure is a real error, not a quota wall, so it is never worth another key.
    if (inBodyFailure || !isOutOfQuota(res.status)) throw lastError;
    lastError.quota = true;
    console.warn(`${ai.label} key rate limited (${res.status}) — trying the next key.`);
  }
  throw lastError || new Error("No API key configured.");
}

const fmt = (v) => (Number.isFinite(v) ? v : 0).toLocaleString("en-US");

/** Total tokens for one reply, whichever of the two shapes the provider reported. */
function usageTotal(usage) {
  if (!usage) return 0;
  return usage.total_tokens ?? (usage.prompt_tokens || 0) + (usage.completion_tokens || 0);
}

/** Post text as a comment on one issue or PR. */
async function postComment({ repo, issueNumber, token, body }) {
  const res = await fetch(
    `https://api.github.com/repos/${repo}/issues/${issueNumber}/comments`,
    {
      method: "POST",
      headers: {
        authorization: `Bearer ${token}`,
        accept: "application/vnd.github+json",
      },
      body: JSON.stringify({ body }),
    }
  );
  if (!res.ok) console.error(`Could not post a comment: HTTP ${res.status}`);
  return res.ok;
}

/**
 * What to say when no key is configured. Names the secret this repo's provider actually needs —
 * telling a MiniMax repo to add an OpenRouter key would send the reader off to fix the wrong
 * thing.
 */
function missingKeyMessage(ai) {
  return (
    `🔑 Could not run: this repo is set to ${ai.label} (\`AGENT_PROVIDER=${ai.id}\`) but no ` +
    `\`${ai.envKeys[0]}\` secret is set. Add it in the MIA app Settings, or in repo Settings → ` +
    "Secrets and variables → Actions" +
    (ai.id === "openrouter"
      ? " (optionally with an `OPENROUTER_API_KEY_FALLBACK` second key for when the first one is rate limited)."
      : ".")
  );
}

module.exports = {
  PROVIDERS,
  DEFAULT_MODEL,
  resolveProvider,
  askAI,
  fmt,
  usageTotal,
  postComment,
  missingKeyMessage,
};
