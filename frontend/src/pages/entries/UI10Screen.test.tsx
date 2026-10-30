import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI10Screen from "./UI10Screen";
import type { FinanceEntry, EntryList } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let rows: FinanceEntry[],
  drafts: FinanceEntry[],
  fetcher: ReturnType<typeof vi.fn>;
let closed: boolean, offline: boolean, rejected: boolean;
const original: FinanceEntry = {
  entry_id: 7,
  entry_ym: "2026-10",
  fin_se_cd: "SGA",
  item_nm: "임대료",
  entry_amt: 300000,
  vat_amt: 123,
  inout_dt: "2026-10-20",
  rpt_yn: "Y",
  inout_cmpl_yn: "N",
  remark: "비고",
  copy_entry_id: null,
  auto_fill_yn: "N",
  already_copied: false,
};
const previous: FinanceEntry = {
  ...original,
  entry_id: null,
  copy_entry_id: 6,
  auto_fill_yn: "Y",
  item_nm: "전월 반복",
};
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  rows = [{ ...original }];
  drafts = [];
  closed = false;
  offline = false;
  rejected = false;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (offline) throw new Error("offline");
    if (url.startsWith("/api/codes/FIN_CATEGORY"))
      return response([
        { code: "SGA", name: "판관비" },
        { code: "NOI", name: "영업외수익" },
        { code: "NOE", name: "영업외비용" },
      ]);
    if (url.includes("copy-previous"))
      return response({
        items: [
          { ...previous, auto_fill_yn: "N" },
          {
            ...previous,
            copy_entry_id: 5,
            item_nm: "중복항목",
            already_copied: true,
          },
        ],
      });
    if (init?.method && init.method !== "GET") {
      if (rejected)
        return new Response(
          JSON.stringify({
            success: false,
            code: "CLOSED_MONTH",
            message: "마감된 달입니다.",
            field: "entry_ym",
            requestId: "entries-request",
          }),
          { status: 409 },
        );
      if (init.method === "DELETE")
        rows = rows.filter((r) => r.entry_id !== Number(url.split("/").at(-1)));
      else {
        const input = JSON.parse(init.body as string);
        if (init.method === "PUT")
          rows = rows.map((r) =>
            r.entry_id === Number(url.split("/").at(-1))
              ? { ...r, ...input }
              : r,
          );
        else {
          const saved = { ...input, entry_id: 8, already_copied: false };
          rows.push(saved);
          drafts = [];
          return response(saved);
        }
      }
      return response({});
    }
    if (url.startsWith("/api/finance/entries?")) {
      const p = new URLSearchParams(url.split("?")[1]);
      const month = `${p.get("year")}-${p.get("month")!.padStart(2, "0")}`;
      const items = rows.filter((r) => r.entry_ym === month);
      const data: EntryList = {
        items,
        drafts: month === "2026-10" ? drafts : [],
        saved_count: items.length,
        total: items.length,
        total_amount: items.reduce((s, r) => s + r.entry_amt, 0),
        total_vat: items.reduce((s, r) => s + r.vat_amt, 0),
        month_status: closed ? "CLOSED" : "OPEN",
        months: Array.from({ length: 12 }, (_, i) => ({
          month: `2026-${String(i + 1).padStart(2, "0")}`,
          status: closed ? "CLOSED" : "OPEN",
          saved_count: i === 9 ? rows.length : 0,
        })),
      };
      return response(data);
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = (extra = {}) =>
  render(<UI10Screen initialMonth="2026-10" todayMonth="2026-10" {...extra} />);
const writes = () =>
  fetcher.mock.calls.filter(
    (c) =>
      ["POST", "PUT", "DELETE"].includes(c[1]?.method ?? "") &&
      !String(c[0]).includes("copy-previous"),
  );
describe("재무 항목", () => {
  it("shows reference columns and full saved totals", async () => {
    panel();
    const row = await screen.findByTestId("entry-row-7");
    expect(within(row).getByText("임대료")).toBeVisible();
    for (const name of [
      "구분",
      "세부 항목",
      "금액",
      "부가세",
      "입출금일",
      "매달 반복",
      "비고",
      "삭제",
    ])
      expect(screen.getByRole("columnheader", { name })).toBeVisible();
    expect(screen.getByTestId("entries-totals")).toHaveTextContent("300,000");
    expect(writes()).toHaveLength(0);
  });
  it("renders empty state without invented rows", async () => {
    rows = [];
    panel();
    expect(
      await screen.findByText("등록된 재무 항목이 없습니다."),
    ).toBeVisible();
  });
  it("read failure offers retry and cannot write", async () => {
    offline = true;
    panel();
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(screen.queryByTestId("entry-add")).not.toBeInTheDocument();
    offline = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByTestId("entry-row-7")).toBeVisible();
  });
  it("closed month hides writes and shows lock", async () => {
    closed = true;
    panel();
    await screen.findByTestId("entry-row-7");
    expect(
      screen.getByText("마감된 달입니다. 조회만 가능합니다."),
    ).toBeVisible();
    expect(screen.queryByTestId("entry-edit-7")).not.toBeInTheDocument();
    expect(screen.queryByTestId("entry-add")).not.toBeInTheDocument();
  });
  it("future month is disabled", async () => {
    panel();
    await screen.findByTestId("entry-row-7");
    expect(screen.getByTestId("entries-month-11")).toBeDisabled();
  });
  it("past year is read only", async () => {
    panel({ initialMonth: "2025-10" });
    await screen.findByText("등록된 재무 항목이 없습니다.");
    expect(screen.queryByTestId("entry-add")).not.toBeInTheDocument();
  });
  it("mobile shows PC guidance only", () => {
    Object.defineProperty(window, "innerWidth", { value: 375 });
    panel();
    expect(screen.getByText("PC에서 이용해 주세요.")).toBeVisible();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
  it("auto drafts are separate and remain unsaved until confirmed", async () => {
    drafts = [{ ...previous }];
    panel();
    expect(await screen.findByTestId("entry-draft-6")).toBeVisible();
    expect(screen.getByTestId("entries-totals")).toHaveTextContent("300,000");
    expect(writes()).toHaveLength(0);
    fireEvent.click(screen.getByTestId("entry-draft-save-6"));
    await waitFor(() => expect(writes()).toHaveLength(1));
    const b = JSON.parse(writes()[0][1].body as string);
    expect(b.copy_entry_id).toBe(6);
    expect(b.auto_fill_yn).toBe("Y");
    await screen.findByText("저장했습니다.");
  });
  it("create validates required fields and allows zero", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("entry-add"));
    fireEvent.click(screen.getByTestId("entry-save"));
    expect(await screen.findByText("세부 항목을 입력하세요.")).toBeVisible();
    expect(writes()).toHaveLength(0);
    fireEvent.change(screen.getByLabelText("세부 항목"), {
      target: { value: "교육비" },
    });
    fireEvent.change(screen.getByLabelText("금액"), { target: { value: "0" } });
    fireEvent.click(screen.getByTestId("entry-save"));
    await screen.findByText("저장했습니다.");
    expect(JSON.parse(writes()[0][1].body as string)).not.toHaveProperty(
      "entry_id",
    );
  });
  it("edit targets selected identity and keeps custom VAT", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("entry-edit-7"));
    fireEvent.change(screen.getByLabelText("비고"), {
      target: { value: "변경" },
    });
    fireEvent.click(screen.getByTestId("entry-save"));
    await screen.findByText("저장했습니다.");
    expect(writes()[0][0]).toBe("/api/finance/entries/7");
    expect(JSON.parse(writes()[0][1].body as string).vat_amt).toBe(123);
  });
  it("failed save retains fields and request id", async () => {
    rejected = true;
    panel();
    fireEvent.click(await screen.findByTestId("entry-edit-7"));
    fireEvent.change(screen.getByLabelText("비고"), {
      target: { value: "유지" },
    });
    fireEvent.click(screen.getByTestId("entry-save"));
    await screen.findByText("문의 번호 entries-request");
    expect(screen.getByLabelText("비고")).toHaveValue("유지");
    expect(screen.getByRole("dialog")).toBeVisible();
  });
  it("deletes only selected row and refreshes", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("entry-delete-7"));
    await screen.findByText("등록된 재무 항목이 없습니다.");
    expect(writes()[0][0]).toBe("/api/finance/entries/7");
  });
  it("failed delete preserves row", async () => {
    rejected = true;
    panel();
    fireEvent.click(await screen.findByTestId("entry-delete-7"));
    await screen.findByText("마감된 달입니다.");
    expect(screen.getByTestId("entry-row-7")).toBeVisible();
  });
  it("copy preview marks duplicates and only adds local drafts", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("entries-copy"));
    await screen.findByText("이미 불러옴");
    expect(screen.getByTestId("entry-copy-select-5")).toBeDisabled();
    fireEvent.click(screen.getByTestId("entry-copy-select-6"));
    fireEvent.click(screen.getByTestId("entry-copy-apply"));
    expect(await screen.findByTestId("entry-draft-6")).toBeVisible();
    expect(writes()).toHaveLength(0);
    expect(
      fetcher.mock.calls.some((c) =>
        String(c[0]).includes("includeNonRecurring=true"),
      ),
    ).toBe(true);
  });
  it("pagination is local while totals remain monthly", async () => {
    rows = Array.from({ length: 21 }, (_, i) => ({
      ...original,
      entry_id: i + 1,
    }));
    panel();
    await screen.findByTestId("entry-row-1");
    expect(screen.getByTestId("entries-totals")).toHaveTextContent("6,300,000");
    const reads = fetcher.mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));
    expect(screen.getByTestId("entry-row-21")).toBeVisible();
    expect(screen.queryByTestId("entry-row-1")).not.toBeInTheDocument();
    expect(fetcher.mock.calls.length).toBe(reads);
  });
  it("drafts survive switching months", async () => {
    drafts = [{ ...previous }];
    panel();
    await screen.findByTestId("entry-draft-6");
    fireEvent.click(screen.getByTestId("entry-draft-edit-6"));
    fireEvent.change(screen.getByLabelText("비고"), {
      target: { value: "브라우저 초안" },
    });
    fireEvent.click(screen.getByTestId("entry-draft-keep"));
    fireEvent.click(screen.getByTestId("entries-month-9"));
    await waitFor(() =>
      expect(screen.queryByTestId("entry-draft-6")).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByTestId("entries-month-10"));
    expect(await screen.findByText("브라우저 초안")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
});
