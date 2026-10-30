import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import FinanceEntriesScreen from "./FinanceEntriesScreen";
const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let closed: boolean, replaced: boolean, fetcher: ReturnType<typeof vi.fn>;
const entry = {
  entry_id: 7,
  entry_ym: "2026-10",
  fin_se_cd: "SGA",
  item_nm: "기존 항목",
  entry_amt: 100,
  vat_amt: 0,
  inout_dt: null,
  rpt_yn: "N",
  inout_cmpl_yn: "N",
  remark: null,
  copy_entry_id: null,
  auto_fill_yn: "N",
  already_copied: false,
};
const draft = {
  ...entry,
  entry_id: null,
  copy_entry_id: 6,
  item_nm: "브라우저 초안",
  auto_fill_yn: "Y",
};
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  closed = false;
  replaced = false;
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/codes/"))
      return ok([{ code: "SGA", name: "판관비" }]);
    if (url.startsWith("/api/finance/entries?"))
      return ok({
        items: [
          replaced ? { ...entry, entry_id: 8, item_nm: "업로드 항목" } : entry,
        ],
        drafts: replaced ? [] : [draft],
        total: 1,
        saved_count: 1,
        total_amount: 100,
        total_vat: 0,
        month_status: closed ? "CLOSED" : "OPEN",
        months: [],
      });
    if (url.endsWith("/apply"))
      replaced = JSON.parse(String(init?.body)).confirm;
    return ok({
      upload_id: "123",
      error_count: 0,
      warning_count: 0,
      row_count: 1,
      can_apply: !replaced,
      applied_count: replaced ? 1 : 0,
      overwrite_count: 1,
      overwrite_notice: "해당 연월 전체 교체",
      items: [],
      rows: [
        {
          row: 5,
          severity: "NORMAL",
          entry: { ...entry, item_nm: "업로드 항목" },
        },
      ],
    });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
it("opens upload from existing UI-10, refreshes saved data and retains browser drafts", async () => {
  render(<FinanceEntriesScreen initialMonth="2026-10" todayMonth="2026-10" />);
  await screen.findByTestId("entry-draft-6");
  fireEvent.click(await screen.findByTestId("entries-upload"));
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [new File(["xlsx"], "재무.xlsx")] },
  });
  fireEvent.click(screen.getByTestId("finance-entries-validate"));
  await screen.findByText("엑셀 업로드 결과 · 재무 항목");
  fireEvent.click(screen.getByLabelText("해당 연월 전체 교체에 동의합니다"));
  fireEvent.click(screen.getByTestId("finance-entries-apply"));
  await waitFor(() => expect(screen.getByTestId("entry-row-8")).toBeVisible());
  expect(screen.getByTestId("entry-draft-6")).toHaveTextContent(
    "브라우저 초안",
  );
  fireEvent.click(screen.getByTestId("finance-entries-close"));
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});
it("closed months keep upload unavailable", async () => {
  closed = true;
  render(<FinanceEntriesScreen initialMonth="2026-10" todayMonth="2026-10" />);
  await screen.findByTestId("entry-row-7");
  expect(screen.queryByTestId("entries-upload")).not.toBeInTheDocument();
  expect(screen.getByTestId("entries-template")).toBeVisible();
});
it("mobile keeps PC-only boundary", () => {
  Object.defineProperty(window, "innerWidth", { value: 375 });
  render(<FinanceEntriesScreen initialMonth="2026-10" todayMonth="2026-10" />);
  expect(screen.getByText("PC에서 이용해 주세요.")).toBeVisible();
  expect(screen.queryByTestId("entries-upload")).not.toBeInTheDocument();
});
