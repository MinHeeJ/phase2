import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { UI13ReceiptPanel } from "./UI13ReceiptPanel";
import type { BillingReceiptDetail } from "./client";
import type { MonthClose } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const changed = vi.fn().mockResolvedValue(undefined);
let detail: BillingReceiptDetail;
let months: MonthClose[];
let fetcher: ReturnType<typeof vi.fn>;
let failRead: boolean;
let failWrite: string | null;
let failCalendar: boolean;
const year = () => Number(seoulMonth().slice(0, 4));
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  detail = {
    billing: {
      bill_id: 17,
      bill_no: "입금 검증",
      bill_stts_cd: "CONFIRM",
      adjusted_total_amt: 110,
      received_amt: 30,
      receivable_amt: 80,
      sply_amt: 100,
      atrb_ym: "2026-01",
    },
    receipts: [
      {
        receipt_id: 45,
        rcpt_dt: "2026-10-23",
        rcpt_amt: 30,
        rmrk_desc: "첫 입금",
      },
    ],
    details: [],
    amendments: [],
    original_bill_id: null,
    change_history: [],
    warnings: [],
  };
  months = [];
  failRead = false;
  failWrite = null;
  failCalendar = false;
  changed.mockClear();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/month-close?")) {
      if (failCalendar) throw new Error("offline");
      return response(months);
    }
    if (init?.method && init.method !== "GET") {
      if (failWrite)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            code: failWrite,
            message: "입금 내용을 확인하세요.",
            field: "rcpt_amt",
            requestId: "receipt-request",
          }),
          { status: 409 },
        );
      if (init.method === "DELETE")
        detail.receipts = detail.receipts.filter(
          (r) => !url.endsWith(`/${r.receipt_id}`),
        );
      else {
        const body = JSON.parse(init.body as string);
        if (init.method === "POST")
          detail.receipts.push({ receipt_id: 46, ...body });
        else
          detail.receipts = detail.receipts.map((r) =>
            url.endsWith(`/${r.receipt_id}`) ? { ...r, ...body } : r,
          );
      }
      detail.billing.received_amt = detail.receipts.reduce(
        (n, r) => n + r.rcpt_amt,
        0,
      );
      detail.billing.receivable_amt =
        detail.billing.adjusted_total_amt - detail.billing.received_amt;
      return response(detail);
    }
    if (url === "/api/billings/17/receipts") {
      if (failRead) throw new Error("offline");
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
const panel = (selectedYear = year()) =>
  render(
    <UI13ReceiptPanel billId={17} year={selectedYear} onChanged={changed} />,
  );
const writes = () =>
  fetcher.mock.calls.filter(
    (call) => call[1]?.method && call[1].method !== "GET",
  );
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
async function openCreate() {
  fireEvent.click(await screen.findByTestId("receipt-add"));
  change("입금일", "2026-11-01");
  change("입금액 (부가세 포함)", "20");
}

describe("UI-13 receipts", () => {
  it("renders actual receipts, balance, warning badge and shared table classes", async () => {
    panel();
    expect(await screen.findByTestId("receipt-row-45")).toHaveTextContent(
      "2026-10-23",
    );
    expect(screen.getByText(/미수금 80원/)).toBeVisible();
    expect(screen.getByText("미수").className).toContain("warning");
    expect(screen.getByRole("table").className).toContain("table");
    expect(
      within(screen.getByTestId("receipt-row-45")).getByText("30원").className,
    ).toContain("amount");
  });
  it("renders completed payment badge separately", async () => {
    detail.billing.receivable_amt = 0;
    detail.billing.received_amt = 110;
    panel();
    expect((await screen.findByText("입금 완료")).className).toContain(
      "success",
    );
  });
  it("renders an empty receipts list", async () => {
    detail.receipts = [];
    panel();
    expect(await screen.findByText("등록된 입금이 없습니다.")).toBeVisible();
  });
  it("renders loading and recovers failed read", async () => {
    failRead = true;
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    await screen.findByText("서버에 연결할 수 없습니다.");
    failRead = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("receipt-row-45")).toBeVisible();
  });
  it("creates without an output identity and refreshes receipts and parent", async () => {
    panel();
    await openCreate();
    change("비고", "추가 입금");
    fireEvent.click(screen.getByTestId("receipt-save"));
    await screen.findByText("저장했습니다.");
    expect(writes()[0][0]).toBe("/api/billings/17/receipts");
    expect(writes()[0][1].method).toBe("POST");
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({
      rcpt_dt: "2026-11-01",
      rcpt_amt: 20,
      rmrk_desc: "추가 입금",
    });
    expect(changed).toHaveBeenCalledOnce();
    expect(screen.getByText(/미수금 60원/)).toBeVisible();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("updates selected path identity and never sends receipt_id", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("receipt-edit-45"));
    change("입금액 (부가세 포함)", "40");
    change("입금일", "2026-11-02");
    fireEvent.click(screen.getByTestId("receipt-save"));
    await screen.findByText("저장했습니다.");
    expect(writes()[0][0]).toBe("/api/billings/17/receipts/45");
    expect(writes()[0][1].method).toBe("PUT");
    expect(JSON.parse(writes()[0][1].body as string)).not.toHaveProperty(
      "receipt_id",
    );
    expect(changed).toHaveBeenCalledOnce();
    expect(screen.getByText(/미수금 70원/)).toBeVisible();
  });
  it("delete mode is read only, cancel sends nothing, confirmation deletes selected receipt", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("receipt-delete-45"));
    expect(screen.getByLabelText("입금일")).toHaveAttribute("readonly");
    expect(screen.getByRole("button", { name: "삭제 확인" })).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("receipt-cancel"));
    expect(writes()).toHaveLength(0);
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId("receipt-delete-45"));
    fireEvent.click(screen.getByTestId("receipt-save"));
    await screen.findByText("삭제했습니다.");
    expect(writes()[0][0]).toBe("/api/billings/17/receipts/45");
    expect(writes()[0][1].method).toBe("DELETE");
    expect(await screen.findByText("등록된 입금이 없습니다.")).toBeVisible();
    expect(screen.getByText(/미수금 110원/)).toBeVisible();
    expect(changed).toHaveBeenCalledOnce();
  });
  it.each(["입금일", "입금액 (부가세 포함)"])(
    "requires %s before sending",
    async (label) => {
      panel();
      await openCreate();
      change(label, "");
      fireEvent.click(screen.getByTestId("receipt-save"));
      await screen.findByText("입금일과 양수 입금액을 입력하세요.");
      expect(writes()).toHaveLength(0);
      expect(screen.getByLabelText(label)).toHaveFocus();
    },
  );
  it.each(["0", "-1"])(
    "rejects nonpositive amount %s locally",
    async (amount) => {
      panel();
      await openCreate();
      change("입금액 (부가세 포함)", amount);
      fireEvent.click(screen.getByTestId("receipt-save"));
      await screen.findByText("입금일과 양수 입금액을 입력하세요.");
      expect(writes()).toHaveLength(0);
    },
  );
  it.each(["OVER_RECEIPT", "CLOSED_MONTH", "STALE_BILL"])(
    "preserves failed %s input and request id",
    async (code) => {
      failWrite = code;
      panel();
      await openCreate();
      fireEvent.click(screen.getByTestId("receipt-save"));
      await screen.findByText("문의 번호 receipt-request");
      expect(screen.getByLabelText("입금일")).toHaveValue("2026-11-01");
      expect(screen.getByLabelText("입금액 (부가세 포함)")).toHaveValue("20");
      expect(screen.getByLabelText("입금액 (부가세 포함)")).toHaveFocus();
      expect(screen.getByRole("dialog")).toBeVisible();
      expect(changed).not.toHaveBeenCalled();
    },
  );
  it("shows dirty discard confirmation without a write", async () => {
    panel();
    await openCreate();
    fireEvent.click(screen.getByTestId("receipt-cancel"));
    expect(screen.getByTestId("receipt-discard-dialog")).toBeVisible();
    fireEvent.click(screen.getByTestId("receipt-keep"));
    await waitFor(() =>
      expect(
        screen.queryByTestId("receipt-discard-dialog"),
      ).not.toBeInTheDocument(),
    );
    expect(screen.getByLabelText("입금일")).toHaveValue("2026-11-01");
    fireEvent.click(screen.getByTestId("receipt-cancel"));
    fireEvent.click(screen.getByTestId("receipt-discard"));
    expect(writes()).toHaveLength(0);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("hides old closed-month mutation controls", async () => {
    months = [{ month: "2026-10", status: "CLOSED" }];
    panel();
    await screen.findByText("마감");
    expect(screen.queryByTestId("receipt-edit-45")).not.toBeInTheDocument();
    expect(screen.queryByTestId("receipt-delete-45")).not.toBeInTheDocument();
  });
  it("disables saving into a new closed month", async () => {
    months = [{ month: "2026-11", status: "CLOSED" }];
    panel();
    await openCreate();
    expect(
      screen.getByText("마감된 입금 월은 변경할 수 없습니다."),
    ).toBeVisible();
    expect(screen.getByTestId("receipt-save")).toBeDisabled();
    expect(writes()).toHaveLength(0);
  });
  it("fails closed when calendar lookup fails", async () => {
    failCalendar = true;
    panel();
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
    expect(screen.queryByTestId("receipt-edit-45")).not.toBeInTheDocument();
    failCalendar = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("receipt-add")).toBeVisible();
  });
  it.each(["DRAFT", "CANCEL", "PLAN"] as const)(
    "hides writes for %s",
    async (status) => {
      detail.billing.bill_stts_cd = status;
      panel();
      await screen.findByTestId("receipt-row-45");
      expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
      expect(screen.queryByTestId("receipt-edit-45")).not.toBeInTheDocument();
    },
  );
  it("hides all amendment writes", async () => {
    detail.original_bill_id = 9;
    panel();
    await screen.findByTestId("receipt-row-45");
    expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
  });
  it("past year stays readable and has no writes", async () => {
    panel(year() - 1);
    await screen.findByTestId("receipt-row-45");
    expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
    expect(screen.queryByTestId("receipt-edit-45")).not.toBeInTheDocument();
    expect(screen.queryByTestId("receipt-delete-45")).not.toBeInTheDocument();
  });
  it("mobile resize hides writes and closes open modal", async () => {
    panel();
    await openCreate();
    Object.defineProperty(window, "innerWidth", { value: 767 });
    fireEvent(window, new Event("resize"));
    await waitFor(() =>
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
    expect(screen.getByTestId("receipt-row-45")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
});
