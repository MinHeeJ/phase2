import { test, expect } from "@playwright/test";

test("실제 메뉴 계약의 모든 route와 SPA 새로고침", async ({
  page,
  request,
}) => {
  test.skip(!process.env.BASE_URL, "기동한 Compose의 BASE_URL 필요");
  const response = await request.get("/api/menus");
  expect(response.ok()).toBeTruthy();
  const payload = await response.json();
  expect(payload.success).toBe(true);
  const screens = payload.data.flatMap(
    (m: { children: { screenId: string; name: string; mobile: boolean }[] }) =>
      m.children,
  );
  expect(screens).toHaveLength(31);
  for (const screen of screens) {
    await page.goto(`/${screen.screenId}`);
    await page.reload();
    if (page.viewportSize()!.width < 768 && !screen.mobile) {
      await expect(page.getByText("PC에서 이용해 주세요")).toBeVisible();
    } else {
      await expect(
        page.getByRole("heading", { name: screen.name, exact: true }),
      ).toBeVisible();
      if (screen.screenId !== "UI-27")
        await expect(
          page.getByText("이 화면은 다음 단계에서 만듭니다."),
        ).toBeVisible();
    }
  }
});

test("360px 가로 넘침과 로컬 폰트", async ({ page }) => {
  test.skip(!process.env.BASE_URL, "기동한 Compose의 BASE_URL 필요");
  await page.setViewportSize({ width: 360, height: 800 });
  const externalFonts: string[] = [];
  page.on("request", (req) => {
    if (
      req.resourceType() === "font" &&
      !req.url().startsWith(process.env.BASE_URL!)
    )
      externalFonts.push(req.url());
  });
  await page.goto("/UI-16");
  await expect(
    page.getByText("이 화면은 다음 단계에서 만듭니다."),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  expect(externalFonts).toEqual([]);
});
