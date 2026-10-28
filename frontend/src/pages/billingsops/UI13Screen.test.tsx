import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import UI13Screen from "./UI13Screen";
import { BillingDialog } from "./BillingDialog";
import type { BillingRow, BillingDetail } from "./client";
import { seoulMonth } from "../shared/PeriodBar";

const year = Number(seoulMonth().slice(0, 4));
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let row: BillingRow,
  detail: BillingDetail,
  fetcher: ReturnType<typeof vi.fn>,
  failRead: boolean,
  failWrite: boolean;
const saved = vi.fn().mockResolvedValue(undefined),
  closed = vi.fn();
beforeEach(() => {
  sessionStorage.clear();
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  failRead = false;
  failWrite = false;
  saved.mockClear();
  closed.mockClear();
  row = {
    bill_id: 41,
    bill_stg_id: null,
    virtual_plan: false,
    bill_no: "B-TEST-41",
    prj_id: 72,
    project_name: "계약 검증 프로젝트",
    customer_id: 5,
    customer_name: "고객",
    contract_type: "MO",
    atrb_ym: `${year}-10`,
    bill_dt: `${year}-10-01`,
    sply_amt: 100000,
    vat_amt: 10000,
    tot_amt: 110000,
    bill_stts_cd: "DRAFT",
    txinv_no: null,
    txinv_dt: null,
    rcpt_plan_dt: null,
    received_amt: 0,
    adjusted_total_amt: 110000,
    receivable_amt: 0,
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
            message: "환불 확인이 필요합니다.",
            field: "sply_amt",
          }),
          { status: 409 },
        );
      return response(detail);
    }
    if (url.startsWith("/api/partners/options"))
      return response([{ id: "5", name: "고객", no: "C-5" }]);
    if (url.startsWith("/api/projects/options"))
      return response([
        {
          id: 72,
          name: "계약 검증 프로젝트",
          type: "MO",
          no: "P-TEST",
          custName: "고객",
        },
        {
          id: 73,
          name: "내부 프로젝트",
          type: "IN",
          no: "P-IN",
          custName: null,
        },
      ]);
    if (failRead) throw new Error("offline");
    if (url.startsWith("/api/billings/summary"))
      return response({
        year,
        annual_confirmed_supply_amt: 100000,
        current_month_confirmed_supply_amt: 100000,
        confirmed_receivable_amt: 110000,
        overdue_amt: 0,
        planned_supply_amt: 200,
        draft_supply_amt: 100000,
      });
    if (url.startsWith("/api/billings/gaps"))
      return response({
        month: `${year}-09`,
        total_difference_amt: 100000,
        total: 1,
        page: 0,
        size: 50,
        items: [
          {
            project_id: 72,
            project_name: row.project_name,
            month: `${year}-09`,
            basis_supply_amt: 100000,
            confirmed_supply_amt: 0,
            difference_amt: 100000,
            judgment: "근태 미입력",
            attendance_missing: true,
            explanation: "근태 경고",
            suspected_people: [],
            billing_ids: [],
          },
        ],
      });
    if (url.startsWith("/api/billings/overdue"))
      return response({
        total: 1,
        page: 0,
        size: 50,
        items: [
          {
            billing: {
              ...row,
              bill_stts_cd: "CONFIRM",
              receivable_amt: 110000,
            },
            overdue_days: null,
            stage: null,
            due_status: "예정일 미입력",
            recipient_roles: [],
            next_notification_date: null,
            sent_history: [],
          },
        ],
        stage_counts: [{ stage: 0, count: 0 }],
        customer_totals: [],
      });
    if (url.startsWith("/api/billings/preview"))
      return response({
        project_id: 72,
        bill_stg_id: null,
        month: `${year}-10`,
        sply_amt: 100000,
        suggested_vat_amt: 10000,
        rcpt_plan_dt: null,
        details: [],
        warnings: [],
        missing_price_assignments: [],
      });
    if (url === "/api/billings/41") return response(detail);
    if (url.startsWith("/api/billings?"))
      return response({ items: [row], total: 1, page: 0, size: 50 });
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = () =>
  render(
    <MemoryRouter>
      <UI13Screen />
    </MemoryRouter>,
  );
const writes = () =>
  fetcher.mock.calls.filter((c) => c[1]?.method && c[1].method !== "GET");
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const dialog = (
  mode: "amend" | "cancel" | "confirm" | "receipt" | "edit" | "create",
) =>
  render(
    <BillingDialog
      mode={mode}
      detail={mode === "create" ? undefined : detail}
      onClose={closed}
      onSaved={saved}
    />,
  );

describe("billing screens and local dialogs", () => {
  it("shows real summary, separate plan and draft and shared table styles", async () => {
    panel();
    const row = await screen.findByTestId("billing-row-41");
    expect(row.closest("table")?.className).toContain("table");
    expect(screen.getByText(/예정 200원/)).toBeVisible();
    expect(within(row).getByText("작성")).toBeVisible();
    expect(screen.getByTestId("billing-project-41")).toHaveAttribute(
      "href",
      "/UI-01?open=72",
    );
  });
  it("retains each tab filter independently and displays missing attendance as warning", async () => {
    panel();
    await screen.findByTestId("billing-row-41");
    change("검색", "청구검색");
    fireEvent.click(screen.getByTestId("billing-search"));
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    const row = await screen.findByTestId("billing-gap-72");
    expect(within(row).getByText("근태 미입력").className).toContain("warning");
    change("검색", "누락검색");
    fireEvent.click(screen.getByTestId("billing-search"));
    fireEvent.click(screen.getByTestId("billing-tab-list"));
    await screen.findByTestId("billing-row-41");
    expect(screen.getByLabelText("검색")).toHaveValue("청구검색");
    fireEvent.click(screen.getByTestId("billing-tab-gaps"));
    await screen.findByTestId("billing-gap-72");
    expect(screen.getByLabelText("검색")).toHaveValue("누락검색");
  });
  it("shows no fabricated notification date/history and disables sending", async () => {
    panel();
    await screen.findByTestId("billing-row-41");
    fireEvent.click(screen.getByTestId("billing-tab-overdue"));
    const row = await screen.findByTestId("billing-overdue-41");
    expect(within(row).getByText("보낸 알림 없음")).toBeVisible();
    expect(screen.getByTestId("billing-remind-41")).toBeDisabled();
    expect(writes()).toHaveLength(0);
  });
  it("renders empty list", async () => {
    fetcher.mockImplementation(async (url: string) =>
      url.startsWith("/api/billings?")
        ? response({ items: [], total: 0, page: 0, size: 50 })
        : url.includes("summary")
          ? response({ year })
          : response([]),
    );
    panel();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeVisible();
  });
  it("shows loading then failed request and retries", async () => {
    failRead = true;
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    await screen.findByText("서버에 연결할 수 없습니다.");
    failRead = false;
    fireEvent.click(screen.getByTestId("billing-retry"));
    expect(await screen.findByTestId("billing-row-41")).toBeVisible();
  });
  it("past year is read only but download remains available", async () => {
    sessionStorage.setItem("UI-13.year", String(year - 1));
    panel();
    await screen.findByTestId("billing-row-41");
    expect(screen.queryByTestId("billing-create")).not.toBeInTheDocument();
    expect(screen.getByTestId("billing-export")).toBeVisible();
    fireEvent.click(screen.getByTestId("billing-open-41"));
    await screen.findByTestId("billing-detail");
    expect(screen.queryByTestId("billing-confirm")).not.toBeInTheDocument();
  });
  it("mobile hides all write affordances even on detail", async () => {
    Object.defineProperty(window, "innerWidth", { value: 767 });
    panel();
    await screen.findByTestId("billing-row-41");
    fireEvent.click(screen.getByTestId("billing-open-41"));
    await screen.findByTestId("billing-detail");
    expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-cancel")).not.toBeInTheDocument();
  });
  it("confirmed detail has no edit or recalculate and amendment links to selected bill", async () => {
    row.bill_stts_cd = "CONFIRM";
    panel();
    await screen.findByTestId("billing-row-41");
    fireEvent.click(screen.getByTestId("billing-open-41"));
    await screen.findByTestId("billing-detail");
    expect(screen.queryByTestId("billing-edit")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-recalculate")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("billing-amend"));
    expect(screen.getByTestId("billing-dialog-amend")).toHaveTextContent(
      "B-TEST-41",
    );
  });
  it("cancel requires reason without sending, explicit confirmation sends selected ID", async () => {
    dialog("cancel");
    fireEvent.click(screen.getByTestId("billing-dialog-save"));
    expect(await screen.findByText("reason: 사유를 입력하세요.")).toBeVisible();
    expect(writes()).toHaveLength(0);
    change("사유", "중복 취소");
    fireEvent.click(screen.getByTestId("billing-dialog-save"));
    await waitFor(() => expect(saved).toHaveBeenCalledOnce());
    expect(writes()[0][0]).toBe("/api/billings/41/cancel");
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({
      reason: "중복 취소",
    });
  });
  it("amendment requires negative supply and nonpositive VAT", async () => {
    dialog("amend");
    change("사유", "감액");
    change("공급가액", "10");
    change("부가세", "0");
    fireEvent.click(screen.getByTestId("billing-dialog-save"));
    await screen.findByText("수정 공급가는 음수, 부가세는 0 이하여야 합니다.");
    expect(writes()).toHaveLength(0);
  });
  it("failed amendment keeps inputs and field error", async () => {
    failWrite = true;
    dialog("amend");
    change("사유", "감액");
    change("공급가액", "-10000");
    change("부가세", "-1000");
    fireEvent.click(screen.getByTestId("billing-dialog-save"));
    await screen.findByText("sply_amt: 환불 확인이 필요합니다.");
    expect(screen.getByLabelText("공급가액")).toHaveValue(-10000);
    expect(closed).not.toHaveBeenCalled();
  });
  it("edit does not send project month or stage identity", async () => {
    dialog("edit");
    expect(screen.queryByLabelText("프로젝트")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("billing-dialog-save"));
    await waitFor(() => expect(saved).toHaveBeenCalledOnce());
    const body = JSON.parse(writes()[0][1].body as string);
    expect(body).not.toHaveProperty("project_id");
    expect(body).not.toHaveProperty("month");
    expect(body).not.toHaveProperty("stage_id");
    expect(writes()[0][1].method).toBe("PUT");
  });
  it("creation excludes internal options and preview is read-only", async () => {
    dialog("create");
    await screen.findByRole("option", { name: "계약 검증 프로젝트" });
    expect(
      screen.queryByRole("option", { name: "내부 프로젝트" }),
    ).not.toBeInTheDocument();
    change("프로젝트", "72");
    change("귀속월", `${year}-10`);
    fireEvent.click(screen.getByTestId("billing-dialog-preview"));
    await waitFor(() =>
      expect(screen.getByLabelText("공급가액")).toHaveValue(100000),
    );
    expect(writes()).toHaveLength(0);
  });
  it("dirty closing requires explicit discard", async () => {
    dialog("amend");
    change("사유", "감액");
    fireEvent.click(screen.getByTestId("billing-dialog-close"));
    expect(screen.getByTestId("billing-discard")).toBeVisible();
    fireEvent.click(screen.getByTestId("billing-discard-yes"));
    expect(closed).toHaveBeenCalledOnce();
    expect(writes()).toHaveLength(0);
  });
  it("receipt registration validates positive VAT included amount", async () => {
    dialog("receipt");
    change("입금일", `${year}-10-23`);
    change("입금액 (부가세 포함)", "0");
    fireEvent.click(screen.getByTestId("billing-dialog-save"));
    await screen.findByText("입금일과 양수 입금액을 입력하세요.");
    expect(writes()).toHaveLength(0);
  });
});
