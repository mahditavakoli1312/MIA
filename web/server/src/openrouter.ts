import { config } from "./config.js";
import { buildClassifierPrompt } from "./prompt.js";
import type { ProjectContext, VoiceCommandIntent } from "./types.js";

/**
 * Port of VoiceIntentClassifier.kt. Sends the Persian speech-recognition candidates
 * (plus the user's current projects/tasks as grounding context) to the OpenRouter-style
 * gateway and parses the model's strict-JSON reply into a VoiceCommandIntent.
 */
export async function classifyIntent(
  candidates: string[],
  projects: ProjectContext[] = [],
): Promise<VoiceCommandIntent> {
  if (candidates.length === 0) throw new Error("هیچ متنی برای تحلیل وجود ندارد");

  const userMessage =
    candidates.length === 1
      ? candidates[0]
      : [
          "Candidate transcriptions (most likely first):",
          ...candidates.map((c, i) => `${i + 1}. ${c}`),
        ].join("\n");

  const body = {
    model: config.openRouter.model,
    temperature: 0.0,
    max_tokens: 150,
    messages: [
      { role: "system", content: buildClassifierPrompt(new Date(), projects) },
      { role: "user", content: userMessage },
    ],
  };

  const res = await fetch(`${config.openRouter.baseUrl}/v1/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${config.openRouter.apiKey}`,
      // OpenRouter routing hints (harmless for gateways that ignore them).
      "HTTP-Referer": config.openRouter.httpReferer,
      "X-Title": config.openRouter.appTitle,
    },
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    const detail = await safeText(res);
    throw new Error(`مدل در دسترس نیست (${res.status}): ${detail}`);
  }

  const data = (await res.json()) as { choices?: { message?: { content?: string } }[] };
  const raw = data.choices?.[0]?.message?.content;
  if (!raw) throw new Error("پاسخ خالی از مدل دریافت شد");

  return parseIntent(raw);
}

// Defensive: some models wrap JSON in ```json fences despite the system prompt forbidding it.
function sanitize(raw: string): string {
  return raw
    .trim()
    .replace(/^```json\s*/i, "")
    .replace(/^```\s*/, "")
    .replace(/```\s*$/, "")
    .trim();
}

const ALLOWED_ACTIONS = new Set(["create_project", "delete_project", "add_task", "remove_task"]);

function parseIntent(raw: string): VoiceCommandIntent {
  const obj = JSON.parse(sanitize(raw)) as Partial<VoiceCommandIntent>;
  const actionType = obj.action_type;
  if (!actionType || !ALLOWED_ACTIONS.has(actionType)) {
    throw new Error("پاسخ مدل قابل تفسیر نیست");
  }
  const projectName = (obj.project_name ?? "").toString().trim();
  if (!projectName) throw new Error("نام پروژه مشخص نشده است");
  return {
    action_type: actionType,
    project_name: projectName,
    task_title: obj.task_title ?? null,
    due_date: obj.due_date ?? null,
  };
}

async function safeText(res: Response): Promise<string> {
  try {
    const text = await res.text();
    return text.length > 300 ? text.slice(0, 300) + "…" : text;
  } catch {
    return res.statusText;
  }
}
