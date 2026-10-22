import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/e2e",
  testMatch: "**/*.pw.ts",
  use: { baseURL: process.env.BASE_URL },
  projects: [
    { name: "desktop", use: { viewport: { width: 1280, height: 900 } } },
    { name: "tablet", use: { viewport: { width: 1024, height: 900 } } },
    { name: "mobile", use: { viewport: { width: 375, height: 812 } } },
  ],
});
