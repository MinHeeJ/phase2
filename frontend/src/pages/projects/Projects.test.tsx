import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI01Screen from "./UI01Screen";
import UI33Screen from "./UI33Screen";
import { ProjectDrawer } from "./ProjectDrawer";
import { ProjectForm } from "./ProjectForm";
import { emptyProject, projectPayload, type ProjectDetail } from "./client";
import { response, failure } from "../developers/DeveloperTestSupport";

const fixture: ProjectDetail = {
  ...emptyProject,
  prj_id: 1,
  prj_no: "P-2601",
  prj_nm: "신규 업무",
  cntrct_ty_cd: "MO",
  prj_stts_cd: "RUN",
  cust_ptnr_id: 1,
  custName: "고객사",
  bsn_part_id: 1,
  partName: "본사(직접 영업)",
  cntrct_bgn_dt: "2026-03-01",
  cntrct_end_dt: "2026-12-31",
  cur_end_dt: "2026-12-31",
  cur_cntrct_amt: 500000000,
  init_cntrct_amt: 500000000,
  trgt_mrgn_rt: 20,
  trgt_cst_amt: 400000000,
  cntrct_mm_qty: 0,
  use_yn: "Y",
  pmName: null,
  assignmentCount: 3,
  prj_skl: ["JAVA"],
  stlm_mthd_cd: "MONTH",
};
const codes: Record<string, { code: string; name: string }[]> = {
  CONTRACT_TYPE: [
    { code: "TK", name: "턴키" },
    { code: "MO", name: "월단위" },
    { code: "DP", name: "파견" },
    { code: "IN", name: "내부" },
  ],
  PROJECT_STATUS: [
    { code: "PLAN", name: "수주예정" },
    { code: "RUN", name: "진행" },
    { code: "HOLD", name: "보류" },
    { code: "END", name: "종료" },
    { code: "LOST", name: "실주" },
  ],
  SETTLE_METHOD: [
    { code: "STAGE", name: "단계별" },
    { code: "MONTH", name: "월 고정" },
    { code: "DAY", name: "일 단가" },
    { code: "HOUR", name: "시간 단가" },
  ],
  INDUSTRY: [{ code: "SW", name: "SW 개발" }],
  SKILL: [
    { code: "JAVA", name: "Java" },
    { code: "REACT", name: "React" },
  ],
};
function projectServer() {
  const state = {
    rows: [{ ...fixture }],
    failList: false,
    failWrite: false,
    failCodes: false,
    failDetail: false,
    notice: 2,
    confirmed: false,
    failAssignments: false,
    assignments: [] as {
      name: string;
      job: string;
      beginDate: string;
      endDate: string | null;
      assignmentRate: number;
      status: string;
    }[],
  };
  const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.startsWith("/api/codes/"))
      return state.failCodes
        ? failure()
        : response(codes[path.split("/").at(-1)!] ?? []);
    if (path.startsWith("/api/params/BUDGET_RATE"))
      return response({ num_val: 80 });
    if (path === "/api/partners/options?kind=CUST")
      return response([{ id: "1", name: "고객사" }]);
    if (path === "/api/sales-parts/options")
      return response([{ id: "1", name: "본사", hq: true }]);
    if (path.startsWith("/api/developers/options"))
      return response([
        {
          id: 1,
          name: "개발자",
          mngNo: "K-0001",
          mainJob: "백엔드",
          empType: "FT",
        },
      ]);
    if (path === "/api/projects/options")
      return response([
        {
          id: 9,
          no: "P-2501",
          name: "선행 업무",
          type: "MO",
          custName: "고객사",
        },
      ]);
    if (path.endsWith("/part-changes"))
      return response([
        {
          bsn_part_id: 1,
          aply_bgn_ym: "2026-03",
          partName: "본사(직접 영업)",
          rsn_desc: null,
        },
      ]);
    if (path.endsWith("/contract-changes"))
      return response({
        items: state.confirmed ? [{ cntrct_chg_stts_cd: "CONFIRM" }] : [],
      });
    if (path.endsWith("/assignments"))
      return state.failAssignments
        ? failure(null, 500)
        : response(state.assignments);
    if (path.endsWith("/history"))
      return response([
        {
          changedAt: "2026-10-23T10:00:00",
          field: "prj_nm",
          before: "이전",
          after: "신규 업무",
          actor: "기본 사용자",
        },
      ]);
    if (path === "/api/projects" && init?.method === "POST") {
      if (state.failWrite) return failure("prj_nm");
      const row = {
        ...fixture,
        ...JSON.parse(init.body as string),
        prj_id: 3,
        prj_no: "P-2603",
      };
      state.rows.push(row);
      return response(row);
    }
    if (path.startsWith("/api/projects/") && init?.method === "PUT") {
      if (state.failWrite) return failure("cntrct_ty_cd", 409);
      const row = { ...state.rows[0], ...JSON.parse(init.body as string) };
      state.rows[0] = row;
      return response(row);
    }
    if (path.startsWith("/api/projects?")) {
      if (state.failList) return failure(null, 500);
      const q = new URLSearchParams(path.split("?")[1]);
      const rows = state.rows.filter(
        (r) =>
          (!q.get("q") || r.prj_nm.includes(q.get("q")!)) &&
          (q.get("part") !== "NONE" ||
            (r.bsn_part_id == null && r.cntrct_ty_cd !== "IN")),
      );
      return response({
        items: rows,
        total: rows.length,
        page: Number(q.get("page")),
        size: Number(q.get("size")),
        unassignedCount: state.notice,
      });
    }
    if (path.startsWith("/api/projects/"))
      return state.failDetail ? failure(null, 404) : response(state.rows[0]);
    throw new Error(`Unexpected project test request ${path}`);
  });
  vi.stubGlobal("fetch", fetcher);
  return {
    state,
    fetcher,
    writes: () =>
      fetcher.mock.calls.filter(
        (c) => c[1]?.method === "POST" || c[1]?.method === "PUT",
      ),
  };
}
let server: ReturnType<typeof projectServer>;
beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  vi.stubGlobal("innerWidth", 1280);
  vi.setSystemTime(new Date("2026-10-23T00:00:00Z"));
  server = projectServer();
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function list(path = "/UI-01") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <UI01Screen />
    </MemoryRouter>,
  );
}
function form(initial?: ProjectDetail, onSaved = vi.fn()) {
  return render(
    <ProjectForm
      initial={initial}
      onCancel={() => {}}
      onSaved={onSaved}
      onDirtyChange={() => {}}
    />,
  );
}
const change = (label: string, value: string) =>
  fireEvent.change(
    within(screen.getByTestId("project-form")).getByLabelText(label),
    { target: { value } },
  );
const ready = async () => {
  await waitFor(() => expect(screen.getByTestId("project-save")).toBeEnabled());
};
async function valid() {
  await ready();
  change("프로젝트명", "새 프로젝트");
  change("계약유형", "MO");
  change("고객사", "1");
  change("영업 파트", "1");
  change("계약 시작일", "2026-03-01");
  change("계약 종료일", "2026-12-31");
  change("계약금액", "500000000");
}
function drawer() {
  return render(
    <ProjectDrawer id={1} onClose={() => {}} onSaved={async () => {}} />,
  );
}

describe("project list and drawer", () => {
  it("renders exact nine columns, current values and distinct count", async () => {
    list();
    await screen.findByTestId("project-row-1");
    for (const text of [
      "프로젝트ID",
      "프로젝트명",
      "고객사",
      "계약유형",
      "계약 기간",
      "계약금액",
      "투입 인원",
      "영업 파트",
      "상태",
    ])
      expect(screen.getByRole("columnheader", { name: text })).toBeVisible();
    expect(screen.getByText("3명")).toBeVisible();
    expect(screen.getByText("500,000,000")).toBeVisible();
    expect(
      server.fetcher.mock.calls.some(
        (c) =>
          c[0].includes("sort=cntrct_bgn_dt%2Cdesc") &&
          c[0].includes("size=50"),
      ),
    ).toBe(true);
  });
  it("unassigned notice applies NONE and excludes internal row", async () => {
    server.state.rows = [
      { ...fixture, bsn_part_id: null, partName: null },
      { ...fixture, prj_id: 2, cntrct_ty_cd: "IN", bsn_part_id: null },
    ];
    list();
    await screen.findByTestId("project-row-2");
    fireEvent.click(screen.getByTestId("project-unassigned"));
    await waitFor(() =>
      expect(screen.queryByTestId("project-row-2")).not.toBeInTheDocument(),
    );
    expect(screen.getByTestId("project-part-filter")).toHaveValue("NONE");
    expect(screen.getByText(/영업 파트가 없는 프로젝트가 2건/)).toBeVisible();
  });
  it("zero notice stays hidden", async () => {
    server.state.notice = 0;
    list();
    await screen.findByTestId("project-row-1");
    expect(screen.queryByTestId("project-unassigned")).not.toBeInTheDocument();
  });
  it("remembers screen year and hides create/upload in past years", async () => {
    const view = list();
    await screen.findByTestId("project-row-1");
    fireEvent.change(screen.getByLabelText("조회 연도"), {
      target: { value: "2025" },
    });
    expect(screen.queryByTestId("project-create")).not.toBeInTheDocument();
    view.unmount();
    list();
    expect(screen.getByLabelText("조회 연도")).toHaveValue(2025);
  });
  it("mobile has no create/upload/edit but keeps read detail", async () => {
    vi.stubGlobal("innerWidth", 767);
    list();
    await screen.findByTestId("project-row-1");
    expect(screen.queryByTestId("project-create")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("project-open-1"));
    await screen.findByRole("dialog");
    expect(screen.queryByTestId("project-edit")).not.toBeInTheDocument();
  });
  it("search filters AND and empty reset restores list", async () => {
    list();
    await screen.findByTestId("project-row-1");
    fireEvent.change(screen.getByTestId("project-type-filter"), {
      target: { value: "MO" },
    });
    fireEvent.change(screen.getByTestId("project-status-filter"), {
      target: { value: "END" },
    });
    fireEvent.change(screen.getByTestId("project-query-input"), {
      target: { value: "없음" },
    });
    fireEvent.click(screen.getByTestId("project-query"));
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다."),
    ).toBeVisible();
    expect(
      server.fetcher.mock.calls.some(
        (c) => c[0].includes("type=MO") && c[0].includes("status=END"),
      ),
    ).toBe(true);
    fireEvent.click(screen.getByTestId("project-empty-reset"));
    await screen.findByTestId("project-row-1");
    expect(screen.getByTestId("project-status-filter")).toHaveValue("");
  });
  it("sort and size reach server not client-only sorting", async () => {
    list();
    await screen.findByTestId("project-row-1");
    fireEvent.click(screen.getByTestId("project-sort-prj-nm"));
    await waitFor(() =>
      expect(
        server.fetcher.mock.calls.some((c) =>
          c[0].includes("sort=prj_nm%2Casc"),
        ),
      ).toBe(true),
    );
    fireEvent.change(screen.getByLabelText("목록 건수"), {
      target: { value: "20" },
    });
    await waitFor(() =>
      expect(
        server.fetcher.mock.calls.some((c) => c[0].includes("size=20")),
      ).toBe(true),
    );
  });
  it("list error retries and never claims empty on failure", async () => {
    server.state.failList = true;
    list();
    await screen.findByText("입력 오류");
    expect(
      screen.queryByText("조건에 맞는 결과가 없습니다."),
    ).not.toBeInTheDocument();
    server.state.failList = false;
    fireEvent.click(screen.getByText("다시 시도"));
    await screen.findByTestId("project-row-1");
  });
  it("row opens right drawer without separate detail route", async () => {
    list();
    await screen.findByTestId("project-row-1");
    fireEvent.click(screen.getByTestId("project-open-1"));
    const d = await screen.findByTestId("project-drawer");
    expect(d.className).toContain("drawer");
    expect(await within(d).findByText("최초 계약금액")).toBeVisible();
    expect(
      within(d).queryByRole("tab", { name: "진행 현황" }),
    ).not.toBeInTheDocument();
  });
  it("canonical disabled deep link uses same GET and shows disabled banner", async () => {
    server.state.rows[0].use_yn = "N";
    list("/UI-01?open=P-2601");
    expect(
      await screen.findByText("사용 안 함으로 바꾼 프로젝트입니다."),
    ).toBeVisible();
    expect(
      server.fetcher.mock.calls.some((c) => c[0] === "/api/projects/P-2601"),
    ).toBe(true);
    expect(screen.queryByTestId("project-edit")).not.toBeInTheDocument();
  });
  it("PLAN nonzero shows precontract banner", async () => {
    server.state.rows[0].prj_stts_cd = "PLAN";
    drawer();
    expect(
      await screen.findByText(
        "계약 전에 3명이 먼저 투입되었습니다(미계약 선투입).",
      ),
    ).toBeVisible();
  });
  it("PLAN zero hides banner", async () => {
    server.state.rows[0].prj_stts_cd = "PLAN";
    server.state.rows[0].assignmentCount = 0;
    drawer();
    await screen.findByText("최초 계약금액");
    expect(screen.queryByText(/계약 전에/)).not.toBeInTheDocument();
  });
  it("IN hides part action and financial values", async () => {
    server.state.rows[0] = {
      ...fixture,
      cntrct_ty_cd: "IN",
      cust_ptnr_id: null,
      bsn_part_id: null,
    };
    drawer();
    await screen.findByText("최초 계약금액");
    expect(screen.queryByTestId("project-part-change")).not.toBeInTheDocument();
    expect(screen.getByText("없음")).toBeVisible();
    expect(screen.queryByText("500,000,000")).not.toBeInTheDocument();
  });
  it("assignments empty and sensitive field exclusion", async () => {
    drawer();
    await screen.findByText("최초 계약금액");
    fireEvent.click(screen.getByTestId("project-tab-assignments"));
    expect(await screen.findByText("투입된 인력이 없습니다.")).toBeVisible();
    expect(screen.getByTestId("project-tab-assignments")).toHaveTextContent(
      "투입 인력 (0)",
    );
    expect(screen.queryByText("단가")).not.toBeInTheDocument();
  });
  it("assignments projects only six safe fields and one visible panel", async () => {
    server.state.assignments = [
      {
        name: "개발자",
        job: "백엔드",
        beginDate: "2026-03-01",
        endDate: null,
        assignmentRate: 0.5,
        status: "ACTIVE",
      },
    ];
    drawer();
    await screen.findByText("최초 계약금액");
    fireEvent.click(screen.getByTestId("project-tab-assignments"));
    expect(await screen.findByText("50%")).toBeVisible();
    expect(screen.queryByTestId("project-panel-basic")).not.toBeInTheDocument();
    expect(screen.queryByText("급여")).not.toBeInTheDocument();
  });
  it("assignment failure supports retry", async () => {
    server.state.failAssignments = true;
    drawer();
    await screen.findByText("최초 계약금액");
    fireEvent.click(screen.getByTestId("project-tab-assignments"));
    await screen.findByText("입력 오류");
    server.state.failAssignments = false;
    fireEvent.click(screen.getByText("다시 시도"));
    expect(await screen.findByText("투입된 인력이 없습니다.")).toBeVisible();
  });
  it("overflow opens scoped change history", async () => {
    drawer();
    await screen.findByText("최초 계약금액");
    fireEvent.click(screen.getByTestId("project-more"));
    fireEvent.click(screen.getByTestId("project-history-open"));
    expect(await screen.findByText("prj_nm")).toBeVisible();
    expect(screen.getByText("기본 사용자")).toBeVisible();
    expect(screen.queryByTestId("project-panel-basic")).not.toBeInTheDocument();
  });
});

describe("project registration and edit", () => {
  it("enables saving only after the API budget default is applied", async () => {
    let resolveBudget!: (value: Response) => void;
    const budgetResponse = new Promise<Response>((resolve) => {
      resolveBudget = resolve;
    });
    const fetcher = server.fetcher.getMockImplementation()!;
    server.fetcher.mockImplementation((path, init) =>
      path.startsWith("/api/params/BUDGET_RATE")
        ? budgetResponse
        : fetcher(path, init),
    );
    form();
    await screen.findByRole("option", { name: "본사(직접 영업)" });
    expect(screen.getByTestId("project-save")).toBeDisabled();
    expect(screen.getByLabelText("목표 마진율")).toHaveValue(null);

    resolveBudget(response({ num_val: 75 }));
    await ready();
    expect(screen.getByLabelText("목표 마진율")).toHaveValue(25);
    change("목표 마진율", "");
    expect(screen.getByTestId("project-save")).toBeEnabled();
    expect(screen.getByLabelText("목표 마진율")).toHaveValue(null);
  });
  it("renders all fields, limits and API-backed choices", async () => {
    form();
    await ready();
    expect(screen.getByLabelText("프로젝트명")).toHaveAttribute(
      "maxlength",
      "100",
    );
    expect(screen.getByLabelText("업무 분야")).toHaveAttribute(
      "maxlength",
      "100",
    );
    expect(screen.getByLabelText("비고")).toHaveAttribute("maxlength", "500");
    expect(screen.getByLabelText("상태")).toHaveValue("RUN");
    expect(
      screen.getByLabelText("目標".replace("目標", "목표 마진율")),
    ).toHaveValue(20);
    expect(
      screen.getByRole("option", { name: "본사(직접 영업)" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/본사가 직접 영업했으면/)).toBeVisible();
  });
  it("IN transition hides and nulls customer part financial sections", async () => {
    const saved = vi.fn();
    form(undefined, saved);
    await valid();
    change("계약유형", "IN");
    expect(screen.queryByLabelText("고객사")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("영업 파트")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "금액" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("project-save"));
    await waitFor(() => expect(saved).toHaveBeenCalled());
    const body = JSON.parse(server.writes()[0][1]!.body as string);
    expect(body.cust_ptnr_id).toBeNull();
    expect(body.bsn_part_id).toBeNull();
    expect(body.init_cntrct_amt).toBeNull();
    expect(body.trgt_cst_amt).toBeNull();
  });
  it.each([
    ["프로젝트명", "프로젝트명을 100자 이내로 입력하세요."],
    ["고객사", "고객사를 골라 주세요."],
    ["영업 파트", "영업 파트를 골라 주세요."],
    ["계약 시작일", "계약 시작일을 입력하세요."],
  ])(
    "validates %s and focuses first error keeping draft",
    async (label, message) => {
      form();
      await valid();
      change(label, "");
      fireEvent.click(screen.getByTestId("project-save"));
      expect(await screen.findByText(message)).toBeVisible();
      expect(screen.getByLabelText(label)).toHaveFocus();
      expect(server.writes()).toHaveLength(0);
    },
  );
  it("invalid end boundary preserves name and focuses end", async () => {
    form();
    await valid();
    change("계약 종료일", "2026-02-28");
    fireEvent.click(screen.getByTestId("project-save"));
    expect(await screen.findByText("계약 종료일을 확인하세요.")).toBeVisible();
    expect(screen.getByLabelText("계약 종료일")).toHaveFocus();
    expect(screen.getByLabelText("프로젝트명")).toHaveValue("새 프로젝트");
  });
  it("PLAN probability field toggles and validates", async () => {
    form();
    await valid();
    expect(screen.queryByLabelText("수주 확률")).not.toBeInTheDocument();
    change("상태", "PLAN");
    change("수주 확률", "101");
    fireEvent.click(screen.getByTestId("project-save"));
    expect(await screen.findByText("0~100 사이로 입력하세요.")).toBeVisible();
    expect(screen.getByLabelText("수주 확률")).toHaveFocus();
  });
  it("target integer floor recalculates then manual value is submitted", async () => {
    form();
    await valid();
    change("계약금액", "123456789");
    expect(screen.getByLabelText("목표 원가")).toHaveValue("98,765,431");
    change("목표 원가", "1234");
    fireEvent.click(screen.getByTestId("project-save"));
    await waitFor(() => expect(server.writes()).toHaveLength(1));
    expect(JSON.parse(server.writes()[0][1]!.body as string).trgt_cst_amt).toBe(
      1234,
    );
  });
  it("other information and multiple skills survive payload", async () => {
    form();
    await valid();
    change("업종", "SW");
    change("업무 분야", "업무");
    change("선행 프로젝트", "9");
    change("비고", "기록");
    fireEvent.click(screen.getByTestId("project-skill-java"));
    fireEvent.click(screen.getByTestId("project-skill-react"));
    fireEvent.click(screen.getByTestId("project-save"));
    await waitFor(() => expect(server.writes()).toHaveLength(1));
    expect(JSON.parse(server.writes()[0][1]!.body as string)).toMatchObject({
      indty_cd: "SW",
      duty_fld_nm: "업무",
      prev_prj_id: 9,
      rmrk_desc: "기록",
      prj_skl: ["JAVA", "REACT"],
    });
  });
  it("server field failure retains inputs and does not navigate", async () => {
    server.state.failWrite = true;
    form();
    await valid();
    fireEvent.click(screen.getByTestId("project-save"));
    await waitFor(() =>
      expect(screen.getByLabelText("프로젝트명")).toHaveFocus(),
    );
    expect(screen.getByLabelText("프로젝트명")).toHaveValue("새 프로젝트");
    expect(screen.getByTestId("project-form")).toBeVisible();
  });
  it("same workspace registration returns and highlights actual new project", async () => {
    list();
    await screen.findByTestId("project-row-1");
    fireEvent.click(screen.getByTestId("project-create"));
    await valid();
    fireEvent.click(screen.getByTestId("project-save"));
    const r = await screen.findByTestId("project-row-3");
    expect(r.className).toContain("highlight");
    expect(await screen.findByText("저장했습니다.")).toBeVisible();
  });
  it("dirty cancel keeps inputs or leaves with no write", async () => {
    render(
      <MemoryRouter initialEntries={["/UI-33"]}>
        <UI33Screen />
      </MemoryRouter>,
    );
    await ready();
    change("프로젝트명", "작성 중");
    fireEvent.click(screen.getByTestId("project-cancel"));
    expect(screen.getByText("저장하지 않은 내용이 있습니다")).toBeVisible();
    fireEvent.click(screen.getByText("계속 작성"));
    expect(screen.getByLabelText("프로젝트명")).toHaveValue("작성 중");
    expect(server.writes()).toHaveLength(0);
  });
  it("direct mobile registration has PC guidance and no form", async () => {
    vi.stubGlobal("innerWidth", 767);
    render(
      <MemoryRouter initialEntries={["/UI-33"]}>
        <UI33Screen />
      </MemoryRouter>,
    );
    expect(
      screen.getByText("프로젝트 등록은 PC에서 이용해 주세요."),
    ).toBeVisible();
    expect(screen.queryByTestId("project-form")).not.toBeInTheDocument();
  });
  it("confirmed edit makes initial fields read-only and excludes current and part payload", async () => {
    server.state.confirmed = true;
    form(fixture);
    await ready();
    expect(screen.getByLabelText("프로젝트ID")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("현재 계약금액")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("최초 계약금액")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("계약 종료일")).toHaveAttribute("readonly");
    expect(screen.getByLabelText("영업 파트")).toHaveAttribute("readonly");
    change("프로젝트명", "수정");
    fireEvent.click(screen.getByTestId("project-save"));
    await waitFor(() => expect(server.writes()).toHaveLength(1));
    const b = JSON.parse(server.writes()[0][1]!.body as string);
    for (const key of [
      "prj_no",
      "cur_cntrct_amt",
      "cur_end_dt",
      "cntrct_mm_qty",
      "bsn_part_id",
    ])
      expect(b).not.toHaveProperty(key);
    expect(b.prj_nm).toBe("수정");
  });
  it("unchanged edit returns no-change without PUT", async () => {
    const saved = vi.fn();
    form(fixture, saved);
    await ready();
    fireEvent.click(screen.getByTestId("project-save"));
    expect(saved).toHaveBeenCalledWith(fixture, false);
    expect(server.writes()).toHaveLength(0);
  });
  it("code errors block saves and retry keeps draft", async () => {
    server.state.failCodes = true;
    form();
    await screen.findAllByText("입력 오류");
    change("프로젝트명", "유지");
    expect(screen.getByTestId("project-save")).toBeDisabled();
    server.state.failCodes = false;
    fireEvent.click(screen.getAllByText("다시 시도")[0]);
    expect(screen.getByLabelText("프로젝트명")).toHaveValue("유지");
  });
  it("payload trims and omits response-only fields", () => {
    const p = projectPayload({ ...fixture, prj_nm: "  이름  " });
    expect(p.prj_nm).toBe("이름");
    expect(p).not.toHaveProperty("prj_id");
    expect(p).not.toHaveProperty("use_yn");
  });
});
