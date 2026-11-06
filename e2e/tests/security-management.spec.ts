import { expect, test } from "@playwright/test";

// Use the repository Playwright runner. Authenticated state must be captured only
// after password change and required email MFA in the isolated acceptance setup.
test.describe("security entrypoints", () => {
  test("anonymous direct links show only login, never a business shell", async ({
    page,
    request,
  }) => {
    for (const path of [
      "/api/admin/users",
      "/api/admin/roles",
      "/api/admin/menus",
      "/api/notifications",
      "/api/developers/options",
      "/api/projects/export",
    ]) {
      const response = await request.get(path);
      expect(response.status()).toBe(401);
      expect((await response.json()).success).toBe(false);
    }
    await page.goto("/UI-23");
    await expect(page).toHaveURL(/\/UI-22$/);
    await expect(page.getByTestId("authentication-screen")).toBeVisible();
    await expect(page.getByTestId("user-accounts-screen")).toHaveCount(0);
  });
  for (const width of [375, 1440]) {
    test(`login password visibility and layout at ${width}px`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 900 });
      await page.goto("/UI-22");
      const password = page.getByLabel("비밀번호", { exact: true });
      await expect(password).toHaveAttribute("type", "password");
      await page
        .getByRole("button", { name: "비밀번호 보기", exact: true })
        .click();
      await expect(password).toHaveAttribute("type", "text");
      await page
        .getByRole("button", { name: "비밀번호 숨기기", exact: true })
        .click();
      await expect(password).toHaveAttribute("type", "password");
      await expect(page.locator("body")).toHaveJSProperty("scrollWidth", width);
      await page
        .getByRole("button", { name: "비밀번호 찾기", exact: true })
        .click();
      await expect(
        page.getByRole("heading", { name: "비밀번호 찾기", exact: true }),
      ).toBeVisible();
    });
  }
});

test.describe("authenticated administrator bindings", () => {
  test.use({ storageState: process.env.SECURITY_E2E_STORAGE_STATE });
  test.skip(
    !process.env.SECURITY_E2E_STORAGE_STATE,
    "Requires runner-created isolated administrator state after MFA; no authentication bypass",
  );
  test("actual admin routes render their screens, selected identities remain immutable", async ({
    page,
  }) => {
    await page.goto("/UI-15");
    await expect(page.getByTestId("partners-screen")).toHaveAttribute(
      "data-screen-id",
      "SCR-UI-15",
    );
    await page.goto("/UI-23");
    await expect(page.getByTestId("user-accounts-screen")).toBeVisible();
    await expect(page.getByTestId("users-search-input")).toBeVisible();
    await page.goto("/UI-24");
    await expect(page.getByTestId("role-permissions-screen")).toBeVisible();
    await page.goto("/UI-25");
    await expect(page.getByTestId("menu-management-screen")).toBeVisible();
    const menu = page.locator('[data-testid^="menu-select-"]').first();
    if (await menu.count()) {
      await menu.click();
      await expect(page.getByTestId("menu-screen-readonly")).toHaveAttribute(
        "readonly",
        "",
      );
    }
  });
});

test.describe("partner business contact", () => {
  test.use({ storageState: process.env.SECURITY_E2E_SALES_STORAGE_STATE });
  test.skip(
    !process.env.SECURITY_E2E_SALES_STORAGE_STATE,
    "Requires runner-owned scoped sales state and an active assigned partner; no seed rows are mutated",
  );
  test("UI-15 selected partner contact is purpose-bound and is discarded when detail closes", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto("/UI-15");
    await expect(page.getByTestId("partners-screen")).toHaveAttribute(
      "data-screen-id",
      "SCR-UI-15",
    );
    const partner = page.locator('[data-testid^="partner-open-"]').first();
    await expect(partner).toBeVisible();
    await partner.click();
    const detail = page.getByRole("dialog", {
      name: "거래처 상세",
      exact: true,
    });
    await expect(detail).toBeVisible();
    await detail
      .getByLabel("업무 목적", { exact: true })
      .selectOption("CONTRACT_SALES");
    const received = page.waitForResponse(
      (response) =>
        /\/api\/partners\/[^/]+\/contact\?purpose=CONTRACT_SALES$/.test(
          response.url(),
        ) && response.request().method() === "GET",
    );
    await detail
      .getByRole("button", { name: "원문 보기", exact: true })
      .click();
    const result = await received;
    expect(result.status()).toBe(200);
    const body = await result.json();
    expect(body.success).toBe(true);
    expect(Object.keys(body.data).sort()).toEqual(["email", "masked", "phone"]);
    expect(body.data.masked).toBe(false);
    await expect(
      detail.getByRole("button", { name: "원문 닫기", exact: true }),
    ).toBeVisible();
    await detail
      .getByRole("button", { name: "상세 닫기", exact: true })
      .click();
    await expect(detail).toHaveCount(0);
    await partner.click();
    await expect(
      detail.getByRole("button", { name: "원문 보기", exact: true }),
    ).toBeVisible();
    await expect(
      detail.getByRole("button", { name: "원문 닫기", exact: true }),
    ).toHaveCount(0);
  });
});
