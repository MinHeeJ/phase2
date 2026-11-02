import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppRouter } from "./router";
import { useSharedPreferences } from "../pages/shared/preferences";
import { response } from "../pages/developers/DeveloperTestSupport";
import type { MenuNode } from "../pages/shared/api";
import * as client from "../api/apiClient";

const names: Record<string, string> = {
  "UI-07": "전체 수익 구조",
  "UI-08": "영업이익표",
  "UI-09": "순이익표",
  "UI-10": "재무 항목",
  "UI-18": "사업계획 대비 실적",
  "UI-20": "지급 관리",
  "UI-21": "자금수지표",
};
const menus: MenuNode[] = [
  {
    menuId: 1,
    name: "재무·자금",
    screenId: null,
    icon: null,
    mobile: true,
    children: Object.entries(names).map(([screenId, name], i) => ({
      menuId: i + 2,
      screenId,
      name,
      icon: null,
      mobile: !["UI-10", "UI-20"].includes(screenId),
      children: [],
    })),
  },
];
const metric = {
  target: null,
  actual: null,
  difference: null,
  achievement_rate: null,
};
let failed: boolean;
let fetcher: ReturnType<typeof vi.fn>;
function Location() {
  const location = useLocation();
  return <output aria-label="현재 주소">{location.pathname}</output>;
}
function mount(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Location />
      <AppRouter />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-30T00:00:00Z"));
  localStorage.clear();
  sessionStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  failed = false;
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/menus") return response(menus);
    if (failed && url.startsWith("/api/finance/"))
      return new Response(
        JSON.stringify({
          success: false,
          code: "INTERNAL_ERROR",
          message: "재무 원천 조회 실패",
          requestId: "finance-read",
        }),
        { status: 500 },
      );
    if (url.startsWith("/api/finance/entries?"))
      return response({
        items: [],
        drafts: [],
        months: [],
        total: 0,
        saved_count: 0,
        month_status: "OPEN",
        total_amount: 0,
        total_vat: 0,
      });
    if (url.startsWith("/api/finance/tax/plan?")) {
      const year = Number(new URL(url, "http://test").searchParams.get("year"));
      return response({
        year,
        annual_amount: 1200,
        reason: "",
        editable: true,
        history: [],
        months: [
          { month: `${year}-10`, closed: false, amount: 100, warning: null },
          { month: `${year}-09`, closed: true, amount: 90, warning: null },
        ],
      });
    }
    if (url.startsWith("/api/finance/tax/payments?"))
      return response({
        year: 2026,
        payments: [],
        suggestions: [],
        locked_types: [],
      });
    if (url.startsWith("/api/finance/profit/monthly?"))
      return response({ year: 2026, months: [], previous_months: [] });
    if (url.startsWith("/api/finance/profit/structure?"))
      return response({
        months: [],
        warnings: [],
        warning: "",
        previous: {},
        difference: {},
        growth_rate: {},
        composition_available: false,
        cost_breakdown: [],
        revenue_by_contract: [],
        revenue_by_customer: [],
        revenue_by_part: [],
        export_file: null,
      });
    if (url.startsWith("/api/finance/business-plan/comparison?"))
      return response({
        items: [],
        actuals: [],
        warnings: [],
        comparison: {
          months: [],
          cumulative_months: [],
          cumulative: {
            revenue: metric,
            operating_profit: metric,
            utilization_rate: metric,
          },
        },
        export_file: null,
      });
    if (url.startsWith("/api/finance/business-plan?"))
      return response({ year: 2026, items: [], parts: [], closed_months: [] });
    if (url.startsWith("/api/finance/payment-orders?"))
      return response({ items: [], month_status: "OPEN" });
    if (url.startsWith("/api/finance/payments?"))
      return response({
        items: [],
        total: 0,
        closed: false,
        warnings: [],
        categories: [],
        banks: [],
        summary: [],
      });
    if (url.startsWith("/api/finance/cashflow/opening?"))
      return response({ month: "2026-10", amount: 0 });
    if (url === "/api/finance/cashflow/other-inflows")
      return response({ items: [], total: 0 });
    if (url.startsWith("/api/finance/cashflow?"))
      return response({
        year: 2026,
        mode: "BOTH",
        months: [],
        initial_month: "2026-10",
        first_risk_month: null,
        export_file: null,
      });
    if (url.startsWith("/api/codes/") || url.startsWith("/api/month-close?"))
      return response([]);
    throw new Error(`Unexpected finance route request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const surfaces = [
  ["/UI-07", "profit-ui-07-screen", "/api/finance/profit/structure?"],
  ["/UI-08", "profit-ui-08-screen", "/api/finance/profit/monthly?"],
  ["/UI-09", "profit-ui-09-screen", "/api/finance/profit/monthly?"],
  ["/UI-10", "entries-screen", "/api/finance/entries?"],
  ["/UI-18", "business-plan-screen", "/api/finance/business-plan?"],
  ["/UI-20", "payments-screen", "/api/finance/payments?"],
  ["/UI-21", "cashflow-screen", "/api/finance/cashflow?"],
] as const;
describe("finance route composition", () => {
  it.each(surfaces)(
    "mounts %s anonymously through the existing menu",
    async (path, testId, endpoint) => {
      mount(path);
      expect(await screen.findByTestId(testId)).toBeVisible();
      await waitFor(() =>
        expect(
          fetcher.mock.calls.some(([url]) => url.startsWith(endpoint)),
        ).toBe(true),
      );
      expect(
        screen.queryByText("이 화면은 다음 단계에서 만듭니다."),
      ).not.toBeInTheDocument();
      expect(fetcher.mock.calls.some(([url]) => url.includes("/auth/"))).toBe(
        false,
      );
    },
  );
  it.each(surfaces)("keeps %s failures retryable", async (path, testId) => {
    failed = true;
    mount(path);
    await screen.findAllByText("재무 원천 조회 실패");
    failed = false;
    const surface = within(screen.getByTestId(testId));
    if (path === "/UI-21")
      fireEvent.click(surface.getByTestId("cashflow-query"));
    else
      surface
        .getAllByRole("button", { name: /다시 시도|다시 조회/ })
        .forEach((button) => fireEvent.click(button));
    await waitFor(() =>
      expect(surface.queryAllByText("재무 원천 조회 실패")).toHaveLength(0),
    );
  });
  it("opens B.2 locally without losing the entries screen", async () => {
    mount("/UI-10");
    fireEvent.click(await screen.findByTestId("entries-upload"));
    expect(await screen.findByRole("dialog")).toHaveTextContent("재무 항목");
    expect(screen.getByTestId("entries-screen")).toBeInTheDocument();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(/^\/UI-10$/);
  });
  it("binds the tax summary and detail to the selected entry month", async () => {
    mount("/UI-10");
    await waitFor(() =>
      expect(screen.getByTestId("entries-tax-row")).toHaveTextContent("100"),
    );
    fireEvent.click(screen.getByTestId("entries-month-9"));
    await waitFor(() =>
      expect(screen.getByTestId("entries-tax-row")).toHaveTextContent("90"),
    );
    fireEvent.click(screen.getByTestId("entries-tax"));
    expect(await screen.findByTestId("corporate-tax-panel")).toBeVisible();
    await waitFor(() =>
      expect(screen.getByTestId("tax-current-amount")).toHaveValue("90"),
    );
    expect(screen.getByTestId("tax-annual")).toBeDisabled();
  });
  it("opens B.10 locally from the business plan", async () => {
    mount("/UI-18");
    await waitFor(() =>
      expect(screen.getByTestId("business-plan-upload")).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId("business-plan-upload"));
    expect(await screen.findByRole("dialog")).toHaveTextContent("B.10");
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(/^\/UI-18$/);
  });
  it("opens B.11 only from the 2025 operating profit view", async () => {
    mount("/UI-08");
    await screen.findByTestId("profit-ui-08-screen");
    expect(screen.queryByTestId("profit-prior-upload")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("profit-previous-year"));
    fireEvent.click(await screen.findByTestId("profit-prior-upload"));
    expect(await screen.findByRole("dialog")).toHaveTextContent("B.11");
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(/^\/UI-08$/);
  });
  it("composes the payment-order owner's panel", async () => {
    mount("/UI-20");
    expect(await screen.findByTestId("payment-orders-panel")).toBeVisible();
    fireEvent.click(await screen.findByTestId("payment-orders-settings"));
    expect(await screen.findByTestId("payment-orders-dialog")).toBeVisible();
    expect(
      screen.queryByTestId("payment-orders-button"),
    ).not.toBeInTheDocument();
  });
  it("preserves mounted periods without synchronizing other finance screens", async () => {
    mount("/UI-08");
    await screen.findByTestId("profit-ui-08-screen");
    fireEvent.change(screen.getByLabelText("누계 기준월"), {
      target: { value: "4" },
    });
    fireEvent.click(screen.getByRole("button", { name: names["UI-09"] }));
    const net = await screen.findByTestId("profit-ui-09-screen");
    expect(within(net).getByLabelText("누계 기준월")).toHaveValue("10");
    fireEvent.click(
      within(screen.getByRole("navigation", { name: "열린 화면" })).getByRole(
        "button",
        { name: names["UI-08"] },
      ),
    );
    expect(
      within(screen.getByTestId("profit-ui-08-screen")).getByLabelText(
        "누계 기준월",
      ),
    ).toHaveValue("4");
  });
  it.each(["/UI-10", "/UI-20"])(
    "preserves the mobile exclusion for %s",
    async (path) => {
      vi.stubGlobal("innerWidth", 375);
      mount(path);
      expect(await screen.findByText("PC에서 이용해 주세요")).toBeVisible();
      expect(
        fetcher.mock.calls.some(([url]) => url.startsWith("/api/finance/")),
      ).toBe(false);
    },
  );
  it("exposes finance reads through the shared client registry", async () => {
    await client.getFinanceCashflow(2026, "BOTH");
    await client.getMonthlyFinanceProfit(2026);
    await client.listFinanceEntries("2026-10");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/finance/cashflow?year=2026&mode=BOTH",
      expect.anything(),
    );
    expect(fetcher.mock.calls.every(([url]) => url.startsWith("/api/"))).toBe(
      true,
    );
  });
});
