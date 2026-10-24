import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import SalesPartsTab, { SalesPartDetailPanel } from "./SalesPartsTab";
import { SalesPartCreateDialog } from "./SalesPartCreateDialog";
import {
  createSalesPart,
  getSalesPart,
  listSalesParts,
  type SalesPartDetail,
  type SalesPartStandard,
} from "./client";
import { useSharedPreferences } from "../shared/preferences";
import { ApiFailure } from "../../shared/api";

const current: SalesPartStandard = {
  part_std_hist_id: "20",
  aply_bgn_ym: "2026-10",
  aply_end_ym: "2026-10",
  ldr_nm: "파트장",
  prft_dstrb_rt: 40,
  bsn_fee_amt: 1500000,
  status: "적용 중",
};
const future: SalesPartStandard = {
  ...current,
  part_std_hist_id: "21",
  aply_bgn_ym: "2026-11",
  aply_end_ym: null,
  prft_dstrb_rt: 45,
  status: "예정",
};
const past: SalesPartStandard = {
  ...current,
  part_std_hist_id: "19",
  aply_bgn_ym: "2026-01",
  aply_end_ym: "2026-09",
  status: "지난 기준",
};
const part: SalesPartDetail = {
  bsn_part_id: "2",
  bsn_part_nm: "영업 파트",
  hq_yn: "N",
  use_yn: "Y",
  sort_seq: 1,
  referenceMonth: "2026-10",
  currentStandard: current,
  scheduledChanges: [future],
  peopleCount: 0,
  projectCount: 0,
  standards: [future, current, past],
  lastClosedMonth: "2026-09",
  firstAllowedMonth: "2026-10",
};
const hq: SalesPartDetail = {
  ...part,
  bsn_part_id: "1",
  bsn_part_nm: "본사(직접 영업)",
  hq_yn: "Y",
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const failed = (
  field: string | null = null,
  code = "INVALID_INPUT",
  status = 400,
) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code,
      message: "입력 오류",
      field,
      requestId: "server-request",
    }),
    { status },
  );
let fetcher: ReturnType<typeof vi.fn>;
let rows: SalesPartDetail[];
beforeEach(() => {
  localStorage.clear();
  useSharedPreferences.setState({
    favorites: [],
    recent: [],
    collapsed: false,
    pageSize: 50,
    dense: false,
  });
  rows = [
    hq,
    part,
    { ...part, bsn_part_id: "3", bsn_part_nm: "중지 파트", use_yn: "N" },
  ];
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/params/"))
      return response({
        num_val: url.includes("PART_PROFIT_RATE") ? 40 : 1500000,
      });
    if (url === "/api/sales-parts" && init?.method === "POST") {
      const draft = JSON.parse(init.body as string);
      const created = {
        ...part,
        bsn_part_id: "4",
        bsn_part_nm: draft.bsn_part_nm,
      };
      rows = [...rows, created];
      return response(created);
    }
    if (url.startsWith("/api/sales-parts?"))
      return response({ items: rows, total: rows.length, page: 1, size: 50 });
    if (url.startsWith("/api/sales-parts/"))
      return response(
        rows.find((r) => url.endsWith(`/${r.bsn_part_id}`)) ?? part,
      );
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
const openAdd = async () => {
  fireEvent.click(await screen.findByRole("button", { name: "+ 파트 추가" }));
  await screen.findByRole("dialog", { name: "영업 파트 추가" });
  await waitFor(() =>
    expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue(40),
  );
};
const submit = () =>
  fireEvent.submit(screen.getByTestId("sales-part-create-dialog"));
const dialog = (firstAllowedMonth: string | null = "2026-10") =>
  render(
    <SalesPartCreateDialog
      firstAllowedMonth={firstAllowedMonth}
      reload={vi.fn(async () => {})}
      onCancel={vi.fn()}
      onSaved={vi.fn()}
    />,
  );

describe("영업 파트 목록·상세", () => {
  it("shows guidance, inactive parts, zero counts and no upload controls", async () => {
    render(<SalesPartsTab />);
    expect(
      await screen.findByRole("button", { name: "본사(직접 영업)" }),
    ).toBeVisible();
    expect(screen.getByText("중지 파트")).toBeVisible();
    expect(screen.getByText(/지난 달 정산은 바뀌지 않습니다/)).toBeVisible();
    expect(
      screen.getByText(/기준 변경은 시스템 관리자·재무 담당만/),
    ).toBeVisible();
    expect(screen.getAllByText("0명")).toHaveLength(3);
    expect(screen.getAllByText("0건")).toHaveLength(3);
    expect(
      screen.queryByRole("button", { name: /업로드|양식/ }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("총 3건")).toBeVisible();
  });
  it("requests default 50 and toggles allowed sorting but never scheduled sorting", async () => {
    render(<SalesPartsTab />);
    await screen.findByText("총 3건");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/sales-parts?page=1&size=50",
      expect.anything(),
    );
    expect(screen.queryByRole("button", { name: "예정된 변경" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "파트명" }));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("sort=bsn_part_nm%2Casc"),
        expect.anything(),
      ),
    );
    fireEvent.click(screen.getByRole("button", { name: "파트명 ↑" }));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("sort=bsn_part_nm%2Cdesc"),
        expect.anything(),
      ),
    );
    fireEvent.change(screen.getByLabelText("목록 건수"), {
      target: { value: "20" },
    });
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        expect.stringContaining("page=1&size=20"),
        expect.anything(),
      ),
    );
  });
  it("row opens current-month detail and descending history with distinct badges", async () => {
    render(<SalesPartsTab onAction={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: "영업 파트" }));
    const drawer = await screen.findByRole("dialog", {
      name: "영업 파트 상세",
    });
    await within(drawer).findByText("2026-10 기준");
    const historyRows = within(drawer).getAllByTestId(/sales-part-standard-/);
    expect(historyRows.map((r) => r.textContent)).toEqual([
      expect.stringContaining("2026-11 ~ 현재"),
      expect.stringContaining("2026-10 ~ 2026-10"),
      expect.stringContaining("2026-01 ~ 2026-09"),
    ]);
    expect(within(drawer).getByText("예정").className).toContain("warning");
    expect(within(drawer).getByText("적용 중").className).toContain("success");
    expect(within(drawer).getByText("지난 기준").className).toContain("locked");
  });
  it("HQ hides all mutation actions", async () => {
    render(<SalesPartsTab onAction={vi.fn()} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "본사(직접 영업)" }),
    );
    const drawer = await screen.findByRole("dialog", {
      name: "영업 파트 상세",
    });
    await within(drawer).findByText("2026-10 기준");
    expect(within(drawer).getByText("본사")).toBeVisible();
    expect(
      within(drawer).queryByRole("button", { name: "기준 변경" }),
    ).toBeNull();
    expect(
      within(drawer).queryByRole("button", { name: "파트 더보기" }),
    ).toBeNull();
  });
  it("inactive detail shows banner and again-use action delegated with selected identity", async () => {
    const onAction = vi.fn();
    render(<SalesPartsTab onAction={onAction} />);
    fireEvent.click(await screen.findByRole("button", { name: "중지 파트" }));
    const drawer = await screen.findByRole("dialog", {
      name: "영업 파트 상세",
    });
    await within(drawer).findByText("사용을 중지한 파트입니다.");
    fireEvent.click(
      within(drawer).getByRole("button", { name: "파트 더보기" }),
    );
    fireEvent.click(
      within(drawer).getByRole("menuitem", { name: "다시 사용" }),
    );
    expect(onAction).toHaveBeenCalledWith(
      "enable",
      expect.objectContaining({ bsn_part_id: "3" }),
      expect.any(Function),
    );
  });
  it("delegates standard, name and disable without changing HQ rules", async () => {
    const onAction = vi.fn();
    render(<SalesPartsTab onAction={onAction} />);
    fireEvent.click(await screen.findByRole("button", { name: "영업 파트" }));
    const drawer = await screen.findByRole("dialog", {
      name: "영업 파트 상세",
    });
    await within(drawer).findByText("2026-10 기준");
    fireEvent.click(within(drawer).getByRole("button", { name: "기준 변경" }));
    expect(onAction).toHaveBeenLastCalledWith(
      "standards",
      expect.objectContaining({ bsn_part_id: "2" }),
      expect.any(Function),
    );
    await act(async () => {
      fireEvent.click(
        within(drawer).getByRole("button", { name: "파트 더보기" }),
      );
    });
    await act(async () => {
      fireEvent.click(
        within(drawer).getByRole("menuitem", { name: "파트명 수정" }),
      );
    });
    expect(onAction).toHaveBeenLastCalledWith(
      "name",
      expect.anything(),
      expect.any(Function),
    );
    await act(async () => {
      fireEvent.click(
        within(drawer).getByRole("button", { name: "파트 더보기" }),
      );
    });
    await act(async () => {
      fireEvent.click(
        within(drawer).getByRole("menuitem", { name: "사용 중지" }),
      );
    });
    expect(onAction).toHaveBeenLastCalledWith(
      "disable",
      expect.anything(),
      expect.any(Function),
    );
  });
  it("Escape closes the drawer", async () => {
    render(<SalesPartsTab />);
    fireEvent.click(await screen.findByRole("button", { name: "영업 파트" }));
    fireEvent.keyDown(await screen.findByRole("dialog"), { key: "Escape" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });
  it("empty results stay empty, with retry after failed request", async () => {
    fetcher.mockResolvedValueOnce(failed());
    render(<SalesPartsTab />);
    expect(await screen.findByRole("alert")).toHaveTextContent("입력 오류");
    rows = [];
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeVisible();
  });
  it("no effective standard is not replaced by future values", () => {
    render(
      <SalesPartDetailPanel
        part={{ ...part, currentStandard: null }}
        loading={false}
        error={null}
        onRetry={vi.fn()}
        onClose={vi.fn()}
        refresh={async () => {}}
      />,
    );
    expect(screen.getAllByText("–").length).toBeGreaterThan(0);
    expect(screen.getByText(/새 기준은 2026-10부터/)).toBeVisible();
  });
  it("detail error renders retry and does not show stale data", () => {
    const retry = vi.fn();
    render(
      <SalesPartDetailPanel
        part={null}
        loading={false}
        error={new ApiFailure("조회 실패", 500, null)}
        onRetry={retry}
        onClose={vi.fn()}
        refresh={async () => {}}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(retry).toHaveBeenCalledOnce();
  });
  it("shows the last closed month and next month without inventing bounds", () => {
    const props = {
      loading: false,
      error: null,
      onRetry: vi.fn(),
      onClose: vi.fn(),
      refresh: async () => {},
    };
    const view = render(
      <SalesPartDetailPanel
        {...props}
        part={{
          ...part,
          lastClosedMonth: "2026-12",
          firstAllowedMonth: "2027-01",
        }}
      />,
    );
    expect(screen.getByText(/마감된 달\(2026-12\)/)).toHaveTextContent(
      /새 기준은 2027-01부터 넣을 수 있습니다/,
    );
    view.rerender(
      <SalesPartDetailPanel
        {...props}
        part={{
          ...part,
          lastClosedMonth: null,
          firstAllowedMonth: null,
        }}
      />,
    );
    expect(
      screen.getByText(
        "마감 이력이 없습니다. 적용 시작월을 직접 선택할 수 있습니다.",
      ),
    ).toBeVisible();
    expect(screen.queryByText(/마감된 달/)).toBeNull();
  });
  it("does not offer stale mutation actions while detail reloads or fails", () => {
    const onAction = vi.fn();
    const props = {
      part,
      loading: false,
      error: null,
      onAction,
      onRetry: vi.fn(),
      onClose: vi.fn(),
      refresh: async () => {},
    };
    const view = render(<SalesPartDetailPanel {...props} />);
    fireEvent.click(screen.getByRole("button", { name: "파트 더보기" }));
    expect(screen.getByRole("menuitem", { name: "사용 중지" })).toBeVisible();
    view.rerender(<SalesPartDetailPanel {...props} loading />);
    expect(screen.queryByRole("button", { name: "기준 변경" })).toBeNull();
    expect(screen.queryByRole("menu")).toBeNull();
    view.rerender(
      <SalesPartDetailPanel
        {...props}
        error={new ApiFailure("조회 실패", 500, null)}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("조회 실패");
    expect(screen.queryByRole("button", { name: "파트 더보기" })).toBeNull();
    expect(screen.queryByText("기준 이력")).toBeNull();
    view.rerender(<SalesPartDetailPanel {...props} />);
    fireEvent.click(screen.getByRole("button", { name: "파트 더보기" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "파트명 수정" }));
    expect(onAction).toHaveBeenCalledWith("name", part, props.refresh);
  });
  it("shows a detail request failure and retries the selected part", async () => {
    render(<SalesPartsTab onAction={vi.fn()} />);
    await screen.findByText("총 3건");
    fetcher.mockResolvedValueOnce(failed(null, "NOT_FOUND", 404));
    fireEvent.click(screen.getByRole("button", { name: "영업 파트" }));
    const drawer = await screen.findByRole("dialog", {
      name: "영업 파트 상세",
    });
    expect(await within(drawer).findByRole("alert")).toHaveTextContent(
      "입력 오류",
    );
    expect(within(drawer).queryByText("기준 이력")).toBeNull();
    fireEvent.click(within(drawer).getByRole("button", { name: "다시 시도" }));
    await within(drawer).findByText("2026-10 기준");
    expect(
      within(drawer).getByRole("button", { name: "기준 변경" }),
    ).toBeEnabled();
    expect(
      fetcher.mock.calls.filter(([url]) => url === "/api/sales-parts/2"),
    ).toHaveLength(2);
  });
  it("keeps future-only list values separate and renders no invented current values", async () => {
    rows = [{ ...part, currentStandard: null, standards: [future] }];
    render(<SalesPartsTab />);
    const row = await screen.findByTestId("sales-part-row-2");
    expect(within(row).getByText("2026-11부터 45%")).toBeVisible();
    expect(within(row).queryByText("파트장")).toBeNull();
    expect(within(row).queryByText("40%")).toBeNull();
    expect(within(row).getAllByText("–")).toHaveLength(4);
    expect(within(row).getByText("0명")).toBeVisible();
    expect(within(row).getByText("0건")).toBeVisible();
  });
});

describe("영업 파트 추가", () => {
  it("loads closing metadata through HQ detail before monthly defaults", async () => {
    render(<SalesPartsTab />);
    await openAdd();
    expect(fetcher).toHaveBeenCalledWith(
      "/api/sales-parts/1",
      expect.anything(),
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/params/PART_PROFIT_RATE?month=2026-10",
      expect.anything(),
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/params/PART_SALES_FEE?month=2026-10",
      expect.anything(),
    );
    expect(screen.getByLabelText("적용 시작월")).toHaveAttribute(
      "min",
      "2026-10",
    );
    expect(screen.getByLabelText("파트명")).toHaveAttribute("maxlength", "30");
    expect(screen.getByLabelText("파트장")).toHaveAttribute("maxlength", "50");
    expect(
      within(
        screen.getByRole("dialog", { name: "영업 파트 추가" }),
      ).queryByRole("combobox"),
    ).toBeNull();
  });
  it("creates exact approved fields and refreshes visible list", async () => {
    render(<SalesPartsTab />);
    await openAdd();
    change("파트명", "신규 파트");
    change("파트장", "파트장");
    fireEvent.click(screen.getByRole("button", { name: /^추가$/ }));
    expect(await screen.findByText("신규 파트을 추가했습니다.")).toBeVisible();
    expect(
      await screen.findByRole("button", { name: "신규 파트" }),
    ).toBeVisible();
    const sent = fetcher.mock.calls.find(
      ([url, init]) => url === "/api/sales-parts" && init?.method === "POST",
    );
    expect(JSON.parse(sent![1].body)).toEqual({
      bsn_part_nm: "신규 파트",
      aply_bgn_ym: "2026-10",
      ldr_nm: "파트장",
      prft_dstrb_rt: 40,
      bsn_fee_amt: 1500000,
    });
  });
  it("requires explicit month without closing history", () => {
    dialog(null);
    expect(screen.getByLabelText("적용 시작월")).toHaveValue("");
    expect(screen.getByLabelText("적용 시작월")).not.toHaveAttribute("min");
    expect(screen.getByText(/마감 이력이 없습니다/)).toBeVisible();
  });
  it("missing HQ never guesses a closing month", async () => {
    rows = [part];
    render(<SalesPartsTab />);
    fireEvent.click(await screen.findByRole("button", { name: "+ 파트 추가" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "본사 파트를 찾을 수 없습니다",
    );
    expect(screen.queryByRole("dialog")).toBeNull();
  });
  it.each([
    ["파트명", "", "파트명을 입력해 주세요."],
    ["파트장", "", "파트장을 입력해 주세요."],
    [
      "적용 시작월",
      "2026-09",
      "적용 시작월은 마감된 달 다음 달부터 고를 수 있습니다.",
    ],
    [
      "이익 배분율 (%)",
      "120",
      "이익 배분율은 0~100 사이 숫자로 입력해 주세요.",
    ],
    [
      "이익 배분율 (%)",
      "1.001",
      "이익 배분율은 0~100 사이 숫자로 입력해 주세요.",
    ],
    ["영업비 (원)", "0", "영업비를 입력해 주세요."],
  ])("validates %s without writing", async (label, value, message) => {
    dialog();
    await waitFor(() =>
      expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue(40),
    );
    change("파트명", "새 파트");
    change("파트장", "파트장");
    change(label, value);
    if (label === "적용 시작월")
      await waitFor(() =>
        expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue(40),
      );
    submit();
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(fetcher.mock.calls.some(([, init]) => init?.method === "POST")).toBe(
      false,
    );
  });
  it("parameter failure allows manual required values, no invented defaults", async () => {
    fetcher
      .mockResolvedValueOnce(failed(null, "NOT_FOUND", 404))
      .mockResolvedValueOnce(failed(null, "NOT_FOUND", 404));
    dialog();
    await screen.findByText(/기준값을 다시 조회하거나/);
    expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue(null);
    expect(screen.getByLabelText("영업비 (원)")).toHaveValue(null);
    change("파트명", "직접 파트");
    change("파트장", "파트장");
    change("이익 배분율 (%)", "0");
    change("영업비 (원)", "1");
    submit();
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([, init]) => init?.method === "POST"),
      ).toBe(true),
    );
  });
  it("late previous-month defaults never overwrite selected month or allow early submit", async () => {
    let complete: (value: Response) => void = () => {};
    const delayed = new Promise<Response>((resolve) => {
      complete = resolve;
    });
    fetcher.mockImplementation((url: string) =>
      url.includes("month=2026-10")
        ? delayed
        : Promise.resolve(
            response({
              num_val: url.includes("PART_PROFIT_RATE") ? 45 : 1600000,
            }),
          ),
    );
    dialog();
    change("적용 시작월", "2026-11");
    await waitFor(() =>
      expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue(45),
    );
    await act(async () => {
      complete(response({ num_val: 99 }));
    });
    expect(screen.getByLabelText("이익 배분율 (%)")).toHaveValue(45);
    expect(screen.getByLabelText("영업비 (원)")).toHaveValue(1600000);
  });
  it("server field errors preserve input and focus the field", async () => {
    render(<SalesPartsTab />);
    await openAdd();
    change("파트명", "중복 파트");
    change("파트장", "파트장");
    fetcher.mockResolvedValueOnce(
      failed("bsn_part_nm", "DUPLICATE_PART_NAME", 409),
    );
    submit();
    await waitFor(() => expect(screen.getByLabelText("파트명")).toHaveFocus());
    expect(screen.getByLabelText("파트명")).toHaveValue("중복 파트");
    expect(screen.getByLabelText("파트장")).toHaveValue("파트장");
  });
  it("cancel never creates a part", async () => {
    render(<SalesPartsTab />);
    await openAdd();
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(fetcher.mock.calls.some(([, init]) => init?.method === "POST")).toBe(
      false,
    );
  });
});

describe("sales part client contract", () => {
  it("encodes selected identifiers and query using relative URLs", async () => {
    await listSalesParts(2, 20, "peopleCount,desc");
    await getSalesPart("2");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/sales-parts?page=2&size=20&sort=peopleCount%2Cdesc",
      expect.anything(),
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/sales-parts/2",
      expect.anything(),
    );
    const result = await createSalesPart({
      bsn_part_nm: "신규",
      aply_bgn_ym: "2026-11",
      ldr_nm: "파트장",
      prft_dstrb_rt: 100,
      bsn_fee_amt: 1,
    });
    expect(result.bsn_part_nm).toBe("신규");
  });
});
