import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: { "/api": "http://backend:8080" },
  },
  preview: {
    port: 4173,
    strictPort: true,
    allowedHosts: ["localhost", "127.0.0.1"],
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./tests/setup.ts"],
    include: ["tests/**/*.test.{ts,tsx}"],
    restoreMocks: true,
    env: { TZ: "Asia/Seoul" },
  },
});
