import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, useLocation } from "react-router-dom";
import OpportunityPipelineScreen, {
  OpportunityActionDialog,
} from "./OpportunityPipelineScreen";
import WorkforceDemandScreen, {
  ForecastContent,
  OpportunityScenarios,
} from "./WorkforceDemandScreen";
import {
  exportOpportunities,
  type ForecastPage,
  type OpportunityDetail,
  type OpportunityPage,
} from "./client";
import {
  PipelineUnsavedDialog,
  PipelineTabOverflowDialog,
  PipelineScreenSearchDialog,
} from "./screens";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, data, meta: {} }), {
    headers: { "Content-Type": "application/json" },
  });
const rejection = (message: string, field = "bsn_stg_cd") =>
  new Response(
    JSON.stringify({
      success: false,
      message,
      field,
      code: "QUOTE_APPROVAL_REQUIRED",
      requestId: "request-test",
    }),
    { status: 409, headers: { "Content-Type": "application/json" } },
  );
let row: OpportunityDetail,
  page: OpportunityPage,
  forecast: ForecastPage,
  fetcher: ReturnType<typeof vi.fn>,
  failStage: boolean,
  failRead: boolean;
const mount = (child: React.ReactNode) =>
  render(<MemoryRouter>{child}</MemoryRouter>);
beforeEach(() => {
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-10-23T00:00:00Z"));
  failStage = false;
  failRead = false;
  row = {
    oppt_id: 7,
    oppt_no: "O-07",
    oppt_nm: "계약 검토 기회",
    cust_ptnr_id: 3,
    bsn_part_id: 1,
    bsn_stg_cd: "DISC",
    win_prblt_rt: 55,
    expc_amt: 90000000,
    cntrct_ty_cd: "MO",
    bgn_ym: "2026-11",
    mth_cnt: 4,
    next_action: "제안서 검토",
    next_action_dt: "2026-10-29",
    oppt_stts_cd: "OPEN",
    win_prj_id: null,
    created_at: "2026-10-01T00:00:00",
    updated_at: null,
    stale_warning: true,
    warnings: [],
    needs: [
      {
        need_id: 11,
        job_cd: "BE",
        grd_cd: "SR",
        psn_cnt: 2,
        bgn_ym: "2026-11",
        mth_cnt: 4,
        mth_mm_qty: 1,
        expc_untprc_amt: 10000000,
        psn_mth_cst_amt: 8000000,
        min_untprc_amt: 8000000,
        trgt_untprc_amt: 10000000,
        warnings: [],
      },
    ],
    quote: {
      expc_sls_amt: 80000000,
      expc_cst_amt: 64000000,
      expc_mrgn_rt: 20,
      cost_status: "VERIFIED",
      approval_state: "PENDING",
      approved_quote_amount: null,
      warnings: [],
    },
    approval_history: [],
  };
  page = {
    items: [row],
    total: 1,
    page: 0,
    size: 50,
    kpi: {
      open_count: 1,
      expected_amount: 90000000,
      weighted_amount: 49500000,
      needed_people: 2,
      stale_count: 1,
    },
  };
  forecast = {
    items: [
      {
        month: "2026-11",
        project_revenue: 100,
        opportunity_revenue: 55,
        weighted_total: 155,
        all_won_total: 200,
      },
    ],
    page: 0,
    size: 50,
    total: 1,
    kpi: {
      weighted_total: 155,
      opportunity_total: 55,
      all_won_total: 200,
      average_demand_mm: 1.41,
      average_supply_mm: 1,
      hiring_job_count: 1,
    },
    workforce: {
      items: [
        {
          month: "2026-11",
          job_cd: "BE",
          grd_cd: "SR",
          supply_mm: 1,
          demand_mm: 1.41,
          remaining_mm: -0.41,
          shortage_mm: 0.41,
          available_mm: 1,
          confirmed_mm: 0,
          extension_mm: 0,
          temporary_mm: 0.5,
          utilization_rate: null,
          idle_cost: 0,
          estimated: true,
          baseline_operating_profit: null,
          average_labor_cost: 0,
          warnings: ["원가 미확인"],
        },
      ],
      page: 0,
      size: 50,
      total: 1,
      months: ["2026-11"],
      target_utilization_rate: 90,
      message: null,
      shortages: [
        {
          job_cd: "BE",
          grd_cd: "SR",
          maximum_shortage_mm: 0.41,
          maximum_shortage_people: 1,
          first_shortage_month: "2026-11",
          recommended_hiring_month: "2026-09",
          review_immediately: true,
          candidate_count: 2,
        },
      ],
    },
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === "/api/auth/csrf")
      return response({ headerName: "X-CSRF-TOKEN", csrfToken: "test-csrf" });
    if (url === "/api/me/permissions")
      return response({
        menus: [{ menuId: 29, screenId: "UI-29", children: [] }],
        permissions: [
          {
            menuId: 29,
            reg: true,
            mdfcn: true,
            del: true,
            dwnld: true,
            cnfrm: true,
          },
        ],
      });
    if (url.startsWith("/api/codes/"))
      return response([
        {
          code: url.endsWith("JOB") ? "BE" : "SR",
          name: url.endsWith("JOB") ? "백엔드" : "고급",
        },
      ]);
    if (url.startsWith("/api/opportunities/forecast"))
      return failRead
        ? rejection("예측 조회 실패", "from")
        : response(forecast);
    if (url.startsWith("/api/opportunities/export"))
      return response({
        file_name: "영업파이프라인_20261023.xlsx",
        media_type:
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        content_base64: btoa("PK"),
      });
    if (url.startsWith("/api/opportunities?"))
      return failRead ? rejection("목록 조회 실패") : response(page);
    if (url === "/api/opportunities/7") return response(row);
    if (url === "/api/opportunities/7/stage") {
      if (failStage) return rejection("견적 승인 후 단계를 변경하세요.");
      const body = JSON.parse(String(init?.body));
      row = { ...row, bsn_stg_cd: body.bsn_stg_cd, win_prblt_rt: 30 };
      page = { ...page, items: [row] };
      return response(row);
    }
    if (url === "/api/opportunities/7/approve")
      return response({
        ...row,
        quote: { ...row.quote, approval_state: "APPROVED" },
      });
    if (
      ["reject", "lose", "disable", "win"].some(
        (a) => url === `/api/opportunities/7/${a}`,
      )
    )
      return response(row);
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("single opportunity pipeline", () => {
  it("keeps write controls unavailable when current permissions deny them", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      url === "/api/me/permissions"
        ? Promise.resolve(response({ menus: [], permissions: [] }))
        : original(url, init),
    );
    mount(<OpportunityPipelineScreen onEdit={vi.fn()} />);
    await screen.findByTestId("pipeline-card-7");
    expect(screen.queryByTestId("opportunity-create")).not.toBeInTheDocument();
    expect(screen.getByTestId("pipeline-stage-7")).toBeDisabled();
    fireEvent.click(screen.getByTestId("pipeline-open-7"));
    await screen.findByRole("heading", { name: row.oppt_nm });
    expect(screen.queryByTestId("opportunity-approve")).not.toBeInTheDocument();
    expect(screen.queryByTestId("opportunity-edit")).not.toBeInTheDocument();
    expect(screen.getByTestId("opportunity-win")).toBeDisabled();
    expect(screen.getByTestId("opportunity-lose")).toBeDisabled();
    expect(screen.getByTestId("opportunity-disable")).toBeDisabled();
  });
  it("renders four OPEN columns, saved override, pending and stale badges without AI", async () => {
    mount(<OpportunityPipelineScreen onEdit={vi.fn()} />);
    await screen.findByTestId("pipeline-card-7");
    expect(screen.getByText("승인 대기")).toBeVisible();
    expect(screen.getByText("장기 미갱신")).toBeVisible();
    expect(screen.getByText("90,000,000원 · 55%")).toBeVisible();
    for (const stage of ["disc", "prop", "nego", "pre"])
      expect(screen.getByTestId(`pipeline-column-${stage}`)).toBeVisible();
    expect(
      screen.queryByRole("button", { name: /AI|인력 추천/ }),
    ).not.toBeInTheDocument();
  });
  it("hands create and edit to the existing resource editor without adding routes", async () => {
    const edit = vi.fn();
    mount(<OpportunityPipelineScreen onEdit={edit} />);
    await screen.findByTestId("pipeline-card-7");
    fireEvent.click(screen.getByTestId("opportunity-create"));
    expect(edit).toHaveBeenCalledWith();
    fireEvent.click(screen.getByTestId("pipeline-open-7"));
    await screen.findByRole("heading", { name: row.oppt_nm });
    fireEvent.click(screen.getByTestId("opportunity-edit"));
    expect(edit).toHaveBeenLastCalledWith(7);
  });
  it("keeps card in its original column when server refuses a move", async () => {
    failStage = true;
    mount(<OpportunityPipelineScreen />);
    await screen.findByTestId("pipeline-card-7");
    fireEvent.change(screen.getByTestId("pipeline-stage-7"), {
      target: { value: "NEGO" },
    });
    await screen.findByText("견적 승인 후 단계를 변경하세요.");
    expect(
      within(screen.getByTestId("pipeline-column-disc")).getByTestId(
        "pipeline-card-7",
      ),
    ).toBeVisible();
    expect(
      within(screen.getByTestId("pipeline-column-nego")).queryByTestId(
        "pipeline-card-7",
      ),
    ).not.toBeInTheDocument();
    expect(screen.getByTestId("pipeline-stage-7")).toHaveValue("DISC");
  });
  it("requeries after a successful move rather than guessing server probability", async () => {
    mount(<OpportunityPipelineScreen />);
    await screen.findByTestId("pipeline-card-7");
    fireEvent.change(screen.getByTestId("pipeline-stage-7"), {
      target: { value: "PROP" },
    });
    await waitFor(() =>
      expect(
        within(screen.getByTestId("pipeline-column-prop")).getByTestId(
          "pipeline-card-7",
        ),
      ).toBeVisible(),
    );
    expect(screen.getByText("90,000,000원 · 30%")).toBeVisible();
  });
  it("reads selected detail and need rows, then opens pending-only approval", async () => {
    mount(<OpportunityPipelineScreen />);
    await screen.findByTestId("pipeline-card-7");
    fireEvent.click(screen.getByTestId("pipeline-open-7"));
    await screen.findByRole("heading", { name: row.oppt_nm });
    fireEvent.click(screen.getByRole("tab", { name: "필요 인력" }));
    expect(screen.getByText("BE SR")).toBeVisible();
    fireEvent.click(screen.getByRole("tab", { name: "견적 점검·승인 이력" }));
    expect(screen.getByText(/견적 매출 80,000,000/)).toBeVisible();
    fireEvent.click(screen.getByTestId("opportunity-approve"));
    expect(screen.getByTestId("opportunity-action-dialog")).toBeVisible();
    expect(screen.getByTestId("opportunity-win")).toBeDisabled();
  });
  it("hides approval and editing on a closed source and retains project link", async () => {
    row = { ...row, oppt_stts_cd: "WON", win_prj_id: 31 };
    page = { ...page, items: [row] };
    mount(<OpportunityPipelineScreen onEdit={vi.fn()} />);
    await screen.findByTestId("pipeline-list");
    fireEvent.click(screen.getByRole("tab", { name: "종료 · 수주·실주" }));
    await screen.findByTestId("closed-open-7");
    fireEvent.click(screen.getByTestId("closed-open-7"));
    await screen.findByText("종료된 기회는 수정할 수 없습니다.");
    expect(screen.queryByTestId("opportunity-edit")).not.toBeInTheDocument();
    expect(screen.queryByTestId("opportunity-approve")).not.toBeInTheDocument();
    expect(screen.getByTestId("opportunity-project-link")).toHaveAttribute(
      "href",
      "/UI-01?open=31",
    );
  });
  it("shows empty and failed list states with retry", async () => {
    failRead = true;
    mount(<OpportunityPipelineScreen />);
    await screen.findByText("목록 조회 실패");
    expect(screen.queryByTestId("pipeline-card-7")).not.toBeInTheDocument();
    failRead = false;
    page = { ...page, items: [], total: 0 };
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByText("조건에 맞는 기회가 없습니다.");
  });
  it("searches only on submit and changes the closed status filter", async () => {
    mount(<OpportunityPipelineScreen />);
    await screen.findByTestId("pipeline-card-7");
    fireEvent.change(screen.getByTestId("pipeline-search"), {
      target: { value: "고객" },
    });
    fireEvent.click(screen.getByTestId("pipeline-query"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([u]) =>
          String(u).includes("q=%EA%B3%A0%EA%B0%9D"),
        ),
      ).toBe(true),
    );
    fireEvent.click(screen.getByRole("tab", { name: "종료 · 수주·실주" }));
    fireEvent.change(screen.getByTestId("pipeline-status"), {
      target: { value: "LOST" },
    });
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([u]) =>
          String(u).includes("oppt_stts_cd=LOST"),
        ),
      ).toBe(true),
    );
  });
});
describe("lifecycle confirmations", () => {
  it.each(["reject", "lose"] as const)(
    "requires a separate nonempty %s reason before writing",
    async (action) => {
      mount(
        <OpportunityActionDialog
          row={row}
          action={action}
          onCancel={vi.fn()}
          onSaved={vi.fn(async () => {})}
        />,
      );
      fireEvent.click(screen.getByTestId("opportunity-action-save"));
      await screen.findByText("사유를 입력하세요.");
      expect(fetcher).not.toHaveBeenCalled();
      fireEvent.change(screen.getByTestId("opportunity-reason"), {
        target: { value: "사유" },
      });
      fireEvent.click(screen.getByTestId("opportunity-action-save"));
      await waitFor(() =>
        expect(fetcher).toHaveBeenCalledWith(
          `/api/opportunities/7/${action}`,
          expect.objectContaining({
            method: "POST",
            body: JSON.stringify({ reason: "사유" }),
          }),
        ),
      );
    },
  );
  it("allows empty approval opinion and reloads before closing", async () => {
    const order: string[] = [];
    mount(
      <OpportunityActionDialog
        row={row}
        action="approve"
        onCancel={() => order.push("close")}
        onSaved={async () => {
          order.push("reload");
        }}
      />,
    );
    fireEvent.click(screen.getByTestId("opportunity-action-save"));
    await waitFor(() => expect(order).toEqual(["reload", "close"]));
    expect(fetcher).toHaveBeenCalledWith(
      "/api/opportunities/7/approve",
      expect.objectContaining({ body: JSON.stringify({ opinion: "" }) }),
    );
  });
  it("keeps server validation and entered reason visible", async () => {
    fetcher.mockImplementation(async () =>
      rejection("사유를 확인하세요.", "reason"),
    );
    mount(
      <OpportunityActionDialog
        row={row}
        action="lose"
        onCancel={vi.fn()}
        onSaved={vi.fn(async () => {})}
      />,
    );
    fireEvent.change(screen.getByTestId("opportunity-reason"), {
      target: { value: "기록" },
    });
    fireEvent.click(screen.getByTestId("opportunity-action-save"));
    await screen.findByText("reason: 사유를 확인하세요.");
    expect(screen.getByTestId("opportunity-reason")).toHaveValue("기록");
  });
  it("confirms logical disable and does not submit on cancel", () => {
    const cancel = vi.fn();
    mount(
      <OpportunityActionDialog
        row={row}
        action="disable"
        onCancel={cancel}
        onSaved={vi.fn(async () => {})}
      />,
    );
    expect(screen.getByText(/원본·필요 인력·이력은 보존/)).toBeVisible();
    fireEvent.click(screen.getByTestId("opportunity-action-cancel"));
    expect(cancel).toHaveBeenCalledOnce();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it("uses readonly quote amount and blocks outside-contract plans without clipping", () => {
    row = { ...row, quote: { ...row.quote, approval_state: "NONE" } };
    mount(
      <OpportunityActionDialog
        row={row}
        action="win"
        onCancel={vi.fn()}
        onSaved={vi.fn(async () => {})}
      />,
    );
    expect(screen.getByTestId("win-amount")).toHaveAttribute("readonly");
    expect(screen.getByTestId("win-amount")).toHaveValue("80000000");
    fireEvent.change(screen.getByTestId("win-end"), {
      target: { value: "2027-01-31" },
    });
    expect(screen.getByText(/계약기간 밖 필요 인력 계획/)).toBeVisible();
    expect(screen.getByTestId("opportunity-action-save")).toBeDisabled();
    expect(row.needs[0].mth_cnt).toBe(4);
  });
  it("blocks UNKNOWN after approval", () => {
    row = {
      ...row,
      quote: {
        ...row.quote,
        approval_state: "APPROVED",
        cost_status: "UNKNOWN",
      },
    };
    mount(
      <OpportunityActionDialog
        row={row}
        action="win"
        onCancel={vi.fn()}
        onSaved={vi.fn(async () => {})}
      />,
    );
    expect(screen.getByTestId("opportunity-action-save")).toBeDisabled();
  });
});
describe("shared forecasts", () => {
  it("uses workforce total for paging and keeps the selected page size", async () => {
    forecast.workforce.total = 73;
    mount(<WorkforceDemandScreen />);
    await screen.findByTestId("opportunity-forecast-content");
    expect(screen.getByRole("button", { name: "다음 페이지" })).toBeEnabled();
    fireEvent.change(screen.getByRole("combobox", { name: "목록 건수" }), {
      target: { value: "20" },
    });
    await screen.findByTestId("opportunity-forecast-content");
    expect(screen.getByRole("combobox", { name: "목록 건수" })).toHaveValue(
      "20",
    );
    fireEvent.click(screen.getByRole("button", { name: "다음 페이지" }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => {
          const params = new URL(String(url), "http://test.invalid")
            .searchParams;
          return params.get("page") === "1" && params.get("size") === "20";
        }),
      ).toBe(true),
    );
    expect(screen.getByRole("combobox", { name: "목록 건수" })).toHaveValue(
      "20",
    );
  });
  it("does not hide workforce rows when a later page has no revenue rows", async () => {
    forecast.items = [];
    forecast.total = 0;
    mount(<WorkforceDemandScreen />);
    await screen.findByTestId("opportunity-forecast-content");
    expect(screen.getByText("원가 미확인")).toBeVisible();
    expect(
      screen.queryByText("조건에 맞는 예측 결과가 없습니다."),
    ).not.toBeInTheDocument();
  });
  it("keeps results hidden while a forecast is loading", async () => {
    let resolve!: (value: Response) => void;
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      url.startsWith("/api/opportunities/forecast")
        ? new Promise<Response>((done) => {
            resolve = done;
          })
        : original(url, init),
    );
    mount(<WorkforceDemandScreen />);
    expect(
      screen.queryByTestId("opportunity-forecast-content"),
    ).not.toBeInTheDocument();
    resolve(response(forecast));
    await screen.findByTestId("opportunity-forecast-content");
  });
  it("shows a forbidden forecast response without exposing stale values", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      url.startsWith("/api/opportunities/forecast")
        ? Promise.resolve(
            new Response(
              JSON.stringify({
                success: false,
                code: "FORBIDDEN",
                message: "조회 권한이 없습니다.",
                requestId: "forecast-denied",
              }),
              {
                status: 403,
                headers: { "Content-Type": "application/json" },
              },
            ),
          )
        : original(url, init),
    );
    mount(<WorkforceDemandScreen />);
    await screen.findByText("조회 권한이 없습니다.");
    expect(
      screen.queryByTestId("opportunity-forecast-content"),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "다시 시도" })).toBeVisible();
  });
  function Destination() {
    const location = useLocation();
    return (
      <output data-testid="destination">
        {JSON.stringify({ path: location.pathname, state: location.state })}
      </output>
    );
  }
  it("renders original values, stacks, warnings and candidate context without replacing UI33", () => {
    mount(
      <>
        <ForecastContent data={forecast} />
        <Destination />
      </>,
    );
    expect(screen.getByTestId("revenue-stack-2026-11")).toBeVisible();
    expect(screen.getByText("즉시 검토")).toBeVisible();
    expect(screen.getByText("원가 미확인")).toBeVisible();
    fireEvent.click(screen.getByTestId("forecast-candidates-be-sr"));
    expect(screen.getByTestId("destination")).toHaveTextContent(
      '"path":"/UI-33"',
    );
    expect(screen.getByTestId("destination")).toHaveTextContent(
      '"workspace":"candidates"',
    );
    expect(screen.getByTestId("destination")).toHaveTextContent('"job":"BE"');
  });
  it("offers at most three local scenarios, no endpoint or invented profit baseline", async () => {
    mount(<OpportunityScenarios data={forecast} />);
    for (let i = 0; i < 3; i++) {
      fireEvent.click(screen.getByTestId("scenario-add"));
      await screen.findByTestId(`scenario-${i + 1}`);
    }
    expect(screen.getByTestId("scenario-add")).toBeDisabled();
    expect(screen.getAllByText("– (기준 원천 없음)")).toHaveLength(3);
    fireEvent.change(screen.getByTestId("scenario-mm-1"), {
      target: { value: "2" },
    });
    expect(screen.getAllByText("원가 미확인 · 인건비 증감 0원")).toHaveLength(
      3,
    );
    expect(fetcher).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId("scenario-remove-1"));
    expect(screen.getByTestId("scenario-add")).toBeEnabled();
    fireEvent.click(screen.getByTestId("scenario-reset"));
    expect(screen.queryByTestId("scenario-2")).not.toBeInTheDocument();
  });
  it("UI17 loads common forecast and preserves YEAR selection", async () => {
    mount(<WorkforceDemandScreen />);
    await screen.findByTestId("opportunity-forecast-content");
    fireEvent.click(screen.getByTestId("demand-year-next"));
    await waitFor(() =>
      expect(sessionStorage.getItem("UI-17.year")).toBe("2027"),
    );
    expect(
      fetcher.mock.calls.some(([u]) => String(u).includes("year=2027")),
    ).toBe(true);
  });
  it("UI17 shows no-future-months empty state instead of fake chart points", async () => {
    forecast = {
      ...forecast,
      items: [],
      total: 0,
      workforce: {
        ...forecast.workforce,
        months: [],
        items: [],
        message: "예측할 미래월이 없습니다.",
      },
    };
    mount(<WorkforceDemandScreen />);
    await screen.findByText("예측할 미래월이 없습니다.");
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });
  it("UI28 forecast panel reports failures and retries without modifying sources", async () => {
    failRead = true;
    mount(<WorkforceDemandScreen screenId="UI-28" scenarios />);
    await screen.findByText("예측 조회 실패");
    failRead = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByTestId("workforce-scenarios");
    expect(
      fetcher.mock.calls.every(
        ([, init]) => !init || !init.method || init.method === "GET",
      ),
    ).toBe(true);
  });
  it("exports all filters without undocumented sort/page/size parameters", async () => {
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: vi.fn(() => "blob:export"),
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: vi.fn(),
    });
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});
    await exportOpportunities({
      q: "고객",
      page: 2,
      size: 20,
      sort: "oppt_nm,desc",
      oppt_stts_cd: "OPEN",
    });
    const url = String(fetcher.mock.calls[0][0]);
    expect(url).not.toMatch(/page=|size=|sort=/);
    expect(url).toContain("oppt_stts_cd=OPEN");
    expect(click).toHaveBeenCalledOnce();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:export");
  });
});
describe("existing shared dialogs remain reused", () => {
  it("SCR-UI-02-M8 / SCR-UI-27-M1 protects unsaved draft", () => {
    const leave = vi.fn(),
      stay = vi.fn();
    mount(<PipelineUnsavedDialog onLeave={leave} onContinue={stay} />);
    fireEvent.click(screen.getByRole("button", { name: "계속 작성" }));
    expect(stay).toHaveBeenCalledOnce();
    expect(leave).not.toHaveBeenCalled();
  });
  it("SCR-UI-16-M2 confirms the named oldest tab", () => {
    const confirm = vi.fn();
    mount(
      <PipelineTabOverflowDialog
        candidate="영업 파이프라인"
        onConfirm={confirm}
        onCancel={vi.fn()}
      />,
    );
    expect(screen.getByText(/후보: 영업 파이프라인/)).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "닫기 확인" }));
    expect(confirm).toHaveBeenCalledOnce();
  });
  it("SCR-UI-16-M6 searches supplied menu data and selects its existing screen", () => {
    const select = vi.fn();
    mount(
      <PipelineScreenSearchDialog
        menus={[
          { screenId: "UI-29", name: "영업 파이프라인" },
          { screenId: "UI-17", name: "인력 수요 예측" },
        ]}
        onSelect={select}
        onCancel={vi.fn()}
      />,
    );
    fireEvent.change(screen.getByTestId("screen-search-input"), {
      target: { value: "영업" },
    });
    expect(screen.queryByText(/인력 수요 예측 UI-17/)).not.toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "영업 파이프라인 UI-29" }),
    );
    expect(select).toHaveBeenCalledWith("UI-29");
  });
});
