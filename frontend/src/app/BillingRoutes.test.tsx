import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppRouter } from "./router";
import { useSharedPreferences } from "../pages/shared/preferences";
import { response } from "../pages/developers/DeveloperTestSupport";
import * as registry from "../api/apiClient";
import * as billing from "../pages/billings/client";
import * as review from "../pages/billingsops/client";
import * as receipts from "../pages/receipts/client";
import * as attendance from "../pages/dispatchattendance/client";
import * as uploads from "../pages/dispatchattendance2/client";

let fetcher: ReturnType<typeof vi.fn>;
let failed: boolean;
let empty: boolean;
let row: billing.BillingRow;
const menus = [
  {
    menuId: 1,
    name: "매출",
    screenId: null,
    icon: null,
    mobile: true,
    children: [
      {
        menuId: 2,
        name: "청구·입금",
        screenId: "UI-13",
        icon: null,
        mobile: true,
        children: [],
      },
    ],
  },
];
const detail = () => ({
  billing: row,
  details: [],
  receipts: [],
  amendments: [],
  original_bill_id: null,
  change_history: [],
  warnings: [],
});
function mount() {
  return render(
    <MemoryRouter initialEntries={["/UI-13"]}>
      <AppRouter />
    </MemoryRouter>,
  );
}
const calls = (path: string) =>
  fetcher.mock.calls.filter(([url]) => String(url).startsWith(path));
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-14T00:00:00Z"));
  localStorage.clear();
  sessionStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  failed = false;
  empty = false;
  row = {
    bill_id: 7,
    bill_stg_id: null,
    virtual_plan: false,
    bill_no: "B-2610-07",
    prj_id: 3,
    project_name: "계약 업무",
    customer_id: 4,
    customer_name: "고객사",
    contract_type: "DP",
    atrb_ym: "2026-10",
    bill_dt: "2026-10-01",
    sply_amt: 100,
    vat_amt: 10,
    tot_amt: 110,
    bill_stts_cd: "CONFIRM",
    txinv_no: null,
    txinv_dt: null,
    rcpt_plan_dt: "2026-10-01",
    received_amt: 0,
    adjusted_total_amt: 110,
    receivable_amt: 110,
    reminder_stage: 1,
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/menus") return response(menus);
    if (url.startsWith("/api/billings/summary?"))
      return response({
        annual_confirmed_supply_amt: 100,
        current_month_confirmed_supply_amt: 100,
        confirmed_receivable_amt: 110,
        overdue_amt: 110,
        planned_supply_amt: 0,
        draft_supply_amt: 0,
      });
    if (url.startsWith("/api/billings/gaps?"))
      return response({
        month: "2026-10",
        total_difference_amt: 20,
        total: 1,
        page: 0,
        size: 50,
        items: [
          {
            project_id: 3,
            project_name: "계약 업무",
            month: "2026-10",
            basis_supply_amt: 120,
            confirmed_supply_amt: 100,
            difference_amt: 20,
            tolerance: 10000,
            judgment: "맞음",
            explanation: "허용 범위",
            attendance_missing: false,
            suspected_people: [],
            billing_ids: [7],
          },
        ],
      });
    if (url.startsWith("/api/billings/overdue?"))
      return response({
        items: [
          {
            billing: row,
            overdue_days: 13,
            stage: 1,
            due_status: "연체",
            recipient_roles: ["영업 담당자", "PM"],
            next_notification_date: "2026-10-31",
            sent_history: [],
            sales_part: null,
          },
        ],
        total: 1,
        page: 0,
        size: 50,
        customer_totals: [],
        stage_counts: [{ stage: 1, count: 1 }],
      });
    if (url.startsWith("/api/billings?")) {
      if (failed) throw new Error("offline");
      return response({
        items: empty ? [] : [row],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
      });
    }
    if (url === "/api/billings/7/receipts") return response(detail());
    if (url === "/api/billings/7/confirm") {
      row = { ...row, bill_stts_cd: "CONFIRM" };
      return response(detail());
    }
    if (url === "/api/billings/7") return response(detail());
    if (url === "/api/projects/options")
      return response([{ id: 3, no: "P-03", name: "계약 업무", type: "DP" }]);
    if (url.startsWith("/api/month-close?")) return response([]);
    if (url.startsWith("/api/codes/")) return response([]);
    throw new Error(
      `Unexpected billing route request ${url} ${init?.method ?? "GET"}`,
    );
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("billing route composition", () => {
  it("registers the real list anonymously in the existing shell", async () => {
    mount();
    expect(await screen.findByTestId("billing-row-7")).toHaveTextContent(
      "B-2610-07",
    );
    expect(screen.getAllByTestId("billing-workspace")).toHaveLength(1);
    expect(screen.getAllByLabelText("열린 화면")).toHaveLength(1);
    expect(
      fetcher.mock.calls.some(([url]) => String(url).includes("/auth/")),
    ).toBe(false);
    expect(screen.getByTestId("billing-project-link-7")).toHaveAttribute(
      "href",
      "/UI-01?open=3",
    );
  });
  it("preserves the empty state", async () => {
    empty = true;
    mount();
    expect(
      await screen.findByText(
        "조건에 맞는 청구가 없습니다. 조회 조건을 변경해 보세요.",
      ),
    ).toBeVisible();
  });
  it("preserves the failed request state", async () => {
    failed = true;
    mount();
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
  });
  it("embeds both reviews without a second heading and preserves their filters", async () => {
    mount();
    await screen.findByTestId("billing-row-7");
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    const gaps = within(screen.getByTestId("billing-review-gaps"));
    expect(await gaps.findByTestId("billing-gap-3")).toBeVisible();
    fireEvent.change(gaps.getByTestId("billing-search"), {
      target: { value: "계약" },
    });
    fireEvent.click(gaps.getByTestId("billing-query"));
    await waitFor(() =>
      expect(
        calls("/api/billings/gaps?").some(([url]) => url.includes("q=")),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId("billing-tab-overdue"));
    const overdue = within(screen.getByTestId("billing-review-overdue"));
    expect(await overdue.findByText("보낸 알림 없음")).toBeVisible();
    expect(overdue.getByTestId("billing-remind-7")).toBeDisabled();
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    expect(gaps.getByTestId("billing-search")).toHaveValue("계약");
    expect(screen.getAllByRole("heading", { name: "청구·입금" })).toHaveLength(
      1,
    );
  });
  it("opens the owning editor with a review row's project and month", async () => {
    mount();
    await screen.findByTestId("billing-row-7");
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    fireEvent.click(await screen.findByTestId("billing-gap-create-3"));
    await waitFor(() =>
      expect(screen.getByTestId("billing-project")).toHaveValue("3"),
    );
    expect(screen.getByTestId("billing-month")).toHaveValue("2026-10");
  });
  it("opens the owning drawer and receipt panel from overdue", async () => {
    mount();
    await screen.findByTestId("billing-row-7");
    fireEvent.click(screen.getByTestId("billing-tab-overdue"));
    fireEvent.click(await screen.findByTestId("billing-overdue-open-7"));
    expect(await screen.findByTestId("billing-receipt-panel")).toBeVisible();
    expect(await screen.findByTestId("receipt-add")).toBeVisible();
    expect(screen.getAllByTestId("billing-detail")).toHaveLength(1);
  });
  it("refreshes list, detail and summary after the reused confirmation dialog", async () => {
    row.bill_stts_cd = "DRAFT";
    mount();
    fireEvent.click(await screen.findByTestId("billing-open-7"));
    fireEvent.click(await screen.findByTestId("billing-confirm"));
    fireEvent.click(screen.getByTestId("billing-action-save"));
    await waitFor(() =>
      expect(calls("/api/billings?").length).toBeGreaterThan(1),
    );
    expect(calls("/api/billings/summary?").length).toBeGreaterThan(1);
    expect(calls("/api/billings/7").length).toBeGreaterThan(2);
  });
  it("mounts B.3 upload rather than a generic billing importer", async () => {
    mount();
    await screen.findByTestId("billing-row-7");
    fireEvent.click(screen.getByTestId("billing-upload"));
    expect(
      await screen.findByTestId("attendance-upload-dialog"),
    ).toHaveTextContent("B.3");
    expect(screen.getByTestId("attendance-upload-month")).toHaveAttribute(
      "min",
      "2026-01",
    );
  });
  it("hides mutations in prior years while keeping downloads", async () => {
    mount();
    await screen.findByTestId("billing-row-7");
    fireEvent.click(screen.getByTestId("billing-year-previous"));
    expect(screen.queryByTestId("billing-register")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-upload")).not.toBeInTheDocument();
    expect(
      within(screen.getByTestId("billing-list-panel")).getByTestId(
        "billing-download",
      ),
    ).toBeVisible();
    fireEvent.click(await screen.findByTestId("billing-open-7"));
    expect(await screen.findByTestId("billing-receipt-panel")).toBeVisible();
    expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
  });
  it("hides mutations on mobile", async () => {
    vi.stubGlobal("innerWidth", 767);
    mount();
    await screen.findByTestId("billing-row-7");
    expect(screen.queryByTestId("billing-register")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-upload")).not.toBeInTheDocument();
  });
  it("exports the existing feature clients without replacing transport", () => {
    expect(registry.listBillings).toBe(billing.listBillings);
    expect(registry.previewBilling).toBe(billing.previewBilling);
    expect(registry.billingWrite).toBe(review.billingWrite);
    expect(registry.listBillingGaps).toBe(review.listBillingGaps);
    expect(registry.listBillingOverdue).toBe(review.listBillingOverdue);
    expect(registry.createBillingReceipt).toBe(receipts.createBillingReceipt);
    expect(registry.saveDispatchAttendance).toBe(
      attendance.saveDispatchAttendance,
    );
    expect(registry.validateDispatchAttendanceUpload).toBe(
      uploads.validateDispatchAttendanceUpload,
    );
    expect(registry.getDispatchAttendanceInspection).toBe(
      uploads.getDispatchAttendanceInspection,
    );
  });
});
