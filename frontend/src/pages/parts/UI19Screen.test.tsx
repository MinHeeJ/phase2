import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI19Screen from "./UI19Screen";
import type { PartSettlementDetail, PartSettlementRow } from "./client";
import table from "../developers/Developers.module.css";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let detail: PartSettlementDetail, row: PartSettlementRow;
let fetcher: ReturnType<typeof vi.fn>;
let failWrite: boolean, failRead: boolean, empty: boolean;
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-29T00:00:00Z"));
  sessionStorage.clear();
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  failWrite = false;
  failRead = false;
  empty = false;
  row = {
    bsn_part_id: 7,
    bsn_part_nm: "영업파트",
    ldr_nm: "파트장",
    prft_dstrb_rt: 40,
    psn_cnt: 1,
    tot_diff_amt: -20,
    tot_psn_prft_amt: -8,
    corp_card_amt: 10,
    part_prft_amt: -18,
    stlm_stts_cd: "DRAFT",
    provisional: true,
  };
  detail = {
    month: "2026-10",
    status: "OPEN",
    referenceMonth: "2026-10",
    unit: "KRW",
    warnings: [],
    provisional: true,
    confirmation_allowed: false,
    bsn_part_nm: "영업파트",
    ldr_nm: "파트장",
    note: "기존 비고",
    settlement: {
      stlm_id: 31,
      bsn_part_id: 7,
      stlm_ym: "2026-10",
      part_std_hist_id: 9,
      prft_dstrb_rt: 40,
      bsn_fee_amt: 0,
      psn_cnt: 1,
      tot_sls_amt: 80,
      tot_exp_amt: 100,
      tot_diff_amt: -20,
      tot_psn_prft_amt: -8,
      corp_card_amt: 10,
      part_prft_amt: -18,
      stlm_stts_cd: "DRAFT",
      cnfrm_at: null,
    },
    items: [
      {
        dev_id: 11,
        dev_nm: "정산인력",
        emp_se_cd: "FL",
        projects: "프로젝트",
        mm_qty: 1,
        mth_untprc_amt: 80,
        slry_amt: 100,
        bsn_fee_amt: 0,
        rtrpay_amt: 0,
        tot_exp_amt: 100,
        diff_amt: -20,
        prft_amt: -8,
        rmrk_desc: "영업비",
      },
    ],
    standards: [
      {
        part_std_hist_id: 9,
        aply_bgn_ym: "2026-01",
        aply_end_ym: null,
        ldr_nm: "파트장",
        prft_dstrb_rt: 40,
        bsn_fee_amt: 0,
      },
    ],
    history: [],
    months: [{ month: "2026-10", stlm_stts_cd: "CONFIRM", provisional: false }],
    trend: ["MM", "PRICE", "COST", "DIFFERENCE", "PROFIT"].map((metric, i) => ({
      dev_id: 11,
      dev_nm: "정산인력",
      metric,
      values: [i === 0 ? 1 : i === 4 ? -8 : 80],
      total: i === 0 ? 1 : i === 4 ? -8 : 80,
    })),
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    const parsed = new URL(url, "https://test.invalid");
    if (init?.method && init.method !== "GET") {
      if (failWrite)
        return new Response(
          JSON.stringify({
            success: false,
            code: "CLOSED_MONTH",
            field: "corp_card_amt",
            message: "마감으로 변경되었습니다.",
            requestId: "settlement-request",
          }),
          { status: 409 },
        );
      const body = init.body ? JSON.parse(String(init.body)) : {};
      if (parsed.pathname.endsWith("/card")) {
        detail.settlement.corp_card_amt = body.corp_card_amt;
        detail.note = body.note;
      }
      if (parsed.pathname.endsWith("/confirm")) {
        detail.settlement.stlm_stts_cd = "CONFIRM";
        row.stlm_stts_cd = "CONFIRM";
      }
      if (parsed.pathname.endsWith("/cancel")) {
        detail.settlement.stlm_stts_cd = "DRAFT";
        row.stlm_stts_cd = "DRAFT";
      }
      return response(detail);
    }
    if (failRead) throw new Error("offline");
    if (parsed.pathname === "/api/settlements/parts")
      return response({
        month: parsed.searchParams.get("month"),
        status: detail.status,
        referenceMonth: "2026-10",
        unit: "KRW",
        warnings: [],
        items: empty ? [] : [row],
        total: empty ? 0 : 1,
        page: 0,
        size: 50,
        summary: { part_count: 1, confirmed_count: 0, part_prft_amt: -18 },
      });
    if (parsed.pathname === "/api/settlements/parts/7")
      return response({ ...detail, month: parsed.searchParams.get("month") });
    throw new Error(`Unexpected API: ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  vi.restoreAllMocks();
});
const open = async () => {
  render(
    <MemoryRouter>
      <UI19Screen />
    </MemoryRouter>,
  );
  await screen.findByTestId("settlement-detail-panel");
};
describe("SCR-UI-19 영업 파트 이익금", () => {
  it("renders monthly data and approval gate, reusing developer table classes", async () => {
    await open();
    expect(
      screen.getByRole("heading", { name: "영업 파트 이익금" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/D-31\/D-32 운영 승인 대기/)).toBeInTheDocument();
    expect(screen.getByTestId("settlement-confirm-open")).toBeDisabled();
    expect(screen.getByTestId("settlement-table-panel")).toHaveClass(
      table.tableScroll,
    );
    expect(
      within(screen.getByTestId("settlement-table-panel")).getByRole("table"),
    ).toHaveClass(table.table);
    expect(screen.getByTestId("settlement-person-11")).toHaveTextContent(
      "정산인력",
    );
    expect(screen.getByTestId("settlement-card-note")).toHaveValue("기존 비고");
  });
  it("sends natural-key card amount and note, then refreshes", async () => {
    await open();
    fireEvent.change(screen.getByTestId("settlement-card-amount"), {
      target: { value: "100" },
    });
    fireEvent.change(screen.getByTestId("settlement-card-note"), {
      target: { value: "카드 비고" },
    });
    fireEvent.click(screen.getByTestId("settlement-card-save"));
    await screen.findByText("카드 금액을 저장했습니다.");
    const call = fetcher.mock.calls.find(([, init]) => init?.method === "PUT");
    expect(call?.[0]).toBe("/api/settlements/parts/7/card?month=2026-10");
    expect(JSON.parse(call?.[1]?.body)).toEqual({
      corp_card_amt: 100,
      note: "카드 비고",
    });
  });
  it("retains amount and note and shows request id on write conflict", async () => {
    failWrite = true;
    await open();
    fireEvent.change(screen.getByTestId("settlement-card-amount"), {
      target: { value: "100" },
    });
    fireEvent.change(screen.getByTestId("settlement-card-note"), {
      target: { value: "유지할 입력" },
    });
    fireEvent.click(screen.getByTestId("settlement-card-save"));
    await screen.findByText("마감으로 변경되었습니다.");
    expect(screen.getByTestId("settlement-card-amount")).toHaveValue("100");
    expect(screen.getByTestId("settlement-card-note")).toHaveValue(
      "유지할 입력",
    );
    expect(
      screen.getByText("문의 번호 settlement-request"),
    ).toBeInTheDocument();
  });
  it("validates whole won locally without a request", async () => {
    await open();
    fireEvent.change(screen.getByTestId("settlement-card-amount"), {
      target: { value: "-1" },
    });
    fireEvent.click(screen.getByTestId("settlement-card-save"));
    await screen.findByText("카드액은 0 이상 원 단위로 입력하세요.");
    expect(
      fetcher.mock.calls.filter(([, init]) => init?.method === "PUT"),
    ).toHaveLength(0);
  });
  it("locks card and confirms while showing cancellation only for confirmed state", async () => {
    detail.settlement.stlm_stts_cd = "CONFIRM";
    detail.provisional = false;
    row.stlm_stts_cd = "CONFIRM";
    await open();
    expect(
      screen.queryByTestId("settlement-card-save"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("settlement-confirm-open"),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("settlement-cancel-open"));
    expect(screen.getByTestId("settlement-dialog-submit")).toBeDisabled();
    fireEvent.change(screen.getByTestId("settlement-cancel-reason"), {
      target: { value: " 원천 정정 " },
    });
    fireEvent.click(screen.getByTestId("settlement-dialog-submit"));
    await screen.findByText("확정을 취소했습니다.");
    const call = fetcher.mock.calls.find(
      ([url, init]) => url.includes("/cancel?") && init?.method === "POST",
    );
    expect(JSON.parse(call?.[1]?.body)).toEqual({ reason: "원천 정정" });
  });
  it("keeps cancellation reason when request fails", async () => {
    detail.settlement.stlm_stts_cd = "CONFIRM";
    failWrite = true;
    await open();
    fireEvent.click(screen.getByTestId("settlement-cancel-open"));
    fireEvent.change(screen.getByTestId("settlement-cancel-reason"), {
      target: { value: "사유 보존" },
    });
    fireEvent.click(screen.getByTestId("settlement-dialog-submit"));
    await screen.findByText("마감으로 변경되었습니다.");
    expect(screen.getByTestId("settlement-cancel-reason")).toHaveValue(
      "사유 보존",
    );
  });
  it("hides all mutations on a closed month", async () => {
    detail.status = "CLOSED";
    detail.settlement.stlm_stts_cd = "CONFIRM";
    await open();
    expect(
      screen.queryByTestId("settlement-card-save"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("settlement-cancel-open"),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("settlement-download")).toBeEnabled();
  });
  it("hides all mutations on past years", async () => {
    sessionStorage.setItem("part-settlement-month", "2025-10");
    await open();
    expect(
      screen.queryByTestId("settlement-card-save"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("settlement-confirm-open"),
    ).not.toBeInTheDocument();
  });
  it("hides mutation controls at mobile breakpoint, retaining read tables", async () => {
    Object.defineProperty(window, "innerWidth", {
      configurable: true,
      writable: true,
      value: 767,
    });
    await open();
    expect(
      screen.queryByTestId("settlement-card-amount"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("settlement-confirm-open"),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("settlement-person-11")).toBeInTheDocument();
  });
  it("renders all five trend rows with stored monthly status", async () => {
    await open();
    fireEvent.click(screen.getByTestId("settlement-trend-tab"));
    await screen.findByTestId("settlement-trend-11-profit");
    expect(screen.getAllByTestId(/^settlement-trend-11-/)).toHaveLength(5);
    expect(
      screen.getByTestId("settlement-trend-11-mm").querySelector("td"),
    ).toHaveAttribute("rowspan", "5");
    expect(
      within(screen.getByTestId("settlement-table-panel")).getByText("확정"),
    ).toBeInTheDocument();
    expect(fetcher.mock.calls.some(([url]) => url.includes("view=TREND"))).toBe(
      true,
    );
  });
  it("displays unavailable amounts as a dash and source warnings", async () => {
    detail.items[0].prft_amt = null;
    detail.warnings = ["급여 미업로드"];
    await open();
    expect(screen.getByText("급여 미업로드")).toBeInTheDocument();
    expect(screen.getByTestId("settlement-person-11")).toHaveTextContent("–");
  });
  it("renders an empty list and no unsafe action", async () => {
    empty = true;
    render(
      <MemoryRouter>
        <UI19Screen />
      </MemoryRouter>,
    );
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    expect(
      screen.queryByTestId("settlement-card-save"),
    ).not.toBeInTheDocument();
  });
  it("shows loading then a failed-read retry that loads actual data", async () => {
    failRead = true;
    render(
      <MemoryRouter>
        <UI19Screen />
      </MemoryRouter>,
    );
    expect(
      screen.getByRole("status", { name: "불러오는 중" }),
    ).toBeInTheDocument();
    await screen.findByText("서버에 연결할 수 없습니다.");
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByTestId("settlement-detail-panel");
  });
  it("initializes the remembered month and queries the chosen month", async () => {
    sessionStorage.setItem("part-settlement-month", "2026-09");
    await open();
    expect(screen.getByTestId("settlement-month")).toHaveValue("2026-09");
    fireEvent.change(screen.getByTestId("settlement-month"), {
      target: { value: "2026-08" },
    });
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => url.includes("month=2026-08")),
      ).toBe(true),
    );
    expect(sessionStorage.getItem("part-settlement-month")).toBe("2026-08");
  });
});
