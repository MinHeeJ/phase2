import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: { host: "0.0.0.0" },
  preview: { host: "0.0.0.0", allowedHosts: ["localhost", ".localhost"] },
  test: {
    environment: "jsdom",
    setupFiles: ["./tests/setup.ts"],
    exclude: ["**/node_modules/**", "**/browser-checks/**"],
    pool: "forks",
    poolOptions: { forks: { singleFork: true } },
  },
});
