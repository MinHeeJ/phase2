import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { UI12ExportButton } from "./UI12ExportButton";

const mime =
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
let fetcher: ReturnType<typeof vi.fn>;
let click: ReturnType<typeof vi.spyOn>;
const create = vi.fn(() => "blob:assignment"),
  revoke = vi.fn();
function workbook(
  name = "투입배정_2026_20261014.xlsx",
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
  fetcher = vi.fn().mockResolvedValue(workbook());
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
describe("SCR-UI-12 export", () => {
  it("downloads all applied AND filters and sort without page and preserves server filename", async () => {
    const filter = {
      year: 2026,
      projectId: 3,
      job: "BE",
      warning: "OVER_MM",
      q: "사람",
      sort: "prj_nm,asc;bgn_dt,asc",
      page: 9,
    };
    render(<UI12ExportButton filter={filter} />);
    fireEvent.click(screen.getByTestId("assignment-export"));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    const query = new URL(fetcher.mock.calls[0][0], "https://test.invalid")
      .searchParams;
    expect([...query.keys()]).toEqual([
      "year",
      "projectId",
      "job",
      "warning",
      "q",
      "sort",
    ]);
    expect(query.get("q")).toBe("사람");
    expect(query.get("projectId")).toBe("3");
    expect((click.mock.instances[0] as HTMLAnchorElement).download).toBe(
      "투입배정_2026_20261014.xlsx",
    );
    expect(revoke).toHaveBeenCalledWith("blob:assignment");
    expect(fetcher.mock.calls[0]).toHaveLength(1);
  });
  it.each([
    ["투입배정_2026_20261014.xlsx", "text/html", [80, 75]],
    ["투입배정_2026_20261014.xlsx", mime, [60, 104]],
    ["../투입배정_2026_20261014.xlsx", mime, [80, 75]],
  ])("rejects invalid artifact %s %s", async (name, type, prefix) => {
    fetcher.mockResolvedValue(
      workbook(name as string, type as string, prefix as number[]),
    );
    render(<UI12ExportButton filter={{ year: 2026 }} />);
    fireEvent.click(screen.getByTestId("assignment-export"));
    await screen.findByRole("alert");
    expect(create).not.toHaveBeenCalled();
    expect(click).not.toHaveBeenCalled();
  });
  it("network failure keeps filter and supports retry", async () => {
    fetcher.mockRejectedValueOnce(new Error("offline"));
    render(<UI12ExportButton filter={{ year: 2026, job: "BE" }} />);
    fireEvent.click(screen.getByTestId("assignment-export"));
    await screen.findByText("서버에 연결할 수 없습니다.");
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(fetcher.mock.calls[0][0]).toBe(fetcher.mock.calls[1][0]);
  });
});
