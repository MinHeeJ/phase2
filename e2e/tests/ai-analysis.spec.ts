import { test, expect } from "@playwright/test";

// Uses the running development app, its servlet session and real API handlers.
// Each browser context owns its conversation; no shared business rows are changed.
test("UI-26 structured simulation keeps displayed criteria for Excel and supports owned chat feedback/hide", async ({
  page,
}) => {
  const capabilities = page.waitForResponse(
    (r) => new URL(r.url()).pathname === "/api/ai/analysis/capabilities",
  );
  await page.goto("/UI-26");
  expect((await capabilities).status()).toBe(200);
  const screen = page.getByTestId("analysis-screen");
  await expect(screen).toBeVisible();
  await screen.getByTestId("analysis-structured-open").click();
  await screen
    .getByTestId("analysis-intent")
    .selectOption("WHAT_IF_PROJECT_COST");
  await screen.getByTestId("analysis-virtual-revenue").fill("100000000");
  await screen.getByTestId("analysis-virtual-cost").fill("80000000");
  await screen.getByTestId("analysis-virtual-cost-change-percent").fill("10");
  const analyzed = page.waitForResponse(
    (r) => new URL(r.url()).pathname === "/api/ai/analysis/structured",
  );
  await screen.getByTestId("analysis-run").click();
  const response = await analyzed;
  expect(response.status()).toBe(200);
  const { data } = await response.json();
  expect(data.status).toBe("OK");
  expect(data.modelUsed).toBe(false);
  expect(data.metrics.virtualCost).toBe(88000000);
  expect(data.metrics.virtualProfit).toBe(12000000);
  await expect(screen.getByTestId("analysis-answer")).toContainText(
    "88,000,000",
  );
  await expect(screen.getByTestId("analysis-row-0-0")).toContainText(
    "12,000,000",
  );

  // Editing the next request must not change the existing answer's export payload.
  await screen.getByTestId("analysis-virtual-cost").fill("90000000");
  const exported = page.waitForResponse(
    (r) => new URL(r.url()).pathname === "/api/ai/analysis/structured/export",
  );
  const download = page.waitForEvent("download");
  await screen.getByTestId("analysis-excel").click();
  const file = await exported;
  expect(file.status()).toBe(200);
  expect(file.request().postDataJSON().virtualCostAssumption.cost).toBe(
    80000000,
  );
  expect(file.headers()["content-type"]).toContain("spreadsheetml.sheet");
  expect((await download).suggestedFilename()).toMatch(/\.xlsx$/);

  // Independent virtual input works without Ollama and is not persisted as raw money.
  const sent = page.waitForResponse((r) =>
    /\/api\/ai\/analysis\/conversations\/\d+\/messages$/.test(
      new URL(r.url()).pathname,
    ),
  );
  await screen
    .getByTestId("analysis-question")
    .fill("가상 매출100000000 원가80000000 +10%");
  await screen.getByTestId("analysis-send").click();
  const messageResponse = await sent;
  expect(messageResponse.status()).toBe(200);
  const message = (await messageResponse.json()).data;
  expect(message.messageId).toBeGreaterThan(0);
  await expect(
    screen.getByTestId(`analysis-message-${message.messageId}`),
  ).toBeVisible();
  await screen.getByTestId("analysis-feedback-open").click();
  await screen.getByTestId("analysis-feedback-rating").selectOption("HELPFUL");
  await screen.getByTestId("analysis-feedback-submit").click();
  await expect(
    screen.getByTestId(`analysis-message-${message.messageId}`),
  ).toContainText("최신 평가: HELPFUL");
  await screen
    .getByTestId(`analysis-history-feedback-${message.messageId}`)
    .click();
  await screen
    .getByTestId("analysis-feedback-rating")
    .selectOption("INACCURATE");
  await screen.getByTestId("analysis-feedback-submit").click();
  await expect(
    screen.getByTestId(`analysis-message-${message.messageId}`),
  ).toContainText("최신 평가: INACCURATE");

  const conversations = await page.request.get(
    "/api/ai/analysis/conversations",
  );
  expect(conversations.status()).toBe(200);
  const owned = (await conversations.json()).data.items;
  expect(owned).toHaveLength(1);
  const conversationId = owned[0].id;
  const summaries = await page.request.get(
    `/api/ai/analysis/conversations/${conversationId}`,
  );
  expect(summaries.status()).toBe(200);
  const stored = JSON.stringify((await summaries.json()).data);
  expect(stored).not.toContain("80000000");
  expect(stored).not.toContain("100000000");

  // A different browser context has no right to the created conversation ID.
  const stranger = await page.context().browser()!.newContext();
  try {
    const forbidden = await stranger.request.get(
      new URL(`/api/ai/analysis/conversations/${conversationId}`, page.url())
        .href,
    );
    expect(forbidden.status()).toBe(403);
  } finally {
    await stranger.close();
  }

  await screen.getByTestId("analysis-collapse").click();
  await screen.getByTestId(`analysis-hide-${conversationId}`).click();
  await expect(
    screen.getByTestId("analysis-delete-confirmation"),
  ).toContainText("물리삭제");
  await screen.getByTestId("analysis-hide-confirm").click();
  await expect(
    screen.getByTestId(`analysis-conversation-${conversationId}`),
  ).toHaveCount(0);
  await expect(
    screen.getByTestId(`analysis-message-${message.messageId}`),
  ).toHaveCount(0);

  for (const width of [360, 375, 1024, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
  }
});
