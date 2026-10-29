import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { BillingReceiptPanel } from "./UI13M5";
import {
  updateBillingReceipt,
  type BillingReceipt,
  type ReceiptBillingDetail,
} from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const original: BillingReceipt = {
  receipt_id: 7,
  rcpt_dt: "2026-10-10",
  rcpt_amt: 20,
  rmrk_desc: "분할",
};
let rows: BillingReceipt[];
let detail: ReceiptBillingDetail;
let fetcher: ReturnType<typeof vi.fn>;
let readFailure: boolean;
let closeFailure: boolean;
let writeFailure: Response | null;
let refreshFailure: boolean;
const changed = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-29T00:00:00Z"));
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  changed.mockClear();
  rows = [{ ...original }];
  readFailure = false;
  closeFailure = false;
  writeFailure = null;
  refreshFailure = false;
  detail = {
    billing: {
      bill_id: 31,
      bill_no: "B-2610-31",
      project_name: "입금 프로젝트",
      contract_type: "MO",
      atrb_ym: "2026-10",
      bill_dt: "2026-10-01",
      sply_amt: 100,
      vat_amt: 10,
      tot_amt: 110,
      bill_stts_cd: "CONFIRM",
      rcpt_plan_dt: "2026-10-10",
      adjusted_total_amt: 110,
      received_amt: 20,
      receivable_amt: 90,
    },
    receipts: rows,
    details: [],
    amendments: [],
    original_bill_id: null,
    change_history: [],
    warnings: [],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/month-close?")) {
      if (closeFailure) throw new Error("offline");
      return response([
        { month: "2026-09", status: "CLOSED" },
        { month: "2026-10", status: "OPEN" },
      ]);
    }
    if (init?.method && init.method !== "GET") {
      if (writeFailure) return writeFailure.clone();
      if (init.method === "DELETE")
        rows = rows.filter(
          (r) => r.receipt_id !== Number(url.split("/").at(-1)),
        );
      else {
        const input = JSON.parse(init.body as string);
        if (init.method === "POST") rows.push({ ...input, receipt_id: 8 });
        else
          rows = rows.map((row) =>
            row.receipt_id === 7 ? { ...row, ...input } : row,
          );
      }
      if (refreshFailure) readFailure = true;
    }
    if (url.startsWith("/api/billings/31/receipts")) {
      if (readFailure && (!init?.method || init.method === "GET"))
        throw new Error("offline");
      const received = rows.reduce((sum, row) => sum + row.rcpt_amt, 0);
      return response({
        ...detail,
        receipts: rows,
        billing: {
          ...detail.billing,
          received_amt: received,
          receivable_amt: detail.billing.adjusted_total_amt - received,
        },
      });
    }
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = (year = 2026, readOnly = false) =>
  render(
    <BillingReceiptPanel
      billId={31}
      year={year}
      readOnly={readOnly}
      onChanged={changed}
    />,
  );
const writes = () =>
  fetcher.mock.calls.filter((c) => c[1]?.method && c[1].method !== "GET");
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
async function add() {
  const button = await screen.findByTestId("receipt-add");
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
}
async function fill(amount = "30", date = "2026-10-15") {
  change("입금일", date);
  change("입금액(부가세 포함)", amount);
  await waitFor(() => expect(screen.getByTestId("receipt-save")).toBeEnabled());
}
const failure = (
  code: string,
  field: string | null = "rcpt_amt",
  status = 409,
) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code,
      field,
      message:
        code === "CLOSED_MONTH" ? "마감된 입금입니다." : "입금액을 확인하세요.",
      requestId: "receipt-request",
    }),
    { status },
  );

describe("UI-13 receipt history and M5", () => {
  it("renders API-backed columns and adjusted VAT-inclusive balance using the shared table", async () => {
    panel();
    const row = await screen.findByTestId("receipt-row-7");
    expect(row).toHaveTextContent("2026-10-10");
    expect(screen.getByRole("table").className).toContain("table");
    for (const label of ["입금일", "입금액(부가세 포함)", "비고", "작업"])
      expect(screen.getByRole("columnheader", { name: label })).toBeVisible();
    expect(screen.getByTestId("receipt-balance")).toHaveTextContent(
      "미수금 90원",
    );
    expect(screen.getByText("미수").className).toContain("warning");
    expect(writes()).toHaveLength(0);
  });
  it("creates a split receipt then refreshes panel and parent summary", async () => {
    panel();
    await add();
    await fill();
    change("비고", "두 번째");
    fireEvent.click(screen.getByTestId("receipt-save"));
    await screen.findByText("저장했습니다.");
    expect(changed).toHaveBeenCalledOnce();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByTestId("receipt-balance")).toHaveTextContent(
      "미수금 60원",
    );
    expect(writes()[0][0]).toBe("/api/billings/31/receipts");
    expect(writes()[0][1].method).toBe("POST");
    expect(JSON.parse(writes()[0][1].body as string)).toEqual({
      rcpt_dt: "2026-10-15",
      rcpt_amt: 30,
      rmrk_desc: "두 번째",
    });
  });
  it("edits selected identity without copying response fields and allows replacing its old amount", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("receipt-edit-7"));
    await fill("100");
    fireEvent.click(screen.getByTestId("receipt-save"));
    await screen.findByText("저장했습니다.");
    expect(writes()[0][0]).toBe("/api/billings/31/receipts/7");
    expect(writes()[0][1].method).toBe("PUT");
    expect(JSON.parse(writes()[0][1].body as string)).not.toHaveProperty(
      "receipt_id",
    );
    expect(screen.getByTestId("receipt-balance")).toHaveTextContent(
      "미수금 10원",
    );
  });
  it("delete has explicit confirmation and no editable inputs, then refreshes balance", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("receipt-delete-7"));
    expect(screen.getByText(/이 입금을 삭제하시겠습니까/)).toBeVisible();
    expect(screen.queryByLabelText("입금일")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
    await waitFor(() =>
      expect(screen.getByTestId("receipt-save")).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "삭제 확인" }));
    await screen.findByText("삭제했습니다.");
    expect(writes()[0][1].method).toBe("DELETE");
    expect(writes()[0][1].body).toBeUndefined();
    expect(screen.getByTestId("receipt-balance")).toHaveTextContent(
      "미수금 110원",
    );
  });
  it.each(["0", "-1", "1.5", "111", "9223372036854775808"])(
    "rejects invalid or excessive won %s",
    async (amount) => {
      panel();
      await add();
      await fill(amount);
      fireEvent.click(screen.getByTestId("receipt-save"));
      await waitFor(() =>
        expect(screen.getByLabelText("입금액(부가세 포함)")).toHaveFocus(),
      );
      expect(writes()).toHaveLength(0);
    },
  );
  it("requires a date without submitting", async () => {
    panel();
    await add();
    change("입금액(부가세 포함)", "30");
    await waitFor(() =>
      expect(screen.getByTestId("receipt-save")).toBeEnabled(),
    );
    fireEvent.click(screen.getByTestId("receipt-save"));
    await waitFor(() => expect(screen.getByLabelText("입금일")).toHaveFocus());
    expect(writes()).toHaveLength(0);
  });
  it("closed original hides edit/delete independently of open billing month", async () => {
    rows[0].rcpt_dt = "2026-09-10";
    panel();
    expect(
      within(await screen.findByTestId("receipt-row-7")).getByText("마감")
        .className,
    ).toContain("locked");
    expect(screen.queryByTestId("receipt-edit-7")).not.toBeInTheDocument();
    expect(screen.queryByTestId("receipt-delete-7")).not.toBeInTheDocument();
    expect(screen.getByTestId("receipt-add")).toBeEnabled();
  });
  it("closed new receipt month disables save", async () => {
    panel();
    await add();
    change("입금일", "2026-09-15");
    change("입금액(부가세 포함)", "30");
    await screen.findByText(
      "입금일 월이 마감되어 등록·수정·삭제할 수 없습니다.",
    );
    expect(screen.getByTestId("receipt-save")).toBeDisabled();
    expect(writes()).toHaveLength(0);
  });
  it.each(["PLAN", "DRAFT", "CANCEL"] as const)(
    "%s has read-only cash history",
    async (status) => {
      detail.billing.bill_stts_cd = status;
      panel();
      await screen.findByTestId("receipt-row-7");
      expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
      expect(screen.queryByTestId("receipt-edit-7")).not.toBeInTheDocument();
    },
  );
  it("amendments do not expose receipt controls", async () => {
    detail.original_bill_id = 99;
    panel();
    await screen.findByTestId("receipt-row-7");
    expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
    expect(screen.queryByTestId("receipt-delete-7")).not.toBeInTheDocument();
  });
  it("past selected year hides every cash write but retains history", async () => {
    panel(2025);
    await screen.findByTestId("receipt-row-7");
    expect(
      screen.getByText("과거 연도는 조회·다운로드만 가능합니다."),
    ).toBeVisible();
    expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
    expect(screen.queryByTestId("receipt-edit-7")).not.toBeInTheDocument();
  });
  it("read-only prop hides every write", async () => {
    panel(2026, true);
    await screen.findByTestId("receipt-row-7");
    expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
    expect(screen.queryByTestId("receipt-delete-7")).not.toBeInTheDocument();
  });
  it("mobile resize closes an open modal and hides writes", async () => {
    panel();
    await add();
    Object.defineProperty(window, "innerWidth", { value: 767 });
    fireEvent(window, new Event("resize"));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByTestId("receipt-add")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("empty list does not fabricate rows", async () => {
    rows = [];
    panel();
    expect(await screen.findByText("등록된 입금이 없습니다.")).toBeVisible();
    expect(screen.queryByTestId("receipt-row-7")).not.toBeInTheDocument();
  });
  it("read errors show retry instead of an empty success", async () => {
    readFailure = true;
    panel();
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(
      screen.queryByText("등록된 입금이 없습니다."),
    ).not.toBeInTheDocument();
    readFailure = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await screen.findByTestId("receipt-row-7");
  });
  it("close lookup failure prevents editing and supports retry", async () => {
    closeFailure = true;
    panel();
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.queryByTestId("receipt-edit-7")).not.toBeInTheDocument();
    closeFailure = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await screen.findByTestId("receipt-edit-7");
  });
  it.each(["OVER_RECEIPT", "CLOSED_MONTH", "STALE_BILL"])(
    "server %s retains modal and draft",
    async (code) => {
      panel();
      await add();
      await fill();
      writeFailure = failure(code);
      fireEvent.click(screen.getByTestId("receipt-save"));
      await screen.findByText("문의 번호 receipt-request");
      expect(screen.getByRole("dialog")).toBeVisible();
      expect(screen.getByLabelText("입금액(부가세 포함)")).toHaveValue("30");
      expect(changed).not.toHaveBeenCalled();
    },
  );
  it("server field validation focuses input and preserves entered values", async () => {
    panel();
    await add();
    await fill();
    writeFailure = failure("VALIDATION_ERROR", "rcpt_dt", 400);
    fireEvent.click(screen.getByTestId("receipt-save"));
    await waitFor(() => expect(screen.getByLabelText("입금일")).toHaveFocus());
    expect(screen.getByLabelText("입금액(부가세 포함)")).toHaveValue("30");
  });
  it("failed deletion retains confirmation and existing row", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("receipt-delete-7"));
    await waitFor(() =>
      expect(screen.getByTestId("receipt-save")).toBeEnabled(),
    );
    writeFailure = failure("CLOSED_MONTH");
    fireEvent.click(screen.getByTestId("receipt-save"));
    await screen.findByText("마감된 입금입니다.");
    expect(screen.getByTestId("receipt-row-7")).toBeVisible();
    expect(screen.getByRole("dialog")).toBeVisible();
  });
  it("cancel never sends and dirty cancellation requires confirmation", async () => {
    panel();
    await add();
    await fill();
    fireEvent.click(screen.getByTestId("receipt-cancel"));
    expect(
      screen.getByRole("dialog", { name: "저장하지 않은 내용이 있습니다." }),
    ).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("a committed write followed by failed refresh blocks a duplicate submit", async () => {
    panel();
    await add();
    await fill();
    refreshFailure = true;
    fireEvent.click(screen.getByTestId("receipt-save"));
    await screen.findByText(
      "저장 후 조회에 실패했습니다. 현재 상태를 확인하세요.",
    );
    expect(screen.getByTestId("receipt-save")).toBeDisabled();
    expect(writes()).toHaveLength(1);
  });
  it("paid balance uses the green completion badge", async () => {
    rows[0].rcpt_amt = 110;
    panel();
    await screen.findByTestId("receipt-row-7");
    expect(screen.getByText("입금 완료").className).toContain("success");
  });
  it("client explicitly strips response-only identity from update payload", async () => {
    await updateBillingReceipt(31, 7, original);
    expect(JSON.parse(writes()[0][1].body as string)).not.toHaveProperty(
      "receipt_id",
    );
  });
});
