import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
  cleanup,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI19Screen from "./UI19Screen";
import type { SettlementDetail } from "./client";
import tables from "../developers/Developers.module.css";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let detail: SettlementDetail;
let fetcher: ReturnType<typeof vi.fn>;
let empty = false,
  failRead = false,
  failWrite = false;
beforeEach(() => {
  Object.defineProperty(window, "innerWidth", {
    configurable: true,
    writable: true,
    value: 1280,
  });
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-29T00:00:00Z"));
  empty = false;
  failRead = false;
  failWrite = false;
  detail = {
    calculation_month: "2026-10",
    reference_month: "2026-10",
    closed: false,
    unit: "원",
    warnings: [],
    provisional: true,
    confirmation_available: false,
    settlement: {
      stlm_id: 91,
      bsn_part_id: 7,
      stlm_ym: "2026-10",
      stlm_stts_cd: "DRAFT",
      part_std_hist_id: 31,
      ldr_nm: "파트장",
      prft_dstrb_rt: 40,
      bsn_fee_amt: 10,
      psn_cnt: 1,
      tot_sls_amt: 1000,
      tot_exp_amt: 500,
      tot_diff_amt: 500,
      tot_psn_prft_amt: 200,
      corp_card_amt: 5,
      part_prft_amt: 195,
      rmrk_desc: "저장 비고",
      cnfrm_at: null,
    },
    items: [
      {
        stlm_dtl_id: null,
        dev_id: 4,
        dev_nm: "실제 인력",
        emp_se_cd: "FL",
        project_names: "실제 프로젝트",
        mm_qty: 0.5,
        mth_untprc_amt: 1000,
        slry_amt: 490,
        bsn_fee_amt: 10,
        rtrpay_amt: 0,
        tot_exp_amt: 500,
        diff_amt: 500,
        prft_amt: 200,
        rmrk_desc: "영업비",
      },
    ],
    trend: [
      "mm_qty",
      "mth_untprc_amt",
      "tot_exp_amt",
      "diff_amt",
      "prft_amt",
    ].map((metric) => ({
      dev_id: 4,
      dev_nm: "실제 인력",
      metric,
      months: [
        {
          month: "2026-09",
          value: metric === "mm_qty" ? 0.5 : 100,
          confirmed: true,
        },
        {
          month: "2026-10",
          value: metric === "mm_qty" ? 0.5 : 200,
          confirmed: false,
        },
      ],
    })),
    history: [],
    standard_history: [
      {
        part_std_hist_id: 31,
        aply_bgn_ym: "2026-01",
        aply_end_ym: null,
        ldr_nm: "파트장",
        prft_dstrb_rt: 40,
        bsn_fee_amt: 10,
      },
    ],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "PUT" || init?.method === "POST") {
      if (failWrite)
        return new Response(
          JSON.stringify({
            success: false,
            code: "CLOSED_MONTH",
            field: "month",
            message: "마감 상태가 변경되었습니다.",
            requestId: "part-request",
          }),
          { status: 409 },
        );
      if (init.method === "PUT") {
        const input = JSON.parse(String(init.body));
        detail.settlement.corp_card_amt = input.corp_card_amt;
        detail.settlement.rmrk_desc = input.rmrk_desc;
      } else if (url.includes("/cancel?")) {
        detail.settlement.stlm_stts_cd = "DRAFT";
        detail.provisional = true;
        detail.history.push({
          stlm_hist_id: 2,
          stlm_prcs_cd: "CANCEL",
          rsn_desc: JSON.parse(String(init.body)).reason,
          prcs_user_id: null,
          prcs_at: "2026-10-29T09:00:00",
        });
      } else {
        detail.settlement.stlm_stts_cd = "CONFIRM";
        detail.provisional = false;
        detail.confirmation_available = false;
      }
      return response(detail);
    }
    if (failRead)
      return new Response(
        JSON.stringify({
          success: false,
          message: "조회 실패",
          requestId: "read-request",
        }),
        { status: 500 },
      );
    if (url.startsWith("/api/settlements/parts?"))
      return response({
        ...detail,
        items: empty
          ? []
          : [
              {
                bsn_part_id: 7,
                bsn_part_nm: "영업 파트",
                settlement: detail.settlement,
                provisional: detail.provisional,
                confirmation_available: detail.confirmation_available,
                warnings: detail.warnings,
              },
            ],
      });
    if (url.startsWith("/api/settlements/parts/7?")) return response(detail);
    throw new Error(`Unexpected URL ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
const open = async () => {
  render(
    <MemoryRouter>
      <UI19Screen />
    </MemoryRouter>,
  );
  await screen.findByRole("table", { name: "월별 인력 정산" });
};
describe("SCR-UI-19 /UI-19 part settlement", () => {
  it("renders API values and common developer table styling with pending approval", async () => {
    await open();
    expect(
      screen.getByRole("heading", { name: "영업 파트 이익금" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "월별 인력 정산" })).toHaveClass(
      tables.table,
    );
    expect(screen.getByText("실제 인력")).toBeInTheDocument();
    expect(screen.getByText("0.50")).toBeInTheDocument();
    expect(screen.getByTestId("part-confirm-button")).toBeDisabled();
    expect(screen.getByText(/운영 기준 승인 전/)).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledWith(
      "/api/settlements/parts/7?month=2026-10&view=MONTH",
      expect.anything(),
    );
  });
  it("saves selected natural-key card and remark without body id and refreshes", async () => {
    await open();
    fireEvent.change(screen.getByLabelText("법인카드 사용액"), {
      target: { value: "12" },
    });
    fireEvent.change(screen.getByLabelText("비고", { exact: true }), {
      target: { value: "새 비고" },
    });
    fireEvent.click(screen.getByText("카드액 저장"));
    await waitFor(() =>
      expect(fetcher).toHaveBeenCalledWith(
        "/api/settlements/parts/7/card?month=2026-10",
        expect.objectContaining({
          method: "PUT",
          body: JSON.stringify({ corp_card_amt: 12, rmrk_desc: "새 비고" }),
        }),
      ),
    );
    await screen.findByText("법인카드 금액과 비고를 저장했습니다.");
  });
  it("rejects fractional card input locally", async () => {
    await open();
    fireEvent.change(screen.getByLabelText("법인카드 사용액"), {
      target: { value: "0.5" },
    });
    fireEvent.click(screen.getByText("카드액 저장"));
    expect(
      await screen.findByText("법인카드 사용액은 0 이상의 정수로 입력하세요."),
    ).toBeInTheDocument();
    expect(fetcher.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(
      false,
    );
  });
  it("keeps card and remark when write fails with request id", async () => {
    failWrite = true;
    await open();
    fireEvent.change(screen.getByLabelText("법인카드 사용액"), {
      target: { value: "27" },
    });
    fireEvent.change(screen.getByLabelText("비고", { exact: true }), {
      target: { value: "유지할 비고" },
    });
    fireEvent.click(screen.getByText("카드액 저장"));
    await screen.findByText("문의 번호 part-request");
    expect(screen.getByLabelText("법인카드 사용액")).toHaveValue("27");
    expect(screen.getByLabelText("비고", { exact: true })).toHaveValue(
      "유지할 비고",
    );
  });
  it("confirmed records hide card editing and allow open-month cancellation", async () => {
    detail.settlement.stlm_stts_cd = "CONFIRM";
    detail.provisional = false;
    await open();
    expect(screen.queryByTestId("part-card-controls")).not.toBeInTheDocument();
    expect(screen.getByText("확정 취소")).toBeInTheDocument();
    fireEvent.click(screen.getByText("확정 취소"));
    const dialog = screen.getByRole("dialog");
    fireEvent.click(within(dialog).getByText("확인"));
    expect(
      within(dialog).getByText("취소 사유를 1~500자로 입력하세요."),
    ).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText("취소 사유"), {
      target: { value: "재검토" },
    });
    await waitFor(() =>
      expect(within(dialog).getByLabelText("취소 사유")).toHaveValue("재검토"),
    );
    fireEvent.click(within(dialog).getByText("확인"));
    await screen.findByText("확정을 취소했습니다.");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("retains cancellation reason and dialog after server rejection", async () => {
    detail.settlement.stlm_stts_cd = "CONFIRM";
    detail.provisional = false;
    failWrite = true;
    await open();
    fireEvent.click(screen.getByText("확정 취소"));
    fireEvent.change(screen.getByLabelText("취소 사유"), {
      target: { value: "보존할 사유" },
    });
    fireEvent.click(within(screen.getByRole("dialog")).getByText("확인"));
    await waitFor(() =>
      expect(
        within(screen.getByRole("dialog")).getByText(
          "마감 상태가 변경되었습니다.",
        ),
      ).toBeInTheDocument(),
    );
    expect(screen.getByLabelText("취소 사유")).toHaveValue("보존할 사유");
  });
  it("closed month hides every financial write control", async () => {
    detail.closed = true;
    detail.settlement.stlm_stts_cd = "CONFIRM";
    detail.provisional = false;
    await open();
    expect(screen.queryByTestId("part-card-controls")).not.toBeInTheDocument();
    expect(screen.queryByTestId("part-cancel-button")).not.toBeInTheDocument();
    expect(screen.getByText(/조회·다운로드만/)).toBeInTheDocument();
  });
  it("mobile is read-only with no card, confirm or cancellation controls", async () => {
    window.innerWidth = 375;
    await open();
    expect(screen.queryByTestId("part-card-controls")).not.toBeInTheDocument();
    expect(screen.queryByTestId("part-confirm-button")).not.toBeInTheDocument();
    expect(screen.queryByTestId("part-cancel-button")).not.toBeInTheDocument();
    expect(screen.getByText("실제 인력")).toBeInTheDocument();
  });
  it("trend contains five rows per person and separate confirmed/provisional month badges", async () => {
    await open();
    fireEvent.click(screen.getByRole("tab", { name: "기간 추이" }));
    const table = await screen.findByRole("table", {
      name: "인력별 기간 추이",
    });
    expect(within(table).getAllByRole("row")).toHaveLength(6);
    expect(within(table).getByText("확정")).toBeInTheDocument();
    expect(within(table).getByText("잠정")).toBeInTheDocument();
    expect(table).toHaveClass(tables.table);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/settlements/parts/7?month=2026-10&view=TREND",
      expect.anything(),
    );
  });
  it("approved confirmation is bodyless and closes dialog on success", async () => {
    detail.confirmation_available = true;
    await open();
    fireEvent.click(screen.getByTestId("part-confirm-button"));
    fireEvent.click(within(screen.getByRole("dialog")).getByText("확인"));
    await screen.findByText("정산을 확정했습니다.");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/settlements/parts/7/confirm?month=2026-10",
      expect.objectContaining({ method: "POST" }),
    );
  });
  it("empty list has a real empty state and no card editor", async () => {
    empty = true;
    render(
      <MemoryRouter>
        <UI19Screen />
      </MemoryRouter>,
    );
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("part-card-input")).not.toBeInTheDocument();
  });
  it("failed list is retryable without resetting month", async () => {
    failRead = true;
    render(
      <MemoryRouter>
        <UI19Screen />
      </MemoryRouter>,
    );
    await screen.findByText("문의 번호 read-request");
    expect(screen.getByLabelText("조회월")).toHaveValue("2026-10");
    failRead = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByText("실제 인력")).toBeInTheDocument();
  });
});
