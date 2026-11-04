import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, useLocation } from "react-router-dom";
import WorkforceDemandScreen from "./WorkforceDemandScreen";
import { getWorkforceForecast, type WorkforceForecastPage } from "./client";
import controls from "../../shared/Controls.module.css";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let data: WorkforceForecastPage,
  fetcher: ReturnType<typeof vi.fn>,
  failRead: boolean,
  failCodes: boolean;
function Destination() {
  const location = useLocation();
  return (
    <output data-testid="demand-destination">
      {JSON.stringify({ path: location.pathname, state: location.state })}
    </output>
  );
}
const mount = () =>
  render(
    <MemoryRouter initialEntries={["/UI-17"]}>
      <WorkforceDemandScreen />
      <Destination />
    </MemoryRouter>,
  );
beforeEach(() => {
  sessionStorage.clear();
  failRead = false;
  failCodes = false;
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-14T00:00:00Z"));
  data = {
    items: [
      {
        month: "2026-11",
        job_cd: "BE",
        grd_cd: "S",
        supply_mm: 1,
        demand_mm: 1.41,
        remaining_mm: -0.41,
        shortage_mm: 0.41,
        available_mm: 2,
        confirmed_mm: 1,
        extension_mm: 0,
        temporary_mm: 0.75,
        utilization_rate: 50,
        idle_cost: 3000000,
        estimated: true,
        baseline_operating_profit: null,
        average_labor_cost: 3000000,
        warnings: ["FUTURE_ACTUAL · 영업이익 기준 원천 없음"],
      },
    ],
    page: 0,
    size: 100,
    total: 1,
    months: ["2026-11"],
    target_utilization_rate: 90,
    message: null,
    shortages: [
      {
        job_cd: "BE",
        grd_cd: "S",
        maximum_shortage_mm: 0.41,
        maximum_shortage_people: 1,
        first_shortage_month: "2026-11",
        recommended_hiring_month: "2026-09",
        review_immediately: true,
        candidate_count: 2,
      },
    ],
  };
  fetcher = vi.fn(async (url: string) => {
    if (url.startsWith("/api/codes/")) {
      if (failCodes) throw new Error("offline");
      return response([
        {
          code: url.endsWith("GRADE") ? "S" : "BE",
          name: url.endsWith("GRADE") ? "고급" : "백엔드 개발",
        },
      ]);
    }
    if (failRead)
      return new Response(
        JSON.stringify({
          success: false,
          code: "INVALID_INPUT",
          field: "year",
          message: "예측 조회 실패",
          requestId: "forecast-test",
          meta: {},
        }),
        { status: 400, headers: { "Content-Type": "application/json" } },
      );
    return response(data);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
describe("UI-17 common workforce forecast", () => {
  it("renders supply demand bars, month difference, source warnings and hiring advice", async () => {
    mount();
    await screen.findByTestId("workforce-demand-content");
    expect(
      screen.getByRole("heading", { name: "인력 수요 예측" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "월별 가용 vs 필요" }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("demand-month-2026-11")).toHaveTextContent(
      "공급 1.00 / 수요 1.41",
    );
    expect(screen.getByText("-0.41")).toHaveClass(controls.danger);
    expect(screen.getByText("즉시 검토")).toBeInTheDocument();
    expect(screen.getByText("가동률 (목표 90%)")).toBeInTheDocument();
    expect(screen.getByText("50.00%")).toBeInTheDocument();
    expect(
      screen.getByText("영업이익 기준 원천 없음"),
    ).toBeInTheDocument();
    expect(screen.queryByText(/FUTURE_ACTUAL/)).not.toBeInTheDocument();
    expect(screen.getByText("3,000,000원")).toBeInTheDocument();
    expect(
      fetcher.mock.calls.some(([url]) =>
        String(url).startsWith("/api/workforce/forecast?"),
      ),
    ).toBe(true);
    expect(
      fetcher.mock.calls.some(([url]) =>
        String(url).startsWith("/api/opportunities/forecast"),
      ),
    ).toBe(false);
  });
  it("forwards candidate context and shortage filters without replacing project registration", async () => {
    mount();
    fireEvent.click(await screen.findByTestId("demand-candidates-be-s"));
    expect(screen.getByTestId("demand-destination")).toHaveTextContent(
      '"path":"/UI-33"',
    );
    expect(screen.getByTestId("demand-destination")).toHaveTextContent(
      '"workspace":"candidates"',
    );
    expect(screen.getByTestId("demand-destination")).toHaveTextContent(
      '"job":"BE"',
    );
    expect(screen.getByTestId("demand-destination")).toHaveTextContent(
      '"grade":"S"',
    );
    expect(screen.getByTestId("demand-destination")).toHaveTextContent(
      '"candidateFilters":{"job":"BE","grade":"S","shortage_month":"2026-11"}',
    );
  });
  it("uses persisted YEAR, remembers navigation and preserves filters on year change", async () => {
    sessionStorage.setItem("UI-17.year", "2027");
    mount();
    await screen.findByTestId("workforce-demand-content");
    fireEvent.change(screen.getByTestId("demand-job"), {
      target: { value: "BE" },
    });
    fireEvent.change(screen.getByTestId("demand-grade"), {
      target: { value: "S" },
    });
    fireEvent.click(screen.getByTestId("demand-year-next"));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringMatching(/year=2028.*job=BE.*grade=S/),
        expect.anything(),
      ),
    );
    expect(sessionStorage.getItem("UI-17.year")).toBe("2028");
    await screen.findByTestId("workforce-demand-content");
    fireEvent.click(screen.getByTestId("demand-reset"));
    expect(screen.getByTestId("demand-job")).toHaveValue("");
    expect(screen.getByTestId("demand-grade")).toHaveValue("");
    expect(screen.getByText("2028년")).toBeInTheDocument();
  });
  it("shows no future months without inventing chart data", async () => {
    data = {
      ...data,
      months: [],
      items: [],
      total: 0,
      shortages: [],
      message: "예측할 미래월이 없습니다.",
    };
    mount();
    await screen.findByText("예측할 미래월이 없습니다.");
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });
  it("shows filtered empty state and reset while retaining selected year", async () => {
    data = { ...data, items: [], total: 0, shortages: [] };
    mount();
    await screen.findByText("조건에 맞는 예측 결과가 없습니다.");
    fireEvent.click(screen.getByTestId("demand-reset"));
    expect(screen.getByText("2026년")).toBeInTheDocument();
  });
  it("reports API field failure, preserves filters and retries", async () => {
    failRead = true;
    mount();
    await screen.findByText("예측 조회 실패");
    expect(screen.getByText("문의 번호 forecast-test")).toBeInTheDocument();
    expect(screen.getByText("2026년")).toBeInTheDocument();
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByTestId("workforce-demand-content");
  });
  it("disables unavailable code options and retries their API", async () => {
    failCodes = true;
    mount();
    await screen.findAllByText("서버에 연결할 수 없습니다.");
    expect(screen.getByTestId("demand-job")).toBeDisabled();
    failCodes = false;
    fireEvent.click(screen.getAllByRole("button", { name: "다시 시도" })[0]);
    await waitFor(() => expect(screen.getByTestId("demand-job")).toBeEnabled());
  });
  it("shows loading before source response", async () => {
    let resolve!: (value: Response) => void;
    fetcher.mockImplementation((url: string) =>
      url.startsWith("/api/codes/")
        ? Promise.resolve(response([]))
        : new Promise<Response>((done) => {
            resolve = done;
          }),
    );
    mount();
    expect(
      screen.queryByTestId("workforce-demand-content"),
    ).not.toBeInTheDocument();
    resolve(response(data));
    await screen.findByTestId("workforce-demand-content");
  });
  it("keeps denominator-zero dash and cost-unavailable warning visible", async () => {
    data.items[0] = {
      ...data.items[0],
      utilization_rate: null,
      average_labor_cost: 0,
      idle_cost: 0,
      warnings: ["원가 미확인"],
    };
    mount();
    await screen.findByText("원가 미확인");
    expect(screen.getByText("–")).toBeInTheDocument();
    expect(screen.getByText("0원")).toBeInTheDocument();
  });
  it.each([
    [0.4, "warning"],
    [0.41, "danger"],
    [0, "success"],
  ])("renders shortage %s with %s tone", async (shortage, tone) => {
    data.items[0] = {
      ...data.items[0],
      shortage_mm: Number(shortage),
      remaining_mm: shortage ? -Number(shortage) : 0,
    };
    mount();
    await screen.findByTestId("workforce-demand-content");
    expect(screen.getByText(data.items[0].remaining_mm.toFixed(2))).toHaveClass(
      controls[tone],
    );
  });
  it("loads every API page for complete charts and makes no writes", async () => {
    const rows = Array.from({ length: 101 }, (_, index) => ({
      ...data.items[0],
      job_cd: `JOB${index}`,
    }));
    fetcher.mockImplementation(async (url: string) => {
      const query = new URLSearchParams(url.split("?")[1]);
      const page = Number(query.get("page"));
      return response({
        ...data,
        total: 101,
        items: rows.slice(page * 100, (page + 1) * 100),
      });
    });
    const result = await getWorkforceForecast({ year: 2026 });
    expect(result.items).toHaveLength(101);
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(
      fetcher.mock.calls.every(
        ([, init]) =>
          !(init as RequestInit)?.method ||
          (init as RequestInit).method === "GET",
      ),
    ).toBe(true);
  });
});
