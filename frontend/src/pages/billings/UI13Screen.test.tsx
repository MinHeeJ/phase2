import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI13Screen from "./UI13Screen";
import { BillingEditor } from "./BillingEditor";
import type { BillingDetail, BillingRow } from "./client";
import tables from "../developers/Developers.module.css";
import { seoulMonth } from "../shared/PeriodBar";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let row: BillingRow;
let detail: BillingDetail;
let fetcher: ReturnType<typeof vi.fn>;
let empty: boolean, failed: boolean, writeFailed: boolean;
let gapQuery: string, overdueQuery: string;
const year = Number(seoulMonth().slice(0, 4));
const month = seoulMonth();
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  row = {
    bill_id: 5,
    bill_stg_id: null,
    virtual_plan: false,
    bill_no: "B-TEST-01",
    prj_id: 8,
    project_name: "청구 시험 프로젝트",
    customer_id: 9,
    customer_name: "시험 고객",
    contract_type: "MO",
    atrb_ym: month,
    bill_dt: `${month}-10`,
    sply_amt: 10000,
    vat_amt: 1000,
    tot_amt: 11000,
    bill_stts_cd: "DRAFT",
    txinv_no: null,
    txinv_dt: null,
    rcpt_plan_dt: null,
    received_amt: 0,
    adjusted_total_amt: 11000,
    receivable_amt: 0,
    reminder_stage: null,
  };
  detail = {
    billing: row,
    details: [
      {
        asgn_id: 2,
        dev_id: 3,
        cntrct_dev_id: 3,
        display_name: "시험 인력",
        work_ym: month,
        mm_qty: 1,
        source_mm_qty: 1,
        untprc_amt: 10000,
        work_day_qty: null,
        work_tm_qty: null,
        ovtm_tm_qty: null,
        ovtm_amt: 0,
        bill_amt: 10000,
        rmrk_desc: null,
      },
    ],
    receipts: [],
    amendments: [],
    original_bill_id: null,
    change_history: [],
    warnings: [],
  };
  empty = false;
  failed = false;
  writeFailed = false;
  gapQuery = "";
  overdueQuery = "";
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method && init.method !== "GET") {
      if (writeFailed)
        return new Response(
          JSON.stringify({
            success: false,
            code: "VALIDATION_ERROR",
            message: "부가세를 확인하세요.",
            field: "vat_amt",
          }),
          { status: 400 },
        );
      return response(detail);
    }
    if (url.startsWith("/api/billings/summary"))
      return response({
        year,
        annual_confirmed_supply_amt: 20000,
        current_month_confirmed_supply_amt: 10000,
        confirmed_receivable_amt: 11000,
        overdue_amt: 5000,
        planned_supply_amt: 3000,
        draft_supply_amt: 10000,
      });
    if (url.startsWith("/api/billings/preview"))
      return response({
        project_id: 8,
        month,
        bill_stg_id: null,
        sply_amt: 10000,
        suggested_vat_amt: 1000,
        rcpt_plan_dt: null,
        details: detail.details,
        warnings: [],
        missing_price_assignments: [],
      });
    if (url.startsWith("/api/billings/gaps")) {
      gapQuery = url;
      return response({
        month,
        total_difference_amt: 10000,
        items: [
          {
            project_id: 8,
            project_name: row.project_name,
            month,
            basis_supply_amt: 10000,
            confirmed_supply_amt: 0,
            difference_amt: 10000,
            judgment: "청구 없음",
            explanation: "확정 청구가 없습니다.",
            attendance_missing: true,
            suspected_people: [],
            billing_ids: [],
          },
        ],
        total: 1,
        page: 0,
        size: 50,
      });
    }
    if (url.startsWith("/api/billings/overdue")) {
      overdueQuery = url;
      return response({
        items: [
          {
            billing: row,
            overdue_days: null,
            stage: null,
            due_status: "예정일 미입력",
            recipient_roles: [],
            next_notification_date: null,
            sent_history: [],
          },
        ],
        total: 1,
        page: 0,
        size: 50,
        customer_totals: [],
        stage_counts: [{ stage: 1, count: 1 }],
      });
    }
    if (url === "/api/billings/5") return response(detail);
    if (url.startsWith("/api/billings?")) {
      if (failed) throw new TypeError("offline");
      return response({
        items: empty ? [] : [row],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
      });
    }
    if (url === "/api/projects/options")
      return response([
        {
          id: 8,
          no: "P-TEST",
          name: row.project_name,
          type: "MO",
          custName: "시험 고객",
        },
        { id: 10, no: "P-IN", name: "내부 제외", type: "IN", custName: null },
      ]);
    if (url.includes("bill-stages"))
      return response({
        items: [],
        totalSupplyAmount: 0,
        cur_cntrct_amt: 10000,
      });
    if (url.includes("/api/partners/options"))
      return response([{ id: "9", no: "C-TEST", name: "시험 고객" }]);
    if (url === "/api/codes/BILL_STATUS")
      return response([
        { code: "DRAFT", name: "작성" },
        { code: "CONFIRM", name: "확정" },
      ]);
    if (url === "/api/codes/CONTRACT_TYPE")
      return response([
        { code: "TK", name: "턴키" },
        { code: "MO", name: "월단위" },
        { code: "IN", name: "내부" },
      ]);
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
  vi.spyOn(window, "confirm").mockReturnValue(true);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = () =>
  render(
    <MemoryRouter>
      <UI13Screen />
    </MemoryRouter>,
  );

describe("청구·입금 화면", () => {
  it("renders real API summaries and UI-02 exact table classes", async () => {
    mount();
    expect(await screen.findByText("B-TEST-01")).toBeInTheDocument();
    expect(screen.getByTestId("billing-summary-0")).toHaveTextContent(
      "20,000원",
    );
    const table = screen.getByRole("table", { name: "청구 목록" });
    expect(table).toHaveClass(tables.table);
    expect(table.parentElement).toHaveClass(tables.tableScroll);
    expect(screen.getByText(/예정 공급가 3,000원/)).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: row.project_name }),
    ).toHaveAttribute("href", "/UI-01?open=8");
  });
  it("preserves list filters independently across tabs and sends zero-based paging", async () => {
    mount();
    await screen.findByText("B-TEST-01");
    fireEvent.change(screen.getByTestId("billing-search"), {
      target: { value: "기억" },
    });
    fireEvent.click(screen.getByTestId("billing-query"));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("q=%EA%B8%B0%EC%96%B5"),
        expect.anything(),
      ),
    );
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    await screen.findByText("확정 청구가 없습니다.");
    fireEvent.change(screen.getByTestId("billing-gap-judgment"), {
      target: { value: "근태 미입력" },
    });
    fireEvent.click(screen.getByTestId("billing-gap-query"));
    await waitFor(() => expect(gapQuery).toContain("judgment="));
    fireEvent.click(screen.getByTestId("billing-tab-list"));
    expect(screen.getByTestId("billing-search")).toHaveValue("기억");
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    expect(screen.getByTestId("billing-gap-judgment")).toHaveValue(
      "근태 미입력",
    );
    expect(gapQuery).toContain("page=0");
  });
  it("empty list shows actionable empty state instead of sample rows", async () => {
    empty = true;
    mount();
    expect(
      await screen.findByText("청구 내역이 없습니다."),
    ).toBeInTheDocument();
    expect(screen.queryByText("B-TEST-01")).not.toBeInTheDocument();
  });
  it("failed request preserves filters and permits retry", async () => {
    failed = true;
    mount();
    expect(
      await screen.findByText("서버에 연결할 수 없습니다."),
    ).toBeInTheDocument();
    fireEvent.change(screen.getByTestId("billing-search"), {
      target: { value: "입력 보존" },
    });
    failed = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(await screen.findByText("B-TEST-01")).toBeInTheDocument();
    expect(screen.getByTestId("billing-search")).toHaveValue("입력 보존");
  });
  it("past year hides write controls but retains download", async () => {
    mount();
    await screen.findByText("B-TEST-01");
    fireEvent.click(screen.getByLabelText("이전 연도"));
    expect(screen.queryByTestId("billing-create")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-upload")).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-export")).toBeInTheDocument();
  });
  it("mobile hides writes without browser layout assertions", async () => {
    window.innerWidth = 600;
    mount();
    await screen.findByText("B-TEST-01");
    expect(screen.queryByTestId("billing-create")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-upload")).not.toBeInTheDocument();
  });
  it("confirmed bill shows immutable snapshot and no DRAFT edit actions", async () => {
    row.bill_stts_cd = "CONFIRM";
    row.receivable_amt = 11000;
    mount();
    await screen.findByText("B-TEST-01");
    fireEvent.click(screen.getByTestId("billing-open-5"));
    const drawer = await screen.findByRole("dialog", { name: "청구 상세" });
    await within(drawer).findByText("시험 인력");
    expect(
      within(drawer).queryByTestId("billing-edit"),
    ).not.toBeInTheDocument();
    expect(
      within(drawer).queryByTestId("billing-recalculate-now"),
    ).not.toBeInTheDocument();
    expect(within(drawer).getByText("미수")).toBeInTheDocument();
  });
  it("DRAFT detail opens update mode with immutable project and month", async () => {
    mount();
    await screen.findByText("B-TEST-01");
    fireEvent.click(screen.getByTestId("billing-open-5"));
    fireEvent.click(await screen.findByTestId("billing-edit"));
    await screen.findByRole("dialog", { name: "청구 수정" });
    expect(screen.getByTestId("billing-project")).toBeDisabled();
    expect(screen.getByTestId("billing-month")).toBeDisabled();
    expect(screen.getByTestId("billing-total")).toHaveAttribute("readonly");
  });
  it("overdue missing due and no history are explicit and actual reminder disabled", async () => {
    mount();
    await screen.findByText("B-TEST-01");
    fireEvent.click(screen.getByTestId("billing-tab-overdue"));
    expect(await screen.findByText("보낸 알림 없음")).toBeInTheDocument();
    expect(screen.getByText("예정일 미입력")).toBeInTheDocument();
    expect(screen.getByTestId("billing-remind")).toBeDisabled();
    fireEvent.change(screen.getByTestId("billing-overdue-days"), {
      target: { value: "30" },
    });
    fireEvent.click(screen.getByTestId("billing-overdue-query"));
    await waitFor(() => expect(overdueQuery).toContain("overDays=30"));
    expect(screen.getByRole("table", { name: "미수 알림" })).toHaveClass(
      tables.table,
    );
  });
});
describe("청구 등록/수정", () => {
  it("excludes IN project options and preview never writes business data", async () => {
    render(<BillingEditor onClose={vi.fn()} onSaved={vi.fn()} />);
    await screen.findByRole("option", { name: row.project_name });
    expect(
      screen.queryByRole("option", { name: "내부 제외" }),
    ).not.toBeInTheDocument();
    fireEvent.change(screen.getByTestId("billing-project"), {
      target: { value: "8" },
    });
    fireEvent.click(screen.getByTestId("billing-preview"));
    await screen.findByRole("table", { name: "인력별 청구 상세" });
    expect(screen.getByTestId("billing-supply")).toHaveValue(10000);
    expect(
      fetcher.mock.calls.every(
        ([, init]) => !init?.method || init.method === "GET",
      ),
    ).toBe(true);
  });
  it("PUT excludes immutable identity and preserves manual supply without recalculate", async () => {
    const saved = vi.fn().mockResolvedValue(undefined);
    render(
      <BillingEditor existing={detail} onClose={vi.fn()} onSaved={saved} />,
    );
    await screen.findByRole("option", { name: row.project_name });
    fireEvent.change(screen.getByTestId("billing-supply"), {
      target: { value: "7777" },
    });
    expect(
      screen.getByText(/상세 합과 공급가액이 다릅니다/),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    const call = fetcher.mock.calls.find(([, init]) => init?.method === "PUT")!;
    const payload = JSON.parse(call[1].body);
    expect(call[0]).toBe("/api/billings/5");
    expect(payload).toMatchObject({ sply_amt: 7777, recalculate: false });
    expect(payload).not.toHaveProperty("project_id");
    expect(payload).not.toHaveProperty("month");
    expect(payload).not.toHaveProperty("tot_amt");
    expect(payload).not.toHaveProperty("details");
  });
  it("failed write preserves dialog inputs and displays flat field feedback", async () => {
    writeFailed = true;
    render(
      <BillingEditor existing={detail} onClose={vi.fn()} onSaved={vi.fn()} />,
    );
    await screen.findByRole("option", { name: row.project_name });
    fireEvent.change(screen.getByTestId("billing-vat"), {
      target: { value: "999" },
    });
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    expect(await screen.findByText("부가세를 확인하세요.")).toBeInTheDocument();
    expect(screen.getByTestId("billing-vat")).toHaveValue(999);
    expect(screen.getByText("입력 항목: vat_amt")).toBeInTheDocument();
  });
  it("negative draft amounts are validated before sending a write", async () => {
    render(
      <BillingEditor existing={detail} onClose={vi.fn()} onSaved={vi.fn()} />,
    );
    fireEvent.change(screen.getByTestId("billing-supply"), {
      target: { value: "-1" },
    });
    expect(
      screen.getByText("금액은 0 이상의 원 단위 정수로 입력하세요."),
    ).toBeInTheDocument();
    expect(screen.getByTestId("billing-editor-save")).toBeDisabled();
  });
});
