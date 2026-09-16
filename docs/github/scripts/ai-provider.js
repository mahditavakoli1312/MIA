// .github/scripts/ai-provider.js
// Managed by MIA — kept byte-for-byte in sync with docs/github/scripts/.
//
// Everything the role scripts share about *talking to a model*: which service an AGENT_MODEL
// lives on, which secret to spend, how to walk down to a spare key when the first one is out of
// quota, and how to post the answer back onto an issue.
//
// Each role resolves its own model — see resolveProvider — so a repo can put its QC reviewer on
// a different model than its PO without splitting this file in two.
//
// It exists because there is now more than one caller. ai-role-review.js (the @po/@qc advisors)
// and decompose-brief.js (the PO decomposing a brief into TEC-sized issues) must agree on all of
// it: a repo pointed at MiniMax has to stay pointed at MiniMax for every role, and a rate-limited
// key has to fail over the same way everywhere. Two copies of this logic would drift.
//
// Nothing here reads the environment at import time — `resolveProvider(env)` does, so a caller
// can be tested with a fake environment.

const { charter, handoff: renderHandoff } = require("./agent-voice.js");
const { skillsFor } = require("./skills.js");

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
 * The provider, keys and model one ROLE should use, read out of `env`.
 *
 * The model id is passed to the provider's API verbatim — no provider prefix, unlike the TEC
 * workflow, which addresses models through OpenCode.
 *
 * `role` ("po", "qc", "brief", …) is what lets one repo run each member of its AI team on a
 * different model: the reviewer can sit on a big model while the brief decomposer stays on a
 * free one. It reads `AGENT_MODEL_<ROLE>` first and falls back to the repo-wide `AGENT_MODEL`,
 * so a repo bootstrapped before roles existed — and any caller that passes no role — behaves
 * exactly as it did when there was only one model. `AGENT_PROVIDER_<ROLE>` follows the same
 * ladder, and must always move with the model: it is what decides which key is spent and which
 * host is called.
 */
function resolveProvider(env, role) {
  const suffix = role ? `_${String(role).toUpperCase()}` : "";
  const id = env[`AGENT_PROVIDER${suffix}`] || env.AGENT_PROVIDER || "openrouter";
  const provider = PROVIDERS[id] || PROVIDERS.openrouter;
  const keys = provider.envKeys
    .map((name) => (env[name] || "").trim())
    .filter((key, i, all) => key && all.indexOf(key) === i);
  const model = env[`AGENT_MODEL${suffix}`] || env.AGENT_MODEL || DEFAULT_MODEL;
  return {
    id,
    role: role || null,
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
async function askAI(ai, { system, user, role }) {
  // The team charter goes in front of every job-specific prompt, for every role, here rather
  // than at the four call sites: this is the only line all of them pass through, so it is the
  // only place where "all four seats sound like the same team" can be true by construction.
  // The role comes from the resolved provider, which already knows which seat it is buying
  // tokens for; passing `role: null` explicitly is the opt-out, and nothing but a test uses it.
  const seatRole = role === undefined ? ai.role : role;
  // The skills this seat needs for THIS request, from the same single point and for the same
  // reason. Which ones those are depends on what the request is about, so it is chosen from the
  // user message — the issue, the comment or the diff — rather than from the role alone, and it
  // comes after the job-specific instructions because a procedure is how to do the job, not a
  // replacement for being told what the job is. Returns "" on a repo with no skills installed,
  // which is every repo bootstrapped before this file existed.
  const skills = seatRole ? skillsFor({ role: seatRole, text: user }) : "";
  const systemPrompt = [
    seatRole ? `${charter(seatRole)}\n\n---\n\n${system}` : system,
    skills,
  ]
    .filter(Boolean)
    .join("\n\n---\n\n");

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
          { role: "system", content: systemPrompt },
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

/**
 * Post text as a comment on one issue or PR, with the handoff footer appended.
 *
 * `handoff` is REQUIRED, and that is the entire design. Every role posts through this function,
 * so making the argument mandatory is what turns "always say who acts next" from a line in a
 * prompt — which a model may or may not honour — into something the code cannot skip. A role
 * that forgets it crashes its own run loudly, which is a bad afternoon; a role that silently
 * posts an ownerless comment strands an issue nobody ever looks at again, which is the failure
 * this whole system exists to prevent.
 *
 * Accepts the object form (see agent-voice.handoff) or an already-rendered block, so a caller
 * that has to build the footer early — to put it in a `details` block, say — can pass it on.
 */
async function postComment({ repo, issueNumber, token, body, handoff }) {
  if (!handoff) {
    throw new Error(
      `postComment on #${issueNumber} was called without a handoff. Every comment must name who ` +
        "acts next — pass { to, next } (see agent-voice.handoff)."
    );
  }
  const footer =
    typeof handoff === "string"
      ? handoff.includes("mia:handoff")
        ? handoff
        : (() => {
            throw new Error("A string handoff must be a rendered `mia:handoff` block.");
          })()
      : renderHandoff({ issue: issueNumber, ...handoff });

  const res = await fetch(
    `https://api.github.com/repos/${repo}/issues/${issueNumber}/comments`,
    {
      method: "POST",
      headers: {
        authorization: `Bearer ${token}`,
        accept: "application/vnd.github+json",
      },
      body: JSON.stringify({ body: `${body}${footer}` }),
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

/**
 * Starts a drain of TEC's queue, now, instead of waiting for the half-hourly timer.
 *
 * THE REASON THIS EXISTS IS A RULE OF GITHUB'S, NOT A DESIGN CHOICE OF OURS: a label applied
 * with GITHUB_TOKEN fires no workflow. So every one of these roles could queue an issue and
 * nothing whatsoever would happen —
 *
 *   • the PO decomposes a brief and labels five children `by-agent`;
 *   • the unblock sweep releases a dependent whose blockers all closed;
 *   • the PO writes a ready brief and auto-queues it;
 *   • a person answers a paused issue and it goes back in the queue.
 *
 * — and in every case the work sat still for up to thirty minutes with a comment on it saying
 * «@tec — شروع کن». That gap is what made a freshly planned brief look like a team that had
 * simply not turned up: the plan was right, the labels were right, and nothing was running.
 *
 * One API call closes it. The worker's `agent-worker` concurrency group makes it safe to call
 * from anywhere — a dispatch that arrives while a drain is in flight waits for it rather than
 * racing it, and the drain it starts rebuilds the queue from the labels, so an extra call is
 * never wrong, only redundant.
 *
 * Failure is not propagated. The timer is still there, and a role must not fail its own run
 * over an optimisation.
 */
async function startTecQueue({ repo, token, ref } = {}) {
  if (!repo || !token) return false;
  // A dispatch names a ref, and it must be the branch the workflow actually lives on. Every
  // workflow that runs these scripts already has the repository in its event payload, so
  // DEFAULT_BRANCH is set wherever it matters; GITHUB_REF_NAME is the runner's own fallback.
  const branch = ref || process.env.DEFAULT_BRANCH || process.env.GITHUB_REF_NAME || "main";
  try {
    const res = await fetch(
      `https://api.github.com/repos/${repo}/actions/workflows/agent-issue-worker.yml/dispatches`,
      {
        method: "POST",
        headers: {
          authorization: `Bearer ${token}`,
          accept: "application/vnd.github+json",
          "content-type": "application/json",
        },
        body: JSON.stringify({ ref: branch }),
      }
    );
    if (!res.ok) {
      console.error(`Could not start the TEC queue: HTTP ${res.status} (the timer will pick it up).`);
      return false;
    }
    console.log("Dispatched a TEC drain — the queued issue starts without waiting for the timer.");
    return true;
  } catch (err) {
    console.error(`Could not start the TEC queue: ${err.message} (the timer will pick it up).`);
    return false;
  }
}

module.exports = {
  PROVIDERS,
  DEFAULT_MODEL,
  resolveProvider,
  askAI,
  fmt,
  usageTotal,
  postComment,
  startTecQueue,
  missingKeyMessage,
};
