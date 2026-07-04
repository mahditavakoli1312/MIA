import cors from "cors";
import express, { type NextFunction, type Request, type Response } from "express";
import { config, isGitHubConfigured } from "./config.js";
import { classifyIntent } from "./openrouter.js";
import {
  createIssueForTask,
  createRepoForProject,
  getAuthenticatedUser,
} from "./github.js";
import type { ProjectContext } from "./types.js";

const app = express();
app.use(express.json({ limit: "256kb" }));
app.use(cors({ origin: config.corsOrigin }));

app.get("/api/health", (_req, res) => {
  res.json({ ok: true });
});

/** Lets the client know whether to even offer GitHub side-effects. */
app.get("/api/config", (_req, res) => {
  res.json({ githubEnabled: isGitHubConfigured() });
});

/** Body: { candidates: string[], projects: {name, tasks}[] } -> { intent } */
app.post("/api/classify-intent", async (req, res, next) => {
  try {
    const candidates = asStringArray(req.body?.candidates);
    const projects = asProjectContext(req.body?.projects);
    const intent = await classifyIntent(candidates, projects);
    res.json({ intent });
  } catch (err) {
    next(err);
  }
});

/** Body: { projectName } -> { name, fullName, htmlUrl } */
app.post("/api/github/repo", async (req, res, next) => {
  try {
    requireGitHub();
    const projectName = asNonEmptyString(req.body?.projectName, "projectName");
    const repo = await createRepoForProject(projectName);
    res.json({ name: repo.name, fullName: repo.full_name, htmlUrl: repo.html_url });
  } catch (err) {
    next(err);
  }
});

/** Body: { projectName, taskTitle, dueDate } -> { number, htmlUrl } */
app.post("/api/github/issue", async (req, res, next) => {
  try {
    requireGitHub();
    const projectName = asNonEmptyString(req.body?.projectName, "projectName");
    const taskTitle = asNonEmptyString(req.body?.taskTitle, "taskTitle");
    const dueDate = typeof req.body?.dueDate === "string" ? req.body.dueDate : null;
    const owner = await getAuthenticatedUser();
    const issue = await createIssueForTask(owner, projectName, taskTitle, dueDate);
    res.json({ number: issue.number, htmlUrl: issue.html_url });
  } catch (err) {
    next(err);
  }
});

function requireGitHub(): void {
  if (!isGitHubConfigured()) {
    const err: ApiError = new Error("GitHub mirroring is disabled (no token configured on the server)");
    err.status = 503;
    throw err;
  }
}

// --- request helpers ---------------------------------------------------------

function asStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) throw badRequest("candidates must be an array of strings");
  const items = value.map((v) => (typeof v === "string" ? v.trim() : "")).filter((v) => v.length > 0);
  if (items.length === 0) throw badRequest("candidates must contain at least one non-empty string");
  // De-dup while preserving order, like the Android onResults path.
  return Array.from(new Set(items));
}

function asProjectContext(value: unknown): ProjectContext[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((p) => {
      if (!p || typeof p !== "object") return null;
      const obj = p as Record<string, unknown>;
      const name = typeof obj.name === "string" ? obj.name : "";
      const tasks = Array.isArray(obj.tasks) ? obj.tasks.filter((t): t is string => typeof t === "string") : [];
      return name ? { name, tasks } : null;
    })
    .filter((p): p is ProjectContext => p !== null);
}

function asNonEmptyString(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw badRequest(`${field} is required`);
  }
  return value.trim();
}

interface ApiError extends Error {
  status?: number;
}

function badRequest(message: string): ApiError {
  const err: ApiError = new Error(message);
  err.status = 400;
  return err;
}

// --- error handler -----------------------------------------------------------

app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
  const e = err as ApiError;
  const status = e?.status ?? 500;
  const message = e instanceof Error ? e.message : "Internal server error";
  if (status >= 500) console.error("[server error]", err);
  res.status(status).json({ error: message });
});

app.listen(config.port, () => {
  console.log(`MIA web proxy listening on http://localhost:${config.port}`);
  console.log(`  GitHub mirroring: ${isGitHubConfigured() ? "enabled" : "disabled"}`);
});
