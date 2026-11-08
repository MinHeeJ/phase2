import { test, expect } from "@playwright/test";

test.use({ storageState: process.env.SECURITY_ADMIN_STORAGE_STATE });
test.describe("시스템 관리 실제 API/화면 회귀", () => {
  test.skip(
    !process.env.SECURITY_ADMIN_STORAGE_STATE,
    "실제 MFA 로그인과 최초 비밀번호 변경을 완료한 관리자 storageState가 필요합니다.",
  );
  test("역할 생성 후 같은 ID 상세와 권한을 조회하고 자기 시험 역할만 중지한다", async ({
    page,
  }) => {
    const name = `e2e-role-${crypto.randomUUID()}`;
    let createdId: number | undefined;
    try {
      await page.goto("/UI-24");
      await expect(page.getByTestId("role-permissions-screen")).toBeVisible();
      await page.getByTestId("role-create").click();
      await page.getByTestId("role-name").fill(name);
      await page
        .getByTestId("role-description")
        .fill("독립 생성 역할 회귀 시험");
      const saved = page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === "/api/admin/roles" &&
          response.request().method() === "POST",
      );
      await page.getByTestId("role-save").click();
      const response = await saved;
      expect(response.status()).toBe(200);
      createdId = (await response.json()).data.roleId;
      await expect(page.getByTestId("role-name")).toHaveValue(name);
      const detail = await page.request.get(`/api/admin/roles/${createdId}`);
      expect(detail.status()).toBe(200);
      expect((await detail.json()).data.name).toBe(name);
      await expect(page.getByTestId("role-matrix-panel")).toBeVisible();
      await page.getByTestId("role-matrix-clear").click();
      const permissions = await page.request.get(
        `/api/admin/roles/${createdId}/permissions`,
      );
      expect(permissions.status()).toBe(200);
      const rows = (await permissions.json()).data;
      expect(rows.every((r: { inq: string }) => r.inq === "N")).toBe(true);
    } finally {
      if (createdId !== undefined) {
        const proof = await page.request.get("/api/auth/csrf");
        const csrf = (await proof.json()).data.csrfToken;
        const cleanup = await page.request.patch(
          `/api/admin/roles/${createdId}`,
          { headers: { "X-CSRF-TOKEN": csrf }, data: { useYn: "N" } },
        );
        expect(cleanup.status()).toBe(200);
      }
    }
  });
  test("사용자·권한·메뉴 고정 주소가 기존 shell에서 열린다", async ({
    page,
  }) => {
    for (const [route, marker] of [
      ["/UI-23", "user-management-screen"],
      ["/UI-24", "role-permissions-screen"],
      ["/UI-25", "menu-management-screen"],
    ]) {
      await page.goto(route);
      await expect(page.getByTestId(marker)).toBeVisible();
      await expect(page.getByTestId(marker)).toHaveAttribute(
        "data-screen-id",
        route.slice(1),
      );
    }
    await page.getByTestId("menu-order-preview").click();
    await expect(page.getByTestId("menu-order-preview-panel")).toBeVisible();
    await page.getByTestId("menu-order-cancel").click();
    await expect(page.getByTestId("menu-order-preview-panel")).toHaveCount(0);
  });
  test("375px 관리 화면은 페이지 전체 가로 넘침 없이 사용 가능하다", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto("/UI-23");
    await expect(page.getByTestId("user-management-screen")).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
  });
});

test.describe("익명 보안 경계", () => {
  test.use({ storageState: { cookies: [], origins: [] } });
  test("로그인 화면은 업무 shell을 노출하지 않고 보호 API는 401이다", async ({
    page,
  }) => {
    await page.goto("/UI-23");
    await expect(page.getByTestId("login-screen")).toBeVisible();
    for (const path of [
      "/api/admin/users",
      "/api/admin/roles",
      "/api/admin/menus",
      "/api/notifications",
    ]) {
      const response = await page.request.get(path);
      expect(response.status()).toBe(401);
      expect((await response.json()).success).toBe(false);
    }
  });
});
