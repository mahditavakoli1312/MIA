import { config, isGitHubConfigured } from "./config.js";
import type { IssueCreated, RepoCreated } from "./types.js";

/**
 * Port of GitHubRepository.kt. Mirrors project/task changes onto GitHub:
 * a project becomes a repository, a task becomes an issue in that repository.
 *
 * The repo name is derived deterministically from the project name (repoNameFor)
 * so adding a task later re-derives the same repo without storing a mapping.
 * Failures are surfaced as thrown errors but the caller treats them as best-effort.
 */

const API = "https://api.github.com";

function authHeaders(): Record<string, string> {
  return {
    Authorization: `Bearer ${config.github.token}`,
    Accept: "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
  };
}

/**
 * GitHub repo names may only contain ASCII letters, digits, '.', '-', '_'.
 * Latin project names become a readable slug; names with no usable ASCII
 * characters (e.g. fully-Persian names) fall back to a stable hash so the
 * result is still deterministic for later issue creation.
 *
 * NOTE: This must stay byte-for-byte compatible with GitHubRepository.repoNameFor
 * in the Android app, because the web client may add a task to a project whose
 * repo was created by the Android app (and vice-versa). See client/src/lib/github.ts
 * for the same function mirrored on the client side to re-derive the repo name.
 */
export function repoNameFor(projectName: string): string {
  const slug = projectName
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return slug || `mia-project-${stableHash(projectName)}`;
}

// Match String.hashCode() from the JVM (the Android app uses projectName.hashCode()).
// A 32-bit signed accumulation; result is the absolute value so it's a valid slug.
function stableHash(input: string): number {
  let hash = 0;
  for (let i = 0; i < input.length; i++) {
    hash = (Math.imul(31, hash) + input.charCodeAt(i)) | 0;
  }
  return Math.abs(hash);
}

export async function createRepoForProject(projectName: string): Promise<RepoCreated> {
  if (!isGitHubConfigured()) throw new Error("GitHub not configured");
  const res = await fetch(`${API}/user/repos`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify({
      name: repoNameFor(projectName),
      description: `Project «${projectName}» — managed by MIA`,
      private: true,
      auto_init: true, // create an initial commit so the repo can accept issues immediately
    }),
  });
  if (!res.ok) throw new Error(httpErrorMessage("create repo", res));
  const repo = (await res.json()) as { name: string; full_name: string; html_url: string; owner: { login: string } };
  // Cache owner implicitly via full_name extraction on the client side when needed.
  return { name: repo.name, full_name: repo.full_name, html_url: repo.html_url };
}

export async function createIssueForTask(
  owner: string,
  projectName: string,
  taskTitle: string,
  dueDate: string | null,
): Promise<IssueCreated> {
  if (!isGitHubConfigured()) throw new Error("GitHub not configured");
  const body = [`Task added via MIA for project «${projectName}».`];
  if (dueDate && dueDate.trim()) body.push(`\n\nDue date: ${dueDate}`);

  const res = await fetch(`${API}/repos/${owner}/${repoNameFor(projectName)}/issues`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify({ title: taskTitle, body: body.join("") }),
  });
  if (!res.ok) throw new Error(httpErrorMessage("create issue", res));
  const issue = (await res.json()) as { number: number; html_url: string };
  return { number: issue.number, html_url: issue.html_url };
}

/** Resolves the authenticated user's login, used as the owner for issue URLs. */
export async function getAuthenticatedUser(): Promise<string> {
  if (!isGitHubConfigured()) throw new Error("GitHub not configured");
  const res = await fetch(`${API}/user`, { headers: authHeaders() });
  if (!res.ok) throw new Error(httpErrorMessage("read user", res));
  const user = (await res.json()) as { login: string };
  return user.login;
}

async function httpErrorMessage(action: string, res: Response): Promise<string> {
  let detail = res.statusText;
  try {
    const data = await res.json();
    detail = (data && (data.message as string)) || detail;
  } catch {
    /* keep statusText */
  }
  return `GitHub ${action} failed (${res.status}): ${detail}`;
}
