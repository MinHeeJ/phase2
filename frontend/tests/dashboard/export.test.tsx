import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DashboardDownload } from "../../src/pages/dashboardops/DashboardDownload";
import { dashboardWorkbookType } from "../../src/pages/dashboardops/dashboardExportClient";

let fetcher: ReturnType<typeof vi.fn>;
let click: ReturnType<typeof vi.spyOn>;
const workbook = () =>
  new Response(new Uint8Array([0x50, 0x4b, 3, 4, 1, 2, 3]), {
    headers: {
      "Content-Type": dashboardWorkbookType,
      "Content-Disposition": "attachment; filename=dashboard_2026-10.xlsx",
    },
  });
beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  fetcher = vi.fn(async () => workbook());
  vi.stubGlobal("fetch", fetcher);
  vi.stubGlobal("innerWidth", 1280);
  vi.stubGlobal(
    "URL",
    class extends URL {
      static createObjectURL() {
        return "blob:dashboard-test";
      }
      static revokeObjectURL() {}
    },
  );
  vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:dashboard-test");
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
  click = vi
    .spyOn(HTMLAnchorElement.prototype, "click")
    .mockImplementation(() => {});
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const mount = (month = "2026-10", disabled = false) =>
  render(
    <MemoryRouter>
      <DashboardDownload month={month} disabled={disabled} />
    </MemoryRouter>,
  );

describe("dashboard Excel download", () => {
  it("requests the selected month anonymously, downloads binary and releases its URL", async () => {
    mount();
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await waitFor(() => expect(click).toHaveBeenCalledTimes(1));
    expect(fetcher).toHaveBeenCalledWith(
      "/api/dashboard/export?month=2026-10",
      expect.objectContaining({
        method: "GET",
        headers: { Accept: dashboardWorkbookType },
      }),
    );
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:dashboard-test");
    expect(click.mock.instances[0]).toHaveProperty(
      "download",
      "dashboard_2026-10.xlsx",
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
  it("keeps the public download control visible without roles, including a CLOSED month", () => {
    mount("2025-09");
    expect(screen.getByRole("button", { name: "엑셀 다운로드" })).toBeEnabled();
    expect(screen.getByText(/선택 기준월 2025-09/)).toBeInTheDocument();
    expect(
      screen.getByText(/OPEN 자료는 조회와 다운로드 사이에 변경될 수 있습니다/),
    ).toBeInTheDocument();
  });
  it("disables download while the dashboard has no coherent selected-month response", () => {
    mount("2026-10", true);
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("disables repeated clicks while pending and makes only one request", async () => {
    let resolve!: (response: Response) => void;
    fetcher.mockImplementation(
      () =>
        new Promise<Response>((done) => {
          resolve = done;
        }),
    );
    mount();
    const button = screen.getByRole("button", { name: "엑셀 다운로드" });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(button).toBeDisabled();
    expect(fetcher).toHaveBeenCalledTimes(1);
    await act(async () => resolve(workbook()));
    await waitFor(() => expect(button).toBeEnabled());
  });
  it.each(["INVALID_MONTH", "FUTURE_MONTH"])(
    "shows %s field validation and request id without producing a file",
    async (code) => {
      fetcher.mockResolvedValue(
        new Response(
          JSON.stringify({
            success: false,
            message: "기준월을 확인하세요.",
            field: "month",
            code,
            requestId: "dashboard-request",
          }),
          { status: 400, headers: { "Content-Type": "application/json" } },
        ),
      );
      mount();
      fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
      expect(await screen.findByRole("alert")).toHaveTextContent(
        "기준월을 확인하세요.",
      );
      expect(screen.getByRole("alert")).toHaveTextContent("dashboard-request");
      expect(click).not.toHaveBeenCalled();
    },
  );
  it("retains the selected month on failure and retries only the same whole export", async () => {
    fetcher
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            message: "파일 생성 실패",
            requestId: "export-request",
          }),
          { status: 500, headers: { "Content-Type": "application/json" } },
        ),
      )
      .mockResolvedValueOnce(workbook());
    mount("2025-03");
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(click).toHaveBeenCalledTimes(1));
    expect(fetcher.mock.calls.map((c) => c[0])).toEqual([
      "/api/dashboard/export?month=2025-03",
      "/api/dashboard/export?month=2025-03",
    ]);
  });
  it("does not save an HTML success response as an Excel workbook", async () => {
    fetcher.mockResolvedValue(
      new Response("<html>error</html>", {
        headers: { "Content-Type": "text/html" },
      }),
    );
    mount();
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "엑셀 파일 응답이 아닙니다.",
    );
    expect(click).not.toHaveBeenCalled();
  });
  it("rejects an empty or renamed non-XLSX body even with the declared MIME", async () => {
    fetcher.mockResolvedValue(
      new Response("not a workbook", {
        headers: { "Content-Type": dashboardWorkbookType },
      }),
    );
    mount();
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "엑셀 파일을 확인할 수 없습니다.",
    );
    expect(click).not.toHaveBeenCalled();
  });
  it("shows a network failure and never claims download success", async () => {
    fetcher.mockRejectedValue(new TypeError("network"));
    mount();
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "서버에 연결할 수 없습니다.",
    );
    expect(click).not.toHaveBeenCalled();
  });
  it("discards a previous-month in-flight file when the selected month changes", async () => {
    let resolve!: (response: Response) => void;
    fetcher.mockImplementationOnce(
      () =>
        new Promise<Response>((done) => {
          resolve = done;
        }),
    );
    const view = mount("2026-10");
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    view.rerender(
      <MemoryRouter>
        <DashboardDownload month="2026-09" />
      </MemoryRouter>,
    );
    await act(async () => resolve(workbook()));
    expect(click).not.toHaveBeenCalled();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await waitFor(() => expect(click).toHaveBeenCalledTimes(1));
    expect(fetcher.mock.calls[1][0]).toBe(
      "/api/dashboard/export?month=2026-09",
    );
  });
});
