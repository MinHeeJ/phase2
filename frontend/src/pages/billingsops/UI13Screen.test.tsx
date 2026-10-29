import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI13Screen from "./UI13Screen";
import { UI13M1 } from "./UI13M1";
import { UI13M3 } from "./UI13M3";
import { UI13M4 } from "./UI13M4";
import type { BillingDetail, BillingGap, BillingRow } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let row: BillingRow, detail: BillingDetail, fetcher: ReturnType<typeof vi.fn>;
let failRead: boolean, failWrite: boolean, empty: boolean;
const reload = vi.fn().mockResolvedValue(undefined),
  saved = vi.fn(),
  close = vi.fn();
const gap: BillingGap = {
  project_id: 9,
  project_name: "월정산 프로젝트",
  month: "2026-09",
  basis_supply_amt: 100000,
  confirmed_supply_amt: 0,
  difference_amt: 100000,
  tolerance: 10000,
  judgment: "청구 없음",
  explanation: "신규 투입자 청구 누락 가능성이 있습니다.",
  attendance_missing: false,
  suspected_people: [{ name: "계약인력", management_number: "K-0099" }],
  billing_ids: [],
};
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-29T00:00:00Z"));
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  failRead = false;
  failWrite = false;
  empty = false;
  reload.mockClear();
  saved.mockClear();
  close.mockClear();
  row = {
    bill_id: 7,
    bill_stg_id: null,
    virtual_plan: false,
    bill_no: "B-2610-71",
    prj_id: 9,
    project_name: "월정산 프로젝트",
    customer_id: 2,
    customer_name: "고객사",
    contract_type: "MO",
    atrb_ym: "2026-10",
    bill_dt: "2026-10-01",
    sply_amt: 100000,
    vat_amt: 10000,
    tot_amt: 110000,
    bill_stts_cd: "DRAFT",
    txinv_no: null,
    txinv_dt: null,
    rcpt_plan_dt: null,
    received_amt: 0,
    adjusted_total_amt: 110000,
    receivable_amt: 110000,
    reminder_stage: null,
  };
  detail = {
    billing: row,
    details: [],
    receipts: [],
    amendments: [],
    original_bill_id: null,
    warnings: ["세금계산서 번호가 없습니다."],
    change_history: [],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method && init.method !== "GET") {
      if (failWrite)
        return new Response(
          JSON.stringify({
            success: false,
            code: "REFUND_REQUIRED",
            field: "sply_amt",
            message: "입금액보다 적게 조정할 수 없습니다.",
            requestId: "billing-request",
          }),
          { status: 409 },
        );
      return response(detail);
    }
    if (failRead) throw new Error("offline");
    if (url === "/api/projects/options")
      return response([
        { id: 9, no: "P-2699", name: "월정산 프로젝트", type: "MO" },
        { id: 8, no: "P-2698", name: "내부", type: "IN" },
      ]);
    if (url.startsWith("/api/billings/preview?"))
      return response({
        project_id: 9,
        bill_stg_id: null,
        month: "2026-10",
        sply_amt: 100000,
        suggested_vat_amt: 7500,
        rcpt_plan_dt: null,
        details: [],
        warnings: [],
        missing_price_assignments: [],
      });
    if (url.startsWith("/api/billings/summary?"))
      return response({
        year: 2026,
        annual_confirmed_supply_amt: 100000,
        current_month_confirmed_supply_amt: 100000,
        confirmed_receivable_amt: 110000,
        overdue_amt: 0,
        planned_supply_amt: 200000,
        draft_supply_amt: 100000,
        cash_receipts_vat_included_amt: 0,
      });
    if (url.startsWith("/api/billings/gaps?"))
      return response({
        month: "2026-09",
        total_difference_amt: 100000,
        items: empty ? [] : [gap],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
      });
    if (url.startsWith("/api/billings/overdue?"))
      return response({
        items: empty
          ? []
          : [
              {
                billing: row,
                overdue_days: null,
                stage: null,
                due_status: "예정일 미입력",
                recipient_roles: [],
                next_notification_date: null,
                sent_history: [],
                sales_part: null,
              },
            ],
        customer_totals: [
          { customer_id: 2, customer_name: "고객사", receivable_amt: 110000 },
        ],
        stage_counts: [{ stage: 0, count: 0 }],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
      });
    if (url.startsWith("/api/billings?"))
      return response({
        items: empty ? [] : [row],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
      });
    if (url === "/api/billings/7") return response(detail);
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});
const writes = () =>
  fetcher.mock.calls.filter((c) => c[1]?.method && c[1].method !== "GET");
const show = () =>
  render(
    <MemoryRouter>
      <UI13Screen />
    </MemoryRouter>,
  );
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
describe("billing queries and workflow", () => {
  it("shows real summary separate from plans and draft and opens selected bill", async () => {
    show();
    await screen.findByText("B-2610-71");
    expect(
      screen.getByText(/예정 200,000원 · 작성 100,000원/),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("billing-open-0"));
    expect(
      await screen.findByRole("dialog", { name: "청구 상세" }),
    ).toBeInTheDocument();
    expect(fetcher.mock.calls.some((c) => c[0] === "/api/billings/7")).toBe(
      true,
    );
  });
  it("gap filter persists between tabs and selected project/month reaches create", async () => {
    show();
    await screen.findByText("B-2610-71");
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    await screen.findByTestId("billing-gap-9");
    change("월", "2026-09");
    change("판정", "청구 없음");
    fireEvent.click(screen.getByTestId("billing-query"));
    await waitFor(() =>
      expect(fetcher.mock.calls.some((c) => c[0].includes("judgment="))).toBe(
        true,
      ),
    );
    fireEvent.click(screen.getByTestId("billing-tab-overdue"));
    await screen.findByText("보낸 알림 없음");
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    await screen.findByTestId("billing-gap-9");
    expect(screen.getByLabelText("판정")).toHaveValue("청구 없음");
    fireEvent.click(screen.getByTestId("billing-gap-action-9"));
    await screen.findByRole("dialog", { name: "청구 등록" });
    expect(screen.getByTestId("billing-editor-month")).toHaveValue("2026-09");
    expect(screen.getByTestId("billing-editor-project")).toHaveValue("9");
  });
  it("undated outstanding rows remain and never offer sending", async () => {
    show();
    await screen.findByText("B-2610-71");
    fireEvent.click(screen.getByTestId("billing-tab-overdue"));
    expect(await screen.findByText("예정일 미입력")).toBeInTheDocument();
    expect(screen.getByText("보낸 알림 없음")).toBeInTheDocument();
    expect(screen.getByTestId("billing-remind-7")).toBeDisabled();
    expect(
      screen.getByText(
        "알림 발송은 사용자·권한 기능 구축 후 사용할 수 있습니다.",
      ),
    ).toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("hides all write commands for past year while keeping export", async () => {
    show();
    await screen.findByText("B-2610-71");
    fireEvent.click(screen.getByTestId("billing-year-prev"));
    expect(screen.queryByTestId("billing-add")).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-export")).toBeInTheDocument();
    fireEvent.click(await screen.findByTestId("billing-open-0"));
    await screen.findByRole("dialog", { name: "청구 상세" });
    expect(screen.queryByTestId("billing-confirm")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-cancel")).not.toBeInTheDocument();
  });
  it("hides every mobile write action including amendments and cancellation", async () => {
    window.innerWidth = 767;
    row.bill_stts_cd = "CONFIRM";
    show();
    await screen.findByText("B-2610-71");
    expect(screen.queryByTestId("billing-add")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("billing-open-0"));
    await screen.findByRole("dialog", { name: "청구 상세" });
    expect(screen.queryByTestId("billing-amend")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-cancel")).not.toBeInTheDocument();
  });
  it("confirmed snapshot hides edit and recalculation but offers amendment", async () => {
    row.bill_stts_cd = "CONFIRM";
    show();
    await screen.findByText("B-2610-71");
    fireEvent.click(screen.getByTestId("billing-open-0"));
    await screen.findByRole("dialog", { name: "청구 상세" });
    expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-recalculate")).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-amend")).toBeInTheDocument();
  });
  it("confirmation sends no body and refreshes detail list summary", async () => {
    show();
    await screen.findByText("B-2610-71");
    fireEvent.click(screen.getByTestId("billing-open-0"));
    await screen.findByRole("dialog", { name: "청구 상세" });
    fireEvent.click(screen.getByTestId("billing-confirm"));
    fireEvent.click(screen.getByTestId("billing-confirm-submit"));
    await screen.findByTestId("billing-success");
    const call = writes().find((c) => c[0] === "/api/billings/7/confirm")!;
    expect(call[1]?.body).toBeUndefined();
    expect(
      fetcher.mock.calls.filter((c) => c[0] === "/api/billings/7").length,
    ).toBeGreaterThan(1);
    expect(
      fetcher.mock.calls.filter((c) =>
        c[0].startsWith("/api/billings/summary?"),
      ).length,
    ).toBeGreaterThan(1);
  });
  it.each(["list", "gaps", "overdue"])(
    "empty %s tab renders empty and reset, never sample rows",
    async (tab) => {
      empty = true;
      show();
      fireEvent.click(screen.getByTestId(`billing-tab-${tab}`));
      expect(
        await screen.findByText("조건에 맞는 결과가 없습니다"),
      ).toBeInTheDocument();
      expect(writes()).toHaveLength(0);
    },
  );
  it("failed reads show retryable error instead of fabricated rows", async () => {
    failRead = true;
    show();
    expect(
      await screen.findByText("서버에 연결할 수 없습니다."),
    ).toBeInTheDocument();
    expect(screen.queryByText("B-2610-71")).not.toBeInTheDocument();
  });
});
describe("billing command dialogs", () => {
  it("cancel requires reason and sends exact selected bill; close has no write", async () => {
    const view = render(
      <UI13M3 billing={row} onClose={close} onSaved={saved} reload={reload} />,
    );
    expect(screen.getByTestId("billing-cancel-submit")).toBeDisabled();
    change("취소 사유 *", "계약 취소");
    fireEvent.click(screen.getByTestId("billing-cancel-submit"));
    await waitFor(() => expect(saved).toHaveBeenCalledOnce());
    expect(JSON.parse(writes()[0][1]!.body as string)).toEqual({
      reason: "계약 취소",
    });
    expect(writes()[0][0]).toBe("/api/billings/7/cancel");
    view.unmount();
    fetcher.mockClear();
    render(
      <UI13M3 billing={row} onClose={close} onSaved={saved} reload={reload} />,
    );
    fireEvent.click(screen.getByTestId("billing-cancel-close"));
    expect(writes()).toHaveLength(0);
  });
  it("amendment blocks positive supply and retains input on refund rejection", async () => {
    render(
      <UI13M4 billing={row} onClose={close} onSaved={saved} reload={reload} />,
    );
    change("수정 사유 *", "정정");
    change("공급가액", "1");
    fireEvent.click(screen.getByTestId("billing-amend-save"));
    expect(screen.getByText(/사유와 원 단위/)).toBeInTheDocument();
    expect(writes()).toHaveLength(0);
    failWrite = true;
    change("공급가액", "-100000");
    fireEvent.click(screen.getByTestId("billing-amend-save"));
    await waitFor(() => expect(writes()).toHaveLength(1));
    await screen.findByText(/sply_amt:/);
    expect(screen.getByLabelText("공급가액")).toHaveValue("-100000");
    expect(saved).not.toHaveBeenCalled();
  });
  it("create preview uses source VAT and save includes identity only for create", async () => {
    render(
      <UI13M1
        projectId={9}
        month="2026-10"
        onClose={close}
        onSaved={saved}
        reload={reload}
      />,
    );
    await screen.findByRole("option", { name: "P-2699 · 월정산 프로젝트" });
    expect(
      screen.queryByRole("option", { name: "P-2698 · 내부" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("billing-editor-preview"));
    await waitFor(() =>
      expect(screen.getByLabelText("부가세")).toHaveValue("7500"),
    );
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    const input = JSON.parse(writes()[0][1]!.body as string);
    expect(input.project_id).toBe(9);
    expect(input.month).toBe("2026-10");
    expect(input.vat_amt).toBe(7500);
  });
  it("edit fixes identity and excludes identity keys in PUT", async () => {
    render(
      <UI13M1
        existing={detail}
        onClose={close}
        onSaved={saved}
        reload={reload}
      />,
    );
    await screen.findByText(/변경 불가/);
    await waitFor(() =>
      expect(screen.getByTestId("billing-editor-save")).not.toBeDisabled(),
    );
    expect(
      screen.queryByTestId("billing-editor-project"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("billing-editor-month"),
    ).not.toBeInTheDocument();
    change("공급가액", "120000");
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    const input = JSON.parse(writes()[0][1]!.body as string);
    expect(input).not.toHaveProperty("project_id");
    expect(input).not.toHaveProperty("stage_id");
    expect(input).not.toHaveProperty("month");
    expect(input).not.toHaveProperty("additional");
    expect(input).not.toHaveProperty("bill_odr_no");
    expect(writes()[0][0]).toBe("/api/billings/7");
  });
});
