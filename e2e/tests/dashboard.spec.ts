import { test, expect } from "@playwright/test";

test("home and UI-16 use one real dashboard, export, and responsive keyboard flow", async ({
  page,
}) => {
  const reads: string[] = [];
  const writes: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith("/api/")) return;
    if (request.method() !== "GET")
      writes.push(`${request.method()} ${url.pathname}`);
    if (url.pathname === "/api/dashboard") reads.push(url.search);
  });
  const initial = page.waitForResponse(
    (response) => new URL(response.url()).pathname === "/api/dashboard",
  );
  await page.goto("/");
  const response = await initial;
  expect(response.status()).toBe(200);
  const { data } = await response.json();
  await expect(page).toHaveURL(/\/UI-16$/);
  await expect(page.getByTestId("dashboard-screen")).toBeVisible();
  await expect(
    page.locator('[data-testid="dashboard-screen"]:visible'),
  ).toHaveCount(1);
  await expect(page.getByTestId("dashboard-month")).toHaveValue(
    data.basisMonth,
  );
  await expect(page.getByTestId("dashboard-revenue-card")).toContainText(
    "이번 달 매출",
  );
  await expect(page.getByTestId("dashboard-trend-panel")).toBeVisible();
  await expect(page.getByTestId("dashboard-parts-panel")).toBeVisible();
  await expect(page.getByTestId("dashboard-pipeline-panel")).toBeVisible();
  await expect(page.getByTestId("dashboard-cash-panel")).toBeVisible();
  await page.getByTestId("dashboard-trend-table-toggle").click();
  await expect(
    page.getByTestId("dashboard-trend-panel").getByRole("table"),
  ).toBeVisible();
  const downloadResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === "/api/dashboard/export",
  );
  const downloadEvent = page.waitForEvent("download");
  await page.getByTestId("dashboard-export").click();
  const exported = await downloadResponse;
  expect(exported.status()).toBe(200);
  expect(exported.headers()["content-type"]).toContain("spreadsheetml.sheet");
  expect(new URL(exported.url()).searchParams.get("month")).toBe(
    data.basisMonth,
  );
  expect((await downloadEvent).suggestedFilename()).toContain(data.basisMonth);
  await page.setViewportSize({ width: 375, height: 812 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  const boxes = await Promise.all(
    ["revenue", "profit", "ytd"].map((id) =>
      page.getByTestId(`dashboard-${id}-card`).boundingBox(),
    ),
  );
  expect(boxes.every(Boolean)).toBe(true);
  expect(boxes[0]!.y).toBe(boxes[1]!.y);
  expect(boxes[2]!.y).toBeGreaterThan(boxes[0]!.y);
  await page.screenshot({
    path: test.info().outputPath("dashboard-375.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  const revenue = page.getByTestId("dashboard-revenue-link");
  await revenue.focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/UI-08$/);
  await expect(page.getByLabel("누계 기준월")).toHaveValue(
    String(Number(data.basisMonth.slice(5))),
  );
  expect(reads.length).toBeGreaterThan(0);
  expect(writes).toEqual([]);
});

test("past-month navigation refreshes coherently without changing today forecast horizons", async ({
  page,
}) => {
  const initial = page.waitForResponse(
    (response) => new URL(response.url()).pathname === "/api/dashboard",
  );
  await page.goto("/UI-16");
  const first = (await (await initial).json()).data;
  const selected = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === "/api/dashboard" &&
      new URL(response.url()).searchParams.has("month"),
  );
  await page.getByTestId("dashboard-prev-month").click();
  const response = await selected;
  expect(response.status()).toBe(200);
  const next = (await response.json()).data;
  await expect(page.getByTestId("dashboard-month")).toHaveValue(
    next.basisMonth,
  );
  expect(next.basisMonth < first.basisMonth).toBe(true);
  expect(next.workforceForecast.fromMonth).toBe(
    first.workforceForecast.fromMonth,
  );
  expect(next.cashForecast.fromMonth).toBe(first.cashForecast.fromMonth);
  await expect(page.getByTestId("dashboard-screen")).toContainText(next.asOf);
});
