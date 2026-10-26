import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: { proxy: { "/api": "http://backend:8080" } },
  preview: { allowedHosts: true },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/shared/testSetup.ts"],
    include: ["tests/**/*.test.{ts,tsx}", "src/**/*.test.{ts,tsx}"],
    restoreMocks: true,
  },
});
