import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  preview: { allowedHosts: true },
  test: {
    environment: "jsdom",
    setupFiles: ["./tests/setup.ts"],
    include: [
      "tests/**/*.test.ts",
      "tests/**/*.test.tsx",
      "tests/e2e/routes.spec.ts",
    ],
  },
});
