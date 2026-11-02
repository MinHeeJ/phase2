import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI21Screen from "./UI21Screen";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let failed: boolean;
let initial: boolean;
let closed: boolean;
let rows: unknown[];
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-30T00:00:00Z"));
  sessionStorage.clear();
  failed = false;
  initial = true;
  closed = false;
  rows = [];
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (failed) throw new Error("offline");
    if (url.startsWith("/api/month-close"))
      return response([
        { month: "2026-10", status: closed ? "CLOSED" : "OPEN" },
      ]);
    if (url.includes("/other-inflows")) {
      if (init?.method === "POST" || init?.method === "PUT") {
        const row = JSON.parse(init.body as string);
        rows = [{ ...row, inflw_id: 7 }];
        return response({ ...row, inflw_id: 7 });
      }
      return response({ items: rows, total: rows.length });
    }
    if (url.includes("/opening")) {
      if (init?.method === "PUT") {
        initial = true;
        return response(JSON.parse(init.body as string));
      }
      return initial
        ? response({ month: "2026-10", amount: 100 })
        : new Response(
            JSON.stringify({
              success: false,
              code: "NOT_FOUND",
              message: "기초 잔액이 없습니다.",
            }),
            { status: 404 },
          );
    }
    if (!initial)
      return new Response(
        JSON.stringify({
          success: false,
          code: "OPENING_BALANCE_REQUIRED",
          message: "먼저 시작 월 기초 잔액을 입력하세요.",
        }),
        { status: 409 },
      );
    return response({
      year: Number(new URL(url, "http://test").searchParams.get("year")),
      mode: "BOTH",
      initial_month: "2026-10",
      first_risk_month: "2026-10",
      months: [
        {
          month: "2026-10",
          closed,
          planned_opening: 100,
          planned_inflow: 20,
          planned_outflow: 150,
          planned_closing: -30,
          actual_opening: 100,
          actual_inflow: 10,
          actual_outflow: 40,
          actual_closing: 70,
          forecast_closing: 70,
          warning: "RISK",
        },
      ],
      export_file: null,
    });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const writes = () =>
  fetcher.mock.calls.filter((c) => c[1]?.method && c[1].method !== "GET");
describe("UI-21 cashflow", () => {
  it("shows separate API chains and risk badge, shared table", async () => {
    render(<UI21Screen />);
    expect(screen.getAllByLabelText("불러오는 중").length).toBeGreaterThan(0);
    await screen.findByText("예상 최종 잔액");
    expect(screen.getByText("-30")).toBeVisible();
    expect(screen.getAllByText("70").length).toBeGreaterThan(0);
    expect(screen.getByText("위험").className).toContain("danger");
    expect(
      screen.getByRole("table", { name: "월별 자금수지" }).className,
    ).toContain("table");
    expect(writes()).toHaveLength(0);
  });
  it("switches year without another screen's period", async () => {
    render(<UI21Screen />);
    await screen.findByText("예상 최종 잔액");
    fireEvent.click(screen.getByLabelText("이전 연도"));
    await waitFor(() =>
      expect(fetcher.mock.calls.some((c) => c[0].includes("year=2025"))).toBe(
        true,
      ),
    );
    expect(screen.queryByTestId("cashflow-inflow-add")).not.toBeInTheDocument();
  });
  it("switches plan/actual visible columns via contract mode", async () => {
    render(<UI21Screen />);
    await screen.findByText("예상 최종 잔액");
    fireEvent.change(screen.getByLabelText("표시"), {
      target: { value: "PLAN" },
    });
    await waitFor(() =>
      expect(fetcher.mock.calls.some((c) => c[0].includes("mode=PLAN"))).toBe(
        true,
      ),
    );
    expect(
      screen.queryByRole("rowheader", { name: "실적 수입" }),
    ).not.toBeInTheDocument();
  });
  it("read failure provides retry, not empty data", async () => {
    failed = true;
    render(<UI21Screen />);
    await screen.findAllByText("서버에 연결할 수 없습니다.");
    expect(
      screen.queryByText("등록된 기타 수입이 없습니다."),
    ).not.toBeInTheDocument();
    failed = false;
    fireEvent.click(screen.getAllByText("다시 시도")[0]);
    await screen.findByText("예상 최종 잔액");
  });
  it("missing opening allows initial entry", async () => {
    initial = false;
    render(<UI21Screen />);
    fireEvent.click(await screen.findByTestId("cashflow-opening-button"));
    fireEvent.change(screen.getByLabelText("시작 월"), {
      target: { value: "2026-10" },
    });
    fireEvent.change(screen.getByLabelText("기초 잔액"), {
      target: { value: "0" },
    });
    fireEvent.click(screen.getByTestId("cashflow-opening-save"));
    await screen.findByText("저장했습니다.");
    expect(writes()[0][0]).toContain("/opening?month=2026-10");
  });
  it("existing opening is not editable", async () => {
    render(<UI21Screen />);
    await screen.findByText("예상 최종 잔액");
    expect(screen.getByTestId("cashflow-opening-button")).toBeDisabled();
  });
  it("empty inflows and zero amount creation refresh", async () => {
    render(<UI21Screen />);
    await screen.findByText("등록된 기타 수입이 없습니다.");
    fireEvent.click(screen.getByTestId("cashflow-inflow-add"));
    fireEvent.change(screen.getByLabelText("항목"), {
      target: { value: "대여금" },
    });
    fireEvent.change(screen.getByLabelText("금액"), { target: { value: "0" } });
    fireEvent.change(screen.getByLabelText("예정일"), {
      target: { value: "2026-10-15" },
    });
    fireEvent.click(screen.getByTestId("cashflow-inflow-save"));
    await screen.findByText("저장했습니다.");
    expect(JSON.parse(writes()[0][1].body as string).amount).toBe(0);
    expect(screen.getByTestId("cashflow-inflow-row-7")).toHaveTextContent(
      "대여금",
    );
  });
  it.each(["-1", "1.5", ""])("invalid won %s never writes", async (value) => {
    render(<UI21Screen />);
    await screen.findByText("등록된 기타 수입이 없습니다.");
    fireEvent.click(screen.getByTestId("cashflow-inflow-add"));
    fireEvent.change(screen.getByLabelText("금액"), { target: { value } });
    fireEvent.click(screen.getByTestId("cashflow-inflow-save"));
    expect(writes()).toHaveLength(0);
    expect(screen.getByRole("dialog")).toBeVisible();
  });
  it("closed original hides edit", async () => {
    closed = true;
    rows = [
      {
        inflw_id: 7,
        month: "2026-10",
        amount: 10,
        inflw_se_cd: "LOAN",
        inflw_item_nm: "대여금",
        planned_date: "2026-10-15",
        actual_date: null,
        remark: null,
      },
    ];
    render(<UI21Screen />);
    await screen.findByTestId("cashflow-inflow-row-7");
    expect(
      screen.queryByTestId("cashflow-inflow-edit-7"),
    ).not.toBeInTheDocument();
  });
  it("failed save retains draft", async () => {
    render(<UI21Screen />);
    await screen.findByText("등록된 기타 수입이 없습니다.");
    fireEvent.click(screen.getByTestId("cashflow-inflow-add"));
    fireEvent.change(screen.getByLabelText("항목"), {
      target: { value: "대여금" },
    });
    fireEvent.change(screen.getByLabelText("금액"), {
      target: { value: "10" },
    });
    fireEvent.change(screen.getByLabelText("예정일"), {
      target: { value: "2026-10-15" },
    });
    failed = true;
    fireEvent.click(screen.getByTestId("cashflow-inflow-save"));
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.getByLabelText("항목")).toHaveValue("대여금");
  });
  it("updates only the selected inflow identity", async () => {
    rows = [
      {
        inflw_id: 7,
        month: "2026-10",
        amount: 10,
        inflw_se_cd: "LOAN",
        inflw_item_nm: "대여금",
        planned_date: "2026-10-15",
        actual_date: null,
        remark: null,
      },
    ];
    render(<UI21Screen />);
    fireEvent.click(await screen.findByTestId("cashflow-inflow-edit-7"));
    fireEvent.change(screen.getByLabelText("금액"), {
      target: { value: "20" },
    });
    fireEvent.click(screen.getByTestId("cashflow-inflow-save"));
    await screen.findByText("저장했습니다.");
    expect(writes()[0][1].method).toBe("PUT");
    expect(JSON.parse(writes()[0][1].body as string)).toMatchObject({
      inflw_id: 7,
      amount: 20,
      inflw_se_cd: "LOAN",
    });
  });
  it("closed destination disables save and keeps form", async () => {
    closed = true;
    render(<UI21Screen />);
    await screen.findByText("등록된 기타 수입이 없습니다.");
    fireEvent.click(screen.getByTestId("cashflow-inflow-add"));
    fireEvent.change(screen.getByLabelText("예정일"), {
      target: { value: "2026-10-15" },
    });
    expect(screen.getByTestId("cashflow-inflow-save")).toBeDisabled();
    expect(screen.getByText("마감된 달은 변경할 수 없습니다.")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
});
