import { test, expect } from "@playwright/test";

test.use({ storageState: process.env.SECURITY_SALES_STORAGE_STATE });
test.describe("담당 영업 업무 연락처", () => {
  test.skip(
    !process.env.SECURITY_SALES_STORAGE_STATE,
    "담당 PART와 실제 업무 자료가 준비된 영업 파트장 세션이 필요합니다.",
  );
  test("UI-02 개발자 상세에서 목적별 열람 후 닫으면 원문을 제거한다", async ({
    page,
  }) => {
    await page.goto("/UI-02");
    const listed = await page.request.get("/api/developers");
    expect(listed.status()).toBe(200);
    const rows = (await listed.json()).data.items;
    expect(
      rows.length,
      "시험 계정 담당 PART에 활성 개발자가 필요합니다.",
    ).toBeGreaterThan(0);
    const row = rows.find((item: { use_yn: string }) => item.use_yn === "Y");
    expect(row).toBeTruthy();
    await page.getByTestId(`developer-open-${row.dev_id}`).click();
    await page
      .getByTestId("developers-contact-purpose")
      .selectOption("STAFF_OPERATION");
    const requested = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
        `/api/developers/${row.dev_id}/contact`,
    );
    await page.getByTestId("developers-contact-view").click();
    const result = await requested;
    expect(result.status()).toBe(200);
    const body = await result.json();
    expect(Object.keys(body.data).sort()).toEqual(["email", "phone"]);
    await expect(page.getByTestId("developers-contact-result")).toBeVisible();
    await page.getByTestId("developers-contact-close").click();
    await expect(page.getByTestId("developers-contact-result")).toHaveCount(0);
  });
  test("UI-15 거래처는 독립 API를 사용하며 계정 인증 정보를 반환하지 않는다", async ({
    page,
  }) => {
    await page.goto("/UI-15");
    const listed = await page.request.get("/api/partners");
    expect(listed.status()).toBe(200);
    const rows = (await listed.json()).data.items;
    expect(
      rows.length,
      "시험 계정 담당 PART에 활성 거래처가 필요합니다.",
    ).toBeGreaterThan(0);
    const row = rows.find((item: { use_yn: string }) => item.use_yn === "Y");
    expect(row).toBeTruthy();
    expect(row.chrg_tel_no).toBeNull();
    await page.getByTestId(`partner-open-${row.ptnr_id}`).click();
    await page
      .getByTestId("partners-contact-purpose")
      .selectOption("CONTRACT_SALES");
    const requested = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname ===
        `/api/partners/${row.ptnr_id}/contact`,
    );
    await page.getByTestId("partners-contact-view").click();
    const result = await requested;
    expect(result.status()).toBe(200);
    const body = await result.json();
    expect(body.data).not.toHaveProperty("userId");
    expect(body.data).not.toHaveProperty("loginId");
    expect(body.data).not.toHaveProperty("mfa");
    await expect(page.getByTestId("partners-contact-result")).toBeVisible();
    await page.getByTestId("partners-contact-close").click();
    await expect(page.getByTestId("partners-contact-result")).toHaveCount(0);
  });
});
