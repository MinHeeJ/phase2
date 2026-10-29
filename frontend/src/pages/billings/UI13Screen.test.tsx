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
import { UI13M1 } from "./UI13M1";
import { UI13DetailPanel } from "./UI13DetailPanel";
import type { BillingDetail } from "./client";
import tables from "../developers/Developers.module.css";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let detail: BillingDetail;
let fetcher: ReturnType<typeof vi.fn>;
let empty: boolean, failRead: boolean, failWrite: boolean, closed: boolean;
const saved = vi.fn(),
  cancelled = vi.fn(),
  reload = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-20T00:00:00Z"));
  empty = false;
  failRead = false;
  failWrite = false;
  closed = false;
  saved.mockClear();
  cancelled.mockClear();
  reload.mockClear();
  detail = {
    billing: {
      bill_id: 7,
      bill_stg_id: null,
      virtual_plan: false,
      bill_no: "B-2610-07",
      prj_id: 3,
      project_name: "실제 프로젝트",
      customer_id: 2,
      customer_name: "고객사",
      contract_type: "MO",
      atrb_ym: "2026-10",
      bill_dt: "2026-10-10",
      sply_amt: 1000,
      vat_amt: 77,
      tot_amt: 1077,
      bill_stts_cd: "DRAFT",
      txinv_no: null,
      txinv_dt: null,
      rcpt_plan_dt: "2026-11-01",
      received_amt: 0,
      adjusted_total_amt: 1077,
      receivable_amt: 0,
      reminder_stage: null,
      bill_odr_no: 1,
      amnd_yn: "N",
    },
    details: [
      {
        asgn_id: 9,
        dev_id: 4,
        cntrct_dev_id: 4,
        display_name: "청구 인력",
        work_ym: "2026-10",
        mm_qty: 0.5,
        source_mm_qty: 0.5,
        untprc_amt: 2000,
        work_day_qty: null,
        work_tm_qty: null,
        ovtm_tm_qty: null,
        ovtm_amt: 0,
        bill_amt: 1000,
        rmrk_desc: null,
      },
    ],
    receipts: [],
    amendments: [],
    original_bill_id: null,
    change_history: [],
    warnings: [],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/projects/options")
      return response([
        { id: 3, no: "P-2603", name: "실제 프로젝트", type: "MO" },
        { id: 4, no: "P-2604", name: "턴키 프로젝트", type: "TK" },
        { id: 5, no: "P-2605", name: "내부 제외", type: "IN" },
      ]);
    if (url === "/api/partners/options?kind=CUST")
      return response([{ id: "2", no: "C-002", name: "고객사" }]);
    if (url === "/api/projects/3" || url === "/api/projects/4")
      return response({
        prj_id: Number(url.split("/").at(-1)),
        cntrct_ty_cd: url.endsWith("4") ? "TK" : "MO",
        stlm_mthd_cd: "MONTH",
        use_yn: "Y",
      });
    if (url.includes("/bill-stages"))
      return response({
        items: [
          {
            bill_stg_id: 6,
            stg_odr_no: 1,
            bill_stg_nm: "착수금",
            sply_amt: 1000,
            bill_plan_dt: "2026-10-01",
            billed: false,
          },
        ],
      });
    if (url.startsWith("/api/month-close"))
      return response([
        { month: "2026-10", status: closed ? "CLOSED" : "OPEN" },
      ]);
    if (url.startsWith("/api/billings/summary"))
      return response({
        annual_confirmed_supply_amt: 9000,
        current_month_confirmed_supply_amt: 3000,
        confirmed_receivable_amt: 5000,
        overdue_amt: 2000,
        planned_supply_amt: 1000,
        draft_supply_amt: 4000,
      });
    if (url.startsWith("/api/billings/preview"))
      return response({
        details: detail.details,
        sply_amt: 1000,
        suggested_vat_amt: 77,
        warnings: [],
        missing_price_assignments: [],
      });
    if (init?.method === "POST" || init?.method === "PUT") {
      if (failWrite)
        return new Response(
          JSON.stringify({
            success: false,
            code: "VALIDATION_ERROR",
            message: "부가세 입력을 확인하세요.",
            field: "vat_amt",
            requestId: "billing-request",
          }),
          { status: 400 },
        );
      return response(detail);
    }
    if (url.startsWith("/api/billings?")) {
      if (failRead)
        return new Response(
          JSON.stringify({
            success: false,
            message: "조회 실패",
            requestId: "billing-read",
            code: "SERVER_ERROR",
          }),
          { status: 500 },
        );
      return response({
        items: empty ? [] : [detail.billing],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
      });
    }
    if (url === "/api/billings/7") return response(detail);
    throw new Error(`Unexpected URL ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
const renderScreen = () =>
  render(
    <MemoryRouter>
      <UI13Screen />
    </MemoryRouter>,
  );
const renderEditor = (edit = false) =>
  render(
    <UI13M1
      existing={edit ? detail : undefined}
      projectId={3}
      month="2026-10"
      onCancel={cancelled}
      onSaved={saved}
      reload={reload}
    />,
  );

describe("청구 목록", () => {
  it("renders API amounts, separate plan/draft figures and the existing UI02 table classes", async () => {
    renderScreen();
    const row = await screen.findByTestId("billing-row-7");
    expect(row.closest("table")).toHaveClass(tables.table);
    expect(screen.getByText(/예정 공급가/)).toHaveTextContent("1,000원");
    expect(screen.getByText(/작성 공급가/)).toHaveTextContent("4,000원");
    expect(screen.getByText("9,000 원")).toBeInTheDocument();
    expect(within(row).getByText("1,077")).toBeInTheDocument();
  });
  it("submits filters and preserves them when extension tabs switch", async () => {
    render(
      <MemoryRouter>
        <UI13Screen
          gapsPanel={() => (
            <input aria-label="누락 월" defaultValue="2026-09" />
          )}
        />
      </MemoryRouter>,
    );
    await screen.findByTestId("billing-row-7");
    fireEvent.change(screen.getByTestId("billing-filter-status"), {
      target: { value: "CONFIRM" },
    });
    fireEvent.change(screen.getByTestId("billing-filter-customer"), {
      target: { value: "2" },
    });
    fireEvent.click(screen.getByTestId("billing-search"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([u]) => u.includes("status=CONFIRM") && u.includes("customerId=2"),
        ),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId("billing-gaps-tab"));
    fireEvent.click(screen.getByTestId("billing-list-tab"));
    expect(screen.getByTestId("billing-filter-status")).toHaveValue("CONFIRM");
  });
  it("shows empty state and working reset", async () => {
    empty = true;
    renderScreen();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    empty = false;
    fireEvent.click(screen.getByTestId("billing-empty-reset"));
    expect(await screen.findByTestId("billing-row-7")).toBeInTheDocument();
  });
  it("keeps filters and displays failed request with retry", async () => {
    failRead = true;
    renderScreen();
    expect(await screen.findByText("조회 실패")).toBeInTheDocument();
    failRead = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("billing-row-7")).toBeInTheDocument();
  });
  it("past year hides create but retains download", async () => {
    renderScreen();
    await screen.findByTestId("billing-row-7");
    fireEvent.click(screen.getByTestId("billing-year-prev"));
    expect(screen.queryByTestId("billing-add")).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-export")).toBeInTheDocument();
  });
  it("mobile hides all writes including injected actions and dialogs", async () => {
    window.innerWidth = 767;
    render(
      <MemoryRouter>
        <UI13Screen
          initialBillingId={7}
          toolbar={<button>업로드</button>}
          dialogs={<button>입금 저장</button>}
          detailActions={() => <button>청구 취소</button>}
        />
      </MemoryRouter>,
    );
    await screen.findByText("입금 이력 (부가세 포함)");
    expect(screen.queryByText("업로드")).not.toBeInTheDocument();
    expect(screen.queryByText("청구 취소")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument();
    expect(screen.queryByText("입금 저장")).not.toBeInTheDocument();
  });
  it("opens real row detail and confirmed rows have no draft edit controls", async () => {
    detail.billing.bill_stts_cd = "CONFIRM";
    renderScreen();
    await screen.findByTestId("billing-open-7");
    fireEvent.click(screen.getByTestId("billing-open-7"));
    await screen.findByText("입금 이력 (부가세 포함)");
    expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("billing-recalculate-save"),
    ).not.toBeInTheDocument();
  });
});
describe("청구 등록/수정", () => {
  it("keeps detail field identifiers stable when optional values are null", async () => {
    detail.billing.atrb_ym = null;
    detail.billing.bill_dt = null;
    detail.billing.rcpt_plan_dt = null;
    render(
      <UI13DetailPanel
        id={7}
        readOnly={true}
        onClose={cancelled}
        onEdit={vi.fn()}
        onOpen={vi.fn()}
        onProject={vi.fn()}
        refresh={reload}
      />,
    );
    const dialog = screen.getByRole("dialog", { name: "청구 상세" });
    await within(dialog).findByText("입금 이력 (부가세 포함)");
    for (const [id, label, value] of [
      ["month", "귀속월", "–"],
      ["billing-date", "청구일", "–"],
      ["supply-amount", "공급가액", "1,000"],
      ["vat", "부가세", "77"],
      ["total", "합계", "1,077"],
      ["invoice-number", "세금계산서 번호", "–"],
      ["invoice-date", "작성일", "–"],
      ["receipt-plan-date", "입금 예정일", "–"],
      ["received-amount", "누적 입금", "0"],
      ["adjusted-total", "조정 청구 총액", "1,077"],
      ["receivable", "미수금", "0"],
    ]) {
      const field = within(dialog).getByTestId(`billing-detail-field-${id}`);
      expect(within(field).getByRole("term")).toHaveTextContent(label);
      expect(within(field).getByRole("definition")).toHaveTextContent(value);
    }
    expect(
      within(dialog).queryByTestId("billing-edit"),
    ).not.toBeInTheDocument();
  });
  it("edit identities are readonly and PUT contains only BillingUpdate fields", async () => {
    renderEditor(true);
    await screen.findByLabelText("프로젝트");
    expect(screen.getByLabelText("프로젝트")).toBeDisabled();
    expect(screen.getByLabelText("귀속월")).toHaveAttribute("readonly");
    expect(screen.getByTestId("billing-additional")).toBeDisabled();
    expect(screen.getByTestId("billing-ordinal")).toHaveAttribute("readonly");
    fireEvent.change(screen.getByLabelText("공급가액"), {
      target: { value: "1234" },
    });
    await waitFor(() =>
      expect(screen.getByTestId("billing-editor-save")).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    const call = fetcher.mock.calls.find(([, i]) => i?.method === "PUT")!;
    expect(call[0]).toBe("/api/billings/7");
    const payload = JSON.parse(call[1].body as string);
    expect(payload.sply_amt).toBe(1234);
    expect(payload.vat_amt).toBe(77);
    for (const key of [
      "project_id",
      "stage_id",
      "month",
      "additional",
      "bill_odr_no",
      "tot_amt",
    ])
      expect(payload).not.toHaveProperty(key);
    expect(payload).not.toHaveProperty("recalculate");
  });
  it("create previews effective VAT, excludes IN and POST uses project_id/month wire", async () => {
    renderEditor();
    await screen.findByLabelText("프로젝트");
    expect(
      screen.queryByRole("option", { name: /내부 제외/ }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("billing-preview"));
    await waitFor(() =>
      expect(screen.getByLabelText("부가세")).toHaveValue("77"),
    );
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    const call = fetcher.mock.calls.find(([, i]) => i?.method === "POST")!;
    expect(JSON.parse(call[1].body as string)).toMatchObject({
      project_id: 3,
      month: "2026-10",
      vat_amt: 77,
    });
    expect(reload).toHaveBeenCalled();
  });
  it("failed save shows singular field error and keeps manual input", async () => {
    failWrite = true;
    renderEditor(true);
    await screen.findByLabelText("프로젝트");
    fireEvent.change(screen.getByLabelText("부가세"), {
      target: { value: "123" },
    });
    await waitFor(() =>
      expect(screen.getByTestId("billing-editor-save")).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await screen.findAllByText("부가세 입력을 확인하세요.");
    expect(screen.getByLabelText("부가세")).toHaveValue("123");
    expect(screen.getByLabelText("부가세")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(saved).not.toHaveBeenCalled();
  });
  it("preview never overwrites manually entered supply or VAT", async () => {
    renderEditor();
    await screen.findByLabelText("프로젝트");
    fireEvent.change(screen.getByLabelText("공급가액"), {
      target: { value: "1234" },
    });
    fireEvent.change(screen.getByLabelText("부가세"), {
      target: { value: "12" },
    });
    fireEvent.click(screen.getByTestId("billing-preview"));
    await screen.findByTestId("billing-line-9");
    expect(screen.getByLabelText("공급가액")).toHaveValue("1,234");
    expect(screen.getByLabelText("부가세")).toHaveValue("12");
  });
  it("closed month disables save independently of year", async () => {
    closed = true;
    renderEditor(true);
    await screen.findByText("마감된 월은 수정할 수 없습니다.");
    expect(screen.getByTestId("billing-editor-save")).toBeDisabled();
  });
  it("dirty cancellation requires confirmation", async () => {
    renderEditor(true);
    await screen.findByLabelText("프로젝트");
    fireEvent.change(screen.getByLabelText("부가세"), {
      target: { value: "13" },
    });
    fireEvent.click(screen.getByTestId("billing-editor-cancel"));
    expect(cancelled).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("나가기"));
    expect(cancelled).toHaveBeenCalled();
  });
  it("recalculate is an explicit PUT command rather than implicit detail refresh", async () => {
    render(
      <UI13DetailPanel
        id={7}
        readOnly={false}
        onClose={cancelled}
        onEdit={vi.fn()}
        onOpen={vi.fn()}
        onProject={vi.fn()}
        refresh={reload}
      />,
    );
    await screen.findByTestId("billing-recalculate-save");
    fireEvent.click(screen.getByTestId("billing-recalculate-save"));
    await waitFor(() =>
      expect(fetcher.mock.calls.some(([, i]) => i?.method === "PUT")).toBe(
        true,
      ),
    );
    const call = fetcher.mock.calls.find(([, i]) => i?.method === "PUT")!;
    expect(JSON.parse(call[1].body as string)).toEqual({ recalculate: true });
  });
});
