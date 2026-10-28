import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
  cleanup,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, useLocation } from "react-router-dom";
import UI13Screen from "./UI13Screen";
import { UI13M1 } from "./UI13M1";
import { UI13M2 } from "./UI13M2";
import { UI13M3 } from "./UI13M3";
import { UI13M4 } from "./UI13M4";
import { UI13M5 } from "./UI13M5";
import type { BillingDetail, BillingRow } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failed = () =>
  new Response(
    JSON.stringify({
      success: false,
      code: "CLOSED_MONTH",
      message: "마감된 달입니다.",
      field: "month",
      requestId: "billing-test",
    }),
    { status: 409 },
  );
const row: BillingRow = {
  bill_id: 47,
  bill_stg_id: null,
  virtual_plan: false,
  bill_no: "B-2610-47",
  prj_id: 13,
  project_name: "청구 대상 프로젝트",
  customer_id: 9,
  customer_name: "시험 고객사",
  contract_type: "MO",
  atrb_ym: "2026-10",
  bill_dt: "2026-10-10",
  sply_amt: 100000,
  vat_amt: 10000,
  tot_amt: 110000,
  bill_stts_cd: "DRAFT",
  txinv_no: null,
  txinv_dt: null,
  rcpt_plan_dt: "2026-10-20",
  received_amt: 0,
  adjusted_total_amt: 110000,
  receivable_amt: 0,
  reminder_stage: null,
};
let billing: BillingRow,
  failRead: boolean,
  failWrite: boolean,
  empty: boolean,
  fetcher: ReturnType<typeof vi.fn>;
const detail = (): BillingDetail => ({
  billing,
  details: [],
  receipts: [],
  amendments: [],
  original_bill_id: null,
  change_history: [],
  warnings: ["세금계산서 번호 미입력"],
});
const onSaved = vi.fn().mockResolvedValue(undefined),
  onClose = vi.fn();
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  billing = { ...row };
  failRead = false;
  failWrite = false;
  empty = false;
  onSaved.mockClear();
  onClose.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method && init.method !== "GET") {
      if (failWrite) return failed();
      if (url.endsWith("/confirm"))
        billing = { ...billing, bill_stts_cd: "CONFIRM" };
      if (url.endsWith("/cancel"))
        billing = { ...billing, bill_stts_cd: "CANCEL" };
      return response(detail());
    }
    if (url === "/api/partners/options?kind=CUST")
      return response([{ id: 9, name: "시험 고객사" }]);
    if (url === "/api/projects/options")
      return response([
        { id: 13, no: "P-13", name: "청구 대상 프로젝트", type: "MO" },
        { id: 14, no: "P-14", name: "내부", type: "IN" },
        { id: 15, no: "P-15", name: "파견", type: "DP" },
      ]);
    if (url === "/api/projects/15")
      return response({ cntrct_ty_cd: "DP", stlm_mthd_cd: "HOUR" });
    if (url.startsWith("/api/dispatch-attendance"))
      return response({
        items: [
          {
            asgn_id: 4,
            dev_id: 7,
            work_day_qty: null,
            rglr_tm_qty: 80,
            ovtm_tm_qty: 0,
            mth_work_day_qty: 20,
            calculated_mm: null,
            assignment_mm: 1,
            warnings: [],
          },
        ],
      });
    if (url.startsWith("/api/billings/preview"))
      return response({
        project_id: 13,
        month: "2026-10",
        sply_amt: 100000,
        suggested_vat_amt: 10000,
        details: [],
        warnings: [],
        missing_price_assignments: [],
      });
    if (url.startsWith("/api/billings/summary"))
      return response({
        year: 2026,
        annual_confirmed_supply_amt: 100000,
        current_month_confirmed_supply_amt: 100000,
        confirmed_receivable_amt: 110000,
        overdue_amt: 0,
        planned_supply_amt: 0,
        draft_supply_amt: 100000,
      });
    if (url === "/api/billings/47") return response(detail());
    if (failRead) return failed();
    if (url.startsWith("/api/billings/gaps"))
      return response({
        items: empty
          ? []
          : [
              {
                project_id: 13,
                project_name: "청구 대상 프로젝트",
                month: "2026-10",
                basis_supply_amt: 100000,
                confirmed_supply_amt: 0,
                difference_amt: 100000,
                judgment: "청구 없음",
                explanation: "신규 투입 누락 가능",
                attendance_missing: false,
                suspected_people: [],
                billing_ids: [],
              },
            ],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
        month: "2026-10",
        total_difference_amt: 100000,
      });
    if (url.startsWith("/api/billings/overdue"))
      return response({
        items: empty
          ? []
          : [
              {
                billing: {
                  ...billing,
                  bill_stts_cd: "CONFIRM",
                  receivable_amt: 110000,
                },
                overdue_days: 7,
                stage: 1,
                due_status: "연체",
                recipient_roles: ["영업 담당자", "PM"],
                sent_history: [],
                next_notification_date: "2026-11-19",
                sales_part: null,
              },
            ],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
        stage_counts: [{ stage: 1, count: 1 }],
        customer_totals: [
          { customer_name: "시험 고객사", receivable_amt: 110000 },
        ],
      });
    return response({
      items: empty ? [] : [billing],
      total: empty ? 0 : 1,
      page: 0,
      size: 50,
    });
  });
  vi.stubGlobal("fetch", fetcher);
  vi.spyOn(window, "confirm").mockReturnValue(true);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
const mount = () =>
  render(
    <MemoryRouter>
      <UI13Screen />
    </MemoryRouter>,
  );
describe("청구·입금 화면", () => {
  it("renders API amounts and shared list table, without writing on read", async () => {
    mount();
    expect(await screen.findByTestId("billing-open-47")).toHaveTextContent(
      "B-2610-47",
    );
    expect(screen.getByTestId("billing-summary")).toHaveTextContent(
      "연간 청구(공급가) 100,000원",
    );
    expect(screen.getByRole("table").className).toContain("table");
    expect(fetcher.mock.calls.every(([, init]) => !init?.method)).toBe(true);
  });
  it("keeps each tab's search and filters", async () => {
    mount();
    await screen.findByTestId("billing-open-47");
    fireEvent.change(screen.getByTestId("billing-search"), {
      target: { value: "목록 조건" },
    });
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    await screen.findByText("총 차이 100,000원");
    fireEvent.change(screen.getByTestId("billing-search"), {
      target: { value: "누락 조건" },
    });
    fireEvent.click(screen.getByTestId("billing-tab-billings"));
    expect(screen.getByTestId("billing-search")).toHaveValue("목록 조건");
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    expect(screen.getByTestId("billing-search")).toHaveValue("누락 조건");
  });
  it("links the project using the existing project detail query parameter", async () => {
    function Location() {
      const location = useLocation();
      return (
        <output data-testid="billing-test-location">
          {location.pathname + location.search}
        </output>
      );
    }
    render(
      <MemoryRouter>
        <UI13Screen />
        <Location />
      </MemoryRouter>,
    );
    fireEvent.click(await screen.findByTestId("billing-project-47"));
    expect(screen.getByTestId("billing-test-location")).toHaveTextContent(
      "/UI-01?open=13",
    );
  });
  it("shows overdue roles, actual empty sent history and disabled reminders", async () => {
    mount();
    fireEvent.click(screen.getByTestId("billing-tab-overdue"));
    expect(await screen.findByText("보낸 알림 없음")).toBeInTheDocument();
    expect(screen.getByText("영업 담당자 · PM")).toBeInTheDocument();
    expect(screen.getByTestId("billing-remind")).toBeDisabled();
  });
  it("shows empty state instead of fabricated rows", async () => {
    empty = true;
    mount();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
  });
  it("shows failure and retry without losing filters", async () => {
    failRead = true;
    mount();
    expect(await screen.findByRole("alert")).toHaveTextContent("billing-test");
    failRead = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("billing-open-47")).toBeInTheDocument();
  });
  it("hides every write affordance on mobile", async () => {
    Object.defineProperty(window, "innerWidth", { value: 767 });
    mount();
    await screen.findByTestId("billing-open-47");
    expect(screen.queryByTestId("billing-create")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-upload")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("billing-open-47"));
    await screen.findByTestId("billing-detail");
    expect(screen.queryByTestId("billing-confirm")).not.toBeInTheDocument();
  });
  it("past year is read-only independently of API month close", async () => {
    mount();
    await screen.findByTestId("billing-open-47");
    fireEvent.click(screen.getByTestId("billing-year-previous"));
    expect(screen.queryByTestId("billing-create")).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-export")).toBeInTheDocument();
  });
  it("opens selected ID and confirms with a second explicit action then refreshes", async () => {
    mount();
    fireEvent.click(await screen.findByTestId("billing-open-47"));
    await screen.findByTestId("billing-detail");
    fireEvent.click(screen.getByTestId("billing-confirm"));
    expect(fetcher.mock.calls.some(([, i]) => i?.method === "POST")).toBe(
      false,
    );
    fireEvent.click(screen.getByTestId("billing-confirm-submit"));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        "/api/billings/47/confirm",
        expect.objectContaining({ method: "POST" }),
      ),
    );
    await waitFor(() =>
      expect(
        screen.queryByTestId("billing-confirm-dialog"),
      ).not.toBeInTheDocument(),
    );
    expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument();
  });
  it("cancel dialog closing sends no request", () => {
    render(<UI13M3 billing={billing} onSaved={onSaved} onClose={onClose} />);
    fireEvent.click(screen.getByTestId("billing-cancel-close"));
    expect(onClose).toHaveBeenCalled();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("cancel requires reason and preserves it on conflict", async () => {
    failWrite = true;
    render(<UI13M3 billing={billing} onSaved={onSaved} onClose={onClose} />);
    fireEvent.change(screen.getByTestId("billing-cancel-reason"), {
      target: { value: "업무 취소" },
    });
    fireEvent.submit(screen.getByTestId("billing-cancel-dialog"));
    expect(await screen.findByRole("alert")).toHaveTextContent("마감된 달");
    expect(screen.getByTestId("billing-cancel-reason")).toHaveValue(
      "업무 취소",
    );
    expect(onSaved).not.toHaveBeenCalled();
  });
  it("amendment enforces negative/nonpositive amounts and sends only candidate", async () => {
    render(<UI13M4 billing={billing} onSaved={onSaved} onClose={onClose} />);
    expect(screen.getByTestId("billing-amendment-save")).toBeDisabled();
    fireEvent.change(screen.getByTestId("billing-amendment-reason"), {
      target: { value: "감액" },
    });
    fireEvent.change(screen.getByTestId("billing-amendment-sply-amt"), {
      target: { value: "200" },
    });
    fireEvent.change(screen.getByTestId("billing-amendment-vat-amt"), {
      target: { value: "0" },
    });
    expect(screen.getByTestId("billing-amendment-save")).toBeDisabled();
    fireEvent.change(screen.getByTestId("billing-amendment-sply-amt"), {
      target: { value: "-200" },
    });
    fireEvent.submit(screen.getByTestId("billing-amendment-dialog"));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(fetcher).toHaveBeenCalledWith(
      "/api/billings/47/amendments",
      expect.objectContaining({
        method: "POST",
        body: expect.stringContaining('"sply_amt":-200'),
      }),
    );
  });
  it("amendment failure retains input and root identity", async () => {
    failWrite = true;
    render(<UI13M4 billing={billing} onSaved={onSaved} onClose={onClose} />);
    fireEvent.change(screen.getByTestId("billing-amendment-reason"), {
      target: { value: "감액" },
    });
    fireEvent.change(screen.getByTestId("billing-amendment-sply-amt"), {
      target: { value: "-200" },
    });
    fireEvent.change(screen.getByTestId("billing-amendment-vat-amt"), {
      target: { value: "0" },
    });
    fireEvent.submit(screen.getByTestId("billing-amendment-dialog"));
    await screen.findByRole("alert");
    expect(screen.getByTestId("billing-amendment-sply-amt")).toHaveValue(
      "-200",
    );
    expect(onClose).not.toHaveBeenCalled();
  });
  it("edit lifecycle identity is disabled and excluded from payload", async () => {
    render(
      <UI13M1
        detail={detail()}
        onSaved={onSaved}
        onClose={onClose}
        onAttendance={() => {}}
      />,
    );
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        "/api/projects/options",
        expect.anything(),
      ),
    );
    expect(screen.getByTestId("billing-editor-project")).toBeDisabled();
    expect(screen.getByTestId("billing-editor-month")).toBeDisabled();
    fireEvent.submit(screen.getByTestId("billing-editor-dialog"));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const call = fetcher.mock.calls.find(([, i]) => i?.method === "PUT")!;
    const body = JSON.parse(call[1].body as string);
    expect(body).not.toHaveProperty("project_id");
    expect(body).not.toHaveProperty("month");
    expect(body).not.toHaveProperty("stage_id");
  });
  it("new editor excludes internal projects and requires successful preview", async () => {
    render(
      <UI13M1 onSaved={onSaved} onClose={onClose} onAttendance={() => {}} />,
    );
    await screen.findByRole("option", { name: "P-13 청구 대상 프로젝트" });
    expect(
      screen.queryByRole("option", { name: "P-14 내부" }),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-editor-save")).toBeDisabled();
    fireEvent.change(screen.getByTestId("billing-editor-project"), {
      target: { value: "13" },
    });
    fireEvent.click(screen.getByTestId("billing-editor-preview"));
    await waitFor(() =>
      expect(screen.getByTestId("billing-editor-save")).not.toBeDisabled(),
    );
    expect(screen.getByTestId("billing-editor-sply-amt")).toHaveValue("100000");
  });
  it("receipt delete uses selected child path and has no save fields", async () => {
    render(
      <UI13M5
        billing={billing}
        receipt={{
          receipt_id: 83,
          rcpt_dt: "2026-10-12",
          rcpt_amt: 100,
          rmrk_desc: null,
        }}
        mode="delete"
        onSaved={onSaved}
        onClose={onClose}
      />,
    );
    expect(
      screen.queryByTestId("billing-receipt-amount"),
    ).not.toBeInTheDocument();
    fireEvent.submit(screen.getByTestId("billing-receipt-dialog"));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(fetcher).toHaveBeenCalledWith(
      "/api/billings/47/receipts/83",
      expect.objectContaining({ method: "DELETE" }),
    );
  });
  it("receipt conflict preserves supplied date and amount", async () => {
    failWrite = true;
    render(<UI13M5 billing={billing} onSaved={onSaved} onClose={onClose} />);
    fireEvent.change(screen.getByTestId("billing-receipt-date"), {
      target: { value: "2026-10-12" },
    });
    fireEvent.change(screen.getByTestId("billing-receipt-amount"), {
      target: { value: "100" },
    });
    fireEvent.submit(screen.getByTestId("billing-receipt-dialog"));
    await screen.findByRole("alert");
    expect(screen.getByTestId("billing-receipt-amount")).toHaveValue("100");
  });
  it("attendance is DP-only and HOUR permits blank days", async () => {
    render(<UI13M2 onSaved={onSaved} onClose={onClose} />);
    await screen.findByRole("option", { name: "파견" });
    expect(
      screen.queryByRole("option", { name: "청구 대상 프로젝트" }),
    ).not.toBeInTheDocument();
    fireEvent.change(screen.getByTestId("billing-attendance-project"), {
      target: { value: "15" },
    });
    fireEvent.click(screen.getByTestId("billing-attendance-load"));
    const r = await screen.findByTestId("billing-attendance-row-4");
    expect(
      within(r).getByTestId("billing-attendance-work-day-qty-4"),
    ).not.toBeRequired();
    expect(
      within(r).getByTestId("billing-attendance-rglr-tm-qty-4"),
    ).toBeRequired();
    fireEvent.click(screen.getByTestId("billing-attendance-save"));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const call = fetcher.mock.calls.find(([, init]) => init?.method === "PUT")!;
    const body = JSON.parse(call[1].body as string);
    expect(body.items[0]).not.toHaveProperty("work_day_qty");
    expect(body.items[0].rglr_tm_qty).toBe(80);
  });
});
