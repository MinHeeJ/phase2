import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import SalesPartManagement from "../salespartsops/SalesPartManagement";
import { salesPartExportColumns } from "../salespartsops/client";
import type { SalesPartListItem } from "../salesparts/client";

const mime =
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const filename = "영업파트_20261023.xlsx";
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    headers: { "Content-Type": "application/json" },
  });
const fileResponse = () =>
  new Response(new Uint8Array([80, 75, 3, 4]), {
    headers: {
      "Content-Type": mime,
      "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(filename)}`,
    },
  });
const failed = () =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      message: "선택한 파트가 조회 조건에 없습니다.",
      code: "INVALID_INPUT",
      field: "ids",
      requestId: "sales-part-download",
    }),
    { status: 400, headers: { "Content-Type": "application/json" } },
  );
let rows: SalesPartListItem[];
let fetcher: ReturnType<typeof vi.fn>;
let exportResponse: () => Promise<Response>;
let clicked: ReturnType<typeof vi.spyOn>;
const exportCalls = () =>
  fetcher.mock.calls.filter(([url]) =>
    String(url).startsWith("/api/sales-parts/export?"),
  );
const query = (index = 0) =>
  new URL(String(exportCalls()[index][0]), "https://test.invalid").searchParams;
const open = () =>
  fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
async function selectTwo() {
  fireEvent.click(
    await screen.findByRole("checkbox", { name: "영업 파트 51 선택" }),
  );
  fireEvent.click(screen.getByRole("checkbox", { name: "영업 파트 52 선택" }));
}
async function secondPage() {
  render(<SalesPartManagement />);
  await screen.findByText("총 101건");
  fireEvent.click(screen.getByRole("button", { name: /^2$/ }));
  await screen.findByRole("checkbox", { name: "영업 파트 51 선택" });
  expect(
    screen.queryByRole("checkbox", { name: "영업 파트 1 선택" }),
  ).not.toBeInTheDocument();
  expect(screen.getAllByTestId(/^sales-part-row-/)).toHaveLength(50);
}

beforeEach(() => {
  rows = Array.from({ length: 101 }, (_, index) => ({
    bsn_part_id: String(index + 1),
    bsn_part_nm: `영업 파트 ${index + 1}`,
    hq_yn: index === 0 ? "Y" : "N",
    use_yn: index === 51 ? "N" : "Y",
    sort_seq: index,
    referenceMonth: "2026-10",
    currentStandard: {
      part_std_hist_id: String(index + 1),
      aply_bgn_ym: "2026-10",
      aply_end_ym: null,
      ldr_nm: "파트장",
      prft_dstrb_rt: 42.25,
      bsn_fee_amt: 1234567,
      status: "적용 중",
    },
    scheduledChanges: [],
    peopleCount: 0,
    projectCount: 0,
  }));
  exportResponse = async () => fileResponse();
  fetcher = vi.fn(async (url: string) => {
    if (url.startsWith("/api/sales-parts/export?")) return exportResponse();
    if (url.startsWith("/api/sales-parts?")) {
      const params = new URL(url, "https://test.invalid").searchParams;
      const page = Number(params.get("page")),
        size = Number(params.get("size"));
      return response({
        items: rows.slice((page - 1) * size, page * size),
        total: rows.length,
        page,
        size,
      });
    }
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
  vi.stubGlobal("URL", URL);
  URL.createObjectURL = vi.fn(() => "blob:sales-part-export");
  URL.revokeObjectURL = vi.fn();
  clicked = vi
    .spyOn(HTMLAnchorElement.prototype, "click")
    .mockImplementation(() => {});
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("SCR-UI-15 sales part download and SCR-UI-15-M14", () => {
  it("downloads all 101 rows from page two without selection, not just its 50 rows", async () => {
    await secondPage();
    open();
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(exportCalls()).toHaveLength(1);
    expect(query().getAll("columns")).toEqual(salesPartExportColumns);
    for (const key of ["page", "size", "sort", "ids"])
      expect(query().has(key)).toBe(false);
    expect(clicked).toHaveBeenCalledOnce();
    expect(clicked.mock.instances[0]).toHaveProperty("download", filename);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:sales-part-export");
  });
  it("offers selected and whole-result scope; cancel preserves two checks without a request", async () => {
    await secondPage();
    await selectTwo();
    open();
    const modal = screen.getByRole("dialog", { name: "엑셀 다운로드 범위" });
    expect(screen.getByTestId("partner-export-scope")).toHaveAttribute(
      "data-screen-id",
      "SCR-UI-15-M14",
    );
    expect(within(modal).getByLabelText("선택 2건")).toBeChecked();
    expect(within(modal).getByLabelText("조회 전체 101건")).not.toBeChecked();
    fireEvent.click(within(modal).getByRole("button", { name: "취소" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(
      screen.getByRole("checkbox", { name: "영업 파트 51 선택" }),
    ).toBeChecked();
    expect(
      screen.getByRole("checkbox", { name: "영업 파트 52 선택" }),
    ).toBeChecked();
    expect(exportCalls()).toHaveLength(0);
    expect(clicked).not.toHaveBeenCalled();
  });
  it("downloads exactly the selected active and stopped identities in visible column order", async () => {
    await secondPage();
    await selectTwo();
    open();
    fireEvent.click(screen.getByRole("button", { name: "내려받기" }));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(query().getAll("ids")).toEqual(["51", "52"]);
    expect(query().getAll("columns")).toEqual(salesPartExportColumns);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(clicked).toHaveBeenCalledOnce();
  });
  it("whole-result scope omits all selected ids and pagination", async () => {
    await secondPage();
    await selectTwo();
    open();
    fireEvent.click(screen.getByLabelText("조회 전체 101건"));
    fireEvent.click(screen.getByRole("button", { name: "내려받기" }));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    for (const key of ["ids", "page", "size", "sort"])
      expect(query().has(key)).toBe(false);
  });
  it("Escape cancels scope without any file request", async () => {
    await secondPage();
    await selectTwo();
    open();
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(exportCalls()).toHaveLength(0);
  });
  it("pending download prevents duplicate requests, scope changes, and cancellation", async () => {
    let finish!: (value: Response) => void;
    exportResponse = () =>
      new Promise<Response>((resolve) => {
        finish = resolve;
      });
    await secondPage();
    await selectTwo();
    open();
    fireEvent.click(screen.getByRole("button", { name: "내려받기" }));
    expect(screen.getByLabelText("선택 2건")).toBeDisabled();
    expect(screen.getByLabelText("조회 전체 101건")).toBeDisabled();
    expect(screen.getByRole("button", { name: "취소" })).toBeDisabled();
    fireEvent.click(screen.getByTestId("export-confirm"));
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(exportCalls()).toHaveLength(1);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    await act(async () => finish(fileResponse()));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
  });
  it("selected failure keeps M14 and selection, then explicitly retries the same ids", async () => {
    exportResponse = async () => failed();
    await secondPage();
    await selectTwo();
    open();
    fireEvent.click(screen.getByRole("button", { name: "내려받기" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "선택한 파트가 조회 조건에 없습니다.",
    );
    expect(screen.getByRole("alert")).toHaveTextContent("sales-part-download");
    expect(screen.getByLabelText("선택 2건")).toBeChecked();
    expect(clicked).not.toHaveBeenCalled();
    expect(
      screen.queryByText("엑셀 파일을 내려받았습니다."),
    ).not.toBeInTheDocument();
    exportResponse = async () => fileResponse();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(exportCalls()).toHaveLength(2);
    expect(query(1).getAll("ids")).toEqual(["51", "52"]);
  });
  it("network error never downloads or auto-retries and supports explicit retry", async () => {
    exportResponse = async () => {
      throw new TypeError("offline");
    };
    await secondPage();
    open();
    await screen.findByRole("alert");
    expect(exportCalls()).toHaveLength(1);
    expect(clicked).not.toHaveBeenCalled();
    exportResponse = async () => fileResponse();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(exportCalls()).toHaveLength(2);
  });
  it.each([
    ["HTML", "text/html", "<html>error</html>", filename],
    ["renamed text", mime, "not an xlsx", filename],
    ["unsafe filename", mime, new Uint8Array([80, 75]), "../영업파트.xlsx"],
  ])(
    "rejects %s instead of reporting a successful file",
    async (_label, type, body, name) => {
      exportResponse = async () =>
        new Response(body, {
          headers: {
            "Content-Type": type,
            "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
          },
        });
      await secondPage();
      open();
      await screen.findByRole("alert");
      expect(clicked).not.toHaveBeenCalled();
      expect(URL.createObjectURL).not.toHaveBeenCalled();
      expect(
        screen.queryByText("엑셀 파일을 내려받았습니다."),
      ).not.toBeInTheDocument();
    },
  );
  it("empty list keeps download available for a header-only server workbook", async () => {
    rows = [];
    render(<SalesPartManagement />);
    await screen.findByText("조건에 맞는 결과가 없습니다");
    open();
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(query().has("ids")).toBe(false);
    expect(exportCalls()).toHaveLength(1);
  });
});
