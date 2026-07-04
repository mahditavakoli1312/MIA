import "dotenv/config";

function required(name: string): string {
  const value = process.env[name] ?? "";
  if (!value) {
    // Non-fatal: we log instead of crashing so the server still boots and the
    // health/config endpoints work. The route that actually needs the key will
    // return a clear 500 if it's missing.
    console.warn(`[config] env ${name} is not set — feature will fail until provided`);
  }
  return value;
}

export const config = {
  port: Number(process.env.PORT ?? 8787),
  corsOrigin: process.env.CORS_ORIGIN ?? "http://localhost:5173",
  openRouter: {
    // Same base URL the Android app uses (api.gapgpt.app is an OpenRouter-style gateway).
    baseUrl: "https://api.gapgpt.app",
    apiKey: required("GAPGPT_API_KEY"),
    model: "gpt-4o-mini",
    httpReferer: process.env.HTTP_REFERER ?? "http://localhost:5173",
    appTitle: process.env.APP_TITLE ?? "MIA Web",
  },
  github: {
    token: process.env.GITHUB_TOKEN ?? "",
  },
};

/** True only when a GitHub token is configured; callers skip mirroring otherwise. */
export const isGitHubConfigured = (): boolean => config.github.token.trim().length > 0;
