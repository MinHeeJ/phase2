import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { UploadDialogs } from "./UploadDialogs";
import UI15Screen from "../partners/UI15Screen";
import type { UploadValidationResult } from "./client";
const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const result: UploadValidationResult = {
  fileName: "거래처.xlsx",
  upldId: "7",
  totalRows: 6,
  okRows: 1,
  errorRows: 5,
  warningRows: 0,
  items: [
    {
      severity: "ERROR",
      rowNo: 8,
      column: "업종",
      rawValue: "핀테크",
      message: "등록된 업종이 아닙니다.",
      fix: { type: "MAPPING", group: "INDUSTRY" },
    },
    {
      severity: "ERROR",
      rowNo: 5,
      column: "상호",
      rawValue: "",
      message: "상호가 비어 있습니다.",
    },
  ],
};
const clean: UploadValidationResult = {
  fileName: "거래처.xlsx",
  token: "a539614e-9b53-4d19-9db8-c58d9153eaa7",
  totalRows: 5,
  okRows: 5,
  errorRows: 0,
  warningRows: 1,
  items: [
    {
      severity: "WARN",
      rowNo: 5,
      column: "업종",
      rawValue: "핀테크",
      message: "'핀테크'를 'SI'(으)로 바꿔 읽었습니다.",
    },
  ],
};
let fetcher: ReturnType<typeof vi.fn>;
let validation: UploadValidationResult;
const closed = vi.fn(),
  applied = vi.fn();
beforeEach(() => {
  validation = result;
  closed.mockReset();
  applied.mockReset();
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/validate")) return ok(validation);
    if (url.endsWith("/recheck")) return ok(clean);
    if (url.endsWith("/apply")) return ok({ upldId: "9", appliedRows: 5 });
    if (url === "/api/admin/excel-maps") return ok(undefined);
    if (url.startsWith("/api/codes/")) return ok([{ code: "SI", name: "SI" }]);
    if (url.startsWith("/api/partners?"))
      return ok({ items: [], total: 0, page: 1, size: 50 });
    if (url.includes("/templates/") || url.endsWith("/error-file"))
      return new Response("xlsx", { status: 200 });
    throw new Error(`Unexpected test request ${url} ${init?.method}`);
  });
  vi.stubGlobal("fetch", fetcher);
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: vi.fn(() => "blob:test"),
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    configurable: true,
    value: vi.fn(),
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = () =>
  render(<UploadDialogs onClose={closed} onApplied={applied} />);
const choose = (file = new File(["xlsx"], "거래처.xlsx")) =>
  fireEvent.change(screen.getByLabelText("파일 선택"), {
    target: { files: [file] },
  });
async function inspect() {
  choose();
  fireEvent.click(screen.getByTestId("upload-submit"));
  await screen.findByTestId("upload-result");
}

describe("UI-15 uploads", () => {
  it("opens selection from the real partner screen", async () => {
    render(<UI15Screen />);
    fireEvent.click(screen.getByTestId("partner-upload"));
    expect(
      screen.getByRole("dialog", { name: "엑셀 업로드 · 거래처" }),
    ).toBeVisible();
    expect(
      screen.getByText("오류가 하나라도 있으면 파일 전체를 반영하지 않습니다."),
    ).toBeVisible();
    expect(screen.getByTestId("upload-submit")).toBeDisabled();
  });
  it("cancel never writes", () => {
    mount();
    fireEvent.click(screen.getByTestId("upload-cancel"));
    expect(closed).toHaveBeenCalledOnce();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("rejects non-xlsx before network", () => {
    mount();
    choose(new File(["data"], "거래처.xls"));
    expect(screen.getByText("xlsx 파일만 올릴 수 있습니다.")).toBeVisible();
    expect(screen.getByTestId("upload-submit")).toBeDisabled();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("rejects over ten binary megabytes before network", () => {
    mount();
    choose(new File([new Uint8Array(10 * 1024 * 1024 + 1)], "big.xlsx"));
    expect(
      screen.getByText("파일은 10MB 이하만 올릴 수 있습니다."),
    ).toBeVisible();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("supports drag and drop and multipart without JSON headers", async () => {
    mount();
    fireEvent.drop(screen.getByTestId("upload-dropzone"), {
      dataTransfer: { files: [new File(["xlsx"], "끌기.xlsx")] },
    });
    expect(screen.getByText("끌기.xlsx")).toBeVisible();
    fireEvent.click(screen.getByTestId("upload-submit"));
    await screen.findByTestId("upload-result");
    const call = fetcher.mock.calls.find((c) => c[0].endsWith("/validate"))!;
    expect(call[1].body).toBeInstanceOf(FormData);
    expect(call[1].headers).toBeUndefined();
  });
  it.each([
    "UNSUPPORTED_FILE_TYPE",
    "HEADER_NOT_FOUND",
    "REQUIRED_COLUMN_MISSING",
    "EMPTY_FILE",
    "TOO_MANY_ROWS",
  ])("file error %s stays inside selection", async (code) => {
    fetcher.mockResolvedValue(
      new Response(
        JSON.stringify({
          success: false,
          code,
          message: "파일 단위 오류",
          field: "file",
          requestId: "id",
        }),
        { status: 400 },
      ),
    );
    mount();
    choose();
    fireEvent.click(screen.getByTestId("upload-submit"));
    expect(await screen.findByText("파일 단위 오류")).toBeVisible();
    expect(screen.getByTestId("upload-selection")).toBeVisible();
    expect(screen.queryByTestId("upload-result")).not.toBeInTheDocument();
    expect(screen.getByText("거래처.xlsx")).toBeVisible();
  });
  it("network failure keeps selection and permits retry", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockRejectedValueOnce(new Error("offline"));
    mount();
    choose();
    fireEvent.click(screen.getByTestId("upload-submit"));
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    fetcher.mockImplementation(original);
    fireEvent.click(screen.getByTestId("upload-submit"));
    expect(await screen.findByTestId("upload-result")).toBeVisible();
  });
  it("pending request blocks duplicate submit and cancellation", async () => {
    let finish!: (value: Response) => void;
    fetcher.mockImplementation(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    mount();
    choose();
    fireEvent.click(screen.getByTestId("upload-submit"));
    await screen.findByText("검사 중");
    expect(screen.getByTestId("upload-submit")).toBeDisabled();
    expect(screen.getByTestId("upload-cancel")).toBeDisabled();
    fireEvent.click(screen.getByTestId("upload-submit"));
    expect(fetcher).toHaveBeenCalledOnce();
    finish(ok(result));
    await screen.findByTestId("upload-result");
  });
  it("error result uses row counts, item tabs, ascending rows and disabled apply", async () => {
    mount();
    await inspect();
    expect(screen.getByRole("tab", { name: "오류 (2)" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(
      screen.getAllByRole("columnheader").map((n) => n.textContent),
    ).toEqual(["구분", "행", "열", "입력값", "이유"]);
    expect(
      within(screen.getByTestId("upload-issue-0")).getByText("5"),
    ).toBeVisible();
    expect(screen.getByTestId("upload-apply")).toBeDisabled();
    expect(screen.getByTestId("upload-error-download")).toBeEnabled();
    fireEvent.click(screen.getByRole("tab", { name: "경고 (0)" }));
    expect(screen.getByText("경고가 없습니다.")).toBeVisible();
  });
  it("warning-only result opens warning tab and allows apply", async () => {
    validation = clean;
    mount();
    await inspect();
    expect(screen.getByRole("tab", { name: "경고 (1)" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByTestId("upload-error-download")).toBeDisabled();
    expect(screen.getByTestId("upload-apply")).toBeEnabled();
    expect(
      screen
        .getAllByText("경고")
        .some((node) => node.className.includes("warning")),
    ).toBe(true);
    fireEvent.click(screen.getByTestId("upload-apply"));
    await waitFor(() =>
      expect(applied).toHaveBeenCalledWith({ upldId: "9", appliedRows: 5 }),
    );
    expect(closed).toHaveBeenCalledOnce();
  });
  it("zero issues renders empty state instead of a fabricated row", async () => {
    validation = { ...clean, warningRows: 0, items: [] };
    mount();
    await inspect();
    expect(screen.getByText("경고가 없습니다.")).toBeVisible();
    expect(screen.queryByTestId("upload-issue-0")).not.toBeInTheDocument();
    expect(screen.getByTestId("upload-apply")).toBeEnabled();
  });
  it("mapping uses readonly label/group and requires explicit MAIN/SUB", async () => {
    mount();
    await inspect();
    fireEvent.click(screen.getByTestId("upload-mapping-1"));
    await screen.findByRole("option", { name: "SI" });
    expect(screen.getByTestId("upload-map-label")).toHaveAttribute("readonly");
    expect(screen.getByTestId("upload-map-group")).toHaveValue("INDUSTRY");
    expect(screen.getByLabelText("주/보조")).toHaveValue("");
    fireEvent.change(screen.getByLabelText("코드"), {
      target: { value: "SI" },
    });
    fireEvent.click(screen.getByTestId("upload-map-save"));
    expect(await screen.findByText("주/보조를 고르세요.")).toBeVisible();
    expect(
      fetcher.mock.calls.some((c) => c[0] === "/api/admin/excel-maps"),
    ).toBe(false);
    fireEvent.change(screen.getByLabelText("주/보조"), {
      target: { value: "MAIN" },
    });
    fireEvent.click(screen.getByTestId("upload-map-save"));
    await waitFor(() =>
      expect(
        screen.queryByTestId("upload-mapping-dialog"),
      ).not.toBeInTheDocument(),
    );
    expect(
      JSON.parse(
        fetcher.mock.calls.find((c) => c[0] === "/api/admin/excel-maps")![1]
          .body,
      ),
    ).toEqual({
      excel_lbl_nm: "핀테크",
      trns_cd_grp_id: "INDUSTRY",
      trns_cd: "SI",
      map_role_cd: "MAIN",
      rmrk_desc: "",
    });
    expect(screen.getByTestId("upload-recheck").className).toContain("primary");
    fireEvent.click(screen.getByTestId("upload-recheck"));
    await waitFor(() =>
      expect(screen.getByTestId("upload-apply")).toBeEnabled(),
    );
    expect(
      fetcher.mock.calls.some(
        (c) => c[0] === "/api/uploads/7/recheck" && c[1].body === undefined,
      ),
    ).toBe(true);
  });
  it("mapping option failure is recoverable and does not invent options", async () => {
    const original = fetcher.getMockImplementation()!;
    let failed = true;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === "/api/codes/INDUSTRY" && failed) {
        failed = false;
        throw new Error("offline");
      }
      return original(url, init);
    });
    mount();
    await inspect();
    fireEvent.click(screen.getByTestId("upload-mapping-1"));
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(screen.getByTestId("upload-map-save")).toBeDisabled();
    fireEvent.click(screen.getByText("다시 시도"));
    await screen.findByRole("option", { name: "SI" });
  });
  it("mapping cancel preserves original result", async () => {
    mount();
    await inspect();
    fireEvent.click(screen.getByTestId("upload-mapping-1"));
    fireEvent.click(screen.getByTestId("upload-map-cancel"));
    expect(screen.getByTestId("upload-result")).toBeVisible();
    expect(screen.getByTestId("upload-apply")).toBeDisabled();
  });
  it("recheck error preserves old result and cannot enable apply", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      url.endsWith("/recheck")
        ? new Response(
            JSON.stringify({
              success: false,
              code: "NOT_FOUND",
              message: "다시 올려 주세요.",
            }),
            { status: 404 },
          )
        : original(url, init),
    );
    mount();
    await inspect();
    fireEvent.click(screen.getByTestId("upload-recheck"));
    expect(await screen.findByText("다시 올려 주세요.")).toBeVisible();
    expect(screen.getByTestId("upload-apply")).toBeDisabled();
    expect(screen.getByTestId("upload-issue-0")).toBeVisible();
  });
  it.each([
    [409, "STALE_VALIDATION", "다시 검사해 주세요."],
    [410, "VALIDATION_EXPIRED", "검사 후 30분이 지났습니다. 다시 올려 주세요."],
  ])(
    "apply %s preserves result but disables invalid token",
    async (status, code, message) => {
      validation = clean;
      const original = fetcher.getMockImplementation()!;
      fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
        url.endsWith("/apply")
          ? new Response(JSON.stringify({ success: false, code, message }), {
              status: Number(status),
            })
          : original(url, init),
      );
      mount();
      await inspect();
      fireEvent.click(screen.getByTestId("upload-apply"));
      expect(await screen.findByText(String(message))).toBeVisible();
      expect(screen.getByTestId("upload-apply")).toBeDisabled();
      expect(screen.getByTestId("upload-issue-0")).toBeVisible();
      expect(closed).not.toHaveBeenCalled();
      fireEvent.click(screen.getByTestId("upload-again"));
      expect(screen.getByTestId("upload-selection")).toBeVisible();
    },
  );
  it("download failures appear in current dialog", async () => {
    mount();
    fetcher.mockResolvedValueOnce(
      new Response(JSON.stringify({ message: "양식이 없습니다." }), {
        status: 404,
      }),
    );
    fireEvent.click(screen.getByTestId("upload-template"));
    expect(await screen.findByText("양식이 없습니다.")).toBeVisible();
    expect(screen.getByTestId("upload-selection")).toBeVisible();
  });
  it("real screen refreshes the partner list after successful apply", async () => {
    validation = clean;
    render(<UI15Screen />);
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    const before = fetcher.mock.calls.filter((c) =>
      c[0].startsWith("/api/partners?"),
    ).length;
    fireEvent.click(screen.getByTestId("partner-upload"));
    await inspect();
    fireEvent.click(screen.getByTestId("upload-apply"));
    expect(await screen.findByText("거래처 5건을 반영했습니다.")).toBeVisible();
    await waitFor(() =>
      expect(screen.queryByTestId("upload-result")).not.toBeInTheDocument(),
    );
    expect(
      fetcher.mock.calls.filter((c) => c[0].startsWith("/api/partners?"))
        .length,
    ).toBe(before + 1);
  });
});
