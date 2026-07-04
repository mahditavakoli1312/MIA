/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        // Vazirmatn is loaded via CDN in index.html, matching the Android app's font.
        vazir: ['"Vazirmatn"', "system-ui", "sans-serif"],
      },
      colors: {
        // Port of Color.kt — the neon-on-dark palette. Exposed as utilities
        // (bg-base, text-accent-cyan, etc.) and as CSS vars in index.css.
        base: {
          bg: "#0B0B0F",
          surface: "#15151C",
          surface2: "#1E1E27",
          border: "#2A2A36",
        },
        accent: {
          cyan: "#00E5FF",
          green: "#00FF9C",
          pink: "#FF2E97",
          purple: "#9D4EDD",
        },
        ink: {
          primary: "#F5F5F7",
          secondary: "#A0A0B0",
          muted: "#6B6B7B",
        },
      },
      boxShadow: {
        "neon-cyan": "0 0 20px rgba(0, 229, 255, 0.45), 0 0 40px rgba(0, 229, 255, 0.2)",
        "neon-green": "0 0 20px rgba(0, 255, 156, 0.5), 0 0 50px rgba(0, 255, 156, 0.25)",
        "neon-pink": "0 0 20px rgba(255, 46, 151, 0.45), 0 0 40px rgba(255, 46, 151, 0.2)",
      },
      keyframes: {
        "pulse-ring": {
          "0%": { transform: "scale(0.9)", opacity: "0.7" },
          "100%": { transform: "scale(1.8)", opacity: "0" },
        },
      },
      animation: {
        "pulse-ring": "pulse-ring 1.4s ease-out infinite",
      },
    },
  },
  plugins: [],
};
