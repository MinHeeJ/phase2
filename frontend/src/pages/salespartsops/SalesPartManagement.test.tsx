import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import SalesPartManagement from "./SalesPartManagement";
import { SalesPartEditDialog } from "./SalesPartEditDialog";
import { SalesPartStateDialog } from "./SalesPartStateDialog";
import { listSalesPartOptions, salesPartExportColumns } from "./client";
import type { SalesPartDetail, SalesPartStandard } from "../salesparts/client";

const current: SalesPartStandard = {
  part_std_hist_id: "20",
  aply_bgn_ym: "2026-01",
  aply_end_ym: null,
  ldr_nm: "파트장",
  prft_dstrb_rt: 40,
  bsn_fee_amt: 1500000,
  status: "적용 중",
};
const part: SalesPartDetail = {
  bsn_part_id: "2",
  bsn_part_nm: "영업 파트",
  hq_yn: "N",
  use_yn: "Y",
  sort_seq: 1,
  referenceMonth: "2026-10",
  currentStandard: current,
  scheduledChanges: [],
  peopleCount: 0,
  projectCount: 0,
  standards: [current],
  lastClosedMonth: "2026-09",
  firstAllowedMonth: "2026-10",
};
const hq: SalesPartDetail = {
  ...part,
  bsn_part_id: "1",
  bsn_part_nm: "본사",
  hq_yn: "Y",
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failed = (
  message: string,
  field: string | null = null,
  code = "INVALID_INPUT",
  status = 400,
) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      message,
      field,
      code,
      requestId: "test-request",
    }),
    { status, headers: { "Content-Type": "application/json" } },
  );
let fetcher: ReturnType<typeof vi.fn>, rows: SalesPartDetail[];
beforeEach(() => {
  rows = [
    hq,
    part,
    { ...part, bsn_part_id: "3", bsn_part_nm: "중지 파트", use_yn: "N" },
  ];
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/params/"))
      return response({
        num_val: url.includes("PART_PROFIT_RATE") ? 45 : 1600000,
      });
    if (url === "/api/sales-parts/options")
      return response(
        rows
          .filter((r) => r.use_yn === "Y")
          .map((r) => ({
            id: r.bsn_part_id,
            name: r.bsn_part_nm,
            hq: r.hq_yn === "Y",
          })),
      );
    if (url.startsWith("/api/sales-parts/export?"))
      return failed("파일을 생성하지 못했습니다.", null, "SERVER_ERROR", 500);
    if (url.startsWith("/api/sales-parts?")) {
      const q = new URL(url, "https://test.invalid").searchParams,
        page = Number(q.get("page"));
      return response({ items: rows, total: rows.length, page, size: 50 });
    }
    const id = url.split("/")[3],
      index = rows.findIndex((r) => r.bsn_part_id === id),
      row = rows[index];
    if (!row) throw new Error(`Unexpected test request ${url}`);
    if (init?.method === "PUT")
      rows[index] = { ...row, ...JSON.parse(init.body as string) };
    if (url.endsWith("/disable")) rows[index] = { ...row, use_yn: "N" };
    if (url.endsWith("/enable")) rows[index] = { ...row, use_yn: "Y" };
    if (url.endsWith("/standards")) {
      const value = JSON.parse(init?.body as string),
        next: SalesPartStandard = {
          ...value,
          part_std_hist_id: "21",
          aply_end_ym: null,
          status: "예정",
        };
      rows[index] = {
        ...row,
        scheduledChanges: [next],
        standards: [next, { ...current, aply_end_ym: "2026-10" }],
      };
    }
    return response(rows[index]);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const edit = (
  mode: "standards" | "name" = "standards",
  value = part,
  extra = {},
) =>
  render(
    <SalesPartEditDialog
      part={value}
      mode={mode}
      reload={async () => {}}
      onCancel={vi.fn()}
      onSaved={vi.fn()}
      {...extra}
    />,
  );
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const submit = () =>
  fireEvent.submit(screen.getByTestId("sales-part-edit-dialog"));
const openDetail = async (name = "영업 파트") => {
  fireEvent.click(await screen.findByRole("button", { name }));
  const drawer = await screen.findByRole("dialog", { name: "영업 파트 상세" });
  await within(drawer).findByText("2026-10 기준");
  return drawer;
};
const openMenu = async (label: string, name?: string) => {
  const drawer = await openDetail(name);
  fireEvent.click(within(drawer).getByRole("button", { name: "파트 더보기" }));
  fireEvent.click(within(drawer).getByRole("menuitem", { name: label }));
};

describe("sales part standard and name dialogs", () => {
  it("initializes only current values and has no editable part name", () => {
    edit();
    expect(
      screen.getByRole("dialog", { name: "영업 파트 기준 변경" }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("파트명")).not.toBeInTheDocument();
    expect(screen.getByLabelText("파트장")).toHaveValue("파트장");
    expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue(40);
    expect(screen.getByLabelText("영업비 (원)")).toHaveValue(1500000);
    expect(screen.getByLabelText("적용 시작월")).toHaveAttribute(
      "min",
      "2026-10",
    );
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("without closing history the month is blank and required", () => {
    edit("standards", {
      ...part,
      firstAllowedMonth: null,
      lastClosedMonth: null,
    });
    expect(screen.getByLabelText("적용 시작월")).toHaveValue("");
    submit();
    expect(screen.getByRole("alert")).toHaveTextContent("올바른 연월");
  });
  it.each([
    ["파트장", "", "파트장을 입력해 주세요."],
    ["이익 배분율 (%)", "100.001", "이익 배분율은"],
    ["이익 배분율 (%)", "-1", "이익 배분율은"],
    ["이익 배분율 (%)", "101", "이익 배분율은"],
    ["영업비 (원)", "0", "영업비를 입력"],
    ["적용 시작월", "2026-09", "마감된 달"],
  ])("rejects invalid %s without a request", (label, value, message) => {
    edit();
    change(label, value);
    submit();
    expect(screen.getByRole("alert")).toHaveTextContent(message);
    expect(fetcher).not.toHaveBeenCalled();
  });
  it.each([0, 100])("accepts rate bound %s", async (rate) => {
    edit();
    change("적용 시작월", "2026-11");
    change("이익 배분율 (%)", String(rate));
    submit();
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        "/api/sales-parts/2/standards",
        expect.objectContaining({ method: "POST" }),
      ),
    );
  });
  it("shows SAME_STANDARD and keeps values for correction", async () => {
    fetcher.mockResolvedValueOnce(
      failed(
        "지금 기준과 같습니다. 바꿀 값을 입력해 주세요.",
        null,
        "SAME_STANDARD",
        409,
      ),
    );
    edit();
    change("적용 시작월", "2026-11");
    submit();
    expect(
      await screen.findByText("지금 기준과 같습니다. 바꿀 값을 입력해 주세요."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("파트장")).toHaveValue("파트장");
  });
  it("server closed month field is focused and preserved", async () => {
    fetcher.mockResolvedValueOnce(
      failed("마감된 달입니다.", "aply_bgn_ym", "CLOSED_MONTH", 409),
    );
    edit();
    change("적용 시작월", "2026-11");
    submit();
    await waitFor(() =>
      expect(screen.getByLabelText("적용 시작월")).toHaveFocus(),
    );
    expect(screen.getByLabelText("적용 시작월")).toHaveValue("2026-11");
  });
  it("current null never takes scheduled values and loads selected-month parameters", async () => {
    edit("standards", {
      ...part,
      currentStandard: null,
      scheduledChanges: [{ ...current, prft_dstrb_rt: 99 }],
    });
    await waitFor(() =>
      expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue(45),
    );
    expect(screen.getByLabelText("파트장")).toHaveValue("");
    expect(screen.getByLabelText("영업비 (원)")).toHaveValue(1600000);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/params/PART_PROFIT_RATE?month=2026-10",
      expect.anything(),
    );
  });
  it("parameter failure offers retry and manual required values, not constants", async () => {
    fetcher
      .mockResolvedValueOnce(failed("기준값 없음", null, "NOT_FOUND", 404))
      .mockResolvedValueOnce(failed("기준값 없음", null, "NOT_FOUND", 404));
    edit("standards", { ...part, currentStandard: null });
    await screen.findByText(/배분율과 영업비를 직접 입력/);
    expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue(null);
    change("파트장", "수동 파트장");
    change("이익 배분율 (%)", "45");
    change("영업비 (원)", "1");
    submit();
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => url.endsWith("/standards")),
      ).toBe(true),
    );
  });
  it("late previous-month defaults cannot overwrite the newly selected month", async () => {
    let finish: (r: Response) => void = () => {};
    const delayed = new Promise<Response>((r) => {
      finish = r;
    });
    fetcher.mockImplementation((url: string) =>
      url.includes("month=2026-10")
        ? delayed
        : Promise.resolve(
            response({
              num_val: url.includes("PART_PROFIT_RATE") ? 50 : 1700000,
            }),
          ),
    );
    edit("standards", { ...part, currentStandard: null });
    expect(screen.getByTestId("sales-part-edit-submit")).toBeDisabled();
    change("적용 시작월", "2026-11");
    await waitFor(() =>
      expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue(50),
    );
    await act(async () => finish(response({ num_val: 99 })));
    expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue(50);
    expect(screen.getByLabelText("영업비 (원)")).toHaveValue(1700000);
  });
  it("rename shows connection help, length limit, and unchanged name closes without API", () => {
    const close = vi.fn();
    edit("name", part, { onCancel: close });
    expect(
      screen.getByText("소속 인력·프로젝트·사업계획의 파트명도 함께 바뀝니다."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("파트명")).toHaveAttribute("maxlength", "30");
    submit();
    expect(close).toHaveBeenCalledOnce();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("blank rename is rejected with required wording", () => {
    edit("name");
    change("파트명", "");
    submit();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "파트명을 입력해 주세요.",
    );
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("duplicate rename stays open with field error and entered value", async () => {
    fetcher.mockResolvedValueOnce(
      failed(
        "같은 이름의 파트가 이미 있습니다.",
        "bsn_part_nm",
        "DUPLICATE_PART_NAME",
        409,
      ),
    );
    edit("name");
    change("파트명", "다른 이름");
    submit();
    await waitFor(() => expect(screen.getByLabelText("파트명")).toHaveFocus());
    expect(screen.getByLabelText("파트명")).toHaveValue("다른 이름");
    expect(
      screen.getAllByText("같은 이름의 파트가 이미 있습니다.").length,
    ).toBeGreaterThan(0);
  });
  it("dirty cancel uses existing unsaved guard and does not write", () => {
    const close = vi.fn();
    edit("name", part, { onCancel: close });
    change("파트명", "미저장");
    fireEvent.click(screen.getByTestId("sales-part-edit-cancel"));
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(screen.getByLabelText("파트명")).toHaveValue("미저장");
    expect(close).not.toHaveBeenCalled();
    expect(fetcher).not.toHaveBeenCalled();
  });
});

describe("composed sales part actions", () => {
  it("standard save sends no name and refreshes both detail and list with scheduled state", async () => {
    render(<SalesPartManagement />);
    const drawer = await openDetail();
    fireEvent.click(within(drawer).getByRole("button", { name: "기준 변경" }));
    change("적용 시작월", "2026-11");
    change("이익 배분율 (%)", "45");
    submit();
    expect(
      await screen.findByText(
        "영업 파트 기준을 바꿨습니다. 2026-11부터 적용됩니다.",
      ),
    ).toBeInTheDocument();
    const call = fetcher.mock.calls.find(([url]) => url.endsWith("/standards"));
    expect(JSON.parse(call![1].body)).toEqual({
      aply_bgn_ym: "2026-11",
      ldr_nm: "파트장",
      prft_dstrb_rt: 45,
      bsn_fee_amt: 1500000,
    });
    expect(screen.getByText("2026-11부터 45%")).toBeInTheDocument();
    expect(within(drawer).getByText("예정")).toBeInTheDocument();
    expect(
      fetcher.mock.calls.filter(([url]) => url === "/api/sales-parts/2").length,
    ).toBeGreaterThan(1);
  });
  it("rename updates existing detail and list instead of creating another screen", async () => {
    render(<SalesPartManagement />);
    await openMenu("파트명 수정");
    change("파트명", "새 파트명");
    submit();
    expect(await screen.findByText("파트명을 바꿨습니다.")).toBeInTheDocument();
    expect(
      await screen.findByRole("button", { name: "새 파트명" }),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole("dialog", { name: "영업 파트 상세" })).getByRole(
        "heading",
        { name: "새 파트명" },
      ),
    ).toBeInTheDocument();
  });
  it("HQ hides both more menu and standards", async () => {
    render(<SalesPartManagement />);
    const drawer = await openDetail("본사");
    expect(
      within(drawer).queryByRole("button", { name: "기준 변경" }),
    ).toBeNull();
    expect(
      within(drawer).queryByRole("button", { name: "파트 더보기" }),
    ).toBeNull();
  });
  it.each([
    [3, 0],
    [0, 1],
  ])(
    "known people %s and projects %s show blocking explanation without write",
    async (peopleCount, projectCount) => {
      rows[1] = { ...part, peopleCount, projectCount };
      render(<SalesPartManagement />);
      await openMenu("사용 중지");
      expect(
        screen.getByRole("heading", { name: "사용을 중지할 수 없습니다" }),
      ).toBeInTheDocument();
      expect(
        screen.getByText(
          new RegExp(
            `소속 인력 ${peopleCount}명, 진행 프로젝트 ${projectCount}건`,
          ),
        ),
      ).toBeInTheDocument();
      expect(screen.queryByTestId("sales-part-state-confirm")).toBeNull();
      expect(
        fetcher.mock.calls.some(([, init]) => init?.method === "POST"),
      ).toBe(false);
    },
  );
  it("cancel disable never sends request", async () => {
    render(<SalesPartManagement />);
    await openMenu("사용 중지");
    fireEvent.click(screen.getByTestId("sales-part-state-cancel"));
    expect(fetcher.mock.calls.some(([, init]) => init?.method === "POST")).toBe(
      false,
    );
  });
  it("confirm disable changes list and detail with preserved history", async () => {
    render(<SalesPartManagement />);
    await openMenu("사용 중지");
    expect(
      screen.getByText("중지하면 새 인력·프로젝트에 고를 수 없습니다."),
    ).toBeInTheDocument();
    expect(screen.getByTestId("sales-part-state-confirm").className).toContain(
      "danger",
    );
    fireEvent.click(screen.getByTestId("sales-part-state-confirm"));
    expect(await screen.findByText("사용을 중지했습니다.")).toBeInTheDocument();
    expect(screen.getByText("사용을 중지한 파트입니다.")).toBeInTheDocument();
    expect(await listSalesPartOptions()).not.toEqual(
      expect.arrayContaining([expect.objectContaining({ id: "2" })]),
    );
  });
  it("server PART_IN_USE replaces confirmation with latest counts", async () => {
    fetcher.mockResolvedValueOnce(
      failed(
        "영업 파트에 소속 인력 3명, 진행 프로젝트 0건이 있습니다. 먼저 다른 파트로 옮겨 주세요.",
        null,
        "PART_IN_USE",
        409,
      ),
    );
    render(
      <SalesPartStateDialog
        part={part}
        action="disable"
        reload={async () => {}}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId("sales-part-state-confirm"));
    expect(
      await screen.findByRole("heading", { name: "사용을 중지할 수 없습니다" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/소속 인력 3명/)).toBeInTheDocument();
    expect(screen.queryByTestId("sales-part-state-confirm")).toBeNull();
  });
  it("stopped part shows enable action and returns to options", async () => {
    render(<SalesPartManagement />);
    await openMenu("다시 사용", "중지 파트");
    expect(
      await screen.findByText("중지 파트을 다시 사용합니다."),
    ).toBeInTheDocument();
    expect((await listSalesPartOptions()).some((r) => r.id === "3")).toBe(true);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/sales-parts/3/enable",
      expect.objectContaining({ method: "POST" }),
    );
  });
  it("uncertain write recovery only re-reads and never replays a mutation", async () => {
    fetcher.mockRejectedValueOnce(new Error("network"));
    const reload = vi.fn(async () => {});
    render(
      <SalesPartStateDialog
        part={part}
        action="disable"
        reload={reload}
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId("sales-part-state-confirm"));
    await screen.findByText(/저장 결과를 다시 조회/);
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(reload).toHaveBeenCalledOnce());
    expect(fetcher).toHaveBeenCalledOnce();
  });
  it("failed list supports retry and empty results without invented data", async () => {
    fetcher.mockResolvedValueOnce(
      failed("조회 실패", null, "SERVER_ERROR", 500),
    );
    render(<SalesPartManagement />);
    await screen.findByText("조회 실패");
    rows = [];
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
  });
});

describe("independent sales part export", () => {
  const fileResponse = () => ({
    ok: true,
    headers: new Headers({
      "Content-Type":
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent("영업파트_20261022.xlsx")}`,
    }),
    blob: async () => ({
      slice: () => ({
        arrayBuffer: async () => new Uint8Array([80, 75]).buffer,
      }),
    }),
  });
  const download = () => {
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: vi.fn(() => "blob:export"),
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: vi.fn(),
    });
    return vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});
  };
  it("second of three pages without selection exports all, visible columns only, no page or sort", async () => {
    rows = Array.from({ length: 101 }, (_, i) => ({
      ...part,
      bsn_part_id: String(i + 1),
      bsn_part_nm: `파일 파트 ${i + 1}`,
    }));
    const click = download();
    render(<SalesPartManagement />);
    await screen.findByText("총 101건");
    fireEvent.click(screen.getByRole("button", { name: /^2$/ }));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("page=2"),
        expect.anything(),
      ),
    );
    fetcher.mockResolvedValueOnce(fileResponse());
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(
      await screen.findByText("엑셀 파일을 내려받았습니다."),
    ).toBeInTheDocument();
    const call = fetcher.mock.calls.find(([url]) =>
      url.startsWith("/api/sales-parts/export?"),
    );
    const query = new URL(call![0], "https://test.invalid").searchParams;
    expect(query.getAll("columns")).toEqual(salesPartExportColumns);
    expect([...query.keys()]).not.toEqual(
      expect.arrayContaining(["page", "size", "sort", "ids"]),
    );
    expect(click).toHaveBeenCalledOnce();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:export");
  });
  it("selection of two prompts scope and cancel makes no file request", async () => {
    render(<SalesPartManagement />);
    fireEvent.click(
      await screen.findByRole("checkbox", { name: "영업 파트 선택" }),
    );
    fireEvent.click(screen.getByRole("checkbox", { name: "중지 파트 선택" }));
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(screen.getByLabelText("선택 2건")).toBeChecked();
    expect(screen.getByLabelText("조회 전체 3건")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("export-cancel"));
    expect(fetcher.mock.calls.some(([url]) => url.includes("/export?"))).toBe(
      false,
    );
  });
  it("selected two rows send exploded ids and success is a real download action", async () => {
    const click = download();
    render(<SalesPartManagement />);
    fireEvent.click(
      await screen.findByRole("checkbox", { name: "영업 파트 선택" }),
    );
    fireEvent.click(screen.getByRole("checkbox", { name: "중지 파트 선택" }));
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    fetcher.mockResolvedValueOnce(fileResponse());
    fireEvent.click(screen.getByTestId("export-confirm"));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    const query = new URL(
      fetcher.mock.calls.find(([url]) => url.includes("/export?"))![0],
      "https://test.invalid",
    ).searchParams;
    expect(query.getAll("ids")).toEqual(["2", "3"]);
    expect(click).toHaveBeenCalledOnce();
  });
  it("choosing entire scope omits selected ids", async () => {
    download();
    render(<SalesPartManagement />);
    fireEvent.click(
      await screen.findByRole("checkbox", { name: "영업 파트 선택" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    fireEvent.click(screen.getByTestId("export-all"));
    fetcher.mockResolvedValueOnce(fileResponse());
    fireEvent.click(screen.getByTestId("export-confirm"));
    await screen.findByText("엑셀 파일을 내려받았습니다.");
    expect(
      fetcher.mock.calls.find(([url]) => url.includes("/export?"))![0],
    ).not.toContain("ids=");
  });
  it("file failure displays retry, not success", async () => {
    render(<SalesPartManagement />);
    await screen.findByText("총 3건");
    fireEvent.click(screen.getByRole("button", { name: "엑셀 다운로드" }));
    expect(
      await screen.findByText("파일을 생성하지 못했습니다."),
    ).toBeInTheDocument();
    expect(screen.queryByText("엑셀 파일을 내려받았습니다.")).toBeNull();
    expect(
      screen.getByRole("button", { name: "다시 시도" }),
    ).toBeInTheDocument();
  });
});
