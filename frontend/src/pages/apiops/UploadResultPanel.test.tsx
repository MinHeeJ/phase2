import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { UploadResultPanel } from "./UploadResultPanel";
import { getUploadResult, type UploadResult } from "./client";

const summary: UploadResult = {
  upload_id: 41,
  form_type: "B.1",
  month: "2026-10",
  row_count: 5,
  error_count: 0,
  warning_count: 1,
  can_apply: true,
  existing_salary_count: 3,
  overwrite_notice:
    "2026-10 급여 3명분이 이미 있습니다. 반영하면 새 파일로 모두 바뀝니다.",
  issues_url: "/api/uploads/41/issues",
  normalized_rows_available: true,
};
const ok = (data: UploadResult) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetcher = vi.fn(async () => ok(summary));
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("upload inspection summary", () => {
  it("reads the selected numeric ID anonymously and displays salary overwrite and status colours", async () => {
    render(<UploadResultPanel uploadId={41} />);
    expect(screen.getByRole("status")).toHaveTextContent("검사 결과 조회 중");
    expect(
      await screen.findByRole("heading", {
        name: "엑셀 업로드 결과 · 급여 2026-10",
      }),
    ).toBeVisible();
    expect(screen.getByTestId("upload-overwrite-notice")).toHaveTextContent(
      summary.overwrite_notice!,
    );
    expect(screen.getByText("총 5행")).toBeVisible();
    for (const [name, colour, value] of [
      ["성공", "success", "5"],
      ["경고", "warning", "1"],
      ["오류", "danger", "0"],
    ]) {
      const tile = screen.getByText(name, { exact: false, selector: "div" });
      expect(tile.className).toContain(colour);
      expect(within(tile).getByText(value)).toBeVisible();
    }
    const [path, init] = fetcher.mock.calls[0];
    expect(path).toBe("/api/uploads/41");
    expect(init.method).toBeUndefined();
    expect(init.body).toBeUndefined();
    expect(init.headers.Authorization).toBeUndefined();
    expect(
      screen.getByText("검사는 업무 데이터 반영이 아닙니다."),
    ).toBeVisible();
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it("renders developer summary without a salary warning or a fabricated issue row", async () => {
    fetcher.mockResolvedValue(
      ok({
        ...summary,
        form_type: "B.8",
        month: null,
        existing_salary_count: 0,
        overwrite_notice: null,
      }),
    );
    render(<UploadResultPanel uploadId={41} />);
    await screen.findByRole("heading", { name: "엑셀 업로드 결과 · 개발자" });
    expect(
      screen.queryByTestId("upload-overwrite-notice"),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole("row")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "반영" }),
    ).not.toBeInTheDocument();
  });

  it("does not show overwrite when the selected salary month is empty", async () => {
    fetcher.mockResolvedValue(
      ok({ ...summary, existing_salary_count: 0, overwrite_notice: null }),
    );
    render(<UploadResultPanel uploadId={41} />);
    await screen.findByRole("heading");
    expect(
      screen.queryByTestId("upload-overwrite-notice"),
    ).not.toBeInTheDocument();
  });

  it("passes the server error lock to the common dialog without enabling an apply itself", async () => {
    fetcher.mockResolvedValue(
      ok({ ...summary, error_count: 2, can_apply: false }),
    );
    render(
      <UploadResultPanel uploadId={41}>
        {(result) => (
          <button data-testid="test-apply" disabled={!result.can_apply}>
            반영
          </button>
        )}
      </UploadResultPanel>,
    );
    expect(
      await screen.findByText(
        "오류가 하나라도 있으면 파일 전체를 반영하지 않습니다.",
      ),
    ).toBeVisible();
    expect(screen.getByTestId("test-apply")).toBeDisabled();
    expect(
      screen.getByText("오류가 하나라도 있으면 파일 전체를 반영하지 않습니다.")
        .className,
    ).toContain("dangerBanner");
  });

  it("keeps a revalidated or applied context read-only even without errors", async () => {
    fetcher.mockResolvedValue(ok({ ...summary, can_apply: false }));
    render(<UploadResultPanel uploadId={41} />);
    expect(
      await screen.findByText(
        "현재 검사 결과는 반영할 수 없습니다. 다시 검사하거나 파일을 다시 올려 주세요.",
      ),
    ).toBeVisible();
  });

  it("shows a genuine empty summary without inventing rows", async () => {
    fetcher.mockResolvedValue(
      ok({ ...summary, row_count: 0, warning_count: 0, can_apply: false }),
    );
    render(<UploadResultPanel uploadId={41} />);
    expect(await screen.findByText("검사한 데이터가 없습니다.")).toBeVisible();
    expect(screen.queryByRole("row")).not.toBeInTheDocument();
  });

  it("shows expiry guidance and request ID without exposing stale actions", async () => {
    fetcher.mockResolvedValue(
      new Response(
        JSON.stringify({
          success: false,
          code: "VALIDATION_EXPIRED",
          message: "검사 결과가 만료되었습니다. 파일을 다시 올려 주세요.",
          field: "uploadId",
          requestId: "inspection-query",
        }),
        { status: 410 },
      ),
    );
    render(
      <UploadResultPanel uploadId={41}>
        {() => <button>반영</button>}
      </UploadResultPanel>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "검사 결과가 만료되었습니다. 파일을 다시 올려 주세요.",
    );
    expect(screen.getByText("문의 번호 inspection-query")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "반영" }),
    ).not.toBeInTheDocument();
  });

  it("retries only the failed GET and recovers the same selected context", async () => {
    fetcher.mockRejectedValueOnce(new Error("offline"));
    render(<UploadResultPanel uploadId={41} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "서버에 연결할 수 없습니다.",
    );
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByRole("heading");
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls.map((call) => call[0])).toEqual([
      "/api/uploads/41",
      "/api/uploads/41",
    ]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("does not display the preceding result while switching the selected ID", async () => {
    const { rerender } = render(<UploadResultPanel uploadId={41} />);
    await screen.findByRole("heading");
    let finish!: (response: Response) => void;
    fetcher.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    rerender(<UploadResultPanel uploadId={42} />);
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toBeVisible();
    finish(
      ok({
        ...summary,
        upload_id: 42,
        month: "2026-11",
        existing_salary_count: 0,
        overwrite_notice: null,
      }),
    );
    expect(await screen.findByRole("heading")).toHaveTextContent("2026-11");
    expect(fetcher.mock.calls[1][0]).toBe("/api/uploads/42");
  });

  it("ignores a late response after selection changed", async () => {
    let old!: (response: Response) => void;
    fetcher.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          old = resolve;
        }),
    );
    const { rerender } = render(<UploadResultPanel uploadId={41} />);
    rerender(<UploadResultPanel uploadId={42} />);
    await screen.findByRole("heading");
    old(ok({ ...summary, month: "2025-01" }));
    await waitFor(() =>
      expect(screen.getByRole("heading")).not.toHaveTextContent("2025-01"),
    );
    expect(fetcher.mock.calls[0][1].signal.aborted).toBe(true);
  });

  it.each([0, -1, 1.5, Number.NaN, Number.MAX_SAFE_INTEGER + 1])(
    "refuses invalid ID %s before network",
    async (id) => {
      await expect(getUploadResult(id)).rejects.toMatchObject({
        field: "uploadId",
        status: 400,
      });
      expect(fetcher).not.toHaveBeenCalled();
    },
  );
});
