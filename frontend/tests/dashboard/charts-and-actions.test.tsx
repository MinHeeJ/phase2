import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import UI16Screen from "../../src/pages/dashboard/UI16Screen";
import { WorkforceChart } from "../../src/pages/dashboard/DashboardCharts";
import { dashboardFixture } from "./DashboardTestSupport";
import { response } from "../../src/pages/developers/DeveloperTestSupport";

afterEach(() => {
  vi.unstubAllGlobals();
});
function Location() {
  const location = useLocation();
  return <output aria-label="도착 화면">{location.pathname}</output>;
}
function mount(data = dashboardFixture()) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => response(data)),
  );
  return render(
    <MemoryRouter initialEntries={["/UI-16"]}>
      <UI16Screen />
      <Location />
    </MemoryRouter>,
  );
}
it("renders workforce bars and the same accessible MM table, taking shortage only from the server", async () => {
  const data = dashboardFixture();
  data.workforceForecast.months[0] = {
    month: "2026-11",
    availableMm: 2,
    requiredMm: 2.4,
    shortageMm: 0.4,
    isShortage: false,
    state: "READY",
  };
  mount(data);
  const chart = await screen.findByRole("img", {
    name: "6개월 가용 인력과 필요 인력 막대 차트",
  });
  expect(
    within(chart).getByTestId("dashboard-workforce-point-2026-11"),
  ).toHaveAccessibleName("2026-11 가용 2 MM, 필요 2.4 MM");
  const row = screen.getByTestId("dashboard-workforce-2026-11");
  expect(row).toHaveTextContent("2.00");
  expect(row).toHaveTextContent("2.40");
  expect(row).not.toHaveTextContent("부족");
  expect(screen.getByTestId("dashboard-workforce-2026-12")).toHaveTextContent(
    "부족",
  );
});
it("does not draw synthetic zero bars for an unavailable forecast", () => {
  const data = dashboardFixture();
  const months = data.workforceForecast.months.map((point) => ({
    ...point,
    availableMm: null,
    requiredMm: null,
    shortageMm: null,
    isShortage: null,
    state: "MISSING",
  }));
  const { container } = render(<WorkforceChart months={months} />);
  expect(screen.getByText("인력 전망 자료 없음")).toBeInTheDocument();
  expect(container.querySelector("rect")).toBeNull();
});
it("uses the selected display unit in cash chart tooltips and preserves negative balances", async () => {
  const data = dashboardFixture();
  data.cashForecast.months[0] = {
    month: "2026-11",
    expectedFinalBalance: -1000000,
    minimumOperatingCash: 1000000,
    isShortage: true,
    state: "READY",
  };
  mount(data);
  await screen.findByRole("img", {
    name: "3개월 예상 최종 잔액과 최소 운영자금 차트",
  });
  fireEvent.change(screen.getByLabelText("금액 단위"), {
    target: { value: "1000000" },
  });
  expect(
    screen.getByTestId("dashboard-cash-point-balance-2026-11"),
  ).toHaveAccessibleName("2026-11 예상 최종 잔액 -1 백만 원");
  expect(screen.getByTestId("dashboard-cash-2026-11")).toHaveTextContent(
    "-1 백만 원",
  );
});
it("makes the full warning description one keyboard-accessible navigation link", async () => {
  const data = dashboardFixture();
  data.actions = {
    ...data.actions,
    totalKinds: 1,
    urgentKinds: 1,
    items: [
      {
        type: "OVERDUE_RECEIVABLE",
        severity: "URGENT",
        state: "READY",
        title: "연체 미수",
        affectedCount: 2,
        unit: "CASE",
        description: "오늘 현재 미수",
        sourceMonth: null,
        withoutNextAssignmentCount: null,
        recommendedDeveloperCount: null,
      },
    ],
  };
  mount(data);
  const link = await screen.findByTestId(
    "dashboard-action-link-overdue-receivable",
  );
  expect(link).toHaveTextContent("오늘 현재 미수");
  expect(link).toHaveTextContent("UI-13 이동");
  link.focus();
  await userEvent.setup().keyboard("{Enter}");
  expect(screen.getByLabelText("도착 화면")).toHaveTextContent("/UI-13");
});
