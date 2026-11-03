import { test, expect } from "@playwright/test";

test("외부 후보 메뉴는 후보 작업 공간을 열고 기존 프로젝트 등록 주소는 보존한다", async ({
  page,
  request,
}) => {
  const menus = await request.get("/api/menus");
  expect(menus.ok()).toBeTruthy();
  const response = await menus.json();
  expect(response.success).toBe(true);
  const nodes = response.data.flatMap(
    (group: {
      screenId: string | null;
      children: { screenId: string; name: string }[];
    }) => (group.screenId ? [group] : group.children),
  );
  const candidate = nodes.find(
    (node: { screenId: string }) => node.screenId === "UI-33",
  );
  expect(candidate, "DB 메뉴에 UI-33 후보 진입이 있어야 합니다").toBeTruthy();
  await page.goto("/UI-33");
  await expect(page.getByTestId("project-registration")).toBeVisible();
  await page.getByTestId("project-candidate-workspace").click();
  await expect(page.getByTestId("candidate-workspace")).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "외부 인력 후보", exact: true }),
  ).toBeVisible();
  await expect(page).toHaveURL(/\/UI-33$/);
  await page.getByTestId("candidate-project-workspace").click();
  await expect(page.getByTestId("project-registration")).toBeVisible();
  await page.getByTestId("project-back").click();
  await expect(page).toHaveURL(/\/UI-01$/);
  const navigation = page.getByRole("navigation", { name: "화면 탐색" });
  const menu = navigation.getByRole("button", {
    name: candidate.name,
    exact: true,
  });
  if (!(await menu.isVisible())) {
    const groups = response.data.filter(
      (group: { children: { screenId: string }[] }) =>
        group.children?.some((child) => child.screenId === "UI-33"),
    );
    expect(groups).toHaveLength(1);
    await navigation
      .getByRole("button", { name: groups[0].name, exact: true })
      .click();
  }
  await menu.click();
  await expect(page.getByTestId("candidate-workspace")).toBeVisible();
});

test("후보 목록 검색과 상세는 실제 API 식별자를 사용한다", async ({
  page,
  request,
}) => {
  const result = await request.get(
    "/api/candidates?size=20&page=0&sort=cand_id,asc",
  );
  expect(result.ok()).toBeTruthy();
  const { success, data } = await result.json();
  expect(success).toBe(true);
  await page.goto("/UI-33");
  await page.getByTestId("project-candidate-workspace").click();
  await expect(page.getByTestId("candidate-workspace")).toBeVisible();
  if (data.items.length === 0) {
    await expect(page.getByTestId("candidate-empty-row")).toContainText(
      "조건에 맞는 결과가 없습니다.",
    );
    return;
  }
  const candidate = data.items[0];
  await page.getByTestId("candidate-query").fill(candidate.cand_no);
  await page.getByTestId("candidate-search-submit").click();
  await page.getByTestId(`candidate-open-${candidate.cand_id}`).click();
  const detail = page.getByTestId("candidate-detail");
  await expect(detail).toContainText(candidate.cand_no);
  await expect(detail).toContainText(candidate.cand_nm);
  await page.getByTestId("candidate-tab-contacts").click();
  await expect(page.getByTestId("candidate-panel-contacts")).toBeVisible();
  await expect(
    detail.getByRole("button", { name: /기록 삭제|기록 수정/ }),
  ).toHaveCount(0);
  await page.getByTestId("candidate-detail-close").click();
  await expect(detail).toHaveCount(0);
});
