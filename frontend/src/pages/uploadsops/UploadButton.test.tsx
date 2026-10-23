import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { UploadButton, UploadMappingDialog } from "./UploadButton";
import {
  createUploadValidation,
  getUploadTemplate,
  uploadFileError,
  type UploadValidation,
  type UploadIssue,
} from "./client";
import styles from "./Uploads.module.css";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failed = (code: string, message: string, status = 400) =>
  new Response(
    JSON.stringify({ success: false, code, message, field: "file" }),
    { status, headers: { "Content-Type": "application/json" } },
  );
const issue: UploadIssue = {
  severity: "ERROR",
  rowNo: 5,
  column: "업종",
  rawValue: "핀테크",
  message: "등록된 업종이 아닙니다.",
  fix: { type: "MAPPING", group: "INDUSTRY" },
};
const rejected: UploadValidation = {
  fileName: "거래처.xlsx",
  totalRows: 1,
  okRows: 0,
  errorRows: 1,
  warningRows: 0,
  upldId: "7",
  items: [issue],
};
const valid: UploadValidation = {
  fileName: "거래처.xlsx",
  totalRows: 5,
  okRows: 2,
  errorRows: 0,
  warningRows: 3,
  token: "ef4564ab-6344-4d32-bd7b-b6f0ea9e5bd6",
  items: [
    { ...issue, severity: "WARN", message: "바꿔 읽었습니다.", fix: null },
  ],
};
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  vi.stubGlobal(
    "URL",
    Object.assign(URL, {
      createObjectURL: vi.fn(() => "blob:excel"),
      revokeObjectURL: vi.fn(),
    }),
  );
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
async function openAndUpload(
  result: UploadValidation = rejected,
  applied = vi.fn(),
) {
  fetcher.mockResolvedValueOnce(response(result));
  render(<UploadButton form="B.7" onApplied={applied} />);
  fireEvent.click(screen.getByRole("button", { name: "엑셀 업로드" }));
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [new File(["xlsx test input"], "거래처.xlsx")] },
  });
  fireEvent.click(screen.getByRole("button", { name: "올리기" }));
  await screen.findByRole("dialog", { name: "엑셀 업로드 결과 · 거래처" });
  return applied;
}
describe("거래처 엑셀 업로드", () => {
  it("uses the desktop-only action class without blocking the host mobile read screen", () => {
    render(<UploadButton onApplied={vi.fn()} />);
    expect(screen.getByTestId("partner-upload-controls")).toHaveClass(
      styles.controls,
    );
  });
  it("cancel file dialog sends no request", () => {
    render(<UploadButton onApplied={vi.fn()} />);
    fireEvent.click(screen.getByText("엑셀 업로드"));
    expect(
      screen.getByText("오류가 하나라도 있으면 파일 전체를 반영하지 않습니다."),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByText("취소"));
    expect(fetcher).not.toHaveBeenCalled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it.each(["a.xls", "a.txt"])("rejects %s locally", (name) => {
    render(<UploadButton onApplied={vi.fn()} />);
    fireEvent.click(screen.getByText("엑셀 업로드"));
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["data"], name)] },
    });
    expect(screen.getByRole("alert")).toHaveTextContent("xlsx 파일만");
    expect(screen.getByText("올리기")).toBeDisabled();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("rejects oversized files and accepts exact ten MiB locally", () => {
    expect(
      uploadFileError(new File([new Uint8Array(10 * 1024 * 1024)], "a.xlsx")),
    ).toBeNull();
    expect(
      uploadFileError(
        new File([new Uint8Array(10 * 1024 * 1024 + 1)], "a.xlsx"),
      ),
    ).toContain("10MB");
  });
  it("drag and drop uses the selected actual filename", () => {
    render(<UploadButton onApplied={vi.fn()} />);
    fireEvent.click(screen.getByText("엑셀 업로드"));
    fireEvent.drop(
      screen.getByText("파일을 끌어다 놓거나 선택해 주세요.").parentElement!,
      { dataTransfer: { files: [new File(["xlsx"], "내 파일.xlsx")] } },
    );
    expect(screen.getByText("선택 파일: 내 파일.xlsx")).toBeInTheDocument();
    expect(screen.getByText("올리기")).toBeEnabled();
  });
  it("sends multipart without a JSON content type", async () => {
    fetcher.mockResolvedValue(response(valid));
    await createUploadValidation("B.7", new File(["xlsx"], "a.xlsx"));
    const [path, init] = fetcher.mock.calls[0];
    expect(path).toBe("/api/uploads/B.7/validate");
    expect(init.body).toBeInstanceOf(FormData);
    expect(init.headers).toBeUndefined();
  });
  it.each([
    "UNSUPPORTED_FILE_TYPE",
    "HEADER_NOT_FOUND",
    "REQUIRED_COLUMN_MISSING",
    "EMPTY_FILE",
    "TOO_MANY_ROWS",
    "PAYLOAD_TOO_LARGE",
  ])("keeps %s in the upload dialog rather than the result", async (code) => {
    fetcher.mockResolvedValue(
      failed(
        code,
        "파일을 확인해 주세요.",
        code === "PAYLOAD_TOO_LARGE" ? 413 : 400,
      ),
    );
    render(<UploadButton onApplied={vi.fn()} />);
    fireEvent.click(screen.getByText("엑셀 업로드"));
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["xlsx"], "a.xlsx")] },
    });
    fireEvent.click(screen.getByText("올리기"));
    expect(await screen.findByRole("alert")).toHaveTextContent("파일을 확인");
    expect(
      screen.getByRole("dialog", { name: "엑셀 업로드 · 거래처" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("엑셀 업로드 결과 · 거래처"),
    ).not.toBeInTheDocument();
  });
  it("row errors render summary, mapping action and prevent apply", async () => {
    await openAndUpload();
    expect(screen.getByText("오류 1", { exact: true })).toBeInTheDocument();
    expect(screen.getByText("반영", { exact: true })).toBeDisabled();
    expect(screen.getByText("대응 등록")).toBeEnabled();
    expect(screen.getByRole("tab", { name: "오류 (1)" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });
  it("orders actual row numbers and keeps all messages", async () => {
    await openAndUpload({
      ...rejected,
      totalRows: 2,
      errorRows: 2,
      items: [
        { ...issue, rowNo: 9 },
        { ...issue, rowNo: 5 },
      ],
    });
    const rows = within(screen.getByTestId("upload-issues-panel")).getAllByRole(
      "row",
    );
    expect(rows[1]).toHaveTextContent("5");
    expect(rows[2]).toHaveTextContent("9");
  });
  it("token-only results select warnings and disable absent-ledger actions", async () => {
    await openAndUpload(valid);
    expect(screen.getByRole("tab", { name: "경고 (3)" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByText("다시 검사", { exact: true })).toBeDisabled();
    expect(screen.getByText("오류 목록 내려받기")).toBeDisabled();
    expect(screen.getByText("반영", { exact: true })).toBeEnabled();
    expect(screen.queryByText("대응 등록")).not.toBeInTheDocument();
    expect(
      screen.getByText(/정상 검사에는 업로드 원장이 없습니다/),
    ).toBeInTheDocument();
  });
  it("empty issue tab renders an empty state", async () => {
    await openAndUpload({ ...valid, items: [] });
    expect(screen.getByText("검사 항목이 없습니다.")).toBeInTheDocument();
  });
  it("rechecks the stored id and redraws into token-only warning state", async () => {
    await openAndUpload();
    fetcher.mockResolvedValueOnce(response(valid));
    fireEvent.click(screen.getByText("다시 검사", { exact: true }));
    await waitFor(() =>
      expect(screen.getByText("반영", { exact: true })).toBeEnabled(),
    );
    expect(fetcher.mock.calls[1][0]).toBe("/api/uploads/7/recheck");
    expect(fetcher.mock.calls[1][1].body).toBeUndefined();
  });
  it("failed recheck preserves old result", async () => {
    await openAndUpload();
    fetcher.mockResolvedValueOnce(
      failed("NOT_FOUND", "파일을 다시 올려 주세요.", 404),
    );
    fireEvent.click(screen.getByText("다시 검사", { exact: true }));
    expect(await screen.findByRole("alert")).toHaveTextContent("다시 올려");
    expect(screen.getByText("등록된 업종이 아닙니다.")).toBeInTheDocument();
  });
  it.each([
    [409, "STALE_VALIDATION", "다시 검사해 주세요."],
    [410, "VALIDATION_EXPIRED", "검사 후 30분이 지났습니다. 다시 올려 주세요."],
  ])(
    "preserves result on apply %i without automatic POST retry",
    async (status, code, message) => {
      await openAndUpload(valid);
      fetcher.mockResolvedValueOnce(
        failed(String(code), String(message), Number(status)),
      );
      fireEvent.click(screen.getByText("반영", { exact: true }));
      expect(await screen.findByRole("alert")).toHaveTextContent(
        String(message),
      );
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      expect(fetcher).toHaveBeenCalledTimes(2);
    },
  );
  it("shows saving, then closes and requests list refresh once", async () => {
    const applied = await openAndUpload(valid);
    let finish: (value: Response) => void = () => undefined;
    fetcher.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    fireEvent.click(screen.getByText("반영", { exact: true }));
    expect(await screen.findByText("저장 중")).toBeInTheDocument();
    expect(screen.getByText("반영", { exact: true })).toBeDisabled();
    finish(response({ upldId: "8", appliedRows: 5 }));
    await waitFor(() => expect(applied).toHaveBeenCalledWith(5));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "거래처 5건을 반영했습니다.",
    );
  });
  it("template is a binary download using the UTF-8 filename", async () => {
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => undefined);
    fetcher.mockResolvedValue(
      new Response("PK", {
        status: 200,
        headers: {
          "Content-Type":
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent("거래처_업로드양식.xlsx")}`,
        },
      }),
    );
    await getUploadTemplate("B.7");
    expect(click).toHaveBeenCalledOnce();
    expect(fetcher.mock.calls[0][0]).toBe("/api/uploads/templates/B.7");
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:excel");
  });
  it("download failure is visible, not a success", async () => {
    fetcher.mockResolvedValueOnce(failed("NOT_FOUND", "양식이 없습니다.", 404));
    render(<UploadButton onApplied={vi.fn()} />);
    fireEvent.click(screen.getByText("양식 내려받기"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "양식이 없습니다.",
    );
  });
  it("error file uses the selected rejected ledger id", async () => {
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(
      () => undefined,
    );
    await openAndUpload();
    fetcher.mockResolvedValueOnce(
      new Response("PK", {
        headers: {
          "Content-Type":
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        },
      }),
    );
    fireEvent.click(screen.getByText("오류 목록 내려받기"));
    await waitFor(() =>
      expect(fetcher.mock.calls[1][0]).toBe("/api/uploads/7/error-file"),
    );
  });
  it("mapping requires an explicit role, uses same-group options and prompts recheck", async () => {
    await openAndUpload();
    fetcher.mockResolvedValueOnce(
      response([
        { code: "SI", name: "SI", attr1: null, attr2: null, attr3: null },
      ]),
    );
    fireEvent.click(screen.getByText("대응 등록"));
    expect(
      await screen.findByRole("option", { name: "SI" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("그룹")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("엑셀 표기")).toHaveValue("핀테크");
    expect(screen.getByLabelText("주/보조 *")).toHaveValue("");
    fireEvent.change(screen.getByLabelText("코드 *"), {
      target: { value: "SI" },
    });
    expect(screen.getByText("저장", { exact: true })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("주/보조 *"), {
      target: { value: "SUB" },
    });
    fetcher.mockResolvedValueOnce(response(null));
    fireEvent.click(screen.getByText("저장", { exact: true }));
    expect(
      await screen.findByText("대응을 등록했습니다. 다시 검사해 주세요."),
    ).toBeInTheDocument();
    const request = fetcher.mock.calls.find(
      ([path]) => path === "/api/admin/excel-maps",
    );
    expect(JSON.parse(request![1].body)).toEqual({
      excel_lbl_nm: "핀테크",
      trns_cd_grp_id: "INDUSTRY",
      trns_cd: "SI",
      map_role_cd: "SUB",
      rmrk_desc: "",
    });
    expect(fetcher.mock.calls[1][0]).toBe("/api/codes/INDUSTRY");
  });
  it("mapping empty code list prevents saving", async () => {
    fetcher.mockResolvedValue(response([]));
    render(
      <UploadMappingDialog
        issue={issue}
        onCancel={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    expect(
      await screen.findByText("사용 중인 코드가 없습니다."),
    ).toBeInTheDocument();
    expect(screen.getByText("저장", { exact: true })).toBeDisabled();
  });
  it("mapping code request failure allows read-only retry", async () => {
    fetcher.mockResolvedValueOnce(
      failed("SERVER_ERROR", "코드를 읽을 수 없습니다.", 500),
    );
    render(
      <UploadMappingDialog
        issue={issue}
        onCancel={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "코드를 읽을 수 없습니다.",
    );
    fetcher.mockResolvedValueOnce(response([]));
    fireEvent.click(screen.getByText("다시 조회"));
    expect(
      await screen.findByText("사용 중인 코드가 없습니다."),
    ).toBeInTheDocument();
  });
});
