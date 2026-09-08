// .github/scripts/token-usage.js
// Managed by MIA — kept byte-for-byte in sync with docs/github/scripts/.
//
// Reports what the TEC agent spent, in tokens, on one issue — and posts it as a comment on that
// issue so every task carries its own cost next to the work it produced.
//
// Where the numbers come from, in order of preference:
//   1. OpenCode's `--format json` event stream (the log the workflow tee'd). Every assistant
//      message in it carries `tokens: {input, output, reasoning, cache:{read, write}}` and `cost`.
//   2. The OpenRouter credit counter (GET /api/v1/key → data.usage), sampled before and after the
//      run by the workflow and passed in as USAGE_BEFORE/USAGE_AFTER. That delta is the
//      authoritative dollar cost even when no tokens could be parsed — it is what OpenRouter
//      actually billed. On a free model (a `:free` id) it is legitimately $0.
//      Only OpenRouter has that endpoint: on any other provider the workflow skips the samples,
//      both values arrive empty, and source 1 is the only one — which is fine, because OpenCode
//      reports a per-message `cost` for priced providers.
//
// Nothing here is allowed to fail the job: a run that produced a working PR must not go red
// because accounting was unavailable. Every failure path degrades to a shorter report.

const fs = require("fs");

const logPath = process.env.OPENCODE_LOG || "";
const model = process.env.AGENT_MODEL || "unknown";
const providerId = process.env.AGENT_PROVIDER || "openrouter";
// Free tiers are spelled two ways on OpenRouter: a `:free` suffix, and the stealth models,
// which carry no suffix but still bill nothing. Nothing on a paid provider is ever free, so a
// reported $0 there means "not measured", not "cost nothing" — see buildReport.
const isFreeModel = (id) =>
  providerId === "openrouter" && (id.includes(":free") || id.includes("stealth/"));
const status = process.env.AGENT_STATUS || "ok";
const issueNumber = process.env.ISSUE_NUMBER;
const repo = process.env.REPO; // "owner/name"
const githubToken = process.env.GITHUB_TOKEN;
const runUrl = process.env.RUN_URL || "";
const reportFile = process.env.REPORT_FILE || "";
const trailerFile = process.env.TRAILER_FILE || "";

const num = (v) => (Number.isFinite(v) ? v : 0);
const fmt = (v) => num(v).toLocaleString("en-US");

// --- 1. Tokens out of the OpenCode JSON event stream -------------------------------------

/**
 * Walks any JSON value looking for assistant-message shapes that carry token counts, and
 * collects one entry per message. The walk is deliberately shape-tolerant: OpenCode wraps
 * messages in event envelopes that change between releases, so we match on the payload
 * (`tokens.input`/`tokens.output`, or an OpenAI-style `usage.prompt_tokens`) rather than on
 * any particular event name.
 *
 * Messages stream in as repeated updates of the same id, so entries are keyed by id and the
 * largest reading wins — counts only grow as a message completes.
 */
function collectMessages(value, found, seen) {
  if (!value || typeof value !== "object") return;
  if (seen.has(value)) return; // cyclic-safe
  seen.add(value);

  if (Array.isArray(value)) {
    for (const item of value) collectMessages(item, found, seen);
    return;
  }

  const usage = readTokens(value);
  if (usage) {
    // No id (some envelopes omit it) → fall back to a key stable per model+start time.
    const key = value.id || `${value.modelID || model}@${value.time?.created ?? found.size}`;
    const prev = found.get(key);
    if (!prev || usage.total >= prev.total) found.set(key, usage);
  }

  for (const item of Object.values(value)) collectMessages(item, found, seen);
}

/** Maps one object to normalized token counts, or null when it carries none. */
function readTokens(obj) {
  const t = obj.tokens;
  if (t && typeof t === "object" && (typeof t.input === "number" || typeof t.output === "number")) {
    const input = num(t.input);
    const output = num(t.output);
    const reasoning = num(t.reasoning);
    const cacheRead = num(t.cache?.read);
    const cacheWrite = num(t.cache?.write);
    return {
      input,
      output,
      reasoning,
      cacheRead,
      cacheWrite,
      cost: num(obj.cost),
      modelID: obj.modelID,
      total: num(t.total) || input + output + reasoning + cacheRead + cacheWrite,
    };
  }

  // OpenAI/OpenRouter shape, in case a raw provider payload shows up in the stream.
  const u = obj.usage;
  if (u && typeof u === "object" && typeof u.prompt_tokens === "number") {
    const input = num(u.prompt_tokens);
    const output = num(u.completion_tokens);
    const reasoning = num(u.completion_tokens_details?.reasoning_tokens);
    const cacheRead = num(u.prompt_tokens_details?.cached_tokens);
    return {
      input,
      output,
      reasoning,
      cacheRead,
      cacheWrite: 0,
      cost: num(u.cost),
      modelID: obj.model,
      total: num(u.total_tokens) || input + output,
    };
  }

  return null;
}

/**
 * Parses the run log. The common case is one JSON event per line, but the log is also allowed
 * to be a single pretty-printed document, so a brace-matching scan backs the line reader up
 * when it finds nothing.
 */
function parseLog(path) {
  const found = new Map();
  if (!path || !fs.existsSync(path)) return found;

  const text = fs.readFileSync(path, "utf8");
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) continue;
    try {
      collectMessages(JSON.parse(trimmed), found, new WeakSet());
    } catch {
      // Interleaved/partial line — the next one will do.
    }
  }
  if (found.size === 0) {
    for (const block of extractJsonBlocks(text)) {
      collectMessages(block, found, new WeakSet());
    }
  }
  return found;
}

/** Pulls out every balanced top-level {...} block, ignoring braces inside strings. */
function extractJsonBlocks(text) {
  const blocks = [];
  let depth = 0;
  let start = -1;
  let inString = false;
  let escaped = false;

  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === "\\") escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') inString = true;
    else if (ch === "{") {
      if (depth === 0) start = i;
      depth++;
    } else if (ch === "}" && depth > 0) {
      depth--;
      if (depth === 0 && start >= 0) {
        try {
          blocks.push(JSON.parse(text.slice(start, i + 1)));
        } catch {
          // Not valid JSON after all — skip it.
        }
        start = -1;
      }
    }
  }
  return blocks;
}

// --- 2. Cost from the OpenRouter credit counter ------------------------------------------

/** Credits spent between the workflow's two GET /api/v1/key samples, or null if unusable. */
function creditDelta() {
  const before = Number.parseFloat(process.env.USAGE_BEFORE || "");
  const after = Number.parseFloat(process.env.USAGE_AFTER || "");
  if (!Number.isFinite(before) || !Number.isFinite(after)) return null;
  // Clamp: a key shared with other runs can move in ways this run didn't cause.
  return Math.max(0, after - before);
}

// --- Report -------------------------------------------------------------------------------

function buildReport(totals, calls, cost, models) {
  const lines = [];
  const headline =
    status === "quota"
      ? `### 💸 Token spend for #${issueNumber} — stopped at the free-tier limit`
      : status === "error"
        ? `### 💸 Token spend for #${issueNumber} — run failed`
        : `### 💸 Token spend for #${issueNumber}`;
  lines.push(headline, "");

  if (calls === 0) {
    lines.push(
      "TEC could not read a per-token breakdown for this run (the agent runtime reported none).",
      "",
    );
  } else {
    lines.push("| | tokens |", "| --- | ---: |");
    lines.push(`| Prompt (input) | ${fmt(totals.input)} |`);
    if (totals.cacheRead) lines.push(`| Cached input (read) | ${fmt(totals.cacheRead)} |`);
    if (totals.cacheWrite) lines.push(`| Cache write | ${fmt(totals.cacheWrite)} |`);
    lines.push(`| Output | ${fmt(totals.output)} |`);
    if (totals.reasoning) lines.push(`| Thinking | ${fmt(totals.reasoning)} |`);
    lines.push(`| **Total** | **${fmt(totals.total)}** |`, "");
  }

  const modelLabel = models.size ? [...models].join(", ") : model;
  const parts = [`Model \`${modelLabel}\``];
  if (calls > 0) parts.push(`${calls} model ${calls === 1 ? "call" : "calls"}`);
  if (cost === null) {
    parts.push("cost not reported");
  } else if (cost === 0) {
    parts.push(
      isFreeModel(model)
        ? "cost **$0.00** (free model)"
        : providerId === "openrouter"
          ? "cost **$0.00**"
          : "cost not reported"
    );
  } else {
    parts.push(`cost **$${cost.toFixed(4)}**`);
  }
  lines.push(parts.join(" · "));

  if (runUrl) lines.push("", `[Workflow run](${runUrl})`);
  return lines.join("\n");
}

/** One-line `git commit` trailer, so the merged commit itself records what it cost. */
function buildTrailer(totals, calls, cost) {
  const tokens = calls > 0 ? `${fmt(totals.total)} tokens` : "tokens unavailable";
  const money = cost === null ? "" : ` ($${cost.toFixed(4)})`;
  return `Token-Spend: ${tokens}${money} via ${model}`;
}

async function postComment(text) {
  if (!repo || !issueNumber || !githubToken) return;
  const res = await fetch(
    `https://api.github.com/repos/${repo}/issues/${issueNumber}/comments`,
    {
      method: "POST",
      headers: {
        authorization: `Bearer ${githubToken}`,
        accept: "application/vnd.github+json",
      },
      body: JSON.stringify({ body: text }),
    },
  );
  if (!res.ok) console.error(`Could not post the spend comment: HTTP ${res.status}`);
}

async function main() {
  const messages = parseLog(logPath);

  const totals = { input: 0, output: 0, reasoning: 0, cacheRead: 0, cacheWrite: 0, total: 0 };
  const models = new Set();
  let messageCost = 0;
  for (const m of messages.values()) {
    totals.input += m.input;
    totals.output += m.output;
    totals.reasoning += m.reasoning;
    totals.cacheRead += m.cacheRead;
    totals.cacheWrite += m.cacheWrite;
    totals.total += m.total;
    messageCost += m.cost;
    if (m.modelID) models.add(m.modelID);
  }

  // The agent's own numbers when it charged something, else what OpenRouter actually billed.
  const delta = creditDelta();
  const cost = messageCost > 0 ? messageCost : delta;

  const report = buildReport(totals, messages.size, cost, models);
  console.log(report);

  if (reportFile) fs.writeFileSync(reportFile, report, "utf8");
  if (trailerFile) {
    fs.writeFileSync(trailerFile, buildTrailer(totals, messages.size, cost), "utf8");
  }
  if (process.env.GITHUB_STEP_SUMMARY) {
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${report}\n`, "utf8");
  }

  await postComment(report);
}

main().catch((err) => {
  // Accounting is never worth failing a run over.
  console.error(`Token accounting failed: ${err.message}`);
});
