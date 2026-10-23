import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import UI15Screen from "./UI15Screen";
import { SalesPartCreateDialog } from "./SalesPartCreateDialog";
import { SalesPartDetailDrawer } from "./SalesPartDetailDrawer";
import {
  salesPartCreationMetadata,
  type SalesPart,
  type SalesPartDetail,
  type SalesPartStandard,
} from "./client";

const current: SalesPartStandard = {
  part_std_hist_id: "2",
  aply_bgn_ym: "2026-10",
  aply_end_ym: "2026-10",
  ldr_nm: "홍길동",
  prft_dstrb_rt: 40,
  bsn_fee_amt: 1500000,
  status: "적용 중",
};
const future: SalesPartStandard = {
  ...current,
  part_std_hist_id: "3",
  aply_bgn_ym: "2026-11",
  aply_end_ym: null,
  prft_dstrb_rt: 45,
  status: "예정",
};
const part: SalesPart = {
  bsn_part_id: "42",
  bsn_part_nm: "영업 파트",
  hq_yn: "N",
  use_yn: "Y",
  sort_seq: 1,
  referenceMonth: "2026-10",
  currentStandard: current,
  scheduledChanges: [future],
  peopleCount: 0,
  projectCount: 0,
};
const hq: SalesPart = {
  ...part,
  bsn_part_id: "77",
  bsn_part_nm: "본사(직접 영업)",
  hq_yn: "Y",
  sort_seq: 999,
  scheduledChanges: [],
  currentStandard: {
    ...current,
    ldr_nm: null,
    bsn_fee_amt: 0,
    prft_dstrb_rt: 0,
  },
};
const detail = (row = part): SalesPartDetail => ({
  ...row,
  standards: [
    future,
    current,
    {
      ...current,
      part_std_hist_id: "4",
      aply_bgn_ym: "2026-01",
      aply_end_ym: "2026-09",
      status: "지난 기준",
    },
  ],
  lastClosedMonth: "2026-09",
  firstAllowedMonth: "2026-10",
});
const result = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const list = (items: SalesPart[] = [part, hq], total = items.length) => ({
  items,
  total,
  page: 1,
  size: 50,
});
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST")
      return result(detail({ ...part, bsn_part_nm: "신규 파트" }));
    if (url.startsWith("/api/sales-parts?")) return result(list());
    if (url === "/api/sales-parts/77") return result(detail(hq));
    if (url === "/api/sales-parts/42") return result(detail());
    if (url.startsWith("/api/params/"))
      return result({
        num_val: url.includes("PART_PROFIT_RATE") ? 40 : 1500000,
      });
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
const writes = () => fetcher.mock.calls.filter((c) => c[1]?.method === "POST");
const dialog = () =>
  render(
    <SalesPartCreateDialog
      page={list()}
      onCancel={vi.fn()}
      onSaved={vi.fn()}
    />,
  );
const ready = async () =>
  waitFor(() =>
    expect(screen.getByLabelText("영업비(원)")).toHaveValue("1500000"),
  );

describe("SCR-UI-15 sales parts", () => {
  it("shows the nine API-backed columns, zero counts, HQ and future values without upload controls", async () => {
    render(<UI15Screen onPartners={vi.fn()} />);
    await screen.findByRole("button", { name: "영업 파트" });
    expect(screen.getAllByRole("columnheader")).toHaveLength(9);
    expect(screen.getByText("2026-11부터 45%")).toBeVisible();
    expect(screen.getByText("40%")).toBeVisible();
    expect(screen.getAllByText("0명")).toHaveLength(2);
    expect(screen.getAllByText("0건")).toHaveLength(2);
    expect(screen.getByText("본사")).toBeVisible();
    expect(screen.getByLabelText("목록 건수")).toHaveValue("50");
    expect(
      screen.getByText(/지난 달 정산은 바뀌지 않습니다/),
    ).toHaveTextContent(
      "기준 변경은 시스템 관리자·재무 담당만 할 수 있습니다.",
    );
    expect(screen.queryByText("양식 내려받기")).not.toBeInTheDocument();
    expect(screen.queryByText("엑셀 업로드")).not.toBeInTheDocument();
    expect(fetcher.mock.calls[0][0]).toBe("/api/sales-parts?page=1&size=50");
  });
  it("sorts server-side and does not make the scheduled column sortable", async () => {
    render(<UI15Screen />);
    await screen.findByRole("button", { name: "파트명" });
    expect(
      within(
        screen.getByRole("columnheader", { name: "예정된 변경" }),
      ).queryByRole("button"),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "파트명" }));
    await waitFor(() =>
      expect(fetcher.mock.calls.at(-1)?.[0]).toContain(
        "sort=bsn_part_nm%2Casc",
      ),
    );
    fireEvent.change(screen.getByLabelText("목록 건수"), {
      target: { value: "20" },
    });
    await waitFor(() =>
      expect(fetcher.mock.calls.at(-1)?.[0]).toContain("page=1&size=20"),
    );
  });
  it("allows stopped detail, shows period/status/closing notice and closes with Escape", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      url === "/api/sales-parts/42"
        ? result(detail({ ...part, use_yn: "N" }))
        : original(url, init),
    );
    render(<UI15Screen />);
    fireEvent.click(await screen.findByRole("button", { name: "영업 파트" }));
    const drawer = await screen.findByRole("dialog");
    await within(drawer).findByText("사용을 중지한 파트입니다.");
    expect(within(drawer).getByText("2026-10 기준")).toBeVisible();
    expect(within(drawer).getByText("2026-11 ~ 현재")).toBeVisible();
    expect(within(drawer).getByText("지난 기준")).toBeVisible();
    expect(
      within(drawer).getByText(/마감된 달\(2026-09\)까지의 정산/),
    ).toHaveTextContent("새 기준은 2026-10부터 넣을 수 있습니다.");
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("never renders operation menus for HQ even if an operation renderer was supplied", async () => {
    const actions = vi.fn(() => <button>기준 변경</button>);
    render(
      <SalesPartDetailDrawer
        id="77"
        onClose={vi.fn()}
        renderActions={actions}
      />,
    );
    await screen.findByText("2026-10 기준");
    expect(actions).not.toHaveBeenCalled();
    expect(screen.queryByText("기준 변경")).not.toBeInTheDocument();
    expect(screen.queryByText("파트명 수정")).not.toBeInTheDocument();
    expect(screen.queryByText("사용 중지")).not.toBeInTheDocument();
  });
  it("passes the selected non-HQ detail to the operation renderer", async () => {
    const actions = vi.fn((p: SalesPartDetail) => (
      <button data-testid="standard-change">{p.bsn_part_nm} 기준 변경</button>
    ));
    render(
      <SalesPartDetailDrawer
        id="42"
        onClose={vi.fn()}
        renderActions={actions}
      />,
    );
    expect(await screen.findByText("영업 파트 기준 변경")).toBeInTheDocument();
    expect(actions.mock.calls[0][0].bsn_part_id).toBe("42");
  });
  it("keeps missing current standard empty rather than showing a future value", async () => {
    fetcher.mockResolvedValue(
      result(detail({ ...part, currentStandard: null })),
    );
    render(<SalesPartDetailDrawer id="42" onClose={vi.fn()} />);
    expect(await screen.findByText("현재 적용 기준 없음")).toBeVisible();
    expect(screen.getByText("예정")).toBeVisible();
  });
  it("shows empty-list feedback", async () => {
    fetcher.mockResolvedValue(result(list([], 0)));
    render(<UI15Screen />);
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeVisible();
  });
  it("shows failed request feedback and retries with a GET", async () => {
    fetcher.mockRejectedValueOnce(new Error("offline"));
    render(<UI15Screen />);
    fireEvent.click(await screen.findByText("다시 시도"));
    expect(
      await screen.findByRole("button", { name: "영업 파트" }),
    ).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("uses the responsive write-actions token without hiding the read surface", async () => {
    render(<UI15Screen />);
    await screen.findByRole("button", { name: "파트 추가" });
    expect(
      screen.getByRole("button", { name: "파트 추가" }).parentElement
        ?.className,
    ).toContain("writeActions");
    expect(screen.getByTestId("sales-parts-screen")).not.toHaveClass(
      "desktopOnly",
    );
  });
});

describe("SCR-UI-15-M7 create", () => {
  it("uses the real HQ id for closing metadata, narrow text fields, and monthly operating defaults", async () => {
    dialog();
    await ready();
    expect(fetcher.mock.calls.some((c) => c[0] === "/api/sales-parts/77")).toBe(
      true,
    );
    expect(screen.getByLabelText("파트명")).toHaveAttribute("maxlength", "30");
    expect(screen.getByLabelText("파트장")).toHaveAttribute("maxlength", "50");
    expect(screen.getByLabelText("적용 시작월")).toHaveValue("2026-10");
    expect(screen.getByLabelText("적용 시작월")).toHaveAttribute(
      "min",
      "2026-10",
    );
    expect(
      screen.getByText("마감된 달(2026-09) 다음 달부터 고를 수 있습니다."),
    ).toBeVisible();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    change("적용 시작월", "2026-11");
    await ready();
    expect(
      fetcher.mock.calls.some(
        (c) => c[0] === "/api/params/PART_PROFIT_RATE?month=2026-11",
      ),
    ).toBe(true);
    expect(
      fetcher.mock.calls.some(
        (c) => c[0] === "/api/params/PART_SALES_FEE?month=2026-11",
      ),
    ).toBe(true);
  });
  it("walks real list pages to locate HQ rather than hardcoding an id", async () => {
    fetcher.mockImplementation(async (url: string) =>
      url.startsWith("/api/sales-parts?")
        ? result(url.includes("page=1") ? list([part], 51) : list([hq], 51))
        : result(detail(hq)),
    );
    expect(
      (await salesPartCreationMetadata(list([part], 51))).bsn_part_id,
    ).toBe("77");
    expect(
      fetcher.mock.calls.some(
        (c) => c[0] === "/api/sales-parts?page=2&size=50",
      ),
    ).toBe(true);
  });
  it("shows retryable metadata error when HQ is absent and sends no POST", async () => {
    fetcher.mockResolvedValue(result(list([part], 1)));
    render(
      <SalesPartCreateDialog
        page={list([part], 1)}
        onCancel={vi.fn()}
        onSaved={vi.fn()}
      />,
    );
    expect(
      await screen.findByText(/본사 파트를 찾을 수 없습니다/),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "추가" })).toBeDisabled();
    expect(writes()).toHaveLength(0);
  });
  it.each([
    ["파트명", "", "파트명을 입력해 주세요."],
    ["파트장", "", "파트장을 입력해 주세요."],
    ["이익 배분율(%)", "120", "이익 배분율은 0~100 사이 숫자로 입력해 주세요."],
    [
      "이익 배분율(%)",
      "40.001",
      "이익 배분율은 0~100 사이 숫자로 입력해 주세요.",
    ],
    ["영업비(원)", "0", "영업비를 입력해 주세요."],
    ["적용 시작월", "2026-09", "마감된 달 다음 달부터 고를 수 있습니다."],
  ])("validates %s without writing", async (label, value, message) => {
    dialog();
    await ready();
    change("파트명", "신규 파트");
    change("파트장", "홍길동");
    change(label, value);
    fireEvent.click(screen.getByRole("button", { name: "추가" }));
    expect((await screen.findAllByText(message)).length).toBeGreaterThan(0);
    expect(writes()).toHaveLength(0);
  });
  it.each(["0", "100"])(
    "accepts rate boundary %s and sends only the five input fields",
    async (rate) => {
      const saved = vi.fn();
      render(
        <SalesPartCreateDialog
          page={list()}
          onCancel={vi.fn()}
          onSaved={saved}
        />,
      );
      await ready();
      change("파트명", "가".repeat(30));
      change("파트장", "홍길동");
      change("이익 배분율(%)", rate);
      fireEvent.click(screen.getByRole("button", { name: "추가" }));
      await waitFor(() => expect(saved).toHaveBeenCalledOnce());
      expect(JSON.parse(writes()[0][1].body as string)).toEqual({
        bsn_part_nm: "가".repeat(30),
        aply_bgn_ym: "2026-10",
        ldr_nm: "홍길동",
        prft_dstrb_rt: Number(rate),
        bsn_fee_amt: 1500000,
      });
    },
  );
  it("shows singular field errors while preserving the draft", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      init?.method === "POST"
        ? new Response(
            JSON.stringify({
              success: false,
              code: "DUPLICATE_PART_NAME",
              field: "bsn_part_nm",
              message: "같은 이름의 파트가 이미 있습니다.",
              requestId: "part-request",
            }),
            { status: 409 },
          )
        : original(url, init),
    );
    dialog();
    await ready();
    change("파트명", "신규 파트");
    change("파트장", "홍길동");
    fireEvent.click(screen.getByRole("button", { name: "추가" }));
    await screen.findByText("문의 번호 part-request");
    expect(screen.getByLabelText("파트명")).toHaveValue("신규 파트");
    expect(screen.getByLabelText("파트명")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });
  it("protects dirty cancel and never sends a write for continue or close", async () => {
    const cancel = vi.fn();
    render(
      <SalesPartCreateDialog
        page={list()}
        onCancel={cancel}
        onSaved={vi.fn()}
      />,
    );
    await ready();
    change("파트명", "작성 중");
    fireEvent.click(screen.getByText("취소"));
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByLabelText("파트명")).toHaveValue("작성 중");
    expect(cancel).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.queryByText("계속 작성")).not.toBeInTheDocument(),
    );
    fireEvent.click(screen.getByText("취소"));
    fireEvent.click(screen.getByRole("button", { name: "닫기" }));
    expect(cancel).toHaveBeenCalledOnce();
    expect(writes()).toHaveLength(0);
  });
  it("successful create closes dialog, refreshes the list, and displays the exact success message", async () => {
    render(<UI15Screen />);
    fireEvent.click(await screen.findByRole("button", { name: "파트 추가" }));
    await ready();
    change("파트명", "신규 파트");
    change("파트장", "홍길동");
    fireEvent.click(screen.getByRole("button", { name: "추가" }));
    expect(await screen.findByText("신규 파트을 추가했습니다.")).toBeVisible();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await waitFor(() =>
      expect(
        fetcher.mock.calls.filter((c) =>
          String(c[0]).startsWith("/api/sales-parts?"),
        ).length,
      ).toBe(2),
    );
  });
  it("does not replay an uncertain POST", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === "POST") throw new Error("offline");
      return original(url, init);
    });
    dialog();
    await ready();
    change("파트명", "신규 파트");
    change("파트장", "홍길동");
    fireEvent.click(screen.getByRole("button", { name: "추가" }));
    await screen.findByText(
      "저장 결과를 다시 조회한 뒤 확인하고 직접 저장해 주세요.",
    );
    expect(screen.getByRole("button", { name: "추가" })).toBeDisabled();
    expect(writes()).toHaveLength(1);
  });
});
