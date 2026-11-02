import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PriorProfitUploadDialog } from "./PriorProfitUploadDialog";
const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let rejected: boolean, empty: boolean, conflict: boolean;
const applied = vi.fn(),
  close = vi.fn();
const result = () => ({
  upload_id: "9007199254740993",
  error_count: rejected ? 1 : 0,
  row_count: empty ? 0 : 1,
  can_apply: !rejected && !empty,
  applied_count: 0,
  overwrite_notice: "2025년 IMPORT 손익과 판관비 상세 전체를 교체합니다.",
  items: rejected
    ? [
        {
          field: "연월",
          row: 5,
          severity: "ERROR",
          message: "2025년 CLOSE가 있어 연도 전체를 이관할 수 없습니다.",
        },
      ]
    : [],
  details: [],
  rows: empty
    ? []
    : [
        {
          row: 5,
          severity: rejected ? "ERROR" : "NORMAL",
          profit: rejected
            ? null
            : {
                month: "2025-01",
                revenue: 1000000,
                allocatedCost: 300000,
                idleCost: 0,
                directExpense: 0,
                sgaInput: 100,
                indirectLabor: 0,
                researchCost: 0,
                adjustment: -100,
                nonOperatingIncome: 0,
                nonOperatingExpense: 0,
                corporateTax: 0,
                allocatedRevenue: null,
              },
        },
      ],
});
beforeEach(() => {
  rejected = false;
  empty = false;
  conflict = false;
  applied.mockReset();
  close.mockReset();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/apply")) {
      if (conflict)
        return new Response(
          JSON.stringify({
            success: false,
            code: "CLOSED_MONTH",
            message: "2025년 CLOSE가 있어 연도 전체를 이관할 수 없습니다.",
            field: "year",
          }),
          { status: 409 },
        );
      const confirm = JSON.parse(String(init?.body)).confirm;
      return ok({
        ...result(),
        applied_count: confirm ? 12 : 0,
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
    <PriorProfitUploadDialog onApplied={applied} onClose={close} {...props} />,
  );
async function validate() {
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [new File(["xlsx"], "손익.xlsx")] },
  });
  fireEvent.click(screen.getByTestId("prior-profit-validate"));
  await screen.findByText("엑셀 업로드 결과 · 과거 손익");
}
describe("B.11 prior profit upload", () => {
  it("posts only file and requires confirmation before replacing 2025 IMPORT", async () => {
    mount();
    expect(screen.getByTestId("prior-profit-validate")).toBeDisabled();
    await validate();
    const body = (fetcher.mock.calls[0][1] as RequestInit).body as FormData;
    expect(Array.from(body.keys())).toEqual(["file"]);
    expect(fetcher.mock.calls[0][0]).toBe("/api/uploads/prior-profit/validate");
    expect(screen.getByTestId("prior-profit-row-5")).toHaveTextContent(
      "2025-01",
    );
    expect(screen.getByTestId("prior-profit-apply")).toBeDisabled();
    fireEvent.click(
      screen.getByLabelText("2025년 IMPORT 전체 교체에 동의합니다"),
    );
    fireEvent.click(screen.getByTestId("prior-profit-apply"));
    await waitFor(() => expect(applied).toHaveBeenCalledOnce());
    expect(
      JSON.parse(String((fetcher.mock.calls[1][1] as RequestInit).body)),
    ).toEqual({ upload_id: "9007199254740993", confirm: true });
    expect(screen.getByTestId("prior-profit-apply")).toBeDisabled();
    expect(screen.getByTestId("prior-profit-success")).toHaveTextContent(
      "12개월 손익을 반영했습니다.",
    );
  });
  it("preview is confirm false and never refreshes business data", async () => {
    mount();
    await validate();
    fireEvent.click(screen.getByTestId("prior-profit-preview"));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(
      JSON.parse(String((fetcher.mock.calls[1][1] as RequestInit).body))
        .confirm,
    ).toBe(false);
    expect(applied).not.toHaveBeenCalled();
  });
  it("errors show column reason and disable entire apply", async () => {
    rejected = true;
    mount();
    await validate();
    expect(screen.getByTestId("prior-profit-issue-0")).toHaveTextContent(
      "연월",
    );
    expect(screen.getByTestId("prior-profit-issue-0")).toHaveTextContent(
      "CLOSE",
    );
    expect(screen.getByTestId("prior-profit-apply")).toBeDisabled();
  });
  it("closed-year read-only hides writes and keeps template", () => {
    mount({ readOnly: true });
    expect(screen.queryByLabelText("파일 선택")).not.toBeInTheDocument();
    expect(screen.getByTestId("prior-profit-template")).toBeVisible();
  });
  it("failed validation preserves file and enables retry", async () => {
    fetcher.mockRejectedValue(new Error("offline"));
    mount();
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["xlsx"], "손익.xlsx")] },
    });
    fireEvent.click(screen.getByTestId("prior-profit-validate"));
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(screen.getByText("손익.xlsx")).toBeVisible();
    expect(applied).not.toHaveBeenCalled();
  });
  it("late CLOSE disables confirmation until reinspection", async () => {
    mount();
    await validate();
    conflict = true;
    fireEvent.click(
      screen.getByLabelText("2025년 IMPORT 전체 교체에 동의합니다"),
    );
    fireEvent.click(screen.getByTestId("prior-profit-apply"));
    await screen.findByText(
      "2025년 CLOSE가 있어 연도 전체를 이관할 수 없습니다.",
    );
    expect(screen.getByTestId("prior-profit-apply")).toBeDisabled();
    expect(applied).not.toHaveBeenCalled();
  });
  it("empty result has an explicit empty state and cannot apply", async () => {
    empty = true;
    mount();
    await validate();
    expect(screen.getByText("해당 행이 없습니다.")).toBeVisible();
    expect(screen.getByTestId("prior-profit-apply")).toBeDisabled();
  });
  it("invalid extension is rejected before request", () => {
    mount();
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["text"], "손익.csv")] },
    });
    expect(fetcher).not.toHaveBeenCalled();
    expect(screen.getByTestId("prior-profit-validate")).toBeDisabled();
  });
  it("changing file clears old review and consent", async () => {
    mount();
    await validate();
    fireEvent.click(
      screen.getByLabelText("2025년 IMPORT 전체 교체에 동의합니다"),
    );
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["new"], "새손익.xlsx")] },
    });
    expect(screen.queryByTestId("prior-profit-apply")).not.toBeInTheDocument();
  });
  it("close never applies", () => {
    mount();
    fireEvent.click(screen.getByTestId("prior-profit-close"));
    expect(close).toHaveBeenCalledOnce();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("loading prevents duplicate inspection", async () => {
    let resolve!: (r: Response) => void;
    fetcher.mockImplementation(
      () =>
        new Promise<Response>((r) => {
          resolve = r;
        }),
    );
    mount();
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["xlsx"], "손익.xlsx")] },
    });
    fireEvent.click(screen.getByTestId("prior-profit-validate"));
    expect(screen.getByTestId("prior-profit-validate")).toBeDisabled();
    expect(screen.getByText("처리 중")).toBeVisible();
    resolve(ok(result()));
    await screen.findByText("엑셀 업로드 결과 · 과거 손익");
  });
});
