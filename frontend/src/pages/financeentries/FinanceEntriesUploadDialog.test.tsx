import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FinanceEntriesUploadDialog } from "./FinanceEntriesUploadDialog";
const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let rejected: boolean, empty: boolean, conflict: boolean, warning: boolean;
const applied = vi.fn(),
  close = vi.fn();
const result = () => ({
  upload_id: "9007199254740993",
  error_count: rejected ? 1 : 0,
  warning_count: warning ? 1 : 0,
  row_count: empty ? 0 : 1,
  can_apply: !rejected && !empty,
  applied_count: 0,
  overwrite_count: 3,
  overwrite_notice:
    "해당 연월의 저장된 항목 3건(수기 포함) 전체를 교체합니다. 저장 전 초안과 변경 이력은 유지됩니다.",
  items: rejected
    ? [
        {
          field: "금액",
          row: 5,
          severity: "ERROR",
          message: "0 이상의 원 단위 정수를 입력하세요.",
        },
      ]
    : warning
      ? [
          {
            field: "구분",
            row: 5,
            severity: "WARNING",
            message: "등록된 코드 대응으로 읽었습니다.",
          },
        ]
      : [],
  rows: empty
    ? []
    : [
        {
          row: 5,
          severity: rejected ? "ERROR" : warning ? "WARNING" : "NORMAL",
          entry: rejected
            ? null
            : {
                entry_ym: "2026-10",
                fin_se_cd: "SGA",
                item_nm: "임대료",
                entry_amt: 1000000,
                vat_amt: 0,
                inout_dt: null,
                rpt_yn: "Y",
                remark: null,
              },
        },
      ],
});
beforeEach(() => {
  rejected = false;
  empty = false;
  conflict = false;
  warning = false;
  applied.mockReset();
  close.mockReset();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/codes/FIN_CATEGORY"))
      return ok([{ code: "SGA", name: "판관비" }]);
    if (url.endsWith("/apply")) {
      if (conflict)
        return new Response(
          JSON.stringify({
            success: false,
            code: "CLOSED_MONTH",
            message: "마감된 달의 항목을 교체할 수 없습니다.",
            field: "entry_ym",
          }),
          { status: 409 },
        );
      const confirm = JSON.parse(String(init?.body)).confirm;
      return ok({
        ...result(),
        applied_count: confirm ? 1 : 0,
        can_apply: !confirm,
      });
    }
    return ok(result());
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = (props = {}) =>
  render(
    <FinanceEntriesUploadDialog
      onApplied={applied}
      onClose={close}
      {...props}
    />,
  );
const uploads = () =>
  fetcher.mock.calls.filter((c) => String(c[0]).startsWith("/api/uploads/"));
async function validate() {
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [new File(["xlsx"], "재무.xlsx")] },
  });
  fireEvent.click(screen.getByTestId("finance-entries-validate"));
  await screen.findByText("엑셀 업로드 결과 · 재무 항목");
}
describe("B.2 재무 항목 업로드", () => {
  it("posts only file and preserves long ID and requires explicit replacement consent", async () => {
    mount();
    expect(screen.getByTestId("finance-entries-validate")).toBeDisabled();
    await validate();
    expect(
      Array.from(((uploads()[0][1] as RequestInit).body as FormData).keys()),
    ).toEqual(["file"]);
    expect(uploads()[0][0]).toBe("/api/uploads/finance-entries/validate");
    expect(screen.getByTestId("finance-entries-row-5")).toHaveTextContent(
      "2026-10",
    );
    expect(screen.getByTestId("finance-entries-apply")).toBeDisabled();
    fireEvent.click(screen.getByLabelText("해당 연월 전체 교체에 동의합니다"));
    fireEvent.click(screen.getByTestId("finance-entries-apply"));
    await waitFor(() => expect(applied).toHaveBeenCalledOnce());
    expect(JSON.parse(String((uploads()[1][1] as RequestInit).body))).toEqual({
      upload_id: "9007199254740993",
      confirm: true,
    });
    expect(screen.getByTestId("finance-entries-apply")).toBeDisabled();
    expect(screen.getByTestId("finance-entries-success")).toHaveTextContent(
      "1건을 반영했습니다.",
    );
  });
  it("preview sends confirm false without refreshing business rows", async () => {
    mount();
    await validate();
    fireEvent.click(screen.getByTestId("finance-entries-preview"));
    await waitFor(() => expect(uploads()).toHaveLength(2));
    expect(
      JSON.parse(String((uploads()[1][1] as RequestInit).body)).confirm,
    ).toBe(false);
    expect(applied).not.toHaveBeenCalled();
  });
  it("errors show row column reason and prohibit apply", async () => {
    rejected = true;
    mount();
    await validate();
    expect(screen.getByTestId("finance-entries-issue-0")).toHaveTextContent(
      "5금액–0 이상의 원 단위 정수를 입력하세요.",
    );
    expect(screen.getByTestId("finance-entries-apply")).toBeDisabled();
  });
  it("mapped category warning is amber and still permits confirmation", async () => {
    warning = true;
    mount();
    await validate();
    fireEvent.click(screen.getByTestId("finance-entries-tab-warning"));
    expect(screen.getByTestId("finance-entries-row-5")).toHaveTextContent(
      "경고",
    );
    expect(screen.getByTestId("finance-entries-issue-0")).toHaveTextContent(
      "등록된 코드 대응",
    );
    expect(screen.getByTestId("finance-entries-consent")).not.toBeDisabled();
  });
  it("read-only hides writes but allows template", () => {
    mount({ readOnly: true });
    expect(screen.queryByLabelText("파일 선택")).not.toBeInTheDocument();
    expect(screen.getByTestId("finance-entries-template")).toBeVisible();
  });
  it("failed validation retains file and permits retry", async () => {
    fetcher.mockRejectedValue(new Error("offline"));
    mount();
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["xlsx"], "재무.xlsx")] },
    });
    fireEvent.click(screen.getByTestId("finance-entries-validate"));
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(screen.getByText("재무.xlsx")).toBeVisible();
    expect(applied).not.toHaveBeenCalled();
  });
  it("late close disables apply while preserving review", async () => {
    mount();
    await validate();
    conflict = true;
    fireEvent.click(screen.getByLabelText("해당 연월 전체 교체에 동의합니다"));
    fireEvent.click(screen.getByTestId("finance-entries-apply"));
    await screen.findByText("마감된 달의 항목을 교체할 수 없습니다.");
    expect(screen.getByTestId("finance-entries-apply")).toBeDisabled();
    expect(applied).not.toHaveBeenCalled();
  });
  it("empty review has explicit empty state", async () => {
    empty = true;
    mount();
    await validate();
    expect(screen.getByText("해당 행이 없습니다.")).toBeVisible();
    expect(screen.getByTestId("finance-entries-apply")).toBeDisabled();
  });
  it("invalid extension fails locally", () => {
    mount();
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["csv"], "재무.csv")] },
    });
    expect(uploads()).toHaveLength(0);
    expect(screen.getByTestId("finance-entries-validate")).toBeDisabled();
  });
  it("changing file clears old review and consent", async () => {
    mount();
    await validate();
    fireEvent.click(screen.getByLabelText("해당 연월 전체 교체에 동의합니다"));
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["xlsx"], "다른재무.xlsx")] },
    });
    expect(
      screen.queryByTestId("finance-entries-apply"),
    ).not.toBeInTheDocument();
  });
  it("close never applies", () => {
    mount();
    fireEvent.click(screen.getByTestId("finance-entries-close"));
    expect(close).toHaveBeenCalledOnce();
    expect(uploads()).toHaveLength(0);
  });
});
