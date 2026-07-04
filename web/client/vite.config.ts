import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The browser talks to our own proxy for /api/* (LLM + GitHub secrets live there,
// never in the client). Supabase REST/auth is called directly with the public anon
// key + the signed-in user's token, exactly like the Android app.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8787",
        changeOrigin: true,
      },
    },
  },
});
