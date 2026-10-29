import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { BillingReceiptPanel } from "./BillingReceiptPanel";
import type { BillingReceipt, ReceiptBillingDetail } from "./client";
import table from "../developers/Developers.module.css";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const year = Number(
  new Intl.DateTimeFormat("en", {
    timeZone: "Asia/Seoul",
    year: "numeric",
  }).format(new Date()),
);
let detail: ReceiptBillingDetail;
let rows: BillingReceipt[];
let closed: string[];
let failRead: boolean;
let failWrite: string;
let fetcher: ReturnType<typeof vi.fn>;
const changed = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  rows = [
    { receipt_id: 7, rcpt_dt: "2026-10-01", rcpt_amt: 30, rmrk_desc: "1차" },
  ];
  detail = {
    billing: {
      bill_id: 3,
      bill_no: "B-2610-03",
      bill_stts_cd: "CONFIRM",
      sply_amt: 100,
      vat_amt: 10,
      tot_amt: 110,
      adjusted_total_amt: 110,
      received_amt: 30,
      receivable_amt: 80,
      atrb_ym: "2026-09",
      rcpt_plan_dt: "2026-10-01",
    },
    receipts: rows,
    details: [],
    amendments: [],
    original_bill_id: null,
    warnings: [],
    change_history: [],
  };
  failRead = false;
  failWrite = "";
  closed = [];
  changed.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/month-close?"))
      return response(closed.map((month) => ({ month, status: "CLOSED" })));
    if (url.startsWith("/api/billings/3/receipts")) {
      if (init?.method && init.method !== "GET") {
        if (failWrite)
          return new Response(
            JSON.stringify({
              success: false,
              meta: {},
              code: failWrite,
              message:
                failWrite === "CLOSED_MONTH"
                  ? "마감된 달입니다."
                  : "입금액을 확인하세요.",
              field: "rcpt_amt",
              requestId: "receipt-request",
            }),
            { status: failWrite === "VALIDATION_ERROR" ? 400 : 409 },
          );
        if (init.method === "DELETE")
          rows = rows.filter(
            (r) => r.receipt_id !== Number(url.split("/").at(-1)),
          );
        else {
          const input = JSON.parse(init.body as string);
          if (init.method === "PUT")
            rows = rows.map((r) =>
              r.receipt_id === Number(url.split("/").at(-1))
                ? { ...r, ...input }
                : r,
            );
          else rows.push({ ...input, receipt_id: 8 });
        }
      } else if (failRead) throw new Error("offline");
      detail.receipts = rows;
      detail.billing.received_amt = rows.reduce(
        (sum, r) => sum + r.rcpt_amt,
        0,
      );
      detail.billing.receivable_amt =
        detail.billing.adjusted_total_amt - detail.billing.received_amt;
      return response(detail);
    }
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = (selectedYear = year) =>
  render(
    <BillingReceiptPanel billId={3} year={selectedYear} onChanged={changed} />,
  );
const writes = () =>
  fetcher.mock.calls.filter((c) => c[1]?.method && c[1].method !== "GET");
const change = (id: string, value: string) =>
  fireEvent.change(screen.getByTestId(id), { target: { value } });
async function add() {
  fireEvent.click(await screen.findByTestId("receipt-add"));
  change("receipt-date", "2026-11-01");
  change("receipt-amount", "40");
  change("receipt-remark", "분할 입금");
  await waitFor(() =>
    expect(screen.getByTestId("receipt-save")).not.toBeDisabled(),
  );
}
describe("billing receipts", () => {
  it("renders real receipt amounts, original balance, warning badge and UI-02 table classes", async () => {
    panel();
    await screen.findByTestId("receipt-row-7");
    expect(screen.getByRole("table")).toHaveClass(table.table);
    expect(screen.getByRole("table").parentElement).toHaveClass(
      table.tableScroll,
    );
    expect(screen.getByTestId("receipt-balance")).toHaveTextContent("80원");
    expect(screen.getByText("미수").className).toContain("warning");
    for (const label of ["입금일", "입금액", "비고", "작업"])
      expect(screen.getByRole("columnheader", { name: label })).toBeVisible();
  });
  it("renders completion with green badge", async () => {
    rows[0].rcpt_amt = 110;
    panel();
    expect((await screen.findByText("입금 완료")).className).toContain(
      "success",
    );
  });
  it("renders empty receipts without fabricated rows", async () => {
    rows = [];
    panel();
    expect(
      await screen.findByText("등록된 입금 내역이 없습니다."),
    ).toBeVisible();
    expect(screen.getByTestId("receipt-balance")).toHaveTextContent("110원");
  });
  it("loading and failed read retry retain selected parent", async () => {
    failRead = true;
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    await screen.findByText("서버에 연결할 수 없습니다.");
    failRead = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("receipt-row-7")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("creates with exact mutable payload and refreshes parent list detail and summary callback", async () => {
    panel();
    await add();
    fireEvent.click(screen.getByTestId("receipt-save"));
    await screen.findByText("입금을 저장했습니다.");
    expect(changed).toHaveBeenCalledOnce();
    expect(writes()[0][0]).toBe("/api/billings/3/receipts");
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({
      rcpt_dt: "2026-11-01",
      rcpt_amt: 40,
      rmrk_desc: "분할 입금",
    });
    expect(screen.getByTestId("receipt-balance")).toHaveTextContent("40원");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("updates selected receipt using PUT without echoing identifier or totals", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("receipt-edit-7"));
    expect(screen.getByTestId("receipt-amount")).toHaveValue("30");
    change("receipt-amount", "50");
    await waitFor(() =>
      expect(screen.getByTestId("receipt-save")).not.toBeDisabled(),
    );
    fireEvent.click(screen.getByTestId("receipt-save"));
    await screen.findByText("입금을 저장했습니다.");
    expect(writes()[0][0]).toBe("/api/billings/3/receipts/7");
    expect(writes()[0][1].method).toBe("PUT");
    expect(JSON.parse(writes()[0][1].body as string)).not.toHaveProperty(
      "receipt_id",
    );
    expect(screen.getByTestId("receipt-balance")).toHaveTextContent("60원");
  });
  it("delete confirmation cancellation sends nothing and delete increases balance", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("receipt-delete-7"));
    expect(screen.getByTestId("receipt-delete-dialog")).toHaveTextContent(
      "미수금이 늘어납니다.",
    );
    fireEvent.click(screen.getByTestId("receipt-delete-cancel"));
    expect(writes()).toHaveLength(0);
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId("receipt-delete-7"));
    fireEvent.click(screen.getByTestId("receipt-delete-confirm"));
    await screen.findByText("입금을 삭제했습니다.");
    expect(writes()[0][1].method).toBe("DELETE");
    expect(screen.queryByTestId("receipt-row-7")).not.toBeInTheDocument();
    expect(changed).toHaveBeenCalledOnce();
  });
  it.each(["0", "-1", "1.5", "9007199254740992", ""])(
    "rejects invalid won %s locally",
    async (amount) => {
      panel();
      await add();
      change("receipt-amount", amount);
      fireEvent.click(screen.getByTestId("receipt-save"));
      await screen.findByText("필수 항목과 입력 범위를 확인하세요.");
      expect(writes()).toHaveLength(0);
      expect(screen.getByTestId("receipt-amount")).toHaveFocus();
    },
  );
  it("requires a date", async () => {
    panel();
    await add();
    change("receipt-date", "");
    await waitFor(() =>
      expect(screen.getByTestId("receipt-save")).not.toBeDisabled(),
    );
    fireEvent.click(screen.getByTestId("receipt-save"));
    await screen.findByText("필수 항목과 입력 범위를 확인하세요.");
    expect(writes()).toHaveLength(0);
  });
  it("rejects over-receipt locally", async () => {
    panel();
    await add();
    change("receipt-amount", "81");
    fireEvent.click(screen.getByTestId("receipt-save"));
    await screen.findByText("입금액이 미수 잔액을 초과합니다.");
    expect(writes()).toHaveLength(0);
  });
  it.each(["VALIDATION_ERROR", "OVER_RECEIPT", "CLOSED_MONTH", "STALE_BILL"])(
    "retains form and shows server %s",
    async (code) => {
      failWrite = code;
      panel();
      await add();
      fireEvent.click(screen.getByTestId("receipt-save"));
      await screen.findByText("문의 번호 receipt-request");
      expect(screen.getByTestId("receipt-amount")).toHaveValue("40");
      expect(screen.getByTestId("receipt-remark")).toHaveValue("분할 입금");
      expect(screen.getByRole("dialog")).toBeVisible();
      expect(changed).not.toHaveBeenCalled();
    },
  );
  it("closed receipt rows hide edits and deletes, open receipt dates remain independent of bill month", async () => {
    closed = ["2026-10"];
    panel();
    await screen.findByTestId("receipt-row-7");
    expect(screen.getByText("마감").className).toContain("locked");
    expect(screen.queryByTestId("receipt-edit-7")).not.toBeInTheDocument();
    expect(screen.queryByTestId("receipt-delete-7")).not.toBeInTheDocument();
    await add();
    expect(screen.getByTestId("receipt-save")).not.toBeDisabled();
  });
  it("new date closed month disables save", async () => {
    closed = ["2026-11"];
    panel();
    fireEvent.click(await screen.findByTestId("receipt-add"));
    change("receipt-date", "2026-11-01");
    change("receipt-amount", "40");
    await screen.findByText(
      "마감된 입금월은 수정할 수 없습니다. 기존·새 입금월을 확인하세요.",
    );
    expect(screen.getByTestId("receipt-save")).toBeDisabled();
    expect(writes()).toHaveLength(0);
  });
  it.each(["DRAFT", "CANCEL", "PLAN"] as const)(
    "%s billing is read-only",
    async (state) => {
      detail.billing.bill_stts_cd = state;
      panel();
      await screen.findByTestId("receipt-row-7");
      expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
      expect(screen.queryByTestId("receipt-edit-7")).not.toBeInTheDocument();
    },
  );
  it("amendment billing is read-only", async () => {
    detail.original_bill_id = 2;
    panel();
    await screen.findByTestId("receipt-row-7");
    expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
  });
  it("past year remains readable and hides every mutation", async () => {
    panel(year - 1);
    await screen.findByTestId("receipt-row-7");
    expect(screen.getByText("과거 연도는 조회만 가능합니다.")).toBeVisible();
    expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
    expect(screen.queryByTestId("receipt-edit-7")).not.toBeInTheDocument();
  });
  it("mobile suppresses mutation and closes open form on resize", async () => {
    panel();
    await add();
    Object.defineProperty(window, "innerWidth", { value: 767 });
    fireEvent(window, new Event("resize"));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
    expect(screen.getByRole("table")).toBeVisible();
  });
  it("dirty form cancellation needs explicit discard", async () => {
    panel();
    await add();
    fireEvent.click(screen.getByTestId("receipt-cancel"));
    expect(screen.getByTestId("receipt-discard-dialog")).toBeVisible();
    fireEvent.click(screen.getByTestId("receipt-keep"));
    await waitFor(() =>
      expect(
        screen.queryByTestId("receipt-discard-dialog"),
      ).not.toBeInTheDocument(),
    );
    expect(screen.getByTestId("receipt-amount")).toHaveValue("40");
    fireEvent.click(screen.getByTestId("receipt-cancel"));
    fireEvent.click(screen.getByTestId("receipt-discard"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
});
