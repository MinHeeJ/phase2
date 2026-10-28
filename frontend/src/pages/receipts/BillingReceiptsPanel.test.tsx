import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { BillingReceiptsPanel } from "./BillingReceiptsPanel";
import { updateBillingReceipt, type BillingReceiptDetail } from "./client";
import tables from "../developers/Developers.module.css";

vi.mock("../shared/PeriodBar", () => ({ seoulMonth: () => "2026-10" }));
const permission = vi.hoisted(() => ({ value: true }));
vi.mock("../../shared/types", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  can: () => permission.value,
}));
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
  });
let detail: BillingReceiptDetail;
let fetcher: ReturnType<typeof vi.fn>;
let readFailure: boolean, closeFailure: boolean, closed: boolean;
let writeFailure: Response | null;
const changed = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  permission.value = true;
  changed.mockClear();
  readFailure = false;
  closeFailure = false;
  closed = false;
  writeFailure = null;
  detail = {
    billing: {
      bill_id: 3,
      bill_no: "B-2610-91",
      bill_stts_cd: "CONFIRM",
      sply_amt: 100,
      vat_amt: 10,
      tot_amt: 110,
      adjusted_total_amt: 110,
      received_amt: 30,
      receivable_amt: 80,
      atrb_ym: "2026-09",
      bill_dt: "2026-10-01",
    },
    details: [],
    receipts: [
      {
        receipt_id: 7,
        rcpt_dt: "2026-10-15",
        rcpt_amt: 30,
        rmrk_desc: "분할 입금",
      },
    ],
    amendments: [],
    original_bill_id: null,
    change_history: [],
    warnings: [],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/month-close?")) {
      if (closeFailure) throw new Error("offline");
      return response([
        { month: "2026-10", status: closed ? "CLOSED" : "OPEN" },
        { month: "2026-11", status: "CLOSED" },
      ]);
    }
    if (init?.method && init.method !== "GET") {
      if (writeFailure) return writeFailure.clone();
      if (init.method === "DELETE")
        detail.receipts = detail.receipts.filter(
          (r) => r.receipt_id !== Number(url.split("/").at(-1)),
        );
      else {
        const input = JSON.parse(init.body as string);
        if (init.method === "PUT")
          detail.receipts = detail.receipts.map((r) =>
            r.receipt_id === 7 ? { ...r, ...input } : r,
          );
        else detail.receipts.push({ receipt_id: 8, ...input });
      }
      detail.billing.received_amt = detail.receipts.reduce(
        (sum, r) => sum + r.rcpt_amt,
        0,
      );
      detail.billing.receivable_amt =
        detail.billing.adjusted_total_amt - detail.billing.received_amt;
      detail.change_history.push({
        changed_at: "2026-10-23T12:00:00",
        user_id: null,
        description: "입금 변경",
      });
      return response(detail);
    }
    if (url === "/api/billings/3/receipts") {
      if (readFailure) throw new Error("offline");
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
const panel = (year = 2026) =>
  render(
    <BillingReceiptsPanel billId={3} selectedYear={year} onChanged={changed} />,
  );
const writes = () =>
  fetcher.mock.calls.filter((c) => c[1]?.method && c[1].method !== "GET");
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const save = async () => {
  await waitFor(() =>
    expect(screen.getByTestId("billing-receipt-save")).toBeEnabled(),
  );
  fireEvent.click(screen.getByTestId("billing-receipt-save"));
};
async function add() {
  fireEvent.click(await screen.findByTestId("billing-receipt-add"));
}
const failure = (code: string, field: string | null = null, status = 409) =>
  new Response(
    JSON.stringify({
      success: false,
      code,
      message: "입금 요청을 확인하세요.",
      field,
      requestId: "receipt-request",
    }),
    { status },
  );

describe("UI-13 receipts local region and M5", () => {
  it("shows server balance and UI-02 table classes without writes", async () => {
    panel();
    const row = await screen.findByTestId("billing-receipt-row-7");
    expect(within(row).getByText("분할 입금")).toBeVisible();
    expect(screen.getByRole("table")).toHaveClass(tables.table);
    expect(screen.getByRole("table").parentElement).toHaveClass(
      tables.tableScroll,
    );
    expect(screen.getByTestId("billing-receipt-summary")).toHaveTextContent(
      "80원",
    );
    expect(screen.getByText("미수", { exact: true }).className).toContain(
      "warning",
    );
    expect(writes()).toHaveLength(0);
  });
  it("loading then empty is distinct from an error", async () => {
    detail.receipts = [];
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    expect(
      await screen.findByText("등록된 입금 내역이 없습니다."),
    ).toBeVisible();
  });
  it("read failure supports retry without presenting false empty data", async () => {
    readFailure = true;
    panel();
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(
      screen.queryByText("등록된 입금 내역이 없습니다."),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-receipt-add")).not.toBeInTheDocument();
    readFailure = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await screen.findByTestId("billing-receipt-row-7");
  });
  it("month-close read failure fails closed", async () => {
    closeFailure = true;
    panel();
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.queryByTestId("billing-receipt-add")).not.toBeInTheDocument();
  });
  it.each(["PLAN", "DRAFT", "CANCEL"] as const)(
    "%s bills are read-only",
    async (status) => {
      detail.billing.bill_stts_cd = status;
      panel();
      await screen.findByTestId("billing-receipt-row-7");
      expect(
        screen.queryByTestId("billing-receipt-add"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByText("수정", { exact: true }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByText("삭제", { exact: true }),
      ).not.toBeInTheDocument();
    },
  );
  it("amendment is read-only", async () => {
    detail.original_bill_id = 9;
    panel();
    await screen.findByTestId("billing-receipt-row-7");
    expect(screen.queryByTestId("billing-receipt-add")).not.toBeInTheDocument();
  });
  it("permission slot hides every write", async () => {
    permission.value = false;
    panel();
    await screen.findByTestId("billing-receipt-row-7");
    expect(screen.getByText("입금 권한이 없습니다.")).toBeVisible();
    expect(screen.queryByTestId("billing-receipt-add")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("billing-receipt-edit-7"),
    ).not.toBeInTheDocument();
  });
  it("past selected year hides writes even for a current-year receipt", async () => {
    panel(2025);
    await screen.findByTestId("billing-receipt-row-7");
    expect(screen.queryByTestId("billing-receipt-add")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("billing-receipt-delete-7"),
    ).not.toBeInTheDocument();
  });
  it("past receipt year and closed receipt month hide row mutations", async () => {
    closed = true;
    panel();
    await screen.findByTestId("billing-receipt-row-7");
    expect(screen.getByText("마감").className).toContain("locked");
    expect(
      screen.queryByTestId("billing-receipt-edit-7"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("billing-receipt-delete-7"),
    ).not.toBeInTheDocument();
  });
  it("past receipt dates cannot be edited even in current selected year", async () => {
    detail.receipts[0].rcpt_dt = "2025-12-31";
    panel();
    await screen.findByTestId("billing-receipt-row-7");
    expect(
      screen.queryByTestId("billing-receipt-edit-7"),
    ).not.toBeInTheDocument();
  });
  it("mobile transition closes write dialog and hides actions", async () => {
    panel();
    await add();
    Object.defineProperty(window, "innerWidth", { value: 767 });
    fireEvent(window, new Event("resize"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByTestId("billing-receipt-add")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it.each([
    ["입금일", ""],
    ["입금액", "0"],
    ["입금액", "-1"],
    ["입금액", "1.5"],
  ])("validates %s=%s without a request", async (label, value) => {
    panel();
    await add();
    change("입금일", "2026-10-23");
    change("입금액", "20");
    change(label, value);
    await save();
    expect(writes()).toHaveLength(0);
    expect(screen.getByLabelText(label)).toHaveFocus();
  });
  it("create refreshes receipts, full-paid badge and parent list/summary/history", async () => {
    panel();
    await add();
    change("입금일", "2026-10-23");
    change("입금액", "80");
    change("비고", "잔금");
    await save();
    await screen.findByText("저장했습니다.");
    expect(changed).toHaveBeenCalledOnce();
    expect(screen.getByText("입금 완료").className).toContain("success");
    expect(writes()[0][0]).toBe("/api/billings/3/receipts");
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({
      rcpt_dt: "2026-10-23",
      rcpt_amt: 80,
      rmrk_desc: "잔금",
    });
    expect(screen.getByText(/입금 변경/)).toBeInTheDocument();
  });
  it("update uses selected path id and never sends response-only fields", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("billing-receipt-edit-7"));
    change("입금일", "2026-10-24");
    change("입금액", "40");
    await save();
    await screen.findByText("저장했습니다.");
    expect(writes()[0][0]).toBe("/api/billings/3/receipts/7");
    expect(writes()[0][1].method).toBe("PUT");
    expect(JSON.parse(writes()[0][1].body as string)).not.toHaveProperty(
      "receipt_id",
    );
    expect(screen.getByTestId("billing-receipt-summary")).toHaveTextContent(
      "70원",
    );
  });
  it("delete requires explicit confirmation and then increases balance", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("billing-receipt-delete-7"));
    expect(writes()).toHaveLength(0);
    expect(screen.queryByLabelText("입금액")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
    await save();
    await screen.findByText("삭제했습니다.");
    expect(writes()[0][1].method).toBe("DELETE");
    expect(screen.getByTestId("billing-receipt-summary")).toHaveTextContent(
      "110원",
    );
    expect(changed).toHaveBeenCalledOnce();
  });
  it.each(["OVER_RECEIPT", "CLOSED_MONTH", "STALE_BILL"])(
    "%s preserves draft and displays server feedback",
    async (code) => {
      panel();
      await add();
      change("입금일", "2026-10-23");
      change("입금액", "81");
      writeFailure = failure(code, "rcpt_amt");
      await save();
      await screen.findAllByText("입금 요청을 확인하세요.");
      expect(screen.getByLabelText("입금액")).toHaveValue("81");
      expect(screen.getByText("문의 번호 receipt-request")).toBeVisible();
      expect(changed).not.toHaveBeenCalled();
      expect(detail.receipts).toHaveLength(1);
    },
  );
  it("failed deletion preserves confirmation and row", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("billing-receipt-delete-7"));
    writeFailure = failure("CLOSED_MONTH");
    await save();
    await screen.findByText("입금 요청을 확인하세요.");
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(detail.receipts).toHaveLength(1);
  });
  it("new closed date disables saving", async () => {
    panel();
    await add();
    change("입금일", "2026-11-02");
    change("입금액", "20");
    await screen.findByText("마감된 달은 변경할 수 없습니다.");
    expect(screen.getByTestId("billing-receipt-save")).toBeDisabled();
    expect(writes()).toHaveLength(0);
  });
  it("cancel confirmation never sends a delete", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("billing-receipt-delete-7"));
    fireEvent.click(screen.getByTestId("billing-receipt-cancel"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("dirty cancel requires discard confirmation without writes", async () => {
    panel();
    await add();
    change("입금액", "20");
    fireEvent.click(screen.getByTestId("billing-receipt-cancel"));
    expect(
      screen.getByRole("dialog", { name: "저장하지 않은 내용이 있습니다." }),
    ).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    expect(writes()).toHaveLength(0);
  });
  it("explicit client payload excludes a response receipt id", async () => {
    await updateBillingReceipt(3, 7, detail.receipts[0]);
    expect(JSON.parse(writes()[0][1].body as string)).not.toHaveProperty(
      "receipt_id",
    );
  });
  it("failed post-save refresh keeps dialog and prevents duplicate mutation", async () => {
    changed.mockRejectedValueOnce(new Error("refresh failed"));
    panel();
    await add();
    change("입금일", "2026-10-23");
    change("입금액", "20");
    await save();
    await screen.findByText(
      "저장 후 조회에 실패했습니다. 현재 상태를 확인하세요.",
    );
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(screen.getByTestId("billing-receipt-save")).toBeDisabled();
    expect(writes()).toHaveLength(1);
  });
});
