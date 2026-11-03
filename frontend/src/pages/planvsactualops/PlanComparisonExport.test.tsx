import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI11Screen from "../us4/UI11Screen";
import { downloadPlanComparison, planComparisonDefaults } from "../us4/client";

const mime =
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const filename = "계획대비투입_2026_20261014.xlsx";
const response = (data: unknown, meta: Record<string, unknown> = {}) =>
  new Response(JSON.stringify({ success: true, data, meta }), {
    headers: { "Content-Type": "application/json" },
  });
const workbook = (
  name = filename,
  type = mime,
  body = new Uint8Array([80, 75]),
) =>
  new Response(body, {
    headers: {
      "Content-Type": type,
      "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
    },
  });
let fetcher: ReturnType<typeof vi.fn>;
let exportResponse: () => Promise<Response>;
let click: ReturnType<typeof vi.spyOn>;
const create = vi.fn(() => "blob:comparison");
const revoke = vi.fn();
const exportCalls = () =>
  fetcher.mock.calls.filter(([url]) =>
    String(url).startsWith("/api/plan-vs-actual/export?"),
  );
const mount = () =>
  render(
    <MemoryRouter>
      <UI11Screen renderShortages={() => <p>미충원 목록</p>} />
    </MemoryRouter>,
  );

beforeEach(() => {
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-14T00:00:00Z"));
  exportResponse = async () => workbook();
  fetcher = vi.fn(async (url: string) => {
    if (url === "/api/params")
      return response([{ oprt_std_id: "PLAN_GAP_TOLERANCE", num_val: 0.1 }]);
    if (url === "/api/codes/JOB")
      return response([{ code: "BE", name: "백엔드 개발" }]);
    if (url.startsWith("/api/plan-vs-actual/export?")) return exportResponse();
    if (url.startsWith("/api/plan-vs-actual?"))
      return response(
        {
          rows: [],
          total: 51,
          page: Number(new URLSearchParams(url.split("?")[1]).get("page")),
          size: 50,
          totals: { planMm: 153, actualMm: 127.5 },
        },
        {
          today: "2026-10-14",
          projects: [{ prj_id: 7, prj_no: "P-2607", prj_nm: "연간 프로젝트" }],
        },
      );
    throw new Error(`Unexpected request ${url}`);
  });
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
  vi.useRealTimers();
});

describe("UI-11 annual export", () => {
  it("downloads applied filters and sort, never a page or unapplied draft", async () => {
    mount();
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    fireEvent.change(screen.getByLabelText("프로젝트"), {
      target: { value: "7" },
    });
    fireEvent.change(screen.getByLabelText("직무"), {
      target: { value: "BE" },
    });
    fireEvent.change(screen.getByLabelText("차이"), {
      target: { value: "SHORT" },
    });
    fireEvent.click(screen.getByTestId("comparison-query"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => String(url).includes("gap=SHORT")),
      ).toBe(true),
    );
    fireEvent.click(screen.getByTestId("comparison-next-page"));
    await waitFor(() =>
      expect(screen.getByTestId("comparison-previous-page")).toBeEnabled(),
    );
    fireEvent.change(screen.getByLabelText("차이"), {
      target: { value: "OVER" },
    });
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    const query = new URLSearchParams(
      String(exportCalls()[0][0]).split("?")[1],
    );
    expect(Object.fromEntries(query)).toEqual({
      year: "2026",
      projectId: "7",
      job: "BE",
      gap: "SHORT",
      sort: "gapMm,asc;prj_nm,asc;job_cd,asc",
    });
    expect((click.mock.instances[0] as HTMLAnchorElement).download).toBe(
      filename,
    );
    expect(revoke).toHaveBeenCalledWith("blob:comparison");
    expect(document.querySelector('a[href="blob:comparison"]')).toBeNull();
    expect(screen.getByLabelText("차이")).toHaveValue("OVER");
  });
  it("keeps download available for an empty list", async () => {
    mount();
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await waitFor(() => expect(click).toHaveBeenCalledOnce());
  });
  it("keeps historical read-only years downloadable", async () => {
    mount();
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    fireEvent.click(screen.getByLabelText("이전 해"));
    await screen.findByText("지난 해 · 읽기 전용");
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    expect(String(exportCalls()[0][0])).toContain("year=2025");
  });
  it("hides annual download in the monthly shortages tab", async () => {
    mount();
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    fireEvent.click(screen.getByRole("tab", { name: "미충원 직무" }));
    expect(
      screen.queryByRole("button", { name: "엑셀 다운로드" }),
    ).not.toBeInTheDocument();
    expect(exportCalls()).toHaveLength(0);
  });
  it("disables duplicate clicks while a download is pending", async () => {
    let resolve!: (value: Response) => void;
    exportResponse = () =>
      new Promise<Response>((done) => {
        resolve = done;
      });
    mount();
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    const button = screen.getByRole("button", { name: "엑셀 다운로드" });
    fireEvent.click(button);
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(exportCalls()).toHaveLength(1);
    resolve(workbook());
    await waitFor(() => expect(button).toBeEnabled());
    expect(click).toHaveBeenCalledOnce();
  });
  it("shows server error and request id and retries the same filters", async () => {
    exportResponse = async () =>
      new Response(
        JSON.stringify({
          success: false,
          message: "조회 조건을 확인하세요.",
          field: "year",
        }),
        {
          status: 400,
          headers: {
            "Content-Type": "application/json",
            "X-Request-Id": "comparison-request",
          },
        },
      );
    mount();
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await screen.findByText("조회 조건을 확인하세요.");
    expect(
      screen.getByText("문의 번호 comparison-request"),
    ).toBeInTheDocument();
    expect(click).not.toHaveBeenCalled();
    exportResponse = async () => workbook();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(click).toHaveBeenCalledOnce());
    expect(exportCalls()[0][0]).toBe(exportCalls()[1][0]);
  });
  it("shows network errors without replacing the current screen", async () => {
    exportResponse = async () => {
      throw new TypeError("offline");
    };
    mount();
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await screen.findByText("서버에 연결할 수 없습니다.");
    expect(screen.getByLabelText("프로젝트")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "엑셀 다운로드" })).toBeEnabled();
    expect(create).not.toHaveBeenCalled();
  });
  it.each([
    "../export.xlsx",
    "folder\\export.xlsx",
    "export.html",
    "export\u0000.xlsx",
    "a".repeat(201) + ".xlsx",
  ])(
    "refuses unsafe server filename %s before creating a URL",
    async (name) => {
      exportResponse = async () => workbook(name);
      await expect(
        downloadPlanComparison(planComparisonDefaults(2026)),
      ).rejects.toThrow("파일명을 확인할 수 없습니다.");
      expect(create).not.toHaveBeenCalled();
      expect(click).not.toHaveBeenCalled();
    },
  );
  it("refuses malformed encoded filenames before creating a URL", async () => {
    exportResponse = async () =>
      new Response(new Uint8Array([80, 75]), {
        headers: {
          "Content-Type": mime,
          "Content-Disposition": "attachment; filename*=UTF-8''%ZZ.xlsx",
        },
      });
    await expect(
      downloadPlanComparison(planComparisonDefaults(2026)),
    ).rejects.toThrow("파일명을 확인할 수 없습니다.");
    expect(create).not.toHaveBeenCalled();
  });
  it("refuses an absent filename", async () => {
    exportResponse = async () =>
      new Response(new Uint8Array([80, 75]), {
        headers: { "Content-Type": mime },
      });
    await expect(
      downloadPlanComparison(planComparisonDefaults(2026)),
    ).rejects.toThrow("파일명을 확인할 수 없습니다.");
    expect(create).not.toHaveBeenCalled();
  });
  it("refuses HTML instead of spreadsheet content", async () => {
    exportResponse = async () => workbook(filename, "text/html");
    await expect(
      downloadPlanComparison(planComparisonDefaults(2026)),
    ).rejects.toThrow("엑셀 응답을 확인할 수 없습니다.");
    expect(create).not.toHaveBeenCalled();
  });
  it("refuses an empty spreadsheet response", async () => {
    exportResponse = async () => workbook(filename, mime, new Uint8Array());
    await expect(
      downloadPlanComparison(planComparisonDefaults(2026)),
    ).rejects.toThrow("엑셀 응답을 확인할 수 없습니다.");
    expect(create).not.toHaveBeenCalled();
  });
  it("releases the URL and anchor even when browser download fails", async () => {
    click.mockImplementation(() => {
      throw new Error("download blocked");
    });
    await expect(
      downloadPlanComparison(planComparisonDefaults(2026)),
    ).rejects.toThrow("download blocked");
    expect(revoke).toHaveBeenCalledWith("blob:comparison");
    expect(document.querySelector('a[href="blob:comparison"]')).toBeNull();
  });
});
