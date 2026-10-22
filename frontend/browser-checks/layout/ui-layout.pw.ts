import { expect, test } from "@playwright/test";
import { screenIds } from "../../src/pages/shared/registry";

// Transport fixtures isolate browser geometry and route coverage; not a DB acceptance test.
const pcOnly = new Set([
  "UI-10",
  "UI-14",
  "UI-20",
  "UI-23",
  "UI-24",
  "UI-25",
  "UI-27",
]);
test.beforeEach(async ({ page }) => {
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const data =
      path === "/api/menus"
        ? [
            {
              menuId: 1,
              name: "업무",
              screenId: null,
              icon: "folder",
              mobile: true,
              children: screenIds.map((id, i) => ({
                menuId: i + 2,
                name: id === "UI-27" ? "공통코드 관리" : id,
                screenId: id,
                icon: null,
                mobile: !pcOnly.has(id),
                children: [],
              })),
            },
          ]
        : path === "/api/month-close"
          ? []
          : { items: [], total: 0, page: 1, size: 100 };
    await route.fulfill({ json: { success: true, meta: {}, data } });
  });
});

for (const width of [1280, 1024, 375, 360]) {
  test(`responsive shell at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/UI-03");
    await expect(
      page.getByRole("heading", { level: 1, name: "UI-03" }),
    ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    expect(
      await page
        .getByRole("banner")
        .evaluate((el) => el.getBoundingClientRect().height),
    ).toBe(56);
    const menu = page.getByLabel("업무 메뉴");
    if (width >= 768) {
      expect(
        await menu.evaluate((el) => el.getBoundingClientRect().width),
      ).toBe(width >= 1280 ? 240 : 64);
    } else {
      await expect(menu).toBeHidden();
      expect(
        await page
          .getByLabel("다음 연도")
          .evaluate((el) => el.getBoundingClientRect().height),
      ).toBe(44);
      await page.getByLabel("메뉴 열기").click();
      await expect(menu).toBeVisible();
      await menu.getByRole("button", { name: "UI-01", exact: true }).click();
      await expect(page).toHaveURL(/\/UI-01$/);
      await expect(menu).toBeHidden();
    }
    if (width >= 1280) await expect(page.getByLabel("열린 화면")).toBeVisible();
    else await expect(page.getByLabel("열린 화면")).toBeHidden();
  });
}

test("all registered routes render and common-code tabs retain their route", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  for (const id of [...screenIds, "UI-22"]) {
    await page.goto(`/${id}`);
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(
      id === "UI-27" ? "공통코드 관리" : id,
    );
    await expect(page.getByText("화면을 찾을 수 없습니다.")).toHaveCount(0);
  }
  await page.goto("/UI-27");
  for (const label of ["엑셀 표기 대응", "기준값", "변경 이력", "코드"]) {
    await page.getByRole("tab", { name: label, exact: true }).click();
    await expect(
      page.getByRole("tab", { name: label, exact: true }),
    ).toHaveAttribute("aria-selected", "true");
    await expect(page).toHaveURL(/\/UI-27$/);
  }
});

test("collapsed flyout is usable and mobile PC-only route has no business form", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto("/UI-03");
  await page.getByRole("heading", { level: 1 }).waitFor();
  await page.getByLabel("메뉴 접기", { exact: true }).click();
  await page
    .getByLabel("업무 메뉴")
    .getByRole("button", { name: "업무", exact: true })
    .hover();
  await page
    .getByLabel("업무 메뉴")
    .getByRole("button", { name: "UI-01", exact: true })
    .click();
  await expect(page).toHaveURL(/\/UI-01$/);
  await page.setViewportSize({ width: 375, height: 900 });
  await page.goto("/UI-27");
  await expect(page.getByText("PC에서 이용해 주세요")).toBeVisible();
  await expect(page.getByRole("tab")).toHaveCount(0);
  await page.getByRole("button", { name: "대시보드로" }).click();
  await expect(page).toHaveURL(/\/UI-16$/);
});
