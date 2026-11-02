import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
  cleanup,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { OverduePanel } from "./OverduePanel";
import type { BillingScreenContext } from "../billings/UI13Screen";
import tables from "../developers/Developers.module.css";
vi.mock("../uploadsops/client", () => ({
  downloadUploadFile: vi.fn().mockResolvedValue(undefined),
}));
import { downloadUploadFile } from "../uploadsops/client";
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let empty: boolean, fail: boolean;
let fetcher: ReturnType<typeof vi.fn>;
const openBilling = vi.fn();
const context: BillingScreenContext = {
  year: 2026,
  readOnly: false,
  openBilling,
  refresh: vi.fn(),
  createBilling: vi.fn(),
};
function row(id: number, stage: number | null) {
  return {
    billing: {
      bill_id: id,
      bill_no: `B-${id}`,
      project_name: `프로젝트 ${id}`,
      customer_name: "고객사",
      receivable_amt: 700,
      rcpt_plan_dt: stage == null ? null : "2026-09-01",
    },
    overdue_days: stage == null ? null : stage * 30,
    stage,
    due_status: stage == null ? "예정일 미입력" : "연체",
    recipient_roles: ["PM"],
    next_notification_date: null,
    sent_history: [],
  };
}
beforeEach(() => {
  empty = false;
  fail = false;
  vi.clearAllMocks();
  vi.mocked(downloadUploadFile).mockResolvedValue(undefined);
  fetcher = vi.fn(async (url: string) => {
    if (url.startsWith("/api/partners/options"))
      return response([{ id: "2", name: "고객사" }]);
    if (fail)
      return new Response(
        JSON.stringify({
          success: false,
          code: "INTERNAL_ERROR",
          message: "조회 실패",
          requestId: "overdue-read",
        }),
        { status: 500 },
      );
    return response({
      items: empty ? [] : [row(7, 1), row(8, 2), row(9, 3), row(10, null)],
      total: empty ? 0 : 4,
      page: 0,
      size: 50,
      customer_totals: empty
        ? []
        : [{ customer_id: 2, customer_name: "고객사", receivable_amt: 2800 }],
      stage_counts: [
        { stage: 1, count: 1 },
        { stage: 2, count: 1 },
        { stage: 3, count: 1 },
      ],
    });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
describe("미수 알림", () => {
  it("renders reference columns, shared tables, warning/danger badges and missing due state", async () => {
    render(<OverduePanel {...context} />);
    await screen.findByText("미수 청구 4건");
    expect(screen.getByRole("table").className).toContain(tables.table);
    for (const name of [
      "프로젝트",
      "청구번호",
      "미수금",
      "입금 예정일",
      "지난 일수",
      "지금 단계",
      "보낸 알림",
      "다음 알림",
    ])
      expect(screen.getByRole("columnheader", { name })).toBeInTheDocument();
    expect(
      within(screen.getByTestId("overdue-row-7")).getByText("1단계 · 30일")
        .className,
    ).toContain("warning");
    expect(
      within(screen.getByTestId("overdue-row-8")).getByText("2단계 · 60일")
        .className,
    ).toContain("danger");
    expect(
      within(screen.getByTestId("overdue-row-9")).getByText("3단계 · 90일")
        .className,
    ).toContain("danger");
    expect(
      within(screen.getByTestId("overdue-row-10")).getAllByText(
        "예정일 미입력",
      ),
    ).toHaveLength(2);
    expect(
      screen.queryByRole("button", { name: "다시 알림" }),
    ).not.toBeInTheDocument();
  });
  it("opens selected billing instead of a fixed sample id", async () => {
    render(<OverduePanel {...context} />);
    fireEvent.click(await screen.findByTestId("overdue-open-8"));
    expect(openBilling).toHaveBeenCalledWith(8);
  });
  it("applies filters only after 조회 and resets them", async () => {
    render(<OverduePanel {...context} />);
    await screen.findByText("미수 청구 4건");
    const before = fetcher.mock.calls.length;
    fireEvent.change(screen.getByLabelText("단계"), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText("검색"), {
      target: { value: "청구" },
    });
    expect(fetcher.mock.calls.length).toBe(before);
    fireEvent.click(screen.getByTestId("overdue-search"));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("stage=2"),
        expect.anything(),
      ),
    );
    await screen.findByText("미수 청구 4건");
    fireEvent.click(screen.getByTestId("overdue-reset"));
    expect(screen.getByLabelText("단계")).toHaveValue("");
  });
  it("exports applied scope without pagination", async () => {
    render(<OverduePanel {...context} />);
    await screen.findByText("미수 청구 4건");
    fireEvent.click(screen.getByTestId("overdue-export"));
    await waitFor(() => expect(downloadUploadFile).toHaveBeenCalled());
    const url = vi.mocked(downloadUploadFile).mock.calls[0][0];
    expect(url).toContain("/api/billings/overdue/export?");
    expect(url).toContain("year=2026");
    expect(url).not.toContain("page=");
    expect(url).not.toContain("size=");
  });
  it("shows empty results without inventing rows", async () => {
    empty = true;
    render(<OverduePanel {...context} />);
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("overdue-row-7")).not.toBeInTheDocument();
    expect(screen.getByTestId("overdue-empty-reset")).toBeInTheDocument();
  });
  it("shows request failure and retry", async () => {
    fail = true;
    render(<OverduePanel {...context} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("조회 실패");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    fail = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("미수 청구 4건");
  });
  it("shows export failure while preserving results", async () => {
    vi.mocked(downloadUploadFile).mockRejectedValue(new Error("다운로드 실패"));
    render(<OverduePanel {...context} />);
    await screen.findByText("미수 청구 4건");
    fireEvent.click(screen.getByTestId("overdue-export"));
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByTestId("overdue-row-7")).toBeInTheDocument();
  });
  it("keeps read-only data and download visible without write controls", async () => {
    render(<OverduePanel {...context} readOnly />);
    await screen.findByText("미수 청구 4건");
    expect(screen.getByTestId("overdue-export")).toBeEnabled();
    expect(
      screen.queryByRole("button", { name: /등록|수정|삭제|발송/ }),
    ).not.toBeInTheDocument();
  });
  it("resets filters and requests selected year when parent period changes", async () => {
    const { rerender } = render(<OverduePanel {...context} />);
    await screen.findByText("미수 청구 4건");
    fireEvent.change(screen.getByLabelText("검색"), {
      target: { value: "old" },
    });
    rerender(<OverduePanel {...context} year={2025} />);
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("year=2025"),
        expect.anything(),
      ),
    );
    expect(screen.getByLabelText("검색")).toHaveValue("");
  });
});
