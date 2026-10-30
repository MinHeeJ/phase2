import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI32Screen from "./UI32Screen";
import { RenegotiationEntry } from "./RenegotiationEntry";
import { AllocationPanel } from "./AllocationPanel";
import {
  response,
  failure,
  developerFixture,
} from "../developers/DeveloperTestSupport";
import type { RenegotiationResult } from "./client";
import * as client from "./client";

const result: RenegotiationResult = {
  calculation_month: "2026-10",
  reference_month: "2026-10",
  closed: false,
  unit: "원",
  warnings: [],
  total: 1,
  page: 0,
  size: 50,
  target_margin_rate: 20,
  raise_rate: 5,
  summary: {
    psn_cnt: 1,
    weighted_margin_rt: 10,
    assumed_annual_profit_increase_amt: 600000,
    target_annual_profit_increase_amt: 1500000,
  },
  items: [
    {
      asgn_id: 73,
      dev_id: 91,
      dev_nm: "실제 개발자",
      emp_se_cd: "FT",
      prj_id: 42,
      prj_nm: "진행 프로젝트",
      end_dt: "2026-12-31",
      days_remaining: 63,
      mm_qty: 1,
      bill_untprc_amt: 1000000,
      allocated_cost_amt: 900000,
      margin_rt: 10,
      target_price_amt: 1130000,
      required_raise_rt: 12.5,
      assumed_annual_profit_increase_amt: 600000,
      target_annual_profit_increase_amt: 1500000,
    },
  ],
};
function Location() {
  const location = useLocation();
  return (
    <output aria-label="현재 주소">
      {location.pathname} {JSON.stringify(location.state)}
    </output>
  );
}
function mount() {
  return render(
    <MemoryRouter initialEntries={["/UI-04"]}>
      <Location />
      <UI32Screen />
    </MemoryRouter>,
  );
}
let fetcher: ReturnType<typeof vi.fn>;
let state: {
  fail: boolean;
  empty: boolean;
  closed: boolean;
  missing: boolean;
  noPrice: boolean;
};
beforeEach(() => {
  state = {
    fail: false,
    empty: false,
    closed: false,
    missing: false,
    noPrice: false,
  };
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/codes/EMP_TYPE")
      return response([
        { code: "FT", name: "정규직" },
        { code: "FL", name: "프리랜서" },
      ]);
    if (url.startsWith("/api/codes/")) return response([]);
    if (url === "/api/projects/options")
      return response([
        { id: 42, name: "진행 프로젝트", type: "MO" },
        { id: 43, name: "턴키", type: "TK" },
      ]);
    if (url.startsWith("/api/costs/renegotiations?")) {
      if (state.fail) return failure("raiseRate", 500);
      const q = new URLSearchParams(url.split("?")[1]);
      const raise = Number(q.get("raiseRate") ?? 5);
      return response({
        ...result,
        closed: state.closed,
        raise_rate: raise,
        warnings: state.missing ? ["급여 미업로드: dev_id=91"] : [],
        total: state.empty ? 0 : 1,
        summary: {
          ...result.summary,
          assumed_annual_profit_increase_amt: raise * 120000,
        },
        items: state.empty
          ? []
          : result.items.map((row) => ({
              ...row,
              margin_rt: state.noPrice ? null : row.margin_rt,
              target_price_amt: state.noPrice ? null : row.target_price_amt,
              assumed_annual_profit_increase_amt: raise * 120000,
            })),
      });
    }
    if (url === "/api/developers/91")
      return response({
        ...developerFixture,
        dev_id: 91,
        dev_nm: "실제 개발자",
      });
    if (url.startsWith("/api/costs/allocation?"))
      return state.fail
        ? failure("month", 409)
        : response({
            calculation_month: "2026-10",
            reference_month: "2026-10",
            closed: state.closed,
            unit: "원",
            warnings: [],
            items: [],
            unallocated_cost_amt: 0,
          });
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("embedded renegotiation panel", () => {
  it("renders defaults, four API summaries and shared table styles without changing the route", async () => {
    mount();
    await screen.findByRole("button", { name: "실제 개발자" });
    expect(screen.getByLabelText("갱신 기간")).toHaveValue("6");
    expect(screen.getByLabelText("이익률", { selector: "select" })).toHaveValue(
      "15",
    );
    expect(screen.getByLabelText("가정 인상률 (%)")).toHaveValue(5);
    expect(
      screen.getByRole("heading", { name: "매출 가중 평균 이익률" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "목표 단가 연 증가" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("table", { name: "재협상 대상 목록" }).className,
    ).toContain("table");
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-04");
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByText(/실제 향후 계약을 보장하지 않습니다/),
    ).toBeInTheDocument();
  });
  it("applies original filters, pagination and reset to the API", async () => {
    mount();
    await screen.findByRole("button", { name: "실제 개발자" });
    fireEvent.change(screen.getByLabelText("갱신 기간"), {
      target: { value: "3" },
    });
    fireEvent.change(screen.getByLabelText("직원구분"), {
      target: { value: "FL" },
    });
    fireEvent.change(
      screen.getByLabelText("프로젝트", { selector: "select" }),
      { target: { value: "42" } },
    );
    fireEvent.click(screen.getByRole("button", { name: "조회" }));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("windowMonths=3"),
        expect.anything(),
      ),
    );
    const call = fetcher.mock.calls
      .filter(([url]) => String(url).includes("windowMonths=3"))
      .at(-1)!;
    expect(call[0]).toContain("empType=FL");
    expect(call[0]).toContain("projectId=42");
    fireEvent.click(screen.getByRole("button", { name: "초기화" }));
    await waitFor(() =>
      expect(screen.getByLabelText("직원구분")).toHaveValue(""),
    );
    expect(screen.getByLabelText("갱신 기간")).toHaveValue("6");
  });
  it("recomputes assumption rows and summaries with GET only", async () => {
    mount();
    await screen.findByRole("button", { name: "실제 개발자" });
    fireEvent.change(screen.getByLabelText("가정 인상률 (%)"), {
      target: { value: "10" },
    });
    await waitFor(() =>
      expect(screen.getAllByText("1,200,000")).toHaveLength(2),
    );
    expect(
      fetcher.mock.calls.some(
        ([, init]) => init?.method && init.method !== "GET",
      ),
    ).toBe(false);
  });
  it("rejects raise outside 0–50 without sending it", async () => {
    mount();
    await screen.findByRole("button", { name: "실제 개발자" });
    fireEvent.change(screen.getByLabelText("가정 인상률 (%)"), {
      target: { value: "51" },
    });
    expect(
      screen.getByText("가정 인상률은 0~50%로 입력하세요."),
    ).toBeInTheDocument();
    expect(
      fetcher.mock.calls.some(([url]) => String(url).includes("raiseRate=51")),
    ).toBe(false);
  });
  it("retains input after failed request, hides stale rows and retries", async () => {
    mount();
    await screen.findByRole("button", { name: "실제 개발자" });
    state.fail = true;
    fireEvent.change(screen.getByLabelText("가정 인상률 (%)"), {
      target: { value: "12" },
    });
    await screen.findByText("입력 오류");
    expect(screen.getByLabelText("가정 인상률 (%)")).toHaveValue(12);
    expect(
      screen.queryByRole("button", { name: "실제 개발자" }),
    ).not.toBeInTheDocument();
    state.fail = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByRole("button", { name: "실제 개발자" });
  });
  it("shows empty and missing source states", async () => {
    state.empty = true;
    state.missing = true;
    mount();
    await screen.findByText("조건에 맞는 결과가 없습니다");
    expect(screen.getByText(/급여 미업로드/)).toBeInTheDocument();
  });
  it("displays null ratios as a dash and still allows read-only closed simulation", async () => {
    state.closed = true;
    state.noPrice = true;
    mount();
    await screen.findByRole("button", { name: "실제 개발자" });
    expect(screen.getByText("마감 스냅샷")).toBeInTheDocument();
    expect(
      within(screen.getByTestId("renegotiation-row-73")).getAllByText("–"),
    ).toHaveLength(2);
    expect(screen.getByLabelText("가정 인상률 (%)")).toBeEnabled();
  });
  it("opens existing actual developer detail and sends actual assignment to UI-12", async () => {
    mount();
    fireEvent.click(await screen.findByRole("button", { name: "실제 개발자" }));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        "/api/developers/91",
        expect.anything(),
      ),
    );
    expect(
      await screen.findByRole("heading", { name: "실제 개발자" }),
    ).toBeInTheDocument();
  });
  it("hands selected assignment to existing rate-change flow, not to a new write API", async () => {
    mount();
    fireEvent.click(await screen.findByRole("button", { name: "단가 변경" }));
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent(
      '/UI-12 {"assignmentId":73}',
    );
  });
  it("downloads with the applied filter not the draft", async () => {
    const download = vi
      .spyOn(client, "downloadCostRenegotiations")
      .mockResolvedValue();
    mount();
    await screen.findByRole("button", { name: "실제 개발자" });
    fireEvent.change(screen.getByLabelText("갱신 기간"), {
      target: { value: "12" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Excel 다운로드" }));
    await waitFor(() =>
      expect(download).toHaveBeenCalledWith(
        expect.objectContaining({ windowMonths: 6 }),
      ),
    );
  });
  it("explicit entry opens and closes within UI-04 and keeps project context", async () => {
    render(
      <MemoryRouter initialEntries={["/UI-04"]}>
        <Location />
        <RenegotiationEntry projectId={42} />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole("button", { name: "단가 재협상 대상" }));
    await screen.findByRole("button", { name: "실제 개발자" });
    expect(
      screen.getByLabelText("프로젝트", { selector: "select" }),
    ).toHaveValue("42");
    fireEvent.click(
      screen.getByRole("button", { name: "프로젝트 원가 분석으로" }),
    );
    expect(
      screen.getByRole("button", { name: "단가 재협상 대상" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("현재 주소")).toHaveTextContent("/UI-04");
  });
});

describe("allocation basis", () => {
  it("calls the approved allocation API and renders the closed empty state", async () => {
    state.closed = true;
    render(<AllocationPanel month="2026-10" projectId={42} />);
    await screen.findByText("조건에 맞는 결과가 없습니다");
    expect(screen.getByText("마감 스냅샷")).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledWith(
      "/api/costs/allocation?month=2026-10&projectId=42",
      expect.anything(),
    );
  });
  it("shows allocation errors without recalculation fallback", async () => {
    state.fail = true;
    render(<AllocationPanel month="2026-09" />);
    await screen.findByText("입력 오류");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
