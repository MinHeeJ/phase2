import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI13Screen from "./UI13Screen";
import { UI13M1 } from "./UI13M1";
import { UI13DetailPanel } from "./UI13DetailPanel";
import {
  billingUpdatePayload,
  type BillingDetail,
  type BillingRow,
} from "./client";
import { seoulMonth } from "../shared/PeriodBar";
import tables from "../developers/Developers.module.css";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let row: BillingRow;
let detail: BillingDetail;
let fetcher: ReturnType<typeof vi.fn>;
let empty: boolean, failList: boolean, failWrite: boolean, closed: boolean;
const saved = vi.fn().mockResolvedValue(undefined),
  cancelled = vi.fn();
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  sessionStorage.clear();
  saved.mockClear();
  cancelled.mockClear();
  empty = false;
  failList = false;
  failWrite = false;
  closed = false;
  row = {
    bill_id: 42,
    bill_stg_id: 7,
    virtual_plan: false,
    bill_no: "B-2610-100",
    prj_id: 3,
    project_name: "청구 프로젝트",
    customer_id: 5,
    customer_name: "고객사 이름",
    contract_type: "TK",
    atrb_ym: null,
    bill_dt: `${seoulMonth()}-01`,
    sply_amt: 1000,
    vat_amt: 100,
    tot_amt: 1100,
    bill_stts_cd: "DRAFT",
    txinv_no: null,
    txinv_dt: null,
    rcpt_plan_dt: null,
    received_amt: 0,
    adjusted_total_amt: 1100,
    receivable_amt: 0,
    reminder_stage: null,
  };
  detail = {
    billing: row,
    details: [
      {
        asgn_id: null,
        dev_id: null,
        cntrct_dev_id: null,
        display_name: "중도금",
        work_ym: seoulMonth(),
        mm_qty: null,
        source_mm_qty: null,
        untprc_amt: null,
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
    if (init?.method && init.method !== "GET") {
      if (failWrite)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: "VALIDATION_ERROR",
            message: "공급가액을 확인하세요.",
            field: "sply_amt",
            requestId: "billing-request",
          }),
          { status: 400 },
        );
      return response(detail);
    }
    if (url === "/api/projects/options")
      return response([
        {
          id: 3,
          no: "P-TEST",
          name: "청구 프로젝트",
          type: "TK",
          custName: "고객사 이름",
        },
        {
          id: 4,
          no: "P-IN",
          name: "내부 프로젝트",
          type: "IN",
          custName: null,
        },
        {
          id: 6,
          no: "P-MO",
          name: "월단위 프로젝트",
          type: "MO",
          custName: null,
        },
      ]);
    if (url.startsWith("/api/projects/3/bill-stages"))
      return response({
        items: [
          {
            bill_stg_id: 7,
            bill_stg_nm: "중도금",
            sply_amt: 1000,
            bill_plan_dt: `${seoulMonth()}-01`,
            stg_odr_no: 1,
            vat_amt: 100,
            billed: false,
          },
        ],
      });
    if (url.startsWith("/api/partners/options"))
      return response([{ id: "5", no: "C-TEST", name: "고객사 이름" }]);
    if (url.startsWith("/api/month-close"))
      return response(
        closed ? [{ month: seoulMonth(), status: "CLOSED" }] : [],
      );
    if (url.startsWith("/api/billings/summary"))
      return response({
        annual_confirmed_supply_amt: 3000,
        current_month_confirmed_supply_amt: 1000,
        confirmed_receivable_amt: 1100,
        overdue_amt: 100,
        planned_supply_amt: 2000,
        draft_supply_amt: 1000,
      });
    if (url.startsWith("/api/billings/preview"))
      return response({
        project_id: 3,
        bill_stg_id: 7,
        month: seoulMonth(),
        sply_amt: 1000,
        suggested_vat_amt: 100,
        rcpt_plan_dt: null,
        details: detail.details,
        warnings: [],
        missing_price_assignments: [],
      });
    if (url.startsWith("/api/billings/gaps"))
      return response({ items: [], total: 0, total_difference_amt: 0 });
    if (url.startsWith("/api/billings/overdue"))
      return response({ items: [], total: 0 });
    if (url === "/api/billings/42") return response(detail);
    if (url.startsWith("/api/billings?")) {
      if (failList)
        return new Response(
          JSON.stringify({
            success: false,
            code: "SERVER_ERROR",
            message: "조회 실패",
            requestId: "read-request",
          }),
          { status: 500 },
        );
      return response({
        items: empty ? [] : [row],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
      });
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.unstubAllGlobals());
const renderScreen = () =>
  render(
    <MemoryRouter>
      <UI13Screen />
    </MemoryRouter>,
  );
const renderEditor = (existing = false) =>
  render(
    <MemoryRouter>
      <UI13M1
        detail={existing ? detail : undefined}
        onCancel={cancelled}
        onSaved={saved}
      />
    </MemoryRouter>,
  );
const writes = () =>
  fetcher.mock.calls.filter(
    ([, init]) => init?.method && init.method !== "GET",
  );

describe("청구 목록", () => {
  it("loads API amounts and uses the existing UI02 table classes", async () => {
    renderScreen();
    const line = await screen.findByTestId("billing-row-bill-42");
    expect(line.closest("table")).toHaveClass(tables.table);
    expect(line.closest("table")?.parentElement).toHaveClass(
      tables.tableScroll,
    );
    expect(screen.getByText("3,000원")).toBeInTheDocument();
    expect(
      screen.getByTestId("billing-export").getAttribute("href"),
    ).not.toContain("page=");
    expect(
      screen.getByTestId("billing-export").getAttribute("href"),
    ).not.toContain("size=");
  });
  it("shows empty state without fabricated rows", async () => {
    empty = true;
    renderScreen();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("billing-row-bill-42")).not.toBeInTheDocument();
  });
  it("shows failed request and retry", async () => {
    failList = true;
    renderScreen();
    expect(await screen.findByText("조회 실패")).toBeInTheDocument();
    failList = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(
      await screen.findByTestId("billing-row-bill-42"),
    ).toBeInTheDocument();
  });
  it("preserves tab-local list and gap filters", async () => {
    renderScreen();
    await screen.findByTestId("billing-row-bill-42");
    fireEvent.change(screen.getByTestId("billing-filter-query"), {
      target: { value: "계약" },
    });
    fireEvent.click(screen.getByTestId("billing-search"));
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    fireEvent.change(screen.getByTestId("billing-gaps-judgment"), {
      target: { value: "청구 없음" },
    });
    fireEvent.click(screen.getByTestId("billing-tab-list"));
    expect(screen.getByTestId("billing-filter-query")).toHaveValue("계약");
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    expect(screen.getByTestId("billing-gaps-judgment")).toHaveValue(
      "청구 없음",
    );
  });
  it("hides write controls for past years but keeps download", async () => {
    renderScreen();
    await screen.findByTestId("billing-row-bill-42");
    fireEvent.click(screen.getByTestId("billing-year-previous"));
    expect(screen.queryByTestId("billing-create")).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-export")).toBeInTheDocument();
  });
  it("hides write controls on mobile", async () => {
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      value: 767,
    });
    renderScreen();
    await screen.findByTestId("billing-row-bill-42");
    expect(screen.queryByTestId("billing-create")).not.toBeInTheDocument();
  });
  it("opens actual selected id and shows snapshot and receipt history", async () => {
    renderScreen();
    fireEvent.click(await screen.findByTestId("billing-open-42"));
    expect(
      await screen.findByText("인력별 상세 / TK 청구 단계"),
    ).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledWith("/api/billings/42", expect.anything());
    expect(screen.getByText("입금 이력이 없습니다.")).toBeInTheDocument();
  });
  it("sends whitelisted field sort and zero-based page", async () => {
    renderScreen();
    await screen.findByTestId("billing-row-bill-42");
    fireEvent.click(screen.getByTestId("billing-sort-sply_amt"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([u]) => u.includes("sort=sply_amt%2Casc") && u.includes("page=0"),
        ),
      ).toBe(true),
    );
  });
});
describe("청구 등록 및 수정", () => {
  it("excludes internal project options", async () => {
    renderEditor();
    await screen.findByRole("option", { name: "P-TEST · 청구 프로젝트" });
    expect(
      screen.queryByRole("option", { name: /내부 프로젝트/ }),
    ).not.toBeInTheDocument();
  });
  it("uses selected stage, server preview and create-only identity", async () => {
    renderEditor();
    await screen.findByRole("option", { name: "P-TEST · 청구 프로젝트" });
    fireEvent.change(screen.getByLabelText("프로젝트"), {
      target: { value: "3" },
    });
    await screen.findByRole("option", { name: /중도금/ });
    fireEvent.change(screen.getByLabelText("TK 미청구 단계"), {
      target: { value: "7" },
    });
    fireEvent.click(screen.getByTestId("billing-preview"));
    await waitFor(() =>
      expect(screen.getByLabelText("공급가액")).toHaveValue("1,000"),
    );
    expect(writes()).toHaveLength(0);
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    const [url, init] = writes()[0];
    const body = JSON.parse(init.body);
    expect(url).toBe("/api/billings");
    expect(body.project_id).toBe(3);
    expect(body.stage_id).toBe(7);
    expect(body).not.toHaveProperty("month");
    expect(body).not.toHaveProperty("tot_amt");
  });
  it("locks lifecycle identity on edit and PUTs selected path id", async () => {
    renderEditor(true);
    await screen.findByRole("option", { name: "P-TEST · 청구 프로젝트" });
    expect(screen.getByLabelText("프로젝트")).toBeDisabled();
    expect(screen.getByLabelText("TK 미청구 단계")).toBeDisabled();
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    const [url, init] = writes()[0];
    expect(url).toBe("/api/billings/42");
    const body = JSON.parse(init.body);
    expect(body).not.toHaveProperty("project_id");
    expect(body).not.toHaveProperty("stage_id");
    expect(body).not.toHaveProperty("month");
    expect(body).not.toHaveProperty("recalculate");
  });
  it("sends recalculate only after an explicit selection", async () => {
    renderEditor(true);
    await screen.findByRole("option", { name: "P-TEST · 청구 프로젝트" });
    fireEvent.click(screen.getByTestId("billing-recalculate"));
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(JSON.parse(writes()[0][1].body).recalculate).toBe(true);
  });
  it("preserves values and dialog on field validation failure", async () => {
    failWrite = true;
    renderEditor(true);
    await screen.findByRole("option", { name: "P-TEST · 청구 프로젝트" });
    fireEvent.change(screen.getByLabelText("공급가액"), {
      target: { value: "900" },
    });
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await screen.findAllByText("공급가액을 확인하세요.");
    expect(screen.getByLabelText("공급가액")).toHaveValue("900");
    expect(cancelled).not.toHaveBeenCalled();
  });
  it("closed month disables save and leaves data visible", async () => {
    closed = true;
    renderEditor(true);
    await screen.findByText("마감된 달은 변경할 수 없습니다.");
    expect(screen.getByTestId("billing-editor-save")).toBeDisabled();
    expect(screen.getByLabelText("공급가액")).toHaveValue("1,000");
  });
  it("closing a dirty editor needs confirmation without network writes", async () => {
    renderEditor(true);
    await screen.findByRole("option", { name: "P-TEST · 청구 프로젝트" });
    fireEvent.change(screen.getByLabelText("세금계산서 번호"), {
      target: { value: "새 번호" },
    });
    fireEvent.click(screen.getByTestId("billing-editor-cancel"));
    expect(screen.getByText("입력을 버릴까요?")).toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("normalizes update payload without foreign create fields", () => {
    expect(
      billingUpdatePayload({
        sply_amt: 12,
        project_id: 99,
        tot_amt: 99,
      } as never),
    ).not.toHaveProperty("project_id");
    expect(
      billingUpdatePayload({ sply_amt: 12, tot_amt: 99 } as never),
    ).not.toHaveProperty("tot_amt");
  });
});
describe("청구 상세 상태 규칙", () => {
  const mount = (readOnly = false) =>
    render(
      <MemoryRouter>
        <UI13DetailPanel
          id={42}
          version={0}
          readOnly={readOnly}
          onClose={cancelled}
          onOpen={vi.fn()}
          onEdit={vi.fn()}
        />
      </MemoryRouter>,
    );
  it("shows DRAFT edit only while open", async () => {
    mount();
    expect(await screen.findByTestId("billing-edit")).toBeInTheDocument();
  });
  it("does not show edit for CONFIRM", async () => {
    row.bill_stts_cd = "CONFIRM";
    mount();
    await screen.findByText("확정");
    expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument();
  });
  it("does not show edit for CLOSED month", async () => {
    closed = true;
    mount();
    await screen.findByText("마감된 달은 변경할 수 없습니다.");
    expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument();
  });
  it("does not show edit in past-year/mobile read-only context", async () => {
    mount(true);
    await screen.findByText("인력별 상세 / TK 청구 단계");
    expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument();
  });
});
