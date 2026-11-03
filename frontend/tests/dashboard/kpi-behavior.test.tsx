import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI16Screen from "../../src/pages/dashboard/UI16Screen";
import {
  response,
  failure,
} from "../../src/pages/developers/DeveloperTestSupport";
import { dashboardFixture } from "./DashboardTestSupport";
import type { DashboardData } from "../../src/pages/apiops/dashboardClient";
function Location() {
  const location = useLocation();
  return (
    <output aria-label="현재 경로">
      {location.pathname} {JSON.stringify(location.state)}
    </output>
  );
}
function mount() {
  return render(
    <MemoryRouter initialEntries={["/UI-16"]}>
      <UI16Screen />
      <Location />
    </MemoryRouter>,
  );
}
let data: DashboardData;
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  data = dashboardFixture();
  fetcher = vi.fn(async (url: string) => {
    if (!url.startsWith("/api/dashboard"))
      throw new Error(`Unexpected request ${url}`);
    const month = new URLSearchParams(url.split("?")[1]).get("month");
    return response(month ? { ...data, basisMonth: month } : data);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  sessionStorage.clear();
});
describe("대시보드 KPI와 기준월", () => {
  it("renders five ordered real-response cards and server percentages", async () => {
    mount();
    const revenue = await screen.findByTestId("dashboard-revenue-card");
    expect(revenue).toHaveTextContent("1,200,000 원");
    expect(revenue).toHaveTextContent("전월 대비 +20.0%");
    expect(revenue).toHaveTextContent("80.0%");
    expect(screen.getByTestId("dashboard-profit-card")).toHaveTextContent(
      "16.7%",
    );
    expect(screen.getByTestId("dashboard-utilization-card")).toHaveTextContent(
      "-10.0%p",
    );
    expect(screen.getByTestId("dashboard-receivables-card")).toHaveTextContent(
      "오늘 현재 · VAT 포함",
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/dashboard",
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
    expect(screen.getByRole("button", { name: "다음 월" })).toBeDisabled();
  });
  it("preserves actual zero and renders null ratios as dash", async () => {
    data.kpis.revenue.amount = 0;
    data.kpis.revenue.previous.changePercent = null;
    data.kpis.operatingProfit.marginPercent = null;
    mount();
    expect(
      await screen.findByTestId("dashboard-revenue-card"),
    ).toHaveTextContent("0 원");
    expect(screen.getByTestId("dashboard-revenue-card")).toHaveTextContent(
      "전월 대비 –",
    );
    expect(screen.getByTestId("dashboard-profit-card")).toHaveTextContent(
      "이익률 –",
    );
  });
  it("distinguishes missing targets from a zero target", async () => {
    data.kpis.revenue.targetAmount = null;
    data.kpis.ytdOperatingProfit.targetAmount = 0;
    data.kpis.ytdOperatingProfit.achievementPercent = null;
    mount();
    expect(
      await screen.findByTestId("dashboard-revenue-card"),
    ).toHaveTextContent("목표 없음");
    expect(screen.getByTestId("dashboard-ytd-card")).toHaveTextContent(
      "목표 달성률 –",
    );
  });
  it("shows closed missing immutable inputs rather than live or zero values", async () => {
    data.closeStatus = "CLOSED";
    data.kpis.utilization = {
      ...data.kpis.utilization,
      state: "MISSING",
      message: "마감 자료 확인 필요",
      utilizationPercent: null,
      confirmedMm: null,
      availableMm: null,
      idleDeveloperCount: null,
    };
    mount();
    expect(
      await screen.findByTestId("dashboard-utilization-card"),
    ).toHaveTextContent("마감 자료 확인 필요");
    expect(
      screen.getByTestId("dashboard-utilization-card"),
    ).not.toHaveTextContent("80.0%");
    expect(screen.getByTestId("dashboard-revenue-card")).toHaveTextContent(
      "1,200,000 원",
    );
  });
  it("loads previous month and refuses future input locally", async () => {
    mount();
    await screen.findByTestId("dashboard-revenue-card");
    fireEvent.click(screen.getByRole("button", { name: "이전 월" }));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        "/api/dashboard?month=2026-09",
        expect.anything(),
      ),
    );
    fireEvent.change(screen.getByLabelText("기준월"), {
      target: { value: "2026-11" },
    });
    expect(screen.getByLabelText("기준월")).toHaveValue("2026-09");
  });
  it("converts presentation units without another API request", async () => {
    mount();
    await screen.findByTestId("dashboard-revenue-card");
    const count = fetcher.mock.calls.length;
    fireEvent.change(screen.getByLabelText("금액 단위"), {
      target: { value: "1000000" },
    });
    expect(screen.getByTestId("dashboard-revenue-card")).toHaveTextContent(
      "1.2 백만 원",
    );
    expect(fetcher.mock.calls).toHaveLength(count);
  });
  it("announces unchecked closing as 0/9 without failed checks", async () => {
    mount();
    expect(
      await screen.findByTestId("dashboard-closing-panel"),
    ).toHaveTextContent("점검 전 · 0/9");
    expect(screen.getByTestId("dashboard-closing-panel")).not.toHaveTextContent(
      "차단",
    );
  });
});
describe("KPI navigation", () => {
  it.each([
    ["revenue", "UI-08"],
    ["profit", "UI-08"],
    ["ytd", "UI-18"],
    ["utilization", "UI-28"],
    ["receivables", "UI-13"],
  ])("click %s hands off the same basis to %s", async (id, target) => {
    mount();
    const link = await screen.findByTestId(`dashboard-${id}-link`);
    fireEvent.click(link);
    expect(screen.getByLabelText("현재 경로")).toHaveTextContent(`/${target}`);
    expect(screen.getByLabelText("현재 경로")).toHaveTextContent(
      '"closingMonth":"2026-10"',
    );
  });
  it.each([
    ["revenue", "UI-08"],
    ["profit", "UI-08"],
    ["ytd", "UI-18"],
    ["utilization", "UI-28"],
    ["receivables", "UI-13"],
  ])("keyboard %s activates %s", async (id, target) => {
    const user = userEvent.setup();
    mount();
    const link = await screen.findByTestId(`dashboard-${id}-link`);
    link.focus();
    await user.keyboard("{Enter}");
    expect(screen.getByLabelText("현재 경로")).toHaveTextContent(`/${target}`);
  });
});
describe("partial read recovery", () => {
  it("retains prior coherent result while retry waits and replaces atomically", async () => {
    data.kpis.utilization.state = "ERROR";
    data.kpis.utilization.message = "인력 원천 조회 실패";
    mount();
    await screen.findByTestId("dashboard-utilization-retry");
    let resolve!: (value: Response) => void;
    fetcher.mockImplementationOnce(
      () =>
        new Promise<Response>((done) => {
          resolve = done;
        }),
    );
    fireEvent.click(screen.getByTestId("dashboard-utilization-retry"));
    expect(screen.getByTestId("dashboard-revenue-card")).toHaveTextContent(
      "1,200,000 원",
    );
    expect(screen.getByText(/이전 조회 결과/)).toHaveTextContent(data.asOf);
    const next = dashboardFixture();
    next.asOf = "2026-10-29T00:01:00Z";
    next.kpis.revenue.amount = 1300000;
    await act(async () => resolve(response(next)));
    expect(screen.getByTestId("dashboard-revenue-card")).toHaveTextContent(
      "1,300,000 원",
    );
    expect(
      screen.queryByTestId("dashboard-utilization-retry"),
    ).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.every(
        (call) => !call[1]?.method || call[1].method === "GET",
      ),
    ).toBe(true);
  });
  it("drops an older month response arriving after the new month", async () => {
    mount();
    await screen.findByTestId("dashboard-revenue-card");
    let stale!: (value: Response) => void;
    fetcher.mockImplementationOnce(
      () =>
        new Promise<Response>((done) => {
          stale = done;
        }),
    );
    fireEvent.click(screen.getByRole("button", { name: "이전 월" }));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByRole("button", { name: "이전 월" }));
    await waitFor(() =>
      expect(screen.getByLabelText("기준월")).toHaveValue("2026-08"),
    );
    await waitFor(() =>
      expect(screen.queryByText(/이전 조회 결과/)).not.toBeInTheDocument(),
    );
    const old = dashboardFixture("2026-09");
    old.kpis.revenue.amount = 999;
    await act(async () => stale(response(old)));
    expect(screen.getByTestId("dashboard-revenue-card")).not.toHaveTextContent(
      "999 원",
    );
    expect(screen.getByLabelText("기준월")).toHaveValue("2026-08");
  });
  it("shows whole request failure with retry and preserves existing data", async () => {
    mount();
    await screen.findByTestId("dashboard-revenue-card");
    fetcher.mockImplementationOnce(async () => failure(null, 500));
    fireEvent.click(screen.getByTestId("dashboard-refresh"));
    expect(await screen.findByRole("alert")).toHaveTextContent("입력 오류");
    expect(screen.getByTestId("dashboard-revenue-card")).toHaveTextContent(
      "1,200,000 원",
    );
  });
  it("renders skeleton before first response", () => {
    fetcher.mockImplementation(() => new Promise(() => {}));
    mount();
    expect(screen.getByText("매출 불러오는 중")).toBeInTheDocument();
    expect(
      screen.queryByTestId("dashboard-revenue-card"),
    ).not.toBeInTheDocument();
  });
  it("uses server shortage judgement not rounded browser threshold", async () => {
    data.workforceForecast.months[0] = {
      month: "2026-11",
      availableMm: 2,
      requiredMm: 2.4,
      shortageMm: 0.4,
      isShortage: false,
      state: "READY",
    };
    mount();
    const row = await screen.findByTestId("dashboard-workforce-2026-11");
    expect(row).toHaveTextContent("0.40");
    expect(within(row).queryByText("부족")).not.toBeInTheDocument();
    expect(screen.getByTestId("dashboard-workforce-2026-12")).toHaveTextContent(
      "부족",
    );
  });
  it("does not show null future actuals as zero chart points", async () => {
    data = dashboardFixture("2026-03");
    mount();
    const future = await screen.findByTestId("dashboard-trend-2026-04");
    expect(within(future).getAllByText("–")).toHaveLength(2);
    expect(future).toHaveTextContent("1,500,000 원");
  });
  it("shows a genuine empty action list separately from undetermined source", async () => {
    mount();
    expect(
      await screen.findByText("확인할 일이 없습니다."),
    ).toBeInTheDocument();
  });
  it("keeps undetermined rows visible and non-navigable", async () => {
    data.actions.items = [
      {
        type: "MM_EXCESS",
        severity: "CAUTION",
        state: "UNDETERMINED",
        title: "MM 1.0 초과",
        affectedCount: null,
        unit: "PERSON",
        description: "자료 없음",
        sourceMonth: "2026-10",
        withoutNextAssignmentCount: null,
        recommendedDeveloperCount: null,
      },
    ];
    data.actions.totalKinds = 1;
    mount();
    const row = await screen.findByTestId("dashboard-action-mm-excess");
    expect(row).toHaveTextContent("판정 불가");
    expect(within(row).queryByRole("link")).not.toBeInTheDocument();
  });
  it("exposes real export failure rather than a fake workbook", async () => {
    mount();
    await screen.findByTestId("dashboard-revenue-card");
    fetcher.mockImplementationOnce(async () => failure(null, 500));
    fireEvent.click(screen.getByTestId("dashboard-export"));
    expect(await screen.findByRole("alert")).toHaveTextContent("입력 오류");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/dashboard/export?month=2026-10",
      expect.anything(),
    );
  });
});
