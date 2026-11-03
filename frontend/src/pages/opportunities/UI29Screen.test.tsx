import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
  cleanup,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI29Screen, {
  OpportunityDrawer,
  OpportunityForecastTab,
} from "./UI29Screen";
import { OpportunityForm } from "./OpportunityForm";
import {
  getClosedOpportunities,
  opportunityFilters,
  opportunityPayload,
  type OpportunityDetail,
} from "./client";
import { opportunityRevenue, opportunityDifferenceRate } from "./quote";
import { response, failure } from "../developers/DeveloperTestSupport";
import styles from "./Opportunities.module.css";

const fixture: OpportunityDetail = {
  oppt_id: 100,
  oppt_no: "O-100",
  oppt_nm: "은행 구축",
  cust_ptnr_id: 1,
  bsn_part_id: 1,
  owner_user_id: null,
  bsn_stg_cd: "DISC",
  win_prblt_rt: 10,
  expc_amt: 3000000,
  cntrct_ty_cd: "MO",
  bgn_ym: "2026-11",
  mth_cnt: 3,
  next_action: "제안서 제출",
  next_action_dt: "2026-11-12",
  rmrk_desc: null,
  oppt_stts_cd: "OPEN",
  win_prj_id: null,
  updated_at: null,
  created_at: "2026-10-01T00:00:00",
  stale_warning: true,
  warnings: ["14일 초과 미갱신"],
  needs: [
    {
      need_id: 300,
      job_cd: "BE",
      grd_cd: "MID",
      psn_cnt: 2,
      bgn_ym: "2026-11",
      mth_cnt: 3,
      mth_mm_qty: 0.5,
      expc_untprc_amt: 1000000,
      rmrk_desc: null,
      psn_mth_cst_amt: 600000,
      min_untprc_amt: 600000,
      trgt_untprc_amt: 750000,
      warnings: [],
    },
  ],
  quote: {
    expc_sls_amt: 3000000,
    expc_cst_amt: 1800000,
    expc_mrgn_rt: 40,
    minimum_amount: 1800000,
    amount_difference: 0,
    amount_difference_rate: 0,
    cost_status: "VERIFIED",
    approval_state: "PENDING",
    approved_quote_amount: null,
    warnings: [],
  },
  approval_history: [
    {
      action: "REQUEST",
      opinion: null,
      user_id: null,
      created_at: "2026-10-01T00:00:00",
      expc_mrgn_rt: 40,
    },
  ],
};
function server() {
  const state = {
    rows: [structuredClone(fixture)],
    failList: false,
    failDetail: false,
    failWrite: false,
    failOptions: false,
    failForecast: false,
    forecastEmpty: false,
  };
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/codes/")) {
      if (state.failOptions) return failure(null, 500);
      const group = url.split("/").at(-1);
      return response(
        group === "JOB"
          ? [
              { code: "BE", name: "백엔드", attr1: "1000000" },
              { code: "FE", name: "프론트엔드", attr1: null },
            ]
          : group === "GRADE"
            ? [{ code: "MID", name: "중급" }]
            : [
                { code: "MO", name: "월단위" },
                { code: "TK", name: "턴키" },
                { code: "IN", name: "내부" },
              ],
      );
    }
    if (url.startsWith("/api/params/"))
      return response({ num_val: url.includes("PROP") ? 30 : 10 });
    if (url.startsWith("/api/partners/options"))
      return response([{ id: "1", name: "은행" }]);
    if (url === "/api/sales-parts/options")
      return response([{ id: "1", name: "영업1파트", hq: false }]);
    if (url === "/api/opportunities/forecast")
      return state.failForecast
        ? failure(null, 500)
        : response({
            items: state.forecastEmpty
              ? []
              : [
                  {
                    month: "2026-11",
                    project_revenue: 1000000,
                    opportunity_revenue: 3000000,
                    weighted_total: 1300000,
                    all_won_total: 4000000,
                  },
                ],
            kpi: {
              weighted_total: 1300000,
              opportunity_total: 3000000,
              all_won_total: 4000000,
              average_demand_mm: 1,
              average_supply_mm: 2,
              hiring_job_count: 0,
            },
            workforce: {
              months: ["2026-11"],
              message: state.forecastEmpty ? "예측할 미래월이 없습니다." : null,
              items: [],
            },
          });
    if (init?.method === "POST" || init?.method === "PUT") {
      if (state.failWrite) return failure("oppt_nm");
      const payload = JSON.parse(init.body as string);
      const row = {
        ...fixture,
        ...payload,
        needs: payload.needs.map((n: object) => ({
          ...fixture.needs[0],
          ...n,
        })),
      };
      state.rows = [row];
      return response(row);
    }
    if (url.startsWith("/api/opportunities?")) {
      if (state.failList) return failure(null, 500);
      const q = new URLSearchParams(url.split("?")[1]);
      const status = q.get("oppt_stts_cd") ?? "OPEN";
      const rows = state.rows.filter(
        (r) =>
          r.oppt_stts_cd === status &&
          (!q.get("q") || r.oppt_nm.includes(q.get("q")!)),
      );
      const page = Number(q.get("page") ?? 0),
        size = Number(q.get("size") ?? 50);
      return response({
        items: rows.slice(page * size, (page + 1) * size),
        page,
        size,
        total: rows.length,
        kpi: {
          open_count: rows.filter((r) => r.oppt_stts_cd === "OPEN").length,
          expected_amount: rows.reduce((s, r) => s + r.expc_amt, 0),
          weighted_amount: rows.reduce(
            (s, r) => s + (r.expc_amt * r.win_prblt_rt) / 100,
            0,
          ),
          needed_people: rows.reduce(
            (s, r) => s + r.needs.reduce((a, n) => a + n.psn_cnt, 0),
            0,
          ),
          stale_count: rows.filter((r) => r.stale_warning).length,
        },
      });
    }
    if (url.startsWith("/api/opportunities/"))
      return state.failDetail
        ? failure(null, 500)
        : response(
            state.rows.find((r) => r.oppt_id === Number(url.split("/").at(-1))),
          );
    throw new Error(`Unexpected opportunity request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
  return {
    state,
    fetcher,
    writes: () =>
      fetcher.mock.calls.filter((c) =>
        ["POST", "PUT"].includes(c[1]?.method ?? ""),
      ),
  };
}
const mount = (ui: React.ReactNode) =>
  render(<MemoryRouter>{ui}</MemoryRouter>);
beforeEach(() => {
  vi.stubGlobal("innerWidth", 1280);
  window.matchMedia = vi.fn().mockImplementation((query) => ({
    matches: false,
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }));
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("UI-29 기회", () => {
  it("renders four stage columns API KPI cards pending/stale marks and no AI", async () => {
    server();
    mount(<UI29Screen />);
    await screen.findByRole("button", { name: /은행 구축/ });
    expect(screen.getByTestId("opportunities-kpi")).toHaveTextContent(
      "3,000,000원",
    );
    for (const stage of ["disc", "prop", "nego", "pre"])
      expect(screen.getByTestId(`opportunity-column-${stage}`)).toHaveClass(
        styles.column,
      );
    expect(screen.getByText("승인 대기")).toBeVisible();
    expect(screen.getByText("장기 미갱신")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: /AI/ }),
    ).not.toBeInTheDocument();
  });
  it("opens actual selected detail and approval history without writing", async () => {
    const s = server();
    mount(<UI29Screen />);
    fireEvent.click(await screen.findByRole("button", { name: /은행 구축/ }));
    const dialog = await screen.findByRole("dialog", { name: "기회 상세" });
    await within(dialog).findByText("은행 구축");
    fireEvent.click(within(dialog).getByRole("tab", { name: "승인 이력" }));
    expect(await within(dialog).findByText("승인 요청")).toBeVisible();
    expect(within(dialog).getByText("기본 사용자")).toBeVisible();
    expect(s.writes()).toHaveLength(0);
    fireEvent.keyDown(dialog, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("shows unknown as warning and NA distinct from real zero", async () => {
    const s = server();
    s.state.rows[0].quote = {
      ...fixture.quote,
      cost_status: "UNKNOWN",
      expc_mrgn_rt: null,
      amount_difference_rate: null,
    };
    mount(
      <OpportunityDrawer
        id={100}
        onClose={() => {}}
        onEdit={() => {}}
        onRefresh={async () => {}}
      />,
    );
    await screen.findByText("은행 구축");
    fireEvent.click(screen.getByRole("tab", { name: "견적 점검" }));
    expect(screen.getByText("원가 미확인")).toBeVisible();
    expect(screen.getAllByText("N/A")).toHaveLength(2);
  });
  it.each(["WON", "LOST", "DELETED"] as const)(
    "hides edit on %s",
    async (status) => {
      const s = server();
      s.state.rows[0].oppt_stts_cd = status;
      mount(
        <OpportunityDrawer
          id={100}
          onClose={() => {}}
          onEdit={() => {}}
          onRefresh={async () => {}}
        />,
      );
      await screen.findByText("은행 구축");
      expect(
        screen.queryByRole("button", { name: "수정" }),
      ).not.toBeInTheDocument();
    },
  );
  it("hides write controls on narrow read-only surface", async () => {
    server();
    vi.stubGlobal("innerWidth", 767);
    mount(<UI29Screen />);
    await screen.findByRole("button", { name: /은행 구축/ });
    expect(
      screen.queryByRole("button", { name: /기회 등록/ }),
    ).not.toBeInTheDocument();
  });
  it("searches from page zero and provides empty reset", async () => {
    const s = server();
    mount(<UI29Screen />);
    await screen.findByRole("button", { name: /은행 구축/ });
    fireEvent.change(screen.getByPlaceholderText("기회명·고객 검색"), {
      target: { value: "없음" },
    });
    fireEvent.click(screen.getByRole("button", { name: "조회" }));
    await screen.findByText("조건에 맞는 기회가 없습니다");
    expect(
      s.fetcher.mock.calls.some(
        (c) => c[0].includes("q=%EC%97%86%EC%9D%8C") && c[0].includes("page=0"),
      ),
    ).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "필터 초기화" }));
    await screen.findByRole("button", { name: /은행 구축/ });
  });
  it("failed list has request id and retry without stale clickable cards", async () => {
    const s = server();
    s.state.failList = true;
    mount(<UI29Screen />);
    await screen.findByText(/문의 번호 developer-test/);
    expect(
      screen.queryByTestId("opportunity-card-100"),
    ).not.toBeInTheDocument();
    s.state.failList = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByTestId("opportunity-card-100");
  });
  it("failed detail shows error and no edit", async () => {
    const s = server();
    s.state.failDetail = true;
    mount(
      <OpportunityDrawer
        id={100}
        onClose={() => {}}
        onEdit={() => {}}
        onRefresh={async () => {}}
      />,
    );
    await screen.findByText(/문의 번호 developer-test/);
    expect(
      screen.queryByRole("button", { name: "수정" }),
    ).not.toBeInTheDocument();
  });
  it("closed tab merges WON LOST globally sorted and excludes deleted", async () => {
    const s = server();
    s.state.rows = [
      {
        ...fixture,
        oppt_id: 101,
        oppt_nm: "수주 건",
        oppt_stts_cd: "WON",
        win_prj_id: 9,
      },
      { ...fixture, oppt_id: 99, oppt_nm: "실주 건", oppt_stts_cd: "LOST" },
      { ...fixture, oppt_id: 102, oppt_nm: "삭제 건", oppt_stts_cd: "DELETED" },
    ];
    mount(<UI29Screen />);
    fireEvent.click(screen.getByRole("tab", { name: "종료 · 수주·실주" }));
    await screen.findByRole("button", { name: "수주 건" });
    expect(screen.getByRole("button", { name: "실주 건" })).toBeVisible();
    expect(screen.queryByText("삭제 건")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "프로젝트 보기" })).toHaveAttribute(
      "href",
      "/UI-01",
    );
  });
  it("closed pagination is applied after combining both result sets", async () => {
    const s = server();
    s.state.rows = Array.from({ length: 43 }, (_, i) => ({
      ...fixture,
      oppt_id: i + 1,
      oppt_stts_cd: i % 2 ? "WON" : "LOST",
    }));
    const p = await getClosedOpportunities({
      ...opportunityFilters,
      page: 1,
      size: 20,
    });
    expect(p.total).toBe(43);
    expect(p.items.map((r) => r.oppt_id)).toEqual(
      Array.from({ length: 20 }, (_, i) => i + 21),
    );
  });
  it("forecast reads other step API and renders actual amounts", async () => {
    server();
    mount(<OpportunityForecastTab />);
    await screen.findByText("앞으로 6개월 예상 매출");
    expect(screen.getAllByText("1,300,000원").length).toBeGreaterThan(0);
  });
  it("forecast empty message does not fabricate chart points", async () => {
    const s = server();
    s.state.forecastEmpty = true;
    mount(<OpportunityForecastTab />);
    await screen.findByText("예측할 미래월이 없습니다.");
    expect(
      screen.queryByTestId("forecast-month-2026-11"),
    ).not.toBeInTheDocument();
  });
  it("forecast failed request is recoverable", async () => {
    const s = server();
    s.state.failForecast = true;
    mount(<OpportunityForecastTab />);
    await screen.findByText(/문의 번호 developer-test/);
    s.state.failForecast = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("앞으로 6개월 예상 매출");
  });
});
describe("기회 전체 화면 편집", () => {
  it("creates from full-screen registration without generated identifiers", async () => {
    const s = server(),
      saved = vi.fn();
    mount(<OpportunityForm onCancel={() => {}} onSaved={saved} />);
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
    );
    fireEvent.change(screen.getByTestId("opportunity-name"), {
      target: { value: "새 기회" },
    });
    fireEvent.change(screen.getByTestId("opportunity-customer"), {
      target: { value: "1" },
    });
    fireEvent.change(screen.getByTestId("opportunity-part"), {
      target: { value: "1" },
    });
    fireEvent.change(screen.getByTestId("opportunity-contract-type"), {
      target: { value: "MO" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    expect(s.writes()[0][1]?.method).toBe("POST");
    const payload = JSON.parse(s.writes()[0][1]!.body as string);
    expect(payload.oppt_nm).toBe("새 기회");
    expect(payload).not.toHaveProperty("oppt_id");
    expect(payload).not.toHaveProperty("oppt_stts_cd");
  });
  it("preserves need identity omits readonly snapshots and GETs fresh DB data", async () => {
    const s = server(),
      saved = vi.fn();
    mount(
      <OpportunityForm initial={fixture} onCancel={() => {}} onSaved={saved} />,
    );
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
    );
    fireEvent.change(screen.getByLabelText("기회명 *"), {
      target: { value: "수정 기회" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    const payload = JSON.parse(s.writes()[0][1]!.body as string);
    expect(payload.needs[0].need_id).toBe(300);
    expect(payload).not.toHaveProperty("quote");
    expect(payload).not.toHaveProperty("oppt_no");
    expect(payload.needs[0]).not.toHaveProperty("psn_mth_cst_amt");
    expect(
      s.fetcher.mock.calls.some(
        (c) => c[0] === "/api/opportunities/100" && !c[1]?.method,
      ),
    ).toBe(true);
  });
  it("stage default then explicit probability override is sent", async () => {
    const s = server();
    mount(
      <OpportunityForm
        initial={fixture}
        onCancel={() => {}}
        onSaved={() => {}}
      />,
    );
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
    );
    fireEvent.change(screen.getByTestId("opportunity-stage"), {
      target: { value: "PROP" },
    });
    await waitFor(() =>
      expect(screen.getByTestId("opportunity-probability")).toHaveValue(30),
    );
    fireEvent.change(screen.getByTestId("opportunity-probability"), {
      target: { value: "55" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(s.writes()).toHaveLength(1));
    expect(JSON.parse(s.writes()[0][1]!.body as string).win_prblt_rt).toBe(55);
  });
  it("missing difference reason prevents request and displays required field", async () => {
    const s = server();
    mount(
      <OpportunityForm
        initial={fixture}
        onCancel={() => {}}
        onSaved={() => {}}
      />,
    );
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
    );
    fireEvent.change(screen.getByTestId("opportunity-amount"), {
      target: { value: "1" },
    });
    expect(screen.getByTestId("opportunity-difference-reason")).toBeRequired();
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(s.writes()).toHaveLength(0);
  });
  it("outside period warns but allows save with original need month", async () => {
    const s = server();
    mount(
      <OpportunityForm
        initial={fixture}
        onCancel={() => {}}
        onSaved={() => {}}
      />,
    );
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
    );
    fireEvent.change(screen.getByTestId("opportunity-start"), {
      target: { value: "2026-12" },
    });
    expect(screen.getByText(/경고 후 저장하며 실제 필요 기간/)).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await waitFor(() => expect(s.writes()).toHaveLength(1));
    expect(JSON.parse(s.writes()[0][1]!.body as string).needs[0].bgn_ym).toBe(
      "2026-11",
    );
  });
  it("failed save retains inputs and singular field error", async () => {
    const s = server();
    s.state.failWrite = true;
    mount(
      <OpportunityForm
        initial={fixture}
        onCancel={() => {}}
        onSaved={() => {}}
      />,
    );
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
    );
    fireEvent.change(screen.getByTestId("opportunity-name"), {
      target: { value: "보존할 입력" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    await screen.findByText(/문의 번호 developer-test/);
    expect(screen.getByTestId("opportunity-name")).toHaveValue("보존할 입력");
    expect(screen.getByTestId("opportunity-name")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
  });
  it("unknown JOB default is visibly zero/input required and prevents final save", async () => {
    const s = server();
    mount(
      <OpportunityForm
        initial={fixture}
        onCancel={() => {}}
        onSaved={() => {}}
      />,
    );
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "저장" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "행 추가" }));
    fireEvent.change(screen.getByLabelText("2번째 직무"), {
      target: { value: "FE" },
    });
    expect(screen.getByLabelText("2번째 예상 단가")).toHaveValue(0);
    expect(screen.getByText("입력 필요")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(s.writes()).toHaveLength(0);
  });
  it("cancel dirty requires confirmation", async () => {
    server();
    const cancel = vi.fn();
    mount(
      <OpportunityForm
        initial={fixture}
        onCancel={cancel}
        onSaved={() => {}}
      />,
    );
    fireEvent.change(screen.getByTestId("opportunity-name"), {
      target: { value: "미저장" },
    });
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(cancel).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "나가기" }));
    expect(cancel).toHaveBeenCalledTimes(1);
  });
  it("closed record cannot render editable form", () => {
    server();
    mount(
      <OpportunityForm
        initial={{ ...fixture, oppt_stts_cd: "WON" }}
        onCancel={() => {}}
        onSaved={() => {}}
      />,
    );
    expect(screen.getByText("종료된 기회는 수정할 수 없습니다.")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  });
  it("BigInt preview sums decimal products then floors and rounds HALF_UP", () => {
    const n = {
      ...fixture.needs[0],
      psn_cnt: 1,
      mth_cnt: 1,
      mth_mm_qty: 0.33,
      expc_untprc_amt: 1,
    };
    expect(opportunityRevenue([n, n, n, n])).toBe(1n);
    expect(opportunityDifferenceRate(1n, 6)).toBe("16.67%");
    expect(opportunityDifferenceRate(-1n, 6)).toBe("-16.67%");
    expect(opportunityDifferenceRate(10n, 0)).toBe("N/A");
    expect(opportunityPayload(fixture).needs[0]).not.toHaveProperty("warnings");
  });
});
