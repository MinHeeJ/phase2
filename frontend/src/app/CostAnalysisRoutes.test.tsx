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

const names: Record<string, string> = {
  "UI-16": "대시보드",
  "UI-03": "개인 원가·수익표",
  "UI-04": "프로젝트 원가 분석",
  "UI-19": "영업 파트 이익금",
  "UI-02": "개발자 풀",
  "UI-01": "프로젝트",
};
const menus: MenuNode[] = [
  {
    menuId: 1,
    name: "원가·수익 분석",
    screenId: null,
    icon: null,
    mobile: true,
    children: Object.entries(names).map(([screenId, name], index) => ({
      menuId: index + 2,
      name,
      screenId,
      icon: null,
      mobile: true,
      children: [],
    })),
  },
];
const metadata = {
  calculation_month: "2026-10",
  reference_month: "2026-10",
  closed: false,
  unit: "원",
  warnings: [],
};
let fetcher: ReturnType<typeof vi.fn>;
let failed: boolean;
function Location() {
  const location = useLocation();
  return (
    <output aria-label="현재 주소">
      {location.pathname + location.search}
    </output>
  );
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
  vi.setSystemTime(new Date("2026-10-29T00:00:00Z"));
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
    if (
      url.startsWith("/api/costs/") ||
      url.startsWith("/api/settlements/parts")
    ) {
      if (failed)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "SOURCE_UNAVAILABLE",
            message: "원천 조회 실패",
            field: null,
            requestId: "cost-read",
          }),
          { status: 500 },
        );
      if (url.startsWith("/api/costs/people?"))
        return response({
          ...metadata,
          items: [],
          total: 0,
          page: 0,
          size: 50,
          summary: {
            psn_cnt: 0,
            avg_working_psn_cnt: null,
            avg_earning_psn_cnt: null,
            avg_price_amt: null,
            avg_cost_amt: null,
            avg_profit_amt: null,
            loss_psn_cnt: 0,
            utilization_rt: null,
            earning_rt: null,
            contributions: [],
          },
        });
      if (url.startsWith("/api/costs/projects?"))
        return response({
          ...metadata,
          items: [],
          total: 0,
          page: 0,
          size: 50,
          summary: {
            confirmed_supply_amt: 0,
            profit_amt: 0,
            low_margin_count: 0,
            budget_over_count: 0,
            rnd_cost_amt: 0,
          },
        });
      if (url.startsWith("/api/settlements/parts?"))
        return response({ ...metadata, items: [] });
      if (url.startsWith("/api/costs/renegotiations?"))
        return response({
          ...metadata,
          items: [],
          total: 0,
          page: 0,
          size: 50,
          raise_rate: 5,
          target_margin_rate: null,
          summary: {
            psn_cnt: 0,
            weighted_margin_rt: null,
            assumed_annual_profit_increase_amt: 0,
            target_annual_profit_increase_amt: 0,
          },
        });
    }
    if (url.startsWith("/api/developers?"))
      return response({ items: [], total: 0, page: 0, size: 50 });
    if (url.startsWith("/api/projects?"))
      return response({
        items: [],
        total: 0,
        page: 0,
        size: 50,
        unassignedCount: 0,
      });
    if (
      url.startsWith("/api/codes/") ||
      url.includes("/options") ||
      url.startsWith("/api/month-close?")
    )
      return response([]);
    throw new Error(`Unexpected cost route request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const surfaces = [
  ["/UI-03", "people-workspace", "/api/costs/people?"],
  ["/UI-04", "cost-project-screen", "/api/costs/projects?"],
  ["/UI-19", "part-settlement-screen", "/api/settlements/parts?"],
] as const;
describe("cost analysis route composition", () => {
  it.each(surfaces)(
    "mounts %s as an anonymous API-backed business surface",
    async (path, testId, endpoint) => {
      mount(path);
      expect(await screen.findByTestId(testId)).toBeVisible();
      expect(
        await screen.findByText(/조건에 맞는 결과가 없습니다/),
      ).toBeVisible();
      expect(fetcher.mock.calls.some(([url]) => url.startsWith(endpoint))).toBe(
        true,
      );
      expect(fetcher.mock.calls.some(([url]) => url.includes("/auth/"))).toBe(
        false,
      );
      expect(
        fetcher.mock.calls.every(
          ([, init]) => !init || !init.method || init.method === "GET",
        ),
      ).toBe(true);
    },
  );
  it.each(surfaces)(
    "keeps %s read failures retryable through the real shell",
    async (path, testId) => {
      failed = true;
      mount(path);
      expect(await screen.findByText("원천 조회 실패")).toBeVisible();
      failed = false;
      fireEvent.click(
        within(screen.getByTestId(testId)).getByRole("button", {
          name: "다시 시도",
        }),
      );
      expect(
        await screen.findByText(/조건에 맞는 결과가 없습니다/),
      ).toBeVisible();
      await waitFor(() =>
        expect(screen.queryByText("원천 조회 실패")).not.toBeInTheDocument(),
      );
    },
  );
  it("opens renegotiation inside UI-04 and returns without changing the route", async () => {
    mount("/UI-04");
    fireEvent.click(await screen.findByTestId("cost-project-renegotiations"));
    expect(await screen.findByTestId("renegotiation-panel")).toBeVisible();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(/^\/UI-04$/);
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) =>
          url.startsWith("/api/costs/renegotiations?"),
        ),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId("renegotiation-close"));
    expect(await screen.findByTestId("cost-project-screen")).toBeVisible();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(/^\/UI-04$/);
  });
  it("opens the registered screens using the existing API-provided menu entries", async () => {
    mount("/UI-03");
    await screen.findByTestId("people-workspace");
    fireEvent.click(screen.getByRole("button", { name: names["UI-04"] }));
    expect(await screen.findByTestId("cost-project-screen")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: names["UI-19"] }));
    expect(await screen.findByTestId("part-settlement-screen")).toBeVisible();
  });
  it.each([
    ["/UI-32", "developer-registration", "/api/developers?"],
    ["/UI-33", "project-registration", "/api/projects?"],
  ])(
    "preserves the registration alias %s",
    async (path, registration, endpoint) => {
      mount(path);
      expect(await screen.findByTestId(registration)).toBeVisible();
      await waitFor(() =>
        expect(
          fetcher.mock.calls.some(([url]) => url.startsWith(endpoint)),
        ).toBe(true),
      );
      expect(
        screen.queryByTestId("renegotiation-panel"),
      ).not.toBeInTheDocument();
      expect(screen.getByLabelText("현재 주소")).toHaveTextContent(path);
    },
  );
});
