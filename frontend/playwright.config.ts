import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "..",
  testMatch: [
    "frontend/browser-checks/layout/**/*.pw.ts",
    "e2e/tests/**/*.spec.ts",
  ],
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL ?? "http://127.0.0.1:3000",
    timezoneId: "Asia/Seoul",
  },
});
