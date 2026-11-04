import { test, expect } from "@playwright/test";
import type {
  Options,
  Snapshot,
} from "../../frontend/src/pages/staffrecommendations/client";

// Runner prerequisite: isolated FIX-AI-30 database with eligible developers and
// open demand. The spec never edits seed developers, demand or assignments.
test("UI-30 runs real A/B recommendations, comparison, career, decisions and immutable own history", async ({
  page,
}) => {
  const optionsResponse = page.waitForResponse(
    (r) => new URL(r.url()).pathname === "/api/ai/staff-recommendation/options",
  );
  await page.goto("/UI-30");
  const response = await optionsResponse;
  expect(response.status()).toBe(200);
  const options: Options = (await response.json()).data;
  expect(
    options.sources.length,
    "FIX-AI-30 needs an open project/opportunity source",
  ).toBeGreaterThan(0);
  expect(
    options.developers.length,
    "FIX-AI-30 needs a selectable developer",
  ).toBeGreaterThan(0);
  const screen = page.getByTestId("staff-recommendations-screen");
  await expect(screen).toBeVisible();
  const source = options.sources[0];
  await screen
    .getByTestId("recommendation-source")
    .selectOption(String(source.sourceId));
  await expect(screen.getByTestId("criteria-job")).toHaveValue(
    source.criteria.jobCode!,
  );
  await expect(screen.getByTestId("criteria-start")).toHaveValue(
    source.criteria.startYm!,
  );
  const searched = page.waitForResponse(
    (r) => new URL(r.url()).pathname === "/api/ai/staff-recommendations/search",
  );
  await screen.getByTestId("recommendation-run").click();
  const search = await searched;
  expect(search.status()).toBe(200);
  const snapshot: Snapshot = (await search.json()).data;
  expect(snapshot.direction).toBe("PROJECT_TO_PERSON");
  expect(
    snapshot.results.length,
    "FIX-AI-30 needs four candidates to verify the comparison limit",
  ).toBeGreaterThanOrEqual(4);
  expect(snapshot.results.length).toBeLessThanOrEqual(10);
  const row = snapshot.results[0];
  await expect(
    screen.getByTestId(`recommendation-row-${row.resultId}`),
  ).toBeVisible();
  for (const candidate of snapshot.results.slice(0, 4))
    await screen.getByTestId(`compare-${candidate.resultId}`).click();
  await expect(
    screen.getByTestId(`compare-${snapshot.results[3].resultId}`),
  ).not.toBeChecked();
  await screen.getByTestId("open-comparison").click();
  for (const candidate of snapshot.results.slice(0, 3))
    await expect(
      screen.getByTestId(`comparison-${candidate.resultId}`),
    ).toBeVisible();
  await screen.getByTestId("close-comparison").click();
  await screen.getByTestId(`detail-${row.resultId}`).click();
  await expect(screen.getByTestId("recommendation-detail")).toContainText(
    row.reason,
  );
  await screen.getByTestId("close-detail").click();
  await screen.getByTestId(`career-${row.resultId}`).click();
  await expect(screen.getByTestId("career-panel")).toBeVisible();
  const fileResponse = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname ===
      `/api/ai/staff-recommendations/developers/${row.devId}/career-card`,
  );
  const download = page.waitForEvent("download");
  await screen.getByTestId("career-excel").click();
  expect((await fileResponse).status()).toBe(200);
  expect((await download).suggestedFilename()).toMatch(/\.xlsx$/);
  await screen.getByTestId("close-career").click();
  await screen.getByTestId(`reject-${row.resultId}`).click();
  await screen.getByTestId("decision-save").click();
  await expect(screen.getByTestId("decision-panel")).toContainText(
    "거절 사유는 1~500자",
  );
  await screen
    .getByTestId("rejection-reason")
    .fill("독립 시험 요청의 결정 검증");
  const decided = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname ===
      `/api/ai/staff-recommendations/results/${row.resultId}/decision`,
  );
  await screen.getByTestId("decision-save").click();
  const decision = await decided;
  expect(decision.status()).toBe(200);
  expect((await decision.json()).data.assignmentCreated).toBe(false);
  await expect(screen.getByTestId(`reject-${row.resultId}`)).toBeDisabled();
  const saved = await page.request.get(
    `/api/ai/staff-recommendations/${snapshot.requestId}`,
  );
  expect(saved.status()).toBe(200);
  const savedSnapshot: Snapshot = (await saved.json()).data;
  expect(savedSnapshot.results[0].score).toBe(row.score);
  expect(savedSnapshot.results[0].decisionStatus).toBe("REJECT");
  expect(savedSnapshot.results[0].assignmentId).toBeUndefined();

  await screen.getByTestId("tab-history").click();
  await expect(
    screen.getByTestId(`history-row-${snapshot.requestId}`),
  ).toBeVisible();
  await screen.getByTestId(`history-detail-${snapshot.requestId}`).click();
  await expect(
    screen.getByTestId(`recommendation-row-${row.resultId}`),
  ).toBeVisible();
  await screen.getByTestId("tab-b").click();
  await expect(screen.getByTestId("recommendation-results")).toHaveCount(0);
  const developer = options.developers[0];
  await screen
    .getByTestId("next-developer")
    .selectOption(String(developer.devId));
  if (!developer.exitDate) {
    // Use server default next month to derive the preceding assumed exit month.
    const month = new Date(`${options.defaultStartYm}-01T00:00:00Z`);
    month.setUTCMonth(month.getUTCMonth() - 1);
    await screen
      .getByTestId("assumed-exit-month")
      .fill(month.toISOString().slice(0, 7));
  }
  const nextResponse = page.waitForResponse(
    (r) => new URL(r.url()).pathname === "/api/ai/staff-recommendations/search",
  );
  await screen.getByTestId("recommendation-next-run").click();
  const next = await nextResponse;
  expect(next.status()).toBe(200);
  const nextSnapshot: Snapshot = (await next.json()).data;
  expect(nextSnapshot.direction).toBe("PERSON_TO_POSITION");
  expect(nextSnapshot.requestId).not.toBe(snapshot.requestId);
  expect(nextSnapshot.results.length).toBeLessThanOrEqual(10);
  await expect(screen.getByTestId("recommendation-results")).toBeVisible();
  for (const width of [360, 375, 1024, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
  }
});
