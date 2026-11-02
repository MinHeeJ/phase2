import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AllocationPanel } from "./AllocationPanel";
import UI03Screen from "../people/UI03Screen";
import { response, failure } from "../developers/DeveloperTestSupport";
import type { AllocationResult, AllocationRow } from "./client";
import type { PeopleRow, PersonBreakdown } from "../people/client";

const allocationRow: AllocationRow = {
  cst_ym: "2026-10",
  dev_id: 37,
  asgn_id: 2,
  prj_id: 71,
  aloc_ty_cd: "PROJECT",
  mm_qty: 0.5,
  tot_mm_qty: 1.5,
  aloc_rt: 1 / 3,
  lbr_cst_amt: 51,
  drct_expns_amt: 1,
  sls_amt: 500,
  bill_untprc_amt: 1000,
  mm_ovr_yn: "Y",
  free_sprt_yn: "Y",
  pre_cntrct_yn: "Y",
};
const person: PeopleRow = {
  dev_id: 37,
  dev_nm: "검증 인력",
  vendor_name: null,
  pstn_cd: null,
  emp_se_cd: "FT",
  bsn_part_id: null,
  project_names: ["업무"],
  period: "2026-01 ~ 2026-10",
  contract_types: ["MO"],
  mm_qty: 0.5,
  bill_untprc_amt: 1000,
  slry_amt: 101,
  alwnc_amt: 0,
  sins_cmpny_amt: 0,
  sins_emp_amt: 0,
  whtax_amt: 0,
  real_pay_amt: 101,
  rtrpay_acml_amt: 0,
  indv_lbr_cst_amt: 101,
  team_expns_amt: 1,
  project_expns_amt: 0,
  tot_cst_amt: 102,
  sls_amt: 500,
  prft_amt: 398,
  mrgn_rt: null,
  idle_cst_amt: 0,
  mm_ovr_yn: "Y",
  expandable: true,
};
let allocation: AllocationResult;
let breakdown: PersonBreakdown;
let allocationError: number | null;
let breakdownError: number | null;
let fetcher: ReturnType<typeof vi.fn>;

beforeEach(() => {
  allocationError = null;
  breakdownError = null;
  allocation = {
    calculation_month: "2026-10",
    reference_month: "2026-10",
    closed: false,
    unit: "원",
    warnings: [],
    items: [{ ...allocationRow }],
    unallocated_cost_amt: 0,
  };
  breakdown = {
    calculation_month: "2026-10",
    reference_month: "2026-10",
    closed: false,
    unit: "원",
    warnings: [],
    dev_id: 37,
    items: [
      {
        cst_ym: "2026-10",
        asgn_id: 2,
        prj_id: 71,
        prj_nm: "업무",
        aloc_ty_cd: "PROJECT",
        mm_qty: 0.5,
        bill_untprc_amt: 1000,
        lbr_cst_amt: 51,
        drct_expns_amt: 1,
        sls_amt: 500,
        prft_amt: 448,
        idle_cst_amt: 0,
        mm_ovr_yn: "Y",
      },
    ],
  };
  fetcher = vi.fn(async (url: string) => {
    if (url.startsWith("/api/costs/allocation?"))
      return allocationError
        ? failure("month", allocationError)
        : response(allocation);
    if (url.startsWith("/api/costs/people/37/breakdown?"))
      return breakdownError
        ? failure("year", breakdownError)
        : response(breakdown);
    if (url.startsWith("/api/costs/people?"))
      return response({
        calculation_month: "2026-10",
        reference_month: "2026-10",
        closed: false,
        unit: "원",
        warnings: [],
        items: [person],
        total: 1,
        page: 0,
        size: 50,
      });
    if (
      url.startsWith("/api/codes/") ||
      url.startsWith("/api/partners/options") ||
      url === "/api/sales-parts/options"
    )
      return response([]);
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function mountPeople() {
  return render(
    <MemoryRouter initialEntries={["/UI-03"]}>
      <UI03Screen />
    </MemoryRouter>,
  );
}

describe("월별 배분 근거", () => {
  it("renders actual ratio, MM, sensitive amounts and distinct state badges using shared styles", async () => {
    render(<AllocationPanel month="2026-10" projectId={71} />);
    const table = await screen.findByRole("table", { name: "월별 배분 결과" });
    expect(table.className).toContain("table");
    expect(within(table).getByText("33.33%")).toBeInTheDocument();
    expect(within(table).getByText("51")).toHaveAttribute("data-sensitive");
    expect(within(table).getByText("1.0 초과").className).toContain("warning");
    expect(within(table).getByText("무상지원").className).toContain("info");
    expect(within(table).getByText("미계약 투입").className).toContain(
      "warning",
    );
    expect(screen.getByText("원천 재계산").className).toContain("info");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/costs/allocation?month=2026-10&projectId=71",
      expect.anything(),
    );
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });
  it("shows stored closed data without offering changes", async () => {
    allocation.closed = true;
    render(<AllocationPanel month="2026-10" />);
    expect((await screen.findByText("마감 스냅샷")).className).toContain(
      "locked",
    );
    expect(
      screen.queryByRole("button", { name: /저장|변경|확정/ }),
    ).not.toBeInTheDocument();
  });
  it("keeps idle red and absent identity or ratio distinct from zero", async () => {
    allocation.items = [
      {
        ...allocationRow,
        aloc_ty_cd: "IDLE",
        asgn_id: null,
        prj_id: null,
        aloc_rt: null,
        lbr_cst_amt: 0,
        mm_ovr_yn: "N",
        free_sprt_yn: "N",
        pre_cntrct_yn: "N",
      },
    ];
    render(<AllocationPanel month="2026-10" />);
    expect((await screen.findByText("대기")).className).toContain("danger");
    const row = screen.getByTestId("allocation-row-0");
    expect(within(row).getAllByText("–")).toHaveLength(3);
    expect(within(row).getByText("0")).toHaveAttribute("data-sensitive");
    expect(within(row).queryByText("1.0 초과")).not.toBeInTheDocument();
    expect(within(row).queryByText("무상지원")).not.toBeInTheDocument();
    expect(within(row).queryByText("미계약 투입")).not.toBeInTheDocument();
  });
  it("shows RND and indirect classifications without inventing project identities", async () => {
    allocation.items = [
      { ...allocationRow, aloc_ty_cd: "RND" },
      { ...allocationRow, aloc_ty_cd: "INDIRECT", asgn_id: null, prj_id: null },
    ];
    render(<AllocationPanel month="2026-10" />);
    await screen.findByText("연구개발");
    expect(screen.getByText("간접 인력")).toBeInTheDocument();
  });
  it("shows source warnings and explicitly labels the unallocated total as month-wide", async () => {
    allocation.warnings = ["급여 미업로드: dev_id=37"];
    allocation.unallocated_cost_amt = 123;
    render(<AllocationPanel month="2026-10" developerId={37} />);
    expect(await screen.findByTestId("allocation-warning-0")).toHaveTextContent(
      "급여 미업로드",
    );
    expect(screen.getByText("월 전체 미배분 원가: 123")).toBeInTheDocument();
  });
  it("filters the displayed developer only without sending an unsupported API parameter", async () => {
    allocation.items.push({ ...allocationRow, dev_id: 99, lbr_cst_amt: 999 });
    render(<AllocationPanel month="2026-10" developerId={37} />);
    await screen.findByRole("table");
    expect(screen.queryByText("999")).not.toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledWith(
      "/api/costs/allocation?month=2026-10",
      expect.anything(),
    );
  });
  it("renders a real empty list when selected person has no rows", async () => {
    render(<AllocationPanel month="2026-10" developerId={99} />);
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("allocation-row-0")).not.toBeInTheDocument();
  });
  it("shows loading until the allocation request resolves", () => {
    fetcher.mockImplementation(() => new Promise(() => {}));
    render(<AllocationPanel month="2026-10" />);
    expect(screen.getByText("월별 배분 근거 불러오는 중")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
  it.each([403, 409, 500])(
    "handles %s without stale rows or a live fallback and supports retry",
    async (status) => {
      allocationError = status;
      render(<AllocationPanel month="2026-09" />);
      await screen.findByText("입력 오류");
      expect(screen.queryByRole("table")).not.toBeInTheDocument();
      allocationError = null;
      fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
      await screen.findByRole("table");
      expect(
        fetcher.mock.calls.every(
          ([url]) => String(url) === "/api/costs/allocation?month=2026-09",
        ),
      ).toBe(true);
    },
  );
  it("ignores an older response after the selected month changes", async () => {
    let resolveOld!: (response: Response) => void;
    fetcher.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          resolveOld = resolve;
        }),
    );
    const view = render(<AllocationPanel month="2026-09" />);
    view.rerender(<AllocationPanel month="2026-10" />);
    await screen.findByText("51");
    resolveOld(
      response({
        ...allocation,
        items: [{ ...allocationRow, lbr_cst_amt: 999 }],
      }),
    );
    await waitFor(() =>
      expect(screen.queryByText("999")).not.toBeInTheDocument(),
    );
    expect(screen.getByText("51")).toBeInTheDocument();
  });
});

describe("개인 원가 상세에서 배분 근거 연결", () => {
  it("opens the selected person's month through both approved APIs, not a sample identity", async () => {
    mountPeople();
    fireEvent.click(
      await screen.findByRole("button", { name: "검증 인력 계산 근거" }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "2026-10 배분 근거" }),
    );
    await screen.findByRole("table", { name: "월별 배분 결과" });
    expect(
      fetcher.mock.calls.some(([url]) =>
        String(url).startsWith("/api/costs/people/37/breakdown?"),
      ),
    ).toBe(true);
    expect(fetcher).toHaveBeenCalledWith(
      "/api/costs/allocation?month=2026-10",
      expect.anything(),
    );
  });
  it("shows the breakdown's own closed state and warnings", async () => {
    breakdown.closed = true;
    breakdown.warnings = ["저장된 초과 배분"];
    mountPeople();
    fireEvent.click(
      await screen.findByRole("button", { name: "검증 인력 계산 근거" }),
    );
    const panel = await screen.findByTestId("people-breakdown-37");
    expect(await within(panel).findByText("마감 스냅샷")).toBeInTheDocument();
    expect(within(panel).getByRole("status")).toHaveTextContent(
      "저장된 초과 배분",
    );
  });
  it("does not offer an allocation drilldown for an empty breakdown", async () => {
    breakdown.items = [];
    mountPeople();
    fireEvent.click(
      await screen.findByRole("button", { name: "검증 인력 계산 근거" }),
    );
    const panel = await screen.findByTestId("people-breakdown-37");
    await within(panel).findByText("조건에 맞는 결과가 없습니다");
    expect(
      within(panel).queryByRole("button", { name: /배분 근거/ }),
    ).not.toBeInTheDocument();
  });
  it("preserves the selected person after failed detail read and retries before allocation", async () => {
    breakdownError = 409;
    mountPeople();
    fireEvent.click(
      await screen.findByRole("button", { name: "검증 인력 계산 근거" }),
    );
    const panel = await screen.findByTestId("people-breakdown-37");
    await within(panel).findByText("입력 오류");
    expect(
      screen.queryByTestId("cost-allocation-panel"),
    ).not.toBeInTheDocument();
    breakdownError = null;
    fireEvent.click(within(panel).getByRole("button", { name: "다시 시도" }));
    await within(panel).findByRole("button", { name: "2026-10 배분 근거" });
  });
});
