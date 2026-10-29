import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import UI13Screen from "./UI13Screen";
import { BillingActionDialog, BillingDetailPanel } from "./BillingDetailPanel";
import type { BillingDetail, BillingRow } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let failed: boolean;
let failedWrite: boolean;
let rows: BillingRow[];
const row: BillingRow = {
  bill_id: 41,
  bill_stg_id: null,
  virtual_plan: false,
  bill_no: "B-2610-41",
  prj_id: 5,
  project_name: "정산 검증 프로젝트",
  customer_id: 8,
  customer_name: "시험 고객",
  contract_type: "MO",
  atrb_ym: "2026-10",
  bill_dt: "2026-10-21",
  sply_amt: 100000,
  vat_amt: 10000,
  tot_amt: 110000,
  bill_stts_cd: "DRAFT",
  txinv_no: null,
  txinv_dt: null,
  rcpt_plan_dt: "2026-10-25",
  received_amt: 0,
  adjusted_total_amt: 110000,
  receivable_amt: 0,
  reminder_stage: null,
};
const detail = (): BillingDetail => ({
  billing: rows[0],
  details: [],
  receipts: [],
  amendments: [],
  original_bill_id: null,
  change_history: [],
  warnings: ["세금계산서 번호가 없습니다."],
});
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-29T00:00:00Z"));
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  failed = false;
  failedWrite = false;
  rows = [{ ...row }];
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method && init.method !== "GET") {
      if (failedWrite)
        return new Response(
          JSON.stringify({
            success: false,
            code: "REFUND_REQUIRED",
            message: "환불 처리가 필요합니다.",
            field: "sply_amt",
            requestId: "billing-test",
          }),
          { status: 409 },
        );
      if (url.endsWith("confirm"))
        rows = [{ ...rows[0], bill_stts_cd: "CONFIRM" }];
      if (url.endsWith("cancel"))
        rows = [{ ...rows[0], bill_stts_cd: "CANCEL" }];
      return response(detail());
    }
    if (url.startsWith("/api/billings/summary"))
      return response({
        year: 2026,
        annual_confirmed_supply_amt: 100000,
        current_month_confirmed_supply_amt: 100000,
        confirmed_receivable_amt: 110000,
        overdue_amt: 0,
        planned_supply_amt: 300000,
        draft_supply_amt: 100000,
      });
    if (failed) throw new Error("offline");
    if (url.startsWith("/api/billings/gaps"))
      return response({
        month: "2026-09",
        items: [
          {
            project_id: 5,
            project_name: row.project_name,
            month: "2026-09",
            basis_supply_amt: 100000,
            confirmed_supply_amt: 0,
            difference_amt: 100000,
            tolerance: 10000,
            judgment: "근태 미입력",
            explanation: "근태 미입력 — 맞음 아님",
            attendance_missing: true,
            suspected_people: [],
            billing_ids: [41],
          },
        ],
        total_difference_amt: 100000,
        total: 1,
        page: 0,
        size: 50,
      });
    if (url.startsWith("/api/billings/overdue"))
      return response({
        items: [
          {
            billing: {
              ...row,
              bill_stts_cd: "CONFIRM",
              receivable_amt: 110000,
            },
            overdue_days: 7,
            stage: 1,
            due_status: "연체",
            recipient_roles: ["영업 담당자", "PM"],
            next_notification_date: "2026-11-24",
            sent_history: [],
            sales_part: null,
          },
        ],
        total: 1,
        page: 0,
        size: 50,
        stage_counts: [{ stage: 1, count: 1 }],
        customer_totals: [
          {
            customer_id: 8,
            customer_name: "시험 고객",
            receivable_amt: 110000,
          },
        ],
      });
    if (url.startsWith("/api/billings?"))
      return response({ items: rows, total: rows.length, page: 0, size: 50 });
    if (url === "/api/billings/41") return response(detail());
    if (url.startsWith("/api/projects/options"))
      return response([
        {
          id: 5,
          name: row.project_name,
          no: "P-2605",
          type: "MO",
          custName: "시험 고객",
        },
      ]);
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
describe("billing review", () => {
  it("renders shared table classes, actual amounts and separated planned draft totals", async () => {
    render(<UI13Screen />);
    await screen.findByTestId("billing-row-41");
    expect(screen.getByTestId("billing-table").className).toContain("table");
    expect(screen.getByTestId("billing-summary")).toHaveTextContent(
      "예정 300,000원 · 작성 100,000원",
    );
    expect(screen.getByTestId("billing-row-41")).toHaveTextContent("100,000");
  });
  it("loads detail and confirms selected physical row then refreshes summary and list", async () => {
    render(<UI13Screen />);
    fireEvent.click(await screen.findByTestId("billing-open-0"));
    fireEvent.click(await screen.findByTestId("billing-confirm"));
    expect(
      screen.getByText(
        "확정 뒤 공급가액과 원천 상세는 변경할 수 없습니다. 세금계산서는 기록만 합니다.",
      ),
    ).toBeVisible();
    fireEvent.click(screen.getByTestId("billing-action-save"));
    await screen.findByTestId("billing-success");
    expect(
      fetcher.mock.calls.some(
        (c) => c[0] === "/api/billings/41/confirm" && c[1]?.method === "POST",
      ),
    ).toBe(true);
    await waitFor(() =>
      expect(screen.queryByTestId("billing-confirm")).not.toBeInTheDocument(),
    );
    expect(screen.getByTestId("billing-row-41")).toHaveTextContent("CONFIRM");
  });
  it("keeps gap filters when switching tabs and transmits real month judgment", async () => {
    render(<UI13Screen />);
    await screen.findByTestId("billing-row-41");
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    await screen.findByTestId("billing-gap-5");
    fireEvent.change(screen.getByTestId("billing-gap-month"), {
      target: { value: "2026-08" },
    });
    fireEvent.change(screen.getByTestId("billing-judgment"), {
      target: { value: "근태 미입력" },
    });
    fireEvent.click(screen.getByTestId("billing-query"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some((c) => c[0].includes("month=2026-08")),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId("billing-tab-overdue"));
    await screen.findByTestId("billing-overdue-41");
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    expect(screen.getByTestId("billing-gap-month")).toHaveValue("2026-08");
    expect(screen.getByTestId("billing-judgment")).toHaveValue("근태 미입력");
  });
  it("shows next notification, recipient labels, actual absence of history and disabled resend", async () => {
    render(<UI13Screen />);
    fireEvent.click(screen.getByTestId("billing-tab-overdue"));
    const r = await screen.findByTestId("billing-overdue-41");
    expect(r).toHaveTextContent("2026-11-24");
    expect(r).toHaveTextContent("영업 담당자 / PM");
    expect(r).toHaveTextContent("보낸 알림 없음");
    expect(screen.getByTestId("billing-remind-41")).toBeDisabled();
    expect(
      screen.getByText(
        "알림 발송은 사용자·권한 기능 구축 후 사용할 수 있습니다.",
      ),
    ).toBeVisible();
  });
  it("past year is read only but still queries year and retains download", async () => {
    render(<UI13Screen />);
    await screen.findByTestId("billing-row-41");
    fireEvent.click(screen.getByTestId("billing-year-previous"));
    expect(screen.queryByTestId("billing-create")).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-download")).toBeVisible();
    await waitFor(() =>
      expect(fetcher.mock.calls.some((c) => c[0].includes("year=2025"))).toBe(
        true,
      ),
    );
  });
  it("mobile hides all mutation actions and preserves table and download", async () => {
    Object.defineProperty(window, "innerWidth", { value: 767 });
    render(<UI13Screen />);
    await screen.findByTestId("billing-row-41");
    expect(screen.queryByTestId("billing-create")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("billing-open-0"));
    await screen.findByTestId("billing-detail");
    expect(screen.queryByTestId("billing-confirm")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-download")).toBeVisible();
  });
  it("renders empty state from API", async () => {
    rows = [];
    render(<UI13Screen />);
    expect(
      await screen.findByText("조회 결과가 없습니다. 조회 조건을 확인하세요."),
    ).toBeVisible();
  });
  it("shows loading and request failure then retries without losing filters", async () => {
    failed = true;
    render(<UI13Screen />);
    expect(screen.getByTestId("billing-loading")).toBeVisible();
    await screen.findByText("서버에 연결할 수 없습니다.");
    failed = false;
    fireEvent.click(screen.getByTestId("billing-retry"));
    await screen.findByTestId("billing-row-41");
  });
  it("cancel requires reason and closing never posts", async () => {
    const saved = vi.fn(),
      close = vi.fn();
    render(
      <BillingActionDialog
        detail={detail()}
        action="cancel"
        onClose={close}
        onSaved={saved}
      />,
    );
    fireEvent.click(screen.getByTestId("billing-action-save"));
    expect(
      await screen.findByText("사유를 입력하세요. (reason)"),
    ).toBeVisible();
    expect(fetcher).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("billing-action-close"));
    expect(close).toHaveBeenCalledOnce();
  });
  it("amendment requires negative supply and nonpositive VAT", async () => {
    render(
      <BillingActionDialog
        detail={detail()}
        action="amendments"
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByLabelText("사유"), {
      target: { value: "변경" },
    });
    fireEvent.change(screen.getByLabelText("공급가액"), {
      target: { value: "1" },
    });
    fireEvent.change(screen.getByLabelText("부가세"), {
      target: { value: "0" },
    });
    fireEvent.click(screen.getByTestId("billing-action-save"));
    await screen.findByText(
      "공급가는 음수, 부가세는 0 이하의 정수로 입력하세요.",
    );
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("server field error keeps amendment input and dialog", async () => {
    failedWrite = true;
    render(
      <BillingActionDialog
        detail={detail()}
        action="amendments"
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByLabelText("사유"), {
      target: { value: "환불 검증" },
    });
    fireEvent.change(screen.getByLabelText("공급가액"), {
      target: { value: "-10000" },
    });
    fireEvent.change(screen.getByLabelText("부가세"), {
      target: { value: "-1000" },
    });
    fireEvent.click(screen.getByTestId("billing-action-save"));
    await screen.findByText("환불 처리가 필요합니다. (sply_amt)");
    expect(screen.getByLabelText("사유")).toHaveValue("환불 검증");
    expect(screen.getByRole("dialog")).toBeVisible();
  });
  it("confirmed details have no editing or recalculation and permit original amendment", () => {
    rows[0] = { ...row, bill_stts_cd: "CONFIRM" };
    render(
      <BillingDetailPanel
        detail={detail()}
        readOnly={false}
        onClose={vi.fn()}
        onChanged={vi.fn()}
        onEdit={vi.fn()}
      />,
    );
    expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-recalculate")).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-amend")).toBeVisible();
  });
  it("editor keeps immutable identity disabled in edit mode", async () => {
    render(<UI13Screen />);
    fireEvent.click(await screen.findByTestId("billing-open-0"));
    fireEvent.click(await screen.findByTestId("billing-edit"));
    expect(screen.getByTestId("billing-project")).toBeDisabled();
    expect(screen.getByTestId("billing-month")).toBeDisabled();
    await within(screen.getByTestId("billing-project")).findByText(
      row.project_name,
    );
  });
});
