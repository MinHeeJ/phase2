import { test, expect } from "@playwright/test";

test("all business entry points require authentication before rendering their screen", async ({
  page,
  request,
}) => {
  const protectedApis = [
    "/api/admin/access-history",
    "/api/admin/access-history/export",
    "/api/admin/code-groups",
    "/api/admin/code-history",
    "/api/admin/excel-maps",
    "/api/admin/menus",
    "/api/admin/notification-channels",
    "/api/admin/params",
    "/api/admin/roles",
    "/api/admin/users",
    "/api/admin/users/export",
    "/api/auth/me",
    "/api/me/permissions",
    "/api/menus",
    "/api/navigation",
    "/api/developers/export",
    "/api/developers/options",
    "/api/month-close",
    "/api/notifications",
    "/api/notifications/unread-count",
    "/api/partners/export",
    "/api/partners/options",
    "/api/projects/export",
    "/api/projects/options",
    "/api/sales-parts/export",
    "/api/sales-parts/options",
  ];
  for (const path of protectedApis) {
    const denied = await request.get(path);
    expect(denied.status(), path).toBe(401);
    expect((await denied.json()).success, path).toBe(false);
  }
  for (const id of [
    "01",
    "02",
    "03",
    "04",
    "05",
    "07",
    "08",
    "09",
    "10",
    "11",
    "12",
    "13",
    "14",
    "15",
    "16",
    "17",
    "18",
    "19",
    "20",
    "21",
    "23",
    "24",
    "25",
    "26",
    "27",
    "28",
    "29",
    "30",
    "31",
    "32",
    "33",
    "34",
  ]) {
    await page.goto(`/UI-${id}`);
    await expect(page).toHaveURL(/\/UI-22$/);
    await expect(page.getByTestId("login-screen")).toBeVisible();
    await expect(
      page.getByRole("navigation", { name: "열린 화면" }),
    ).toHaveCount(0);
    await expect(page.getByTestId("authenticated-application")).toHaveCount(0);
  }
});

for (const width of [375, 1440]) {
  test(`anonymous protected route shows only login at ${width}px`, async ({
    page,
    request,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    const denied = await request.get("/api/admin/users");
    expect(denied.status()).toBe(401);
    expect((await denied.json()).success).toBe(false);
    await page.goto("/UI-23");
    await expect(page).toHaveURL(/\/UI-22$/);
    await expect(page.getByTestId("login-screen")).toBeVisible();
    await expect(page.getByTestId("user-management-screen")).toHaveCount(0);
    await expect(page.getByTestId("login-password")).toHaveAttribute(
      "type",
      "password",
    );
    await page.getByTestId("password-visibility").click();
    await expect(page.getByTestId("login-password")).toHaveAttribute(
      "type",
      "text",
    );
    const geometry = await page
      .getByTestId("login-screen")
      .evaluate((element) => ({
        pageWidth: document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth,
        cardWidth: element.querySelector("form")!.getBoundingClientRect().width,
      }));
    expect(geometry.scrollWidth).toBeLessThanOrEqual(geometry.pageWidth);
    expect(geometry.cardWidth).toBeLessThanOrEqual(420);
  });
}

test("forgot-password and cancel stay in UI-22 without showing business data", async ({
  page,
}) => {
  await page.goto("/UI-22");
  await page.getByTestId("forgot-password").click();
  await expect(
    page.getByRole("heading", { name: "비밀번호 찾기" }),
  ).toBeVisible();
  await expect(page.getByTestId("login-password")).toHaveCount(0);
  await page.getByTestId("authentication-cancel").click();
  await expect(
    page.getByRole("heading", { name: "로그인", exact: true }),
  ).toBeVisible();
  await expect(page).toHaveURL(/\/UI-22$/);
});
