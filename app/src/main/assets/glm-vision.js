#!/usr/bin/env node
// .github/scripts/glm-vision.js
// Managed by MIA — kept byte-for-byte in sync with docs/github/scripts/.
//
// The eyes and hands behind the three GLM skills in `.github/skills/`: reading an image, reading
// a document, and generating one. It is a thin, dependency-free port of the CLIs Z.ai ships with
// its GLM skills (github.com/zai-org/GLM-skills), which are Python and bring `requests`,
// `pillow` and a `requirements.txt` with them.
//
// Node rather than Python is not a preference. Every workflow in this repository already has Node
// 20 and no install step, and the whole point of a skill is that a role can reach for it mid-run:
// a skill whose first instruction is "pip install" is a skill that fails on the one run it was
// needed for. So this file speaks the same three APIs over plain `fetch`, takes the same kinds of
// argument, and prints JSON on stdout.
//
//   node .github/scripts/glm-vision.js describe <image|pdf|url> [...] --prompt "…"
//   node .github/scripts/glm-vision.js ocr      <image|pdf|url>
//   node .github/scripts/glm-vision.js image    --prompt "…" --save assets/hero.png
//
// It needs ZHIPU_API_KEY (bigmodel.cn). Without it the skills are never offered in the first
// place — skills.js drops any skill whose `requires:` key is unset — so reaching this file with
// no key means somebody called it by hand, and it says so in one line instead of stack-tracing.
//
// SECURITY: the endpoints are fixed constants and are deliberately not configurable. An agent
// that can be talked into posting your API key to a URL from an issue body is a credential leak
// with extra steps; the Z.ai CLIs make the same choice, for the same reason.

const fs = require("fs");
const path = require("path");

const CHAT_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
const OCR_URL = "https://open.bigmodel.cn/api/paas/v4/layout_parsing";
const IMAGE_URL = "https://open.bigmodel.cn/api/paas/v4/images/generations";

const VISION_MODEL = process.env.GLM_VISION_MODEL || "glm-5v-turbo";
const OCR_MODEL = process.env.GLM_OCR_MODEL || "glm-ocr";
const IMAGE_MODEL = process.env.GLM_IMAGE_MODEL || "glm-image";
const TIMEOUT_MS = Number.parseInt(process.env.GLM_TIMEOUT || "", 10) || 120000;

const MIME = {
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".gif": "image/gif",
  ".pdf": "application/pdf",
  ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  ".txt": "text/plain",
};

const isUrl = (value) => /^https?:\/\//i.test(String(value || "").trim());

/**
 * A local file as a `data:` URI, a URL untouched.
 *
 * The 5MB ceiling is the API's, and hitting it is worth a clear error rather than a 413 from a
 * server whose message is in a language the log reader may not have.
 */
function asSource(ref) {
  if (isUrl(ref)) return ref.trim();
  const file = path.resolve(ref);
  if (!fs.existsSync(file)) throw new Error(`file not found: ${ref}`);
  const bytes = fs.readFileSync(file);
  if (bytes.length > 5 * 1024 * 1024) {
    throw new Error(`${ref} is ${(bytes.length / 1048576).toFixed(1)}MB — the limit is 5MB`);
  }
  const mime = MIME[path.extname(file).toLowerCase()] || "application/octet-stream";
  return `data:${mime};base64,${bytes.toString("base64")}`;
}

function apiKey() {
  const key = String(process.env.ZHIPU_API_KEY || "").trim();
  if (!key) {
    throw new Error(
      "ZHIPU_API_KEY is not set. Add it as an Actions secret; get one at " +
        "https://bigmodel.cn/usercenter/proj-mgmt/apikeys"
    );
  }
  return key;
}

async function post(url, body) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${apiKey()}` },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const detail = data?.error?.message || JSON.stringify(data).slice(0, 300);
    throw new Error(`GLM ${res.status}: ${detail}`);
  }
  return data;
}

/**
 * What is in these images, videos or documents — the GLM-V multimodal call.
 *
 * `file_url` parts cannot be mixed with `image_url` parts in one request (the API rejects it), so
 * documents are sent in their own call and the answers are concatenated. That is a limit of the
 * service, not a design choice, and it is handled here so no skill has to explain it.
 */
async function describe(refs, prompt) {
  const images = refs.filter((r) => !/\.(pdf|docx|txt|xlsx|pptx)$/i.test(r));
  const files = refs.filter((r) => /\.(pdf|docx|txt|xlsx|pptx)$/i.test(r));
  const answers = [];
  let usage = null;

  for (const [kind, group] of [["image_url", images], ["file_url", files]]) {
    if (group.length === 0) continue;
    const content = group.map((ref) =>
      kind === "image_url"
        ? { type: "image_url", image_url: { url: asSource(ref) } }
        // Documents are URL-only on this endpoint; a local PDF has to be uploaded somewhere the
        // API can reach, and saying that is more useful than a 400 the caller has to decode.
        : { type: "file_url", file_url: { url: isUrl(ref) ? ref.trim() : fail(ref) } }
    );
    content.push({ type: "text", text: prompt });
    const data = await post(CHAT_URL, {
      model: VISION_MODEL,
      messages: [{ role: "user", content }],
      temperature: 0.2,
    });
    answers.push((data.choices?.[0]?.message?.content || "").trim());
    usage = data.usage || usage;
  }
  return { model: VISION_MODEL, text: answers.filter(Boolean).join("\n\n"), usage };
}

const fail = (ref) => {
  throw new Error(`documents must be URLs on this endpoint, not local paths: ${ref}`);
};

/** Text, tables and formulas out of one scan or PDF — the GLM-OCR layout parser. */
async function ocr(ref) {
  const data = await post(OCR_URL, { model: OCR_MODEL, file: asSource(ref) });
  // The parser has been served under two shapes; take whichever is present rather than
  // guessing, so an upgrade on their side does not read as an empty document on ours.
  const text =
    data?.content ||
    data?.result?.content ||
    data?.choices?.[0]?.message?.content ||
    JSON.stringify(data).slice(0, 4000);
  return { model: OCR_MODEL, text: String(text).trim(), usage: data.usage || null };
}

/** One generated image. Saved next to the repo when `--save` says where. */
async function image(prompt, { size = "1024x1024", save = "" } = {}) {
  const data = await post(IMAGE_URL, { model: IMAGE_MODEL, prompt, size });
  const url = data?.data?.[0]?.url || data?.image_url || "";
  if (!url) throw new Error(`no image in the answer: ${JSON.stringify(data).slice(0, 300)}`);
  let saved = "";
  if (save) {
    const res = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS) });
    if (!res.ok) throw new Error(`could not download the image: HTTP ${res.status}`);
    fs.mkdirSync(path.dirname(path.resolve(save)), { recursive: true });
    fs.writeFileSync(path.resolve(save), Buffer.from(await res.arrayBuffer()));
    saved = save;
  }
  return { model: IMAGE_MODEL, url, saved };
}

// --- CLI ------------------------------------------------------------------------------------

function parse(argv) {
  const opts = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const flag = /^--([\w-]+)$/.exec(argv[i]);
    if (!flag) {
      opts._.push(argv[i]);
    } else if (argv[i + 1] && !argv[i + 1].startsWith("--")) {
      opts[flag[1]] = argv[(i += 1)];
    } else {
      opts[flag[1]] = "true";
    }
  }
  return opts;
}

async function main() {
  const [verb, ...rest] = process.argv.slice(2);
  const opts = parse(rest);
  switch (verb) {
    case "describe":
      if (opts._.length === 0) throw new Error("describe needs at least one image, PDF or URL");
      return describe(opts._, opts.prompt || "Describe this in detail, in Persian.");
    case "ocr":
      if (opts._.length !== 1) throw new Error("ocr takes exactly one file or URL");
      return ocr(opts._[0]);
    case "image":
      if (!opts.prompt) throw new Error("image needs --prompt");
      return image(opts.prompt, { size: opts.size, save: opts.save });
    default:
      throw new Error(`unknown command "${verb || ""}" — use describe, ocr or image`);
  }
}

if (require.main === module) {
  main()
    .then((result) => console.log(JSON.stringify({ ok: true, ...result }, null, 2)))
    .catch((err) => {
      // JSON on both paths: the caller is a model reading stdout, and an error it can parse is
      // one it can report honestly instead of pretending the step succeeded.
      console.log(JSON.stringify({ ok: false, error: err.message }, null, 2));
      process.exitCode = 1;
    });
}

module.exports = { describe, ocr, image, asSource };
