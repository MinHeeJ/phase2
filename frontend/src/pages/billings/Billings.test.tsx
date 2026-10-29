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
import { BillingEditor } from "./BillingEditor";
import { type BillingDetail, type BillingRow } from "./client";
import { seoulMonth } from "../shared/PeriodBar";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const row: BillingRow = {
  bill_id: 7,
  bill_stg_id: null,
  virtual_plan: false,
  bill_no: "B-2610-100",
  prj_id: 3,
  project_name: "청구 시험",
  customer_id: 2,
  customer_name: "시험 고객",
  contract_type: "MO",
  atrb_ym: "2026-10",
  bill_dt: "2026-10-30",
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
let rows: BillingRow[],
  detail: BillingDetail,
  failRead: boolean,
  failWrite: boolean;
let fetcher: ReturnType<typeof vi.fn>;
const year = Number(seoulMonth().slice(0, 4));
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  rows = [{ ...row }];
  failRead = false;
  failWrite = false;
  detail = {
    billing: { ...row },
    details: [],
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
            code: "VALIDATION_ERROR",
            message: "공급가액을 확인하세요.",
            field: "sply_amt",
            requestId: "billing-request",
          }),
          { status: 400 },
        );
      const body = JSON.parse(init.body as string);
      detail = { ...detail, billing: { ...detail.billing, ...body } };
      return response(detail);
    }
    if (url === "/api/projects/options")
      return response([
        {
          id: 3,
          no: "P-TEST",
          name: "청구 시험",
          type: "MO",
          custName: "시험 고객",
        },
        { id: 4, no: "P-IN", name: "내부", type: "IN", custName: null },
      ]);
    if (url.startsWith("/api/billings/summary"))
      return response({
        annual_confirmed_supply_amt: 1200,
        current_month_confirmed_supply_amt: 300,
        confirmed_receivable_amt: 400,
        overdue_amt: 100,
        planned_supply_amt: 50,
        draft_supply_amt: 1000,
      });
    if (url.startsWith("/api/billings/preview"))
      return response({
        project_id: 3,
        sply_amt: 1000,
        suggested_vat_amt: 100,
        rcpt_plan_dt: null,
        details: [],
        warnings: [],
        missing_price_assignments: [],
      });
    if (url === "/api/billings/7") return response(detail);
    if (failRead)
      return new Response(
        JSON.stringify({
          success: false,
          message: "조회 실패",
          requestId: "billing-read",
        }),
        { status: 500 },
      );
    return response({ items: rows, total: rows.length, page: 0, size: 50 });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.unstubAllGlobals());
const show = () =>
  render(
    <MemoryRouter>
      <UI13Screen />
    </MemoryRouter>,
  );
describe("청구 목록", () => {
  it("renders API-backed amounts, shared table, and selected billing details", async () => {
    show();
    await screen.findByText("B-2610-100");
    expect(screen.getByTestId("billing-summary-year")).toHaveTextContent(
      "1,200 원",
    );
    expect(screen.getByText(/예정 공급가/)).toHaveTextContent(
      "확정 매출·미수 제외",
    );
    expect(
      screen.getByTestId("billing-row-7").closest("table")?.className,
    ).toContain("table");
    fireEvent.click(screen.getByTestId("billing-open-7"));
    const drawer = await screen.findByTestId("billing-detail");
    await within(drawer).findByText("입금 이력 (부가세 포함)");
    expect(within(drawer).getByTestId("billing-edit")).toBeVisible();
    expect(within(drawer).getByText("입금 이력이 없습니다.")).toBeVisible();
  });
  it("keeps the selected linked bill when detail actions refresh the workspace", async () => {
    const amendment: BillingDetail = {
      ...detail,
      billing: { ...row, bill_id: 8, bill_no: "B-2610-101" },
      original_bill_id: 7,
    };
    detail.amendments = [amendment.billing];
    const existing = fetcher.getMockImplementation()!;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      url === "/api/billings/8" ? response(amendment) : existing(url, init),
    );
    render(
      <MemoryRouter>
        <UI13Screen
          renderActions={(_, refresh) => (
            <button data-testid="billing-test-refresh" onClick={refresh}>
              상세 새로고침
            </button>
          )}
        />
      </MemoryRouter>,
    );
    fireEvent.click(await screen.findByTestId("billing-open-7"));
    fireEvent.click(await screen.findByTestId("billing-amendment-open-8"));
    await screen.findByTestId("billing-original");
    fireEvent.click(screen.getByTestId("billing-test-refresh"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.filter(([url]) => url === "/api/billings/8").length,
      ).toBeGreaterThan(1),
    );
    expect(await screen.findByTestId("billing-original")).toBeVisible();
    fireEvent.click(screen.getByTestId("billing-original"));
    await screen.findByTestId("billing-amendment-open-8");
    fireEvent.click(screen.getByTestId("billing-test-refresh"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.filter(([url]) => url === "/api/billings/7").length,
      ).toBeGreaterThan(2),
    );
    expect(await screen.findByTestId("billing-amendment-open-8")).toBeVisible();
    expect(screen.queryByTestId("billing-original")).not.toBeInTheDocument();
  });
  it("year changes execute real queries and hide mutation controls for past years", async () => {
    show();
    await screen.findByText("B-2610-100");
    fireEvent.click(screen.getByTestId("billing-year-previous"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) =>
          String(url).includes(`year=${year - 1}`),
        ),
      ).toBe(true),
    );
    expect(screen.queryByTestId("billing-register")).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-download")).toBeVisible();
    fireEvent.click(screen.getByTestId("billing-open-7"));
    await screen.findByTestId("billing-detail");
    await waitFor(() =>
      expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument(),
    );
  });
  it("mobile retains table and download but hides registration", async () => {
    window.innerWidth = 767;
    show();
    await screen.findByText("B-2610-100");
    expect(screen.queryByTestId("billing-register")).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-download")).toBeVisible();
  });
  it("preserves filters across tab changes and sends zero-based pagination", async () => {
    show();
    await screen.findByText("B-2610-100");
    fireEvent.change(screen.getByTestId("billing-filter-search"), {
      target: { value: "원천" },
    });
    fireEvent.click(screen.getByTestId("billing-search"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url]) =>
            String(url).includes("q=%EC%9B%90%EC%B2%9C") &&
            String(url).includes("page=0"),
        ),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    fireEvent.click(screen.getByTestId("billing-tab-list"));
    expect(screen.getByTestId("billing-filter-search")).toHaveValue("원천");
  });
  it("empty list is explicit", async () => {
    rows = [];
    show();
    expect(
      await screen.findByText(/조건에 맞는 청구가 없습니다/),
    ).toBeVisible();
  });
  it("failed request shows recovery without fake rows", async () => {
    failRead = true;
    show();
    expect(await screen.findByText("조회 실패")).toBeVisible();
    expect(screen.queryByTestId("billing-row-7")).not.toBeInTheDocument();
  });
  it("confirmed rows do not show edit", async () => {
    detail.billing.bill_stts_cd = "CONFIRM";
    show();
    await screen.findByText("B-2610-100");
    fireEvent.click(screen.getByTestId("billing-open-7"));
    await screen.findByText("입금 이력 (부가세 포함)");
    expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument();
  });
});
describe("청구 등록·수정", () => {
  it("identity is readonly in edit, ordinary save omits source keys and recalculation", async () => {
    const saved = vi.fn();
    render(
      <BillingEditor
        detail={detail}
        year={year}
        onClose={vi.fn()}
        onSaved={saved}
      />,
    );
    expect(screen.getByTestId("billing-project-readonly")).toHaveAttribute(
      "readonly",
    );
    expect(screen.getByTestId("billing-month")).toHaveAttribute("readonly");
    fireEvent.change(screen.getByTestId("billing-sply-amt"), {
      target: { value: "999" },
    });
    fireEvent.change(screen.getByTestId("billing-vat-amt"), {
      target: { value: "7" },
    });
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    const write = fetcher.mock.calls.find(
      ([, init]) => init?.method === "PUT",
    )!;
    const body = JSON.parse(write[1].body as string);
    expect(body).toMatchObject({ sply_amt: 999, vat_amt: 7 });
    expect(body).not.toHaveProperty("project_id");
    expect(body).not.toHaveProperty("month");
    expect(body).not.toHaveProperty("recalculate");
    expect(body).not.toHaveProperty("tot_amt");
  });
  it("explicit recalculate is sent only when selected", async () => {
    render(
      <BillingEditor
        detail={detail}
        year={year}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId("billing-recalculate"));
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([, init]) => init?.method === "PUT"),
      ).toBe(true),
    );
    expect(
      JSON.parse(
        fetcher.mock.calls.find(([, init]) => init?.method === "PUT")![1]
          .body as string,
      ).recalculate,
    ).toBe(true);
  });
  it("field error keeps all manual input and modal open", async () => {
    failWrite = true;
    const saved = vi.fn();
    render(
      <BillingEditor
        detail={detail}
        year={year}
        onClose={vi.fn()}
        onSaved={saved}
      />,
    );
    fireEvent.change(screen.getByTestId("billing-sply-amt"), {
      target: { value: "999" },
    });
    fireEvent.click(screen.getByTestId("billing-editor-save"));
    await screen.findAllByText("공급가액을 확인하세요.");
    expect(screen.getByTestId("billing-sply-amt")).toHaveValue("999");
    expect(saved).not.toHaveBeenCalled();
    expect(screen.getByTestId("billing-sply-amt")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });
  it("registration excludes internal projects and preview performs no writes", async () => {
    render(<BillingEditor year={year} onClose={vi.fn()} onSaved={vi.fn()} />);
    await screen.findByRole("option", { name: "청구 시험 (MO)" });
    expect(
      screen.queryByRole("option", { name: /내부/ }),
    ).not.toBeInTheDocument();
    fireEvent.change(screen.getByTestId("billing-project"), {
      target: { value: "3" },
    });
    fireEvent.click(screen.getByTestId("billing-preview"));
    await waitFor(() =>
      expect(screen.getByTestId("billing-sply-amt")).toHaveValue("1000"),
    );
    expect(
      fetcher.mock.calls.every(
        ([, init]) => !init?.method || init.method === "GET",
      ),
    ).toBe(true);
  });
});
