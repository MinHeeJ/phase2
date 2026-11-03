import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI17Screen from "./UI17Screen";
import type { ForecastPage } from "./client";

let forecast: ForecastPage;
let requests: string[];
function Location() {
  const location = useLocation();
  return (
    <output data-testid="forecast-location">
      {JSON.stringify({ path: location.pathname, state: location.state })}
    </output>
  );
}
beforeEach(() => {
  requests = [];
  forecast = {
    items: [
      {
        month: "2027-03",
        job_cd: "BE",
        grd_cd: "SR",
        supply_mm: 0.69,
        demand_mm: 1.1,
        remaining_mm: -0.41,
        shortage_mm: 0.41,
        available_mm: 1,
        confirmed_mm: 0.31,
        extension_mm: 0,
        temporary_mm: 0.4,
        utilization_rate: 31,
        idle_cost: 690000,
        estimated: true,
        baseline_operating_profit: null,
        average_labor_cost: 1000000,
        warnings: ["영업이익 기준 원천 없음"],
      },
    ],
    page: 0,
    size: 50,
    total: 1,
    shortages: [
      {
        job_cd: "BE",
        grd_cd: "SR",
        maximum_shortage_mm: 0.41,
        maximum_shortage_people: 1,
        first_shortage_month: "2027-03",
        recommended_hiring_month: "2027-01",
        review_immediately: false,
        candidate_count: 2,
      },
    ],
    months: ["2027-03"],
    target_utilization_rate: 90,
    message: null,
  };
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: string) => {
      requests.push(String(input));
      let data: unknown = forecast;
      if (String(input).startsWith("/api/codes/"))
        data = [
          { code: "BE", name: "백엔드" },
          { code: "SR", name: "고급" },
        ];
      return new Response(JSON.stringify({ success: true, meta: {}, data }));
    }),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = () =>
  render(
    <MemoryRouter initialEntries={["/UI-17"]}>
      <UI17Screen />
      <Location />
    </MemoryRouter>,
  );

describe("workforce forecast", () => {
  it("renders API amounts and passes shortage job/grade/month to the candidate workspace", async () => {
    mount();
    expect(
      await screen.findByTestId("workforce-row-2027-03-be-sr"),
    ).toHaveTextContent("0.69");
    expect(screen.getByTestId("workforce-row-2027-03-be-sr")).toHaveTextContent(
      "1.10",
    );
    expect(screen.getByTestId("workforce-shortage-be-sr")).toHaveTextContent(
      "2027-01",
    );
    fireEvent.click(screen.getByTestId("workforce-candidates-be-sr"));
    expect(screen.getByTestId("forecast-location")).toHaveTextContent(
      '"path":"/UI-33"',
    );
    expect(screen.getByTestId("forecast-location")).toHaveTextContent(
      '"workspace":"candidates"',
    );
    expect(screen.getByTestId("forecast-location")).toHaveTextContent(
      '"shortage_month":"2027-03"',
    );
  });
  it("filters using source option values and resets pagination", async () => {
    mount();
    await screen.findByTestId("workforce-row-2027-03-be-sr");
    fireEvent.change(screen.getByTestId("workforce-job"), {
      target: { value: "BE" },
    });
    fireEvent.change(screen.getByTestId("workforce-grade"), {
      target: { value: "SR" },
    });
    await waitFor(() =>
      expect(
        requests.some(
          (p) =>
            p.includes("job=BE") &&
            p.includes("grade=SR") &&
            p.includes("page=0"),
        ),
      ).toBe(true),
    );
  });
  it("shows no-future-months message without misleading rows or candidate actions", async () => {
    forecast = {
      ...forecast,
      items: [],
      shortages: [],
      months: [],
      total: 0,
      message: "예측할 미래월이 없습니다.",
    };
    mount();
    expect(await screen.findByText("예측할 미래월이 없습니다.")).toBeVisible();
    expect(
      screen.queryByTestId("workforce-forecast-chart"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /외부 후보 보기/ }),
    ).not.toBeInTheDocument();
  });
  it("displays zero capacity utilization as a dash and keeps source warnings visible", async () => {
    forecast.items[0] = { ...forecast.items[0], utilization_rate: null };
    mount();
    const row = await screen.findByTestId("workforce-row-2027-03-be-sr");
    expect(row).toHaveTextContent("–");
    expect(row).toHaveTextContent("영업이익 기준 원천 없음");
  });
});
