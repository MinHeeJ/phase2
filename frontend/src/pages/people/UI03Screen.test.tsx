import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI03Screen from "./UI03Screen";
import type { PeoplePage, PeopleRow } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let data: PeoplePage;
let fetcher: ReturnType<typeof vi.fn>;
let readFailure: boolean;
let detailFailure: boolean;
let exportFailure: boolean;
let pending: boolean;
const mount = () =>
  render(
    <MemoryRouter>
      <UI03Screen />
    </MemoryRouter>,
  );
const listCalls = () =>
  fetcher.mock.calls.filter(
    ([url, init]) =>
      String(url).startsWith("/api/costs/people?") &&
      init?.headers?.Accept === undefined,
  );
const lastQuery = () =>
  new URLSearchParams(String(listCalls().at(-1)?.[0]).split("?")[1]);
const row = (): PeopleRow => ({
  dev_id: 7,
  mng_no: "K-0007",
  dev_nm: "개발자",
  affiliation_name: "자사",
  vendor_id: null,
  bsn_part_id: 1,
  pstn_cd: "MEMBER",
  emp_se_cd: "FT",
  job_cd: "BE",
  grd_cd: "SENIOR",
  on_leave: false,
  projects: [
    {
      asgn_id: 11,
      dev_id: 7,
      prj_id: 3,
      prj_nm: "실제 프로젝트",
      cntrct_ty_cd: "MO",
      bgn_dt: "2026-10-01",
      end_dt: "2026-10-31",
    },
  ],
  cntrct_ty_cd: "MO,IDLE",
  mm_qty: 0.4,
  bill_untprc_amt: 0,
  slry_amt: 100,
  alwnc_amt: 0,
  sins_cmpny_amt: 0,
  sins_emp_amt: 10,
  whtax_amt: 3,
  rtrpay_acml_amt: 0,
  real_pay_amt: 87,
  indv_lbr_cst_amt: 100,
  team_expns_amt: 0,
  project_expns_amt: 0,
  total_cost_amt: 100,
  personal_revenue_amt: 0,
  profit_amt: -100,
  margin_rt: null,
  overlap: false,
  warnings: [],
});
beforeEach(() => {
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-14T00:00:00Z"));
  readFailure = false;
  detailFailure = false;
  exportFailure = false;
  pending = false;
  data = {
    month: "2026-10",
    referenceMonth: "2026-10",
    status: "OPEN",
    unit: "KRW",
    warnings: [],
    items: [row()],
    total: 51,
    page: 0,
    size: 50,
    summary: {
      people_count: 51,
      average_people_count: 50,
      priced_people_count: 40,
      loss_people_count: 10,
      monthly_active_count: 30,
      monthly_paid_count: 20,
      utilization_rt: 60,
      paid_rt: 40,
      average_price_amt: 0,
      average_cost_amt: 100,
      average_profit_amt: -100,
      contributions: [
        { job_cd: "BE", grd_cd: "SENIOR", people_count: 5, profit_amt: -100 },
      ],
    },
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/codes/"))
      return response([
        { code: "FT", name: "정규직" },
        { code: "MO", name: "월단위" },
        { code: "BE", name: "백엔드 개발" },
        { code: "SENIOR", name: "고급" },
        { code: "MEMBER", name: "팀원" },
      ]);
    if (url === "/api/sales-parts/options")
      return response([{ id: "1", name: "영업파트", hq: false }]);
    if (url === "/api/partners/options?kind=VEND")
      return response([{ id: "2", no: "V-2", name: "협력사" }]);
    if (url.startsWith("/api/costs/people/7/breakdown?")) {
      if (detailFailure)
        return new Response(
          JSON.stringify({
            success: false,
            code: "CLOSED_MONTH",
            message: "마감 배분자료 없음",
          }),
          { status: 409 },
        );
      const items = [
        {
          cst_ym: "2026-10",
          dev_id: 7,
          prj_id: 3,
          asgn_id: 11,
          aloc_ty_cd: "PROJECT",
          mm_qty: 0.4,
          lbr_cst_amt: 40,
          drct_expns_amt: 0,
          sls_amt: 0,
          bill_untprc_amt: 0,
          mm_ovr_yn: "N",
        },
        {
          cst_ym: "2026-10",
          dev_id: 7,
          prj_id: null,
          asgn_id: null,
          aloc_ty_cd: "IDLE",
          mm_qty: 0,
          lbr_cst_amt: 60,
          drct_expns_amt: 0,
          sls_amt: 0,
          bill_untprc_amt: null,
          mm_ovr_yn: "N",
        },
      ];
      return response({
        ...data,
        person: row(),
        items,
        monthly_rows: [
          { month: "2026-10", status: "OPEN", items, warnings: [] },
        ],
      });
    }
    if (url.startsWith("/api/costs/people?")) {
      if ((init?.headers as Record<string, string>)?.Accept) {
        if (exportFailure)
          return new Response(
            JSON.stringify({
              success: false,
              message: "다운로드 오류",
              field: "year",
            }),
            { status: 400 },
          );
        return new Response(new Uint8Array([80, 75]), {
          headers: {
            "Content-Type":
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "Content-Disposition": "attachment; filename*=UTF-8''people.xlsx",
          },
        });
      }
      if (pending) return new Promise<Response>(() => {});
      if (readFailure)
        return new Response(
          JSON.stringify({
            success: false,
            code: "VALIDATION_ERROR",
            message: "연도를 확인하세요.",
            field: "year",
            requestId: "request-reference",
          }),
          { status: 400 },
        );
      return response(data);
    }
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("personal cost and profit screen", () => {
  it.each(["/api/partners/options?kind=VEND", "/api/sales-parts/options"])(
    "keeps the screen usable when %s returns a page instead of options",
    async (path) => {
      const validFetch = fetcher.getMockImplementation()!;
      fetcher.mockImplementation((url: string, init?: RequestInit) =>
        url === path
          ? Promise.resolve(
              response({ items: [], total: 0, page: 1, size: 100 }),
            )
          : validFetch(url, init),
      );
      mount();
      await screen.findByText("선택 항목 응답을 확인할 수 없습니다.");
      expect(
        screen.getByRole("heading", { name: "개인 원가·수익표" }),
      ).toBeVisible();
      expect(screen.getByTestId("people-row-7")).toBeVisible();
      fireEvent.change(screen.getByTestId("people-vendor"), {
        target: { value: "SELF" },
      });
      fireEvent.click(screen.getByTestId("people-search"));
      await waitFor(() => expect(lastQuery().get("vendorId")).toBe("SELF"));
    },
  );
  it("renders an empty page without optional warnings", async () => {
    Object.assign(data, { items: [], total: 0, warnings: undefined });
    mount();
    expect(await screen.findByTestId("people-empty")).toBeVisible();
    expect(screen.queryByTestId("people-warnings")).not.toBeInTheDocument();
  });
  it("uses UI02 table classes and renders real rows, summary and initially hidden payroll reference columns", async () => {
    mount();
    await screen.findByTestId("people-row-7");
    expect(
      screen.getByRole("heading", { name: "개인 원가·수익표" }),
    ).toBeInTheDocument();
    expect(screen.getByTestId("people-summary-0")).toHaveTextContent("51명");
    expect(
      screen.getByTestId("people-row-7").closest("table")?.className,
    ).toContain("table");
    for (const title of ["4대보험 본인", "원천세", "실지급액"])
      expect(
        screen.queryByRole("columnheader", { name: title }),
      ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("people-next-year")).toBeDisabled();
  });
  it("queries applied filters only and keeps them across total/average and download", async () => {
    mount();
    await screen.findByTestId("people-row-7");
    fireEvent.change(screen.getByTestId("people-employee"), {
      target: { value: "FT" },
    });
    fireEvent.change(screen.getByTestId("people-part"), {
      target: { value: "1" },
    });
    fireEvent.change(screen.getByTestId("people-vendor"), {
      target: { value: "2" },
    });
    fireEvent.change(screen.getByTestId("people-job"), {
      target: { value: "BE" },
    });
    fireEvent.change(screen.getByTestId("people-contract"), {
      target: { value: "MO" },
    });
    expect(lastQuery().get("empType")).toBeNull();
    fireEvent.click(screen.getByTestId("people-search"));
    await waitFor(() => expect(lastQuery().get("empType")).toBe("FT"));
    fireEvent.click(screen.getByTestId("people-view-average"));
    await waitFor(() => expect(lastQuery().get("view")).toBe("AVERAGE"));
    expect(lastQuery().get("partId")).toBe("1");
    expect(lastQuery().get("vendorId")).toBe("2");
    expect(lastQuery().get("job")).toBe("BE");
    expect(lastQuery().get("contractType")).toBe("MO");
  });
  it.each([
    ["people-loss-only", "lossOnly"],
    ["people-overlap-only", "overlapOnly"],
    ["people-exclude-turnkey-price", "excludeTurnkeyPrice"],
  ])("applies distinct advanced rule %s", async (testId, parameter) => {
    mount();
    await screen.findByTestId("people-row-7");
    expect(screen.queryByTestId(testId)).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("people-advanced"));
    fireEvent.click(screen.getByTestId(testId));
    fireEvent.click(screen.getByTestId("people-search"));
    await waitFor(() => expect(lastQuery().get(parameter)).toBe("true"));
  });
  it("loads selected developer project and idle evidence and collapses without navigation", async () => {
    mount();
    await screen.findByTestId("people-row-7");
    fireEvent.change(screen.getByTestId("people-unit"), {
      target: { value: "1" },
    });
    fireEvent.click(screen.getByTestId("people-expand-7"));
    const evidence = await screen.findByTestId("people-evidence-7");
    await within(evidence).findByText("프로젝트·대기 및 월별 근거");
    expect(within(evidence).getByText("대기")).toBeInTheDocument();
    expect(within(evidence).getByText("60")).toBeInTheDocument();
    const detailCall = fetcher.mock.calls.find(([url]) =>
      String(url).includes("/7/breakdown?"),
    );
    expect(String(detailCall?.[0])).toContain("year=2026&view=TOTAL");
    fireEvent.click(screen.getByTestId("people-expand-7"));
    expect(screen.queryByTestId("people-evidence-7")).not.toBeInTheDocument();
  });
  it("shows detail failure with retry without closing the selected row", async () => {
    detailFailure = true;
    mount();
    await screen.findByTestId("people-row-7");
    fireEvent.click(screen.getByTestId("people-expand-7"));
    await screen.findByText("마감 배분자료 없음");
    detailFailure = false;
    fireEvent.click(
      within(screen.getByTestId("people-evidence-7")).getByText("다시 시도"),
    );
    await screen.findByText("프로젝트·대기 및 월별 근거");
  });
  it("displays loading state instead of treating pending request as empty", () => {
    pending = true;
    mount();
    expect(screen.getByLabelText("불러오는 중")).toBeInTheDocument();
    expect(screen.queryByTestId("people-empty")).not.toBeInTheDocument();
  });
  it("displays empty response and resets all filters through the primary empty action", async () => {
    data.items = [];
    data.total = 0;
    mount();
    await screen.findByTestId("people-empty");
    fireEvent.click(screen.getByTestId("people-empty-reset"));
    await waitFor(() => expect(lastQuery().get("page")).toBe("0"));
  });
  it("retains filter input on failed request, renders field and request id, retries", async () => {
    mount();
    await screen.findByTestId("people-row-7");
    readFailure = true;
    fireEvent.change(screen.getByTestId("people-employee"), {
      target: { value: "FT" },
    });
    fireEvent.click(screen.getByTestId("people-search"));
    await screen.findByText("year: 연도를 확인하세요.");
    expect(screen.getByTestId("people-employee")).toHaveValue("FT");
    expect(screen.getByText("문의 번호 request-reference")).toBeInTheDocument();
    expect(screen.getByTestId("people-download")).toBeDisabled();
    readFailure = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await screen.findByTestId("people-row-7");
  });
  it("shows missing/zero denominator as dash and exposes warnings, leave and overlap independently", async () => {
    data.items[0] = {
      ...row(),
      on_leave: true,
      overlap: true,
      total_cost_amt: null,
      profit_amt: null,
    };
    data.warnings = ["급여 미업로드: 7", "가동률·가득률 분모 없음"];
    mount();
    await screen.findByTestId("people-row-7");
    expect(screen.getByTestId("people-row-7")).toHaveTextContent("휴직");
    expect(screen.getByTestId("people-row-7")).toHaveTextContent("1.0 초과");
    expect(screen.getByTestId("people-warnings")).toHaveTextContent(
      "급여 미업로드",
    );
    expect(
      within(screen.getByTestId("people-row-7")).getAllByText("–").length,
    ).toBeGreaterThan(1);
  });
  it("changes year, remembers it, resets paging and marks prior year read-only", async () => {
    mount();
    await screen.findByTestId("people-row-7");
    fireEvent.click(screen.getByTestId("people-previous-year"));
    await waitFor(() => expect(lastQuery().get("year")).toBe("2025"));
    expect(sessionStorage.getItem("UI-03.year")).toBe("2025");
    expect(screen.getByText("지난 해 · 읽기 전용")).toBeInTheDocument();
  });
  it("paginates zero based and sorts on approved field while preserving unit only locally", async () => {
    mount();
    await screen.findByTestId("people-row-7");
    fireEvent.click(screen.getByTestId("people-page-next"));
    await waitFor(() => expect(lastQuery().get("page")).toBe("1"));
    fireEvent.change(screen.getByTestId("people-page-size"), {
      target: { value: "20" },
    });
    await waitFor(() => expect(lastQuery().get("size")).toBe("20"));
    expect(lastQuery().get("page")).toBe("0");
    fireEvent.click(screen.getByTestId("people-sort-margin"));
    await waitFor(() => expect(lastQuery().get("sort")).toBe("margin_rt,asc"));
    fireEvent.change(screen.getByTestId("people-unit"), {
      target: { value: "1" },
    });
    expect(
      within(screen.getByTestId("people-row-7")).getByText("-100"),
    ).toBeInTheDocument();
  });
  it("downloads from same GET with XLSX accept and filters excluding pagination", async () => {
    const create = vi.fn(() => "blob:people");
    vi.stubGlobal(
      "URL",
      class extends URL {
        static createObjectURL = create;
        static revokeObjectURL = vi.fn();
      },
    );
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    mount();
    await screen.findByTestId("people-row-7");
    fireEvent.click(screen.getByTestId("people-download"));
    await waitFor(() => expect(create).toHaveBeenCalled());
    const call = fetcher.mock.calls.find(([, init]) => init?.headers?.Accept);
    const query = new URLSearchParams(String(call?.[0]).split("?")[1]);
    expect(query.get("year")).toBe("2026");
    expect(query.has("page")).toBe(false);
    expect(query.has("size")).toBe(false);
  });
  it("shows export error without losing displayed results", async () => {
    exportFailure = true;
    mount();
    await screen.findByTestId("people-row-7");
    fireEvent.click(screen.getByTestId("people-download"));
    await screen.findByText("다운로드 오류");
    expect(screen.getByTestId("people-row-7")).toBeInTheDocument();
  });
});
