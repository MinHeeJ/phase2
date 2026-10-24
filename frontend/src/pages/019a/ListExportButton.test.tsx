import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ListExportButton } from "../partnersops/PartnerActions";
import { downloadList } from "../partnersops/client";
import { salesPartExportColumns } from "../salespartsops/client";

const mime =
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const columns = ["ptnr_nm", "paymentAccount", "biz_reg_no"];
const selected = ["1", "2"];
const filter = { kind: "VEND", useYn: "", q: "협력사 B" };
let fetcher: ReturnType<typeof vi.fn>;
let clicked: ReturnType<typeof vi.spyOn>;
const goodFile = (resource = "partners") =>
  new Response(new Uint8Array([80, 75, 3, 4]), {
    headers: {
      "Content-Type": mime,
      "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(resource === "partners" ? "거래처_20261023.xlsx" : "영업파트_20261023.xlsx")}`,
    },
  });
const failed = () =>
  new Response(
    JSON.stringify({
      message: "선택한 거래처가 조회 조건에 없습니다.",
      field: "ids",
      code: "INVALID_INPUT",
      requestId: "download-request",
    }),
    { status: 400, headers: { "Content-Type": "application/json" } },
  );
const query = (index = 0) =>
  new URL(String(fetcher.mock.calls[index][0]), "https://example.test")
    .searchParams;
const button = (
  ids: string[] = selected,
  resource: "partners" | "sales-parts" = "partners",
) => (
  <ListExportButton
    resource={resource}
    filter={resource === "partners" ? filter : {}}
    columns={resource === "partners" ? columns : salesPartExportColumns}
    selectedIds={ids}
    total={151}
  />
);

beforeEach(() => {
  fetcher = vi.fn(async () => goodFile());
  vi.stubGlobal("fetch", fetcher);
  vi.stubGlobal("URL", URL);
  URL.createObjectURL = vi.fn(() => "blob:download");
  URL.revokeObjectURL = vi.fn();
  clicked = vi
    .spyOn(HTMLAnchorElement.prototype, "click")
    .mockImplementation(() => {});
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("UI-15 list export and M13/M14 scope", () => {
  it("opens the existing work modal with two selected rows and the entire query count; cancel sends nothing", () => {
    render(button());
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    const dialog = screen.getByRole("dialog", { name: "엑셀 다운로드 범위" });
    expect(within(dialog).getByLabelText("선택 2건")).toBeChecked();
    expect(within(dialog).getByLabelText("조회 전체 151건")).not.toBeChecked();
    expect(screen.getByTestId("partner-export-scope")).toHaveAttribute(
      "data-screen-id",
      "SCR-UI-15-M13",
    );
    fireEvent.click(within(dialog).getByRole("button", { name: "취소" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("without selection immediately downloads all filters and visible keys without pagination", async () => {
    render(button([]));
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(query().getAll("columns")).toEqual(columns);
    expect(query().get("kind")).toBe("VEND");
    expect(query().get("useYn")).toBe("");
    expect(query().get("q")).toBe("협력사 B");
    for (const key of ["page", "size", "sort", "ids"])
      expect(query().has(key)).toBe(false);
    expect(clicked).toHaveBeenCalledOnce();
    expect(clicked.mock.instances[0]).toHaveProperty(
      "download",
      "거래처_20261023.xlsx",
    );
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:download");
  });
  it("selected scope sends the selected set AND the active filters in visible column order", async () => {
    render(button());
    fireEvent.click(screen.getByTestId("partners-export"));
    fireEvent.click(screen.getByTestId("export-confirm"));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(query().getAll("ids")).toEqual(selected);
    expect(query().getAll("columns")).toEqual(columns);
    expect(query().get("kind")).toBe("VEND");
    expect(query().get("q")).toBe("협력사 B");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("all scope omits ids even when rows are selected", async () => {
    render(button());
    fireEvent.click(screen.getByTestId("partners-export"));
    fireEvent.click(screen.getByLabelText("조회 전체 151건"));
    fireEvent.click(screen.getByTestId("export-confirm"));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(query().has("ids")).toBe(false);
    expect(query().get("q")).toBe("협력사 B");
  });
  it("locks scope and cancel during download and prevents repeated requests", async () => {
    let finish!: (value: Response) => void;
    fetcher.mockImplementation(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    render(button());
    fireEvent.click(screen.getByTestId("partners-export"));
    fireEvent.click(screen.getByTestId("export-confirm"));
    expect(screen.getByLabelText("선택 2건")).toBeDisabled();
    expect(screen.getByLabelText("조회 전체 151건")).toBeDisabled();
    expect(screen.getByTestId("export-cancel")).toBeDisabled();
    fireEvent.click(screen.getByTestId("export-confirm"));
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledOnce();
    finish(goodFile());
    await screen.findByText("엑셀 파일을 내려받았습니다.");
  });
  it("failed selected download keeps the modal and selected scope with no success", async () => {
    fetcher.mockResolvedValue(failed());
    render(button());
    fireEvent.click(screen.getByTestId("partners-export"));
    fireEvent.click(screen.getByTestId("export-confirm"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "선택한 거래처가 조회 조건에 없습니다.",
    );
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByLabelText("선택 2건")).toBeChecked();
    expect(clicked).not.toHaveBeenCalled();
    expect(
      screen.queryByText("엑셀 파일을 내려받았습니다."),
    ).not.toBeInTheDocument();
  });
  it("retry after closing a failed selected request never silently changes it to all", async () => {
    fetcher.mockResolvedValueOnce(failed()).mockResolvedValueOnce(goodFile());
    const view = render(button());
    fireEvent.click(screen.getByTestId("partners-export"));
    fireEvent.click(screen.getByTestId("export-confirm"));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByTestId("export-cancel"));
    view.rerender(
      <ListExportButton
        filter={{ q: "다른 조건" }}
        columns={["ptnr_no"]}
        selectedIds={[]}
        total={0}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(fetcher.mock.calls[1][0]).toEqual(fetcher.mock.calls[0][0]);
    expect(query(1).getAll("ids")).toEqual(selected);
  });
  it("reopening resets the previous error and defaults to the current selection", async () => {
    fetcher.mockResolvedValue(failed());
    render(button());
    fireEvent.click(screen.getByTestId("partners-export"));
    fireEvent.click(screen.getByTestId("export-confirm"));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByTestId("export-cancel"));
    fireEvent.click(screen.getByTestId("partners-export"));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByLabelText("선택 2건")).toBeChecked();
  });
  it("sales part scope uses M14 and sends visible part columns to its existing endpoint", async () => {
    fetcher.mockResolvedValue(goodFile("sales-parts"));
    render(button(selected, "sales-parts"));
    fireEvent.click(screen.getByTestId("sales-parts-export"));
    expect(screen.getByTestId("partner-export-scope")).toHaveAttribute(
      "data-screen-id",
      "SCR-UI-15-M14",
    );
    fireEvent.click(screen.getByTestId("export-confirm"));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(String(fetcher.mock.calls[0][0])).toMatch(
      /^\/api\/sales-parts\/export\?/,
    );
    expect(query().getAll("columns")).toEqual(salesPartExportColumns);
    expect(query().getAll("ids")).toEqual(selected);
    expect(clicked.mock.instances[0]).toHaveProperty(
      "download",
      "영업파트_20261023.xlsx",
    );
  });
  it("empty lists can download the server's header-only workbook", async () => {
    render(
      <ListExportButton
        filter={{}}
        columns={columns}
        selectedIds={[]}
        total={0}
      />,
    );
    fireEvent.click(screen.getByTestId("partners-export"));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it("network failure displays an error and allows an explicit retry", async () => {
    fetcher
      .mockRejectedValueOnce(new TypeError("offline"))
      .mockResolvedValueOnce(goodFile());
    render(button([]));
    fireEvent.click(screen.getByTestId("partners-export"));
    await screen.findByRole("alert");
    expect(clicked).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});

describe("download response validation", () => {
  it.each([
    ["HTML", "text/html", "<html>error</html>", "거래처.xlsx"],
    ["renamed text file", mime, "not an excel file", "거래처.xlsx"],
    ["missing filename", mime, new Uint8Array([80, 75]), null],
    ["forbidden path", mime, new Uint8Array([80, 75]), "../거래처.xlsx"],
  ])(
    "rejects %s without creating a browser download",
    async (_label, type, body, name) => {
      const headers: Record<string, string> = { "Content-Type": String(type) };
      if (name)
        headers["Content-Disposition"] =
          `attachment; filename*=UTF-8''${encodeURIComponent(String(name))}`;
      fetcher.mockResolvedValue(new Response(body as BodyInit, { headers }));
      await expect(downloadList("partners", filter, columns)).rejects.toThrow();
      expect(clicked).not.toHaveBeenCalled();
      expect(URL.createObjectURL).not.toHaveBeenCalled();
    },
  );
});
