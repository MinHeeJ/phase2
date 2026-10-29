import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { ApiFailure } from "../../shared/api";
import UI03Screen from "./UI03Screen";
import {
  listCostPeople,
  getCostPersonBreakdown,
  downloadCostPeople,
  type PeopleList,
  type PeopleRow,
} from "./client";

vi.mock("./client", async () => ({
  ...(await vi.importActual<typeof import("./client")>("./client")),
  listCostPeople: vi.fn(),
  getCostPersonBreakdown: vi.fn(),
  downloadCostPeople: vi.fn(),
}));
const row: PeopleRow = {
  dev_id: 37,
  dev_nm: "검증인력",
  vendor_name: null,
  pstn_cd: "MEMBER",
  emp_se_cd: "FT",
  bsn_part_id: 4,
  project_names: ["실제 업무", "지원 업무"],
  period: "2026-01 ~ 2026-02",
  contract_types: ["MO"],
  mm_qty: 1.25,
  bill_untprc_amt: 0,
  slry_amt: 100,
  alwnc_amt: 10,
  sins_cmpny_amt: 5,
  sins_emp_amt: 3,
  whtax_amt: 1,
  real_pay_amt: 106,
  rtrpay_acml_amt: 8,
  indv_lbr_cst_amt: 123,
  team_expns_amt: 7,
  project_expns_amt: 2,
  tot_cst_amt: 132,
  sls_amt: 0,
  prft_amt: -132,
  mrgn_rt: null,
  idle_cst_amt: 20,
  mm_ovr_yn: "Y",
  expandable: true,
};
function result(items = [row]): PeopleList {
  return {
    calculation_month: "2026-02",
    reference_month: "2026-02",
    closed: false,
    unit: "원",
    warnings: ["급여 미업로드: 2026-02"],
    items,
    total: items.length,
    page: 0,
    size: 50,
    summary: {
      psn_cnt: items.length,
      avg_working_psn_cnt: 1,
      avg_earning_psn_cnt: 0,
      avg_price_amt: 0,
      avg_cost_amt: 132,
      avg_profit_amt: -132,
      loss_psn_cnt: 1,
      utilization_rt: 50,
      earning_rt: 0,
      contributions: [
        { job_cd: "DEV", grd_cd: "SENIOR", psn_cnt: 1, contribution_amt: -132 },
      ],
    },
  };
}
function mount() {
  return render(
    <MemoryRouter initialEntries={["/UI-03"]}>
      <UI03Screen />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  vi.mocked(listCostPeople).mockResolvedValue(result());
  vi.mocked(downloadCostPeople).mockResolvedValue(undefined);
  vi.mocked(getCostPersonBreakdown).mockResolvedValue({
    calculation_month: "2026-02",
    reference_month: "2026-02",
    closed: false,
    unit: "원",
    warnings: [],
    dev_id: 37,
    items: [
      {
        cst_ym: "2026-01",
        asgn_id: null,
        prj_id: null,
        prj_nm: "대기",
        aloc_ty_cd: "IDLE",
        mm_qty: 0.25,
        bill_untprc_amt: null,
        lbr_cst_amt: 20,
        drct_expns_amt: 0,
        sls_amt: 0,
        prft_amt: -20,
        idle_cst_amt: 20,
        mm_ovr_yn: "Y",
      },
    ],
  });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      const data = url.startsWith("/api/codes/")
        ? [
            { code: "FT", name: "정규직" },
            { code: "MO", name: "월단위" },
            { code: "DEV", name: "개발" },
          ]
        : [{ id: "4", name: "영업팀", hq: false }];
      return new Response(JSON.stringify({ success: true, meta: {}, data }), {
        headers: { "Content-Type": "application/json" },
      });
    }),
  );
});
afterEach(() => {
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

describe("SCR-UI-03", () => {
  it("renders read-only source columns using common table styles, hides payroll reference fields and distinguishes zero and null", async () => {
    mount();
    const table = await screen.findByRole("table", {
      name: "개인 원가·수익표",
    });
    expect(table.className).toContain("table");
    expect(within(table).getByText("검증인력")).toBeInTheDocument();
    expect(within(table).getByText("1.25")).toBeInTheDocument();
    expect(within(table).getByText("–")).toBeInTheDocument();
    expect(within(table).getByText("-132").className).toContain("danger");
    expect(within(table).queryByText("实지급액")).not.toBeInTheDocument();
    expect(
      within(table).queryByRole("columnheader", { name: "실지급액" }),
    ).not.toBeInTheDocument();
    expect(within(table).queryByRole("textbox")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("people-payroll-reference"));
    expect(
      within(table).getByRole("columnheader", { name: "보험 본인분" }),
    ).toBeInTheDocument();
    expect(
      within(table).getByRole("columnheader", { name: "실지급액" }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("people-warnings")).toHaveTextContent(
      "급여 미업로드",
    );
  });
  it("queries the selected actual person and collapses monthly calculation grounds", async () => {
    mount();
    fireEvent.click(
      await screen.findByRole("button", { name: "검증인력 계산 근거" }),
    );
    expect(await screen.findByText("대기")).toBeInTheDocument();
    expect(getCostPersonBreakdown).toHaveBeenCalledWith(
      37,
      expect.objectContaining({ view: "TOTAL" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "검증인력 계산 근거" }));
    expect(screen.queryByTestId("people-breakdown-37")).not.toBeInTheDocument();
  });
  it("does not offer expansion for a single project without idle", async () => {
    vi.mocked(listCostPeople).mockResolvedValue(
      result([{ ...row, expandable: false }]),
    );
    mount();
    await screen.findByText("검증인력");
    expect(
      screen.queryByRole("button", { name: "검증인력 계산 근거" }),
    ).not.toBeInTheDocument();
  });
  it("shows an empty result and no fabricated rows", async () => {
    vi.mocked(listCostPeople).mockResolvedValue(result([]));
    mount();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("people-row-37")).not.toBeInTheDocument();
  });
  it("keeps the applied year and filters identical for display and download, not unapplied drafts", async () => {
    mount();
    await screen.findByText("검증인력");
    fireEvent.change(screen.getByTestId("people-year"), {
      target: { value: "2025" },
    });
    fireEvent.click(screen.getByTestId("people-loss-only"));
    fireEvent.click(screen.getByTestId("people-overlap-only"));
    fireEvent.click(screen.getByTestId("people-exclude-turnkey-price"));
    fireEvent.click(screen.getByTestId("people-search"));
    await waitFor(() =>
      expect(listCostPeople).toHaveBeenLastCalledWith(
        expect.objectContaining({
          year: 2025,
          lossOnly: true,
          overlapOnly: true,
          excludeTurnkeyPrice: true,
        }),
      ),
    );
    fireEvent.change(screen.getByTestId("people-year"), {
      target: { value: "2024" },
    });
    fireEvent.click(screen.getByTestId("people-download"));
    await waitFor(() =>
      expect(downloadCostPeople).toHaveBeenLastCalledWith(
        expect.objectContaining({
          year: 2025,
          lossOnly: true,
          overlapOnly: true,
          excludeTurnkeyPrice: true,
        }),
      ),
    );
  });
  it("switches TOTAL/AVERAGE without losing applied filters and resets explicitly", async () => {
    mount();
    await screen.findByText("검증인력");
    fireEvent.click(screen.getByTestId("people-loss-only"));
    fireEvent.click(screen.getByTestId("people-search"));
    await waitFor(() =>
      expect(listCostPeople).toHaveBeenLastCalledWith(
        expect.objectContaining({ lossOnly: true }),
      ),
    );
    fireEvent.click(screen.getByRole("tab", { name: "월 평균" }));
    await waitFor(() =>
      expect(listCostPeople).toHaveBeenLastCalledWith(
        expect.objectContaining({ view: "AVERAGE", lossOnly: true, page: 0 }),
      ),
    );
    fireEvent.click(screen.getByTestId("people-reset"));
    await waitFor(() =>
      expect(listCostPeople).toHaveBeenLastCalledWith(
        expect.objectContaining({ view: "TOTAL", lossOnly: false }),
      ),
    );
  });
  it("renders field errors, preserves filters and retries failed requests", async () => {
    vi.mocked(listCostPeople).mockRejectedValueOnce(
      new ApiFailure(
        "연도를 확인하세요",
        400,
        "request-1",
        "year",
        "VALIDATION_ERROR",
      ),
    );
    mount();
    expect(await screen.findByText(/연도를 확인하세요/)).toBeInTheDocument();
    expect(screen.getByTestId("people-year")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /다시/ }));
    expect(await screen.findByText("검증인력")).toBeInTheDocument();
  });
  it("shows loading while a request is unresolved", async () => {
    vi.mocked(listCostPeople).mockReturnValue(new Promise(() => {}));
    mount();
    expect(screen.getByText("개인 원가·수익 불러오는 중")).toBeInTheDocument();
  });
  it("provides contribution and calculation-method disclosures", async () => {
    mount();
    await screen.findByText("검증인력");
    expect(screen.getByTestId("people-contributions")).not.toHaveAttribute(
      "open",
    );
    fireEvent.click(screen.getByText("직무·등급별 인당 공헌이익"));
    expect(screen.getByTestId("people-contribution-0")).toHaveTextContent(
      "-132",
    );
    expect(screen.getByTestId("people-calculation")).toHaveTextContent(
      "결손 급여 달도 분모에 포함",
    );
  });
  it("retries breakdown failure without hiding the selected row", async () => {
    vi.mocked(getCostPersonBreakdown).mockRejectedValueOnce(
      new ApiFailure("근거 조회 실패", 500, "request-2"),
    );
    mount();
    fireEvent.click(
      await screen.findByRole("button", { name: "검증인력 계산 근거" }),
    );
    expect(await screen.findByText("근거 조회 실패")).toBeInTheDocument();
    fireEvent.click(
      within(screen.getByTestId("people-breakdown-37")).getByRole("button", {
        name: /다시/,
      }),
    );
    expect(await screen.findByText("대기")).toBeInTheDocument();
  });
});
