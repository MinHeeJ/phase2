import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI30Screen from "./UI30Screen";
import type { History, Options, Result, Snapshot } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>,
  options: Options,
  snapshot: Snapshot,
  fail: boolean,
  empty: boolean;
const mount = (entry = "/UI-30") =>
  render(
    <MemoryRouter initialEntries={[entry]}>
      <UI30Screen />
    </MemoryRouter>,
  );
const row = (id: number): Result => ({
  resultId: id,
  devId: id,
  developerName: `개발자 참조 ${id}`,
  developerNumber: `내부 참조 ${id}`,
  sourceIds: [],
  rank: id,
  score: 77.5,
  breakdown: {
    job_scr: 25,
    skl_scr: 20,
    cust_scr: 7.5,
    grd_scr: 5,
    achv_scr: 5,
    exp_scr: 5,
    asgn_psbl_scr: 10,
  },
  reason: "DB 근거",
  evidence: ["구조화 직무"],
  availabilityStatus: "주의",
  availabilityWarnings: ["TEMP_OVERLAP"],
  monthByMonthMm: [
    {
      month: "2027-01",
      confirmedMm: 0,
      activeTemporaryMm: 0.5,
      requestedMm: 1,
      warnings: ["TEMP_OVERLAP"],
    },
  ],
  exitDate: "2026-12-31",
  extensionLikelihood: "H",
  earliestStartYm: "2027-01",
  decisionStatus: "PENDING",
  jobCode: "BE",
  gradeCode: "SR",
  skillCodes: ["JAVA"],
  career: [
    {
      projectId: id,
      projectName: `실제 프로젝트 ${id}`,
      startDate: "2026-01-01",
      endDate: "2026-12-31",
      jobCode: "BE",
      skillCodes: ["JAVA"],
      monthByMonthMm: [{ month: "2026-01", mm: 1 }],
    },
  ],
});
beforeEach(() => {
  fail = false;
  empty = false;
  options = {
    sources: [
      {
        sourceId: 987,
        sourceType: "PROJECT",
        projectId: 31,
        remainingHeadcount: 3,
        criteria: {
          jobCode: "BE",
          startYm: "2027-01",
          headcount: 3,
          durationMonths: 6,
          requestedMm: 1,
        },
      },
    ],
    developers: [
      {
        devId: 8,
        exitDate: null,
        assumedExitYm: null,
        extensionLikelihood: "H",
        hasNextConfirmedAssignment: true,
      },
    ],
    jobCodes: ["BE", "FE"],
    gradeCodes: ["SR"],
    skillCodes: ["JAVA", "SPRING"],
    defaultStartYm: "2027-01",
  };
  snapshot = {
    requestId: 12,
    direction: "PROJECT_TO_PERSON",
    criteria: {
      jobCode: "BE",
      startYm: "2027-01",
      durationMonths: 6,
      headcount: 3,
      requestedMm: 1,
    },
    evaluatedAt: "2026-12-01T09:00:00+09:00",
    results: [row(1), row(2), row(3), row(4)],
    missingDataFields: [],
    warnings: [],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.includes("/options")) return response(options);
    if (fail)
      return new Response(
        JSON.stringify({
          success: false,
          code: "FORBIDDEN",
          message: "접근 거절",
          field: null,
          requestId: "safe-reference",
        }),
        { status: 403 },
      );
    if (url.endsWith("/criteria-draft"))
      return response({
        status: "MODEL_UNAVAILABLE",
        criteria: {},
        clarification: "수동 조건을 사용하세요.",
        warnings: [],
        modelUsed: false,
      });
    if (url.includes("/history")) {
      const history: History = {
        items: empty ? [] : [snapshot],
        total: empty ? 0 : 51,
        page: Number(new URLSearchParams(url.split("?")[1]).get("page") ?? 0),
        size: 50,
        quality: {
          adoption: { numerator: 0, denominator: 0, rate: null },
          assignmentConversion: { numerator: 0, denominator: 0, rate: null },
          observedExtension: { numerator: 0, denominator: 0, rate: null },
          validSampleCountLastThreeMonths: 0,
          warnings: ["배점 근거 부족"],
        },
      };
      return response(history);
    }
    if (url.endsWith("/decision")) {
      const body = JSON.parse(String(init?.body));
      const target = snapshot.results.find((r) =>
        url.includes(`/results/${r.resultId}/`),
      )!;
      target.decisionStatus = body.decision;
      target.assignmentLinkStatus = "채택됨·배정 미생성";
      return response({ assignmentCreated: false });
    }
    return response({ ...snapshot, results: empty ? [] : snapshot.results });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.unstubAllGlobals());
async function run() {
  await screen.findByTestId("criteria-job");
  fireEvent.change(screen.getByTestId("criteria-job"), {
    target: { value: "BE" },
  });
  fireEvent.click(screen.getByTestId("recommendation-run"));
  await screen.findByTestId("recommendation-row-1");
}
describe("UI-30 추천 화면", () => {
  it("uses a single product screen with server default month and source-prefill editable fields", async () => {
    mount();
    await screen.findByTestId("criteria-job");
    expect(
      screen.getAllByRole("heading", { name: "AI 인력 추천" }),
    ).toHaveLength(1);
    expect(screen.getByTestId("criteria-start")).toHaveValue("2027-01");
    fireEvent.change(screen.getByTestId("recommendation-source"), {
      target: { value: "987" },
    });
    expect(screen.getByTestId("criteria-headcount")).toHaveValue(3);
    fireEvent.change(screen.getByTestId("criteria-headcount"), {
      target: { value: "2" },
    });
    expect(screen.getByTestId("criteria-headcount")).toHaveValue(2);
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).endsWith("/search")),
    ).toHaveLength(0);
  });
  it("runs then GETs snapshot and permits partial individual selection with warnings", async () => {
    mount();
    await run();
    fireEvent.click(screen.getByTestId("select-1"));
    fireEvent.click(screen.getByTestId("select-2"));
    expect(screen.getByText("2/3명 선정 · 1명 부족")).toBeInTheDocument();
    expect(
      screen.getByText(/선정 후보 일정: 가배정과 겹침/),
    ).toBeInTheDocument();
    expect(
      fetcher.mock.calls.some(([url]) => String(url).endsWith("/12")),
    ).toBe(true);
    expect(screen.getByTestId("review-1")).toBeDisabled();
  });
  it("limits comparisons to three without losing previous selections", async () => {
    mount();
    await run();
    for (const id of [1, 2, 3, 4])
      fireEvent.click(screen.getByTestId(`compare-${id}`));
    expect(
      screen.getByText("최대 3명까지 비교할 수 있습니다."),
    ).toBeInTheDocument();
    expect(screen.getByTestId("compare-4")).not.toBeChecked();
    fireEvent.click(screen.getByTestId("open-comparison"));
    expect(screen.getByTestId("comparison-1")).toHaveTextContent(
      "실제 프로젝트 1",
    );
    expect(screen.getByTestId("comparison-3")).toHaveTextContent("ACTIVE");
    expect(screen.queryByTestId("comparison-4")).not.toBeInTheDocument();
  });
  it("shows independent availability and actual career only", async () => {
    mount();
    await run();
    fireEvent.click(screen.getByTestId("career-1"));
    expect(screen.getByTestId("career-panel")).toHaveTextContent(
      "실제 프로젝트 1",
    );
    expect(screen.getByTestId("career-panel")).not.toHaveTextContent("급여");
    fireEvent.click(screen.getByTestId("close-career"));
    fireEvent.click(screen.getByTestId("detail-1"));
    expect(screen.getByTestId("recommendation-detail")).toHaveTextContent(
      "TEMP_OVERLAP",
    );
    expect(screen.getByTestId("recommendation-detail")).toHaveTextContent(
      "확정·ACTIVE",
    );
  });
  it("does not save REJECT with blank reason; ADOPT saves only after inline confirmation", async () => {
    mount();
    await run();
    fireEvent.click(screen.getByTestId("reject-1"));
    fireEvent.click(screen.getByTestId("decision-save"));
    expect(screen.getByTestId("decision-panel")).toHaveTextContent(
      "거절 사유는 1~500자",
    );
    expect(
      fetcher.mock.calls.filter(([, init]) => init?.method === "PATCH"),
    ).toHaveLength(0);
    fireEvent.click(screen.getByTestId("decision-cancel"));
    fireEvent.click(screen.getByTestId("adopt-1"));
    fireEvent.click(screen.getByTestId("decision-save"));
    await waitFor(() =>
      expect(screen.queryByTestId("decision-panel")).not.toBeInTheDocument(),
    );
    expect(screen.getByText(/채택됨·배정 미생성/)).toBeInTheDocument();
    expect(screen.getByTestId("adopt-1")).toBeDisabled();
  });
  it("keeps manual recommendations enabled when model is down", async () => {
    mount();
    await screen.findByTestId("criteria-job");
    fireEvent.change(screen.getByTestId("criteria-question"), {
      target: { value: "백엔드 추천" },
    });
    fireEvent.click(screen.getByTestId("criteria-preview"));
    expect(await screen.findByText(/AI 모델에 연결할 수 없습니다/)).toBeInTheDocument();
    expect(screen.getByTestId("draft-apply")).toBeDisabled();
    expect(screen.getByTestId("recommendation-run")).toBeEnabled();
    expect(
      fetcher.mock.calls.filter(([url]) => String(url).endsWith("/search")),
    ).toHaveLength(0);
  });
  it("prefills B from devId and keeps H and next-confirmed warnings", async () => {
    mount("/UI-30?devId=8#person-to-position");
    const select = await screen.findByTestId("next-developer");
    expect(select).toHaveValue("8");
    expect(screen.getByTestId("assumed-exit-month")).toBeRequired();
    expect(
      screen.getByText(/ 연장 가능성 높음, 일정 확인 필요/),
    ).toBeInTheDocument();
    expect(select).toHaveTextContent("차기확정 있음");
  });
  it("distinguishes empty results from denied requests", async () => {
    empty = true;
    mount();
    await screen.findByTestId("criteria-job");
    fireEvent.change(screen.getByTestId("criteria-job"), {
      target: { value: "BE" },
    });
    fireEvent.click(screen.getByTestId("recommendation-run"));
    expect(
      await screen.findByText("조건에 맞는 추천이 없습니다."),
    ).toBeInTheDocument();
    fail = true;
    fireEvent.click(screen.getByTestId("results-refresh"));
    await waitFor(() =>
      expect(screen.getAllByRole("alert")[0]).toHaveTextContent(
        "접근이 제한되었습니다.",
      ),
    );
  });
  it("loads own history, server quality denominators and paging", async () => {
    mount();
    await screen.findByTestId("criteria-job");
    fireEvent.click(screen.getByTestId("tab-history"));
    await screen.findByTestId("history-row-12");
    expect(screen.getByTestId("recommendation-history")).toHaveTextContent(
      "채택률 – · 0/0건",
    );
    expect(screen.getByText(/배점 근거 부족/)).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("history-next"));
    await waitFor(() =>
      expect(screen.getByText("2쪽 · 전체 51건")).toBeInTheDocument(),
    );
    expect(
      fetcher.mock.calls.some(([url]) => String(url).includes("page=1")),
    ).toBe(true);
  });
  it("shows empty history and a disabled next page", async () => {
    empty = true;
    mount();
    await screen.findByTestId("criteria-job");
    fireEvent.click(screen.getByTestId("tab-history"));
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("history-next")).toBeDisabled();
  });
  it("confirms abandoning edited conditions and keeps them when cancelled", async () => {
    mount();
    await screen.findByTestId("criteria-job");
    fireEvent.change(screen.getByTestId("criteria-headcount"), {
      target: { value: "3" },
    });
    fireEvent.click(screen.getByTestId("tab-b"));
    expect(screen.getByTestId("leave-confirmation")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("continue-editing"));
    expect(screen.getByTestId("criteria-headcount")).toHaveValue(3);
    expect(screen.queryByTestId("next-developer")).not.toBeInTheDocument();
  });
  it("shows B original period and actual recommended start separately", async () => {
    snapshot.direction = "PERSON_TO_POSITION";
    snapshot.results = [
      {
        ...row(1),
        needId: 15,
        positionStartYm: "2026-11",
        positionEndYm: "2027-03",
        recommendedStartYm: "2027-01",
        remainingHeadcount: 2,
        waitingDays: 0,
        waitingDaysEstimated: false,
      },
    ];
    mount("/UI-30?devId=8");
    await screen.findByTestId("next-developer");
    fireEvent.change(screen.getByTestId("assumed-exit-month"), {
      target: { value: "2026-12" },
    });
    fireEvent.click(screen.getByTestId("recommendation-next-run"));
    await screen.findByTestId("recommendation-row-1");
    expect(screen.getByText(/실제 추천월 2027-01/)).toBeInTheDocument();
    expect(screen.getByText(/2026-11 ~ 2027-03/)).toBeInTheDocument();
    expect(screen.getByTestId("review-1")).toHaveTextContent("가배정 검토");
  });
  it("does not submit a recommendation when B condition editing is cancelled", async () => {
    mount("/UI-30?devId=8");
    await screen.findByTestId("next-developer");
    fireEvent.change(screen.getByTestId("assumed-exit-month"), {
      target: { value: "2026-12" },
    });
    fireEvent.click(screen.getByTestId("next-cancel"));
    expect(screen.getByTestId("assumed-exit-month")).toHaveValue("");
    expect(
      fetcher.mock.calls.some(([url]) => String(url).endsWith("/search")),
    ).toBe(false);
  });
  it("never shows A results or inline career/detail in the B tab", async () => {
    mount();
    await run();
    fireEvent.click(screen.getByTestId("detail-1"));
    fireEvent.click(screen.getByTestId("career-1"));
    fireEvent.click(screen.getByTestId("tab-b"));
    expect(screen.getByTestId("next-developer")).toBeInTheDocument();
    expect(
      screen.queryByTestId("recommendation-results"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("recommendation-detail"),
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId("career-panel")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("tab-a"));
    expect(screen.getByTestId("recommendation-row-1")).toBeInTheDocument();
  });
  it("binds edited criteria to the existing shell leave guard and clears it on cancellation", async () => {
    const dirty = vi.fn();
    const view = render(
      <MemoryRouter>
        <UI30Screen onDirtyChange={dirty} />
      </MemoryRouter>,
    );
    await waitFor(() =>
      expect(screen.getByTestId("criteria-start")).toHaveValue(
        options.defaultStartYm,
      ),
    );
    fireEvent.change(screen.getByTestId("criteria-headcount"), {
      target: { value: "4" },
    });
    expect(dirty).toHaveBeenLastCalledWith(true);
    fireEvent.click(screen.getByTestId("criteria-cancel"));
    expect(dirty).toHaveBeenLastCalledWith(false);
    view.unmount();
    expect(dirty).toHaveBeenLastCalledWith(false);
  });
  it("renders request-level server warnings without recalculating availability", async () => {
    snapshot.warnings = ["현재 원천의 일정 근거를 확인하세요."];
    mount();
    await run();
    expect(screen.getByTestId("recommendation-results")).toHaveTextContent(
      snapshot.warnings[0],
    );
  });
});
