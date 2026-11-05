import { test, expect } from "@playwright/test";

// Frontend integration with explicit HTTP fixtures. The live anonymous API boundary
// is tested separately in security-entry.spec.ts; these fixtures do not prove SQL or login.
for (const width of [375, 1440]) {
  test(`user filters use API options and preserve submitted export conditions at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    const menu = [
      {
        menuId: 791,
        name: "시스템",
        screenId: null,
        icon: null,
        mobile: true,
        children: [
          {
            menuId: 792,
            name: "사용자 관리",
            screenId: "UI-23",
            icon: null,
            mobile: true,
            children: [],
          },
        ],
      },
    ];
    const unexpected: string[] = [];
    await page.route("**/api/**", async (route) => {
      const url = new URL(route.request().url());
      const data = (() => {
        switch (url.pathname) {
          case "/api/auth/me":
            return { userId: 417, name: "검증 관리자", roles: ["검증 역할"] };
          case "/api/auth/csrf":
            return {
              headerName: "X-CSRF-TOKEN",
              csrfToken: "browser-fixture-csrf",
            };
          case "/api/auth/activity":
            return null;
          case "/api/navigation":
          case "/api/menus":
            return menu;
          case "/api/me/permissions":
            return {
              menus: menu,
              roles: [],
              permissions: [
                {
                  menuId: 792,
                  screenId: "UI-23",
                  inq: true,
                  reg: false,
                  mdfcn: false,
                  del: false,
                  upld: false,
                  dwnld: true,
                  cnfrm: false,
                },
              ],
            };
          case "/api/notifications/unread-count":
            return { count: 0 };
          case "/api/sales-parts/options":
            return [{ id: "731", name: "검증 파트", hq: false }];
          case "/api/admin/roles":
            return [{ id: 837, name: "검증 역할", useYn: "Y" }];
          case "/api/admin/users":
            return {
              items: [],
              page: Number(url.searchParams.get("page")),
              size: 50,
              total: url.searchParams.get("partId") ? 0 : 51,
            };
          default:
            return undefined;
        }
      })();
      if (url.pathname === "/api/admin/users/export") {
        await route.fulfill({
          status: 403,
          contentType: "application/json",
          body: JSON.stringify({ success: false, code: "FORBIDDEN" }),
        });
      } else if (data !== undefined) {
        await route.fulfill({ json: { success: true, meta: {}, data } });
      } else {
        unexpected.push(`${route.request().method()} ${url.pathname}`);
        await route.fulfill({
          status: 500,
          json: { success: false, message: "Unexpected fixture request" },
        });
      }
    });
    await page.goto("/UI-23");
    await expect(page.getByTestId("user-management-screen")).toHaveAttribute(
      "data-screen-id",
      "UI-23",
    );
    await expect(page.getByTestId("user-filter-part")).toBeEnabled();
    await expect(page.getByTestId("user-filter-role")).toBeEnabled();
    await page
      .getByTestId("user-filter-part")
      .selectOption({ label: "검증 파트" });
    await page
      .getByTestId("user-filter-role")
      .selectOption({ label: "검증 역할" });
    const list = page.waitForRequest((request) => {
      const url = new URL(request.url());
      return (
        url.pathname === "/api/admin/users" &&
        url.searchParams.get("partId") === "731" &&
        url.searchParams.get("roleId") === "837"
      );
    });
    await page.getByTestId("user-search-submit").click();
    expect(new URL((await list).url()).searchParams.get("page")).toBe("1");
    await expect(
      page.getByText("검색 결과가 없습니다.", { exact: true }),
    ).toBeVisible();
    await expect(page.getByTestId("users-next")).toBeDisabled();

    // Editing a filter without submitting must not silently change the export scope.
    await page.getByTestId("user-filter-part").selectOption("");
    const exportRequest = page.waitForRequest(
      (request) =>
        new URL(request.url()).pathname === "/api/admin/users/export",
    );
    await page.getByTestId("user-export").click();
    const exported = new URL((await exportRequest).url()).searchParams;
    expect(exported.get("partId")).toBe("731");
    expect(exported.get("roleId")).toBe("837");
    expect(exported.has("page")).toBe(false);
    await expect(page.getByRole("alert")).toContainText(
      "내려받을 권한이 없거나 파일 생성에 실패했습니다.",
    );
    expect(unexpected).toEqual([]);
  });
}
