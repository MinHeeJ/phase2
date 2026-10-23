import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { UploadButton } from "./UploadButton";
import {
  checkUploadFile,
  createUploadValidation,
  MAX_UPLOAD_BYTES,
} from "./client";
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failed = (code = "INVALID_INPUT", status = 400) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code,
      message: "검사 요청 실패",
      field: "file",
      requestId: "server-request",
    }),
    { status },
  );
const token = "12345678-1234-1234-1234-123456789012";
const accepted = {
  fileName: "거래처.xlsx",
  totalRows: 5,
  okRows: 5,
  errorRows: 0,
  warningRows: 1,
  token,
  items: [
    {
      severity: "WARN",
      rowNo: 7,
      column: "구분",
      rawValue: "협력업체",
      message: "변환했습니다.",
    },
  ],
};
const rejected = {
  fileName: "거래처.xlsx",
  totalRows: 6,
  okRows: 1,
  errorRows: 5,
  warningRows: 0,
  upldId: "9001",
  items: [
    {
      severity: "ERROR",
      rowNo: 8,
      column: "업종",
      rawValue: "핀테크",
      message: "대응을 등록해 주세요.",
      fix: { type: "MAPPING", group: "INDUSTRY" },
    },
    {
      severity: "ERROR",
      rowNo: 5,
      column: "사업자번호",
      rawValue: "bad",
      message: "사업자번호 오류",
    },
  ],
};
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function open() {
  fireEvent.click(screen.getByTestId("upload-open"));
}
function choose(file = new File(["file bytes"], "거래처.xlsx")) {
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [file] },
  });
}
async function upload(data: unknown) {
  open();
  choose();
  fetcher.mockResolvedValueOnce(response(data));
  fireEvent.click(screen.getByTestId("upload-submit"));
  await screen.findByTestId("upload-result-dialog");
}
describe("UI-15 upload work windows", () => {
  it("opens local file picker with full-rejection notice and cancellation never writes", () => {
    render(<UploadButton onApplied={vi.fn()} />);
    open();
    expect(screen.getByTestId("upload-select-dialog")).toHaveAttribute(
      "data-screen-id",
      "SCR-UI-15-M1",
    );
    expect(
      screen.getByText("오류가 하나라도 있으면 파일 전체를 반영하지 않습니다."),
    ).toBeInTheDocument();
    expect(screen.getByTestId("upload-submit")).toBeDisabled();
    fireEvent.click(screen.getByTestId("upload-cancel"));
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("rejects extension and oversized selection locally and accepts exact byte boundary", () => {
    render(<UploadButton onApplied={vi.fn()} />);
    open();
    choose(new File(["bad"], "검사.xls"));
    expect(screen.getByRole("alert")).toHaveTextContent("xlsx 파일만");
    expect(fetcher).not.toHaveBeenCalled();
    const big = new File([new Uint8Array(MAX_UPLOAD_BYTES + 1)], "검사.xlsx");
    choose(big);
    expect(screen.getByRole("alert")).toHaveTextContent("10MB");
    expect(
      checkUploadFile(
        new File([new Uint8Array(MAX_UPLOAD_BYTES)], "검사.xlsx"),
      ),
    ).toBeNull();
  });
  it("accepts drop and sends FormData without JSON Content-Type", async () => {
    render(<UploadButton onApplied={vi.fn()} />);
    open();
    fireEvent.drop(
      screen.getByText("파일을 끌어다 놓거나 파일을 선택해 주세요.")
        .parentElement!,
      { dataTransfer: { files: [new File(["xlsx"], "검사.xlsx")] } },
    );
    fetcher.mockResolvedValueOnce(response(accepted));
    fireEvent.click(screen.getByTestId("upload-submit"));
    await screen.findByTestId("upload-result-dialog");
    expect(fetcher.mock.calls[0][0]).toBe("/api/uploads/B.7/validate");
    expect(fetcher.mock.calls[0][1].body).toBeInstanceOf(FormData);
    expect(fetcher.mock.calls[0][1].headers).toBeUndefined();
  });
  it("shows pending state and blocks duplicate validation clicks", async () => {
    let finish!: (value: Response) => void;
    fetcher.mockReturnValueOnce(
      new Promise<Response>((resolve) => {
        finish = resolve;
      }),
    );
    render(<UploadButton onApplied={vi.fn()} />);
    open();
    choose();
    fireEvent.click(screen.getByTestId("upload-submit"));
    expect(screen.getByRole("status")).toHaveTextContent("검사 중");
    expect(screen.getByTestId("upload-submit")).toBeDisabled();
    fireEvent.click(screen.getByTestId("upload-submit"));
    expect(fetcher).toHaveBeenCalledTimes(1);
    finish(response(accepted));
    await screen.findByTestId("upload-result-dialog");
  });
  it.each([
    "UNSUPPORTED_FILE_TYPE",
    "HEADER_NOT_FOUND",
    "REQUIRED_COLUMN_MISSING",
    "EMPTY_FILE",
    "TOO_MANY_ROWS",
  ])("keeps %s file error inside picker with selection", async (code) => {
    fetcher.mockResolvedValueOnce(failed(code));
    render(<UploadButton onApplied={vi.fn()} />);
    open();
    choose();
    fireEvent.click(screen.getByTestId("upload-submit"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "검사 요청 실패",
    );
    expect(screen.getByText("선택 파일: 거래처.xlsx")).toBeInTheDocument();
    expect(
      screen.queryByTestId("upload-result-dialog"),
    ).not.toBeInTheDocument();
  });
  it("renders row-based summary, sorted errors and disables apply", async () => {
    render(<UploadButton onApplied={vi.fn()} />);
    await upload(rejected);
    expect(screen.getByText("성공 1")).toBeInTheDocument();
    expect(screen.getByTestId("upload-apply")).toBeDisabled();
    const rows = within(screen.getByTestId("upload-issues-panel")).getAllByRole(
      "row",
    );
    expect(rows[1]).toHaveTextContent("5사업자번호");
    expect(rows[2]).toHaveTextContent("8업종");
  });
  it("successful validation selects warnings and disables ledger-only actions", async () => {
    render(<UploadButton onApplied={vi.fn()} />);
    await upload(accepted);
    expect(screen.getByTestId("upload-warnings-tab")).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByTestId("upload-recheck")).toBeDisabled();
    expect(screen.getByTestId("upload-error-file")).toBeDisabled();
    expect(screen.getByTestId("upload-apply")).toBeEnabled();
  });
  it("empty warnings remain a successful result and errors tab says empty", async () => {
    render(<UploadButton onApplied={vi.fn()} />);
    await upload({ ...accepted, warningRows: 0, items: [] });
    expect(screen.getByText("경고가 없습니다.")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("upload-errors-tab"));
    expect(screen.getByText("오류가 없습니다.")).toBeInTheDocument();
  });
  it("successful apply closes result and refreshes owner exactly once", async () => {
    const applied = vi.fn();
    render(<UploadButton onApplied={applied} />);
    await upload(accepted);
    fetcher.mockResolvedValueOnce(response({ upldId: "9002", appliedRows: 5 }));
    fireEvent.click(screen.getByTestId("upload-apply"));
    expect(
      await screen.findByText("거래처 5건을 반영했습니다."),
    ).toBeInTheDocument();
    expect(applied).toHaveBeenCalledWith(5);
    expect(
      screen.queryByTestId("upload-result-dialog"),
    ).not.toBeInTheDocument();
    expect(fetcher.mock.calls[1][0]).toBe(
      `/api/uploads/validated/${token}/apply`,
    );
  });
  it.each([
    ["STALE_VALIDATION", 409],
    ["VALIDATION_EXPIRED", 410],
  ])("keeps results on %s and never reports success", async (code, status) => {
    const applied = vi.fn();
    render(<UploadButton onApplied={applied} />);
    await upload(accepted);
    fetcher.mockResolvedValueOnce(failed(String(code), Number(status)));
    fireEvent.click(screen.getByTestId("upload-apply"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "server-request",
    );
    expect(screen.getByTestId("upload-result-dialog")).toBeInTheDocument();
    expect(applied).not.toHaveBeenCalled();
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it("network uncertainty keeps result and disallows automatic or manual duplicate apply", async () => {
    render(<UploadButton onApplied={vi.fn()} />);
    await upload(accepted);
    fetcher.mockRejectedValueOnce(new TypeError("network"));
    fireEvent.click(screen.getByTestId("upload-apply"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "자동으로 다시 반영하지 않습니다.",
    );
    expect(screen.getByTestId("upload-apply")).toBeDisabled();
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it("rechecks the selected ledger and replaces result with token-only state", async () => {
    render(<UploadButton onApplied={vi.fn()} />);
    await upload(rejected);
    fetcher.mockResolvedValueOnce(response(accepted));
    fireEvent.click(screen.getByTestId("upload-recheck"));
    await waitFor(() =>
      expect(screen.getByTestId("upload-apply")).toBeEnabled(),
    );
    expect(fetcher.mock.calls[1][0]).toBe("/api/uploads/9001/recheck");
    expect(screen.getByTestId("upload-recheck")).toBeDisabled();
  });
  it("failed recheck preserves previous issues", async () => {
    render(<UploadButton onApplied={vi.fn()} />);
    await upload(rejected);
    fetcher.mockResolvedValueOnce(failed("NOT_FOUND", 404));
    fireEvent.click(screen.getByTestId("upload-recheck"));
    await screen.findByRole("alert");
    expect(screen.getByText("사업자번호 오류")).toBeInTheDocument();
  });
  it("reuses explicit-role mapping dialog and highlights recheck after save", async () => {
    render(<UploadButton onApplied={vi.fn()} />);
    await upload(rejected);
    fetcher.mockResolvedValueOnce(
      response([
        { code: "SI", name: "SI", attr1: null, attr2: null, attr3: null },
      ]),
    );
    fireEvent.click(screen.getByRole("button", { name: "대응 등록" }));
    expect(screen.getByLabelText("엑셀 표기")).toHaveValue("핀테크");
    expect(screen.getByLabelText("주/보조")).toHaveValue("");
    await screen.findByRole("option", { name: "SI" });
    fireEvent.change(screen.getByLabelText("코드"), {
      target: { value: "SI" },
    });
    fireEvent.change(screen.getByLabelText("주/보조"), {
      target: { value: "MAIN" },
    });
    fetcher.mockResolvedValueOnce(response(undefined));
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(
      await screen.findByText("대응을 저장했습니다. 다시 검사해 주세요."),
    ).toBeInTheDocument();
    expect(screen.getByTestId("upload-recheck").className).toContain("primary");
  });
  it("new-file action returns picker without applying", async () => {
    render(<UploadButton onApplied={vi.fn()} />);
    await upload(accepted);
    fireEvent.click(screen.getByTestId("upload-new-file"));
    expect(screen.getByTestId("upload-select-dialog")).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("multipart network failure keeps unknown write explicit", async () => {
    fetcher.mockRejectedValueOnce(new TypeError("network"));
    await expect(
      createUploadValidation("B.7", new File(["x"], "검사.xlsx")),
    ).rejects.toMatchObject({ uncertainWrite: true });
  });
});
