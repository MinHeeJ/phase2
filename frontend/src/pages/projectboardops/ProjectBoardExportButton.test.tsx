import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ProjectBoardExportButton } from "./ProjectBoardExportButton";
import { downloadProjectBoard } from "./client";

const mime =
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
let fetcher: ReturnType<typeof vi.fn>;
let click: ReturnType<typeof vi.spyOn>;
const create = vi.fn(() => "blob:project-board"),
  revoke = vi.fn();
function fileResponse(
  name = "프로젝트현황판_20261023.xlsx",
  contentType = mime,
  prefix = [80, 75],
) {
  return {
    ok: true,
    status: 200,
    headers: new Headers({
      "Content-Type": contentType,
      "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
    }),
    blob: async () => ({
      slice: () => ({ arrayBuffer: async () => new Uint8Array(prefix).buffer }),
    }),
  };
}
beforeEach(() => {
  fetcher = vi.fn().mockResolvedValue(fileResponse());
  vi.stubGlobal("fetch", fetcher);
  create.mockClear();
  revoke.mockClear();
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: create,
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    configurable: true,
    value: revoke,
  });
  click = vi
    .spyOn(HTMLAnchorElement.prototype, "click")
    .mockImplementation(() => {});
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("SCR-UI-31 title-row workbook download", () => {
  it("uses applied filters only and preserves server filename and anonymous GET", async () => {
    const filter = {
      light: "위험",
      type: "TK",
      part: "7",
      page: 2,
      size: 20,
      sort: "prj_nm,desc",
    };
    render(<ProjectBoardExportButton filter={filter} />);
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(
      await screen.findByText("엑셀 파일을 내려받았습니다."),
    ).toBeVisible();
    const path = fetcher.mock.calls[0][0] as string;
    expect(path).toMatch(/^\/api\/project-board\/export\?/);
    expect(path).not.toContain("${");
    const url = new URL(path, "https://test.invalid");
    expect(url.pathname).toBe("/api/project-board/export");
    const query = url.searchParams;
    expect([...query.keys()]).toEqual(["light", "type", "part"]);
    expect(query.get("light")).toBe("위험");
    expect(query.get("type")).toBe("TK");
    expect(query.get("part")).toBe("7");
    expect(click).toHaveBeenCalledOnce();
    expect((click.mock.instances[0] as HTMLAnchorElement).download).toBe(
      "프로젝트현황판_20261023.xlsx",
    );
    expect(revoke).toHaveBeenCalledWith("blob:project-board");
    expect(fetcher.mock.calls[0]).toHaveLength(1);
  });
  it("no conditions means full matching population without a page parameter", async () => {
    await downloadProjectBoard({ light: "", type: "", part: "" });
    expect(fetcher).toHaveBeenCalledWith("/api/project-board/export");
  });
  it("is available without a login and does not inherit mobile readonly", async () => {
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      value: 375,
    });
    render(<ProjectBoardExportButton filter={{}} />);
    expect(screen.getByTestId("project-board-export-control")).toHaveAttribute(
      "data-screen-id",
      "SCR-UI-31",
    );
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(
      await screen.findByText("엑셀 파일을 내려받았습니다."),
    ).toBeVisible();
  });
  it("pending prevents duplicate requests", async () => {
    let resolve!: (value: ReturnType<typeof fileResponse>) => void;
    fetcher.mockReturnValue(
      new Promise((r) => {
        resolve = r;
      }),
    );
    render(<ProjectBoardExportButton filter={{}} />);
    const button = screen.getByRole("button", { name: "엑셀 다운로드" });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");
    expect(fetcher).toHaveBeenCalledOnce();
    resolve(fileResponse());
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(button).not.toBeDisabled();
  });
  it("error retains input and request id, never saves JSON, and retry succeeds", async () => {
    fetcher.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          success: false,
          meta: {},
          code: "INVALID_INPUT",
          field: "month",
          message: "숫자 기준값이 없습니다: BUDGET_RATE",
          requestId: "board-request",
        }),
        { status: 400 },
      ),
    );
    render(<ProjectBoardExportButton filter={{ type: "MO" }} />);
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(
      await screen.findByText("숫자 기준값이 없습니다: BUDGET_RATE"),
    ).toBeVisible();
    expect(screen.getByText("문의 번호 board-request")).toBeVisible();
    expect(create).not.toHaveBeenCalled();
    expect(
      screen.queryByText("엑셀 파일을 내려받았습니다."),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(fetcher.mock.calls[0][0]).toBe(fetcher.mock.calls[1][0]);
  });
  it("network failure is retryable and not successful", async () => {
    fetcher.mockRejectedValueOnce(new Error("offline"));
    render(<ProjectBoardExportButton filter={{}} />);
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(await screen.findByText("서버에 연결할 수 없습니다.")).toBeVisible();
    expect(create).not.toHaveBeenCalled();
  });
  it.each([
    [
      "response content type",
      "프로젝트현황판_20261023.xlsx",
      "text/html",
      [80, 75],
    ],
    [
      "renamed non-workbook bytes",
      "프로젝트현황판_20261023.xlsx",
      mime,
      [60, 104],
    ],
    [
      "path traversal filename",
      "../프로젝트현황판_20261023.xlsx",
      mime,
      [80, 75],
    ],
    ["wrong filename", "파일.xlsx", mime, [80, 75]],
  ])(
    "rejects %s without creating an object URL",
    async (_rule, name, type, prefix) => {
      fetcher.mockResolvedValueOnce(
        fileResponse(name as string, type as string, prefix as number[]),
      );
      render(<ProjectBoardExportButton filter={{}} />);
      fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
      await screen.findByRole("alert");
      expect(create).not.toHaveBeenCalled();
      expect(click).not.toHaveBeenCalled();
    },
  );
  it("a subsequent click uses the newly applied filters", async () => {
    const view = render(
      <ProjectBoardExportButton filter={{ light: "위험" }} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    view.rerender(<ProjectBoardExportButton filter={{ type: "IN" }} />);
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(fetcher.mock.calls[1][0]).toBe("/api/project-board/export?type=IN");
  });
});
