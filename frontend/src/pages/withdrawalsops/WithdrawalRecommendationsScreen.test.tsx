import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import WithdrawalRecommendationsScreen, {
  NextSlotsCell,
  NextSlotsSummary,
} from "./WithdrawalRecommendationsScreen";
import type { NextSlotsResult } from "./client";
import type { WithdrawalRow } from "../us1/client";

const response = (data: unknown) =>
  new Response(
    JSON.stringify({ success: true, meta: { today: "2026-10-14" }, data }),
    { status: 200, headers: { "Content-Type": "application/json" } },
  );
let fetcher: ReturnType<typeof vi.fn>;
let result: NextSlotsResult;
let row: WithdrawalRow;
let refresh: ReturnType<typeof vi.fn>;
let failed: boolean;
let writeFailed: boolean;
const context = () => ({ today: "2026-10-14", year: 2026, refresh });
function mountCell(year = 2026) {
  return render(<NextSlotsCell row={row} context={{ ...context(), year }} />);
}
async function open() {
  fireEvent.click(await screen.findByRole("button", { name: "추천 1곳" }));
  return screen.findByTestId("next-slots-detail");
}
beforeEach(() => {
  sessionStorage.clear();
  refresh = vi.fn(async () => {});
  failed = false;
  writeFailed = false;
  row = {
    asgn_id: 7,
    asgn_no: "A-0007",
    dev_id: 2,
    dev_nm: "시험 개발자",
    mng_no: "K-0002",
    prj_id: 3,
    prj_nm: "현재 프로젝트",
    job_cd: "BE",
    job_nm: "백엔드 개발",
    bgn_dt: "2026-10-01",
    end_dt: "2026-12-31",
    plan_end_dt: "2026-12-31",
    ext_psblty_cd: null,
    ext_psblty_nm: "미정",
    asgn_stts_cd: "ACTIVE",
    updated_at: "2026-10-14T00:00:00",
    extend_count: 1,
    early_end: false,
    replacement_name: null,
    remaining_days: 78,
    dispatch_first_dt: null,
    dispatch_expiry_dt: null,
    dispatch_display: "–",
    dispatch_tone: "neutral",
    dispatch_alert_dates: [],
    next_asgn_id: null,
    next_prj_nm: null,
    next_bgn_dt: null,
    next_display: "없음",
    chance_locked: false,
    chance_lock_reason: null,
    cntrct_ty_cd: "MO",
    prj_stts_cd: "RUN",
    use_yn: "Y",
  };
  result = {
    items: [
      {
        need_id: 11,
        oppt_id: 6,
        oppt_nm: "조회된 기회",
        job_cd: "BE",
        grd_cd: "SR",
        bgn_ym: "2027-01",
        end_ym: "2027-03",
        remaining_slots: 2,
        mtch_scr: 100,
        score_components: {
          job: 40,
          grade: 20,
          gap: 25,
          customer: 10,
          probability: 5,
        },
        waiting_days: 0,
        win_prblt_rt: 60,
        warnings: [],
      },
    ],
    recommendation_count: 1,
    without_next_assignment_count: 4,
    with_valid_slots_count: 2,
    next_temp_assignments: [],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/codes/"))
      return response(
        url.endsWith("JOB")
          ? [{ code: "BE", name: "백엔드 개발" }]
          : [{ code: "SR", name: "고급" }],
      );
    if (init?.method === "POST" || init?.method === "DELETE") {
      if (writeFailed)
        return new Response(
          JSON.stringify({
            success: false,
            field: "need_id",
            message: "이미 가배정되었습니다.",
            requestId: "test-request",
            code: "DUPLICATE_TEMP_ASSIGNMENT",
          }),
          { status: 409 },
        );
      return response({});
    }
    if (failed) throw new Error("offline");
    if (url.endsWith("/next-slots")) return response(result);
    return response({
      rows: [row],
      total: 1,
      page: 0,
      size: 50,
      summary: { soonPeople: 4, noNextPeople: 4 },
    });
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("UI-05 next slots", () => {
  it("shows loading then server count and the exact breakdown in the detail window", async () => {
    mountCell();
    expect(
      screen.getByRole("status", { name: "추천 불러오는 중" }),
    ).toBeInTheDocument();
    const detail = await open();
    expect(detail).toHaveTextContent("조회된 기회");
    expect(detail).toHaveTextContent("백엔드 개발");
    expect(detail).toHaveTextContent("고급");
    expect(detail).toHaveTextContent("100점");
    expect(detail).toHaveTextContent(
      "직무 40 · 등급 20 · 공백 25 · 같은 고객 10 · 확률 5",
    );
    expect(detail).toHaveTextContent("0일");
    expect(detail).toHaveTextContent("60%");
    expect(detail).toHaveTextContent("확정 MM에 포함되지 않습니다");
    fireEvent.click(screen.getByRole("button", { name: "닫기" }));
    expect(screen.queryByTestId("next-slots-detail")).not.toBeInTheDocument();
  });
  it("does not show a recommendation button for zero results", async () => {
    result.items = [];
    result.recommendation_count = 0;
    mountCell();
    await screen.findByText("맞는 자리 없음");
    expect(
      screen.queryByRole("button", { name: /추천/ }),
    ).not.toBeInTheDocument();
  });
  it("shows temporary assignments separately even when no recommendation remains", async () => {
    result.items = [];
    result.recommendation_count = 0;
    result.next_temp_assignments = [
      {
        id: 19,
        need_id: 11,
        dev_id: 2,
        bgn_ym: "2027-01",
        end_ym: "2027-03",
        mth_mm_qty: 0.5,
        tmpr_asgn_src_cd: "NEXT",
        mtch_scr: 100,
        status: "ACTIVE",
        asgn_id: null,
        warnings: [],
      },
    ];
    mountCell();
    fireEvent.click(await screen.findByRole("button", { name: "가배정 상세" }));
    expect(await screen.findByTestId("next-temporary-19")).toHaveTextContent(
      "0.5 MM",
    );
    expect(screen.getByTestId("next-slots-detail")).toHaveTextContent(
      "실제 다음 배정 없음",
    );
  });
  it("shows overlap as warning without changing server waiting days", async () => {
    result.items[0].warnings = ["OVERLAP: 철수 전 시작하는 자리입니다."];
    mountCell();
    await open();
    expect(screen.getByTestId("next-slot-row-11")).toHaveTextContent("OVERLAP");
    expect(screen.getByTestId("next-slot-row-11")).toHaveTextContent("0일");
  });
  it("retries failed recommendation requests", async () => {
    failed = true;
    mountCell();
    await screen.findByText("서버에 연결할 수 없습니다.");
    failed = false;
    fireEvent.click(screen.getByRole("button", { name: /다시/ }));
    expect(
      await screen.findByRole("button", { name: "추천 1곳" }),
    ).toBeInTheDocument();
  });
  it("uses global n and m from the API rather than the row count", async () => {
    render(<NextSlotsSummary asgnId={7} version="1" />);
    expect(
      await screen.findByText("다음 배정 없는 4명 중 2명은 맞는 자리 있음"),
    ).toBeInTheDocument();
  });
  it("keeps previous-year controls read only", async () => {
    mountCell(2025);
    await open();
    expect(screen.getByRole("button", { name: "가배정" })).toBeDisabled();
    expect(screen.getByText("지난 해 · 읽기 전용")).toBeInTheDocument();
  });
  it("submits selected need and developer with NEXT but never submits score", async () => {
    mountCell();
    await open();
    fireEvent.click(screen.getByRole("button", { name: "가배정" }));
    fireEvent.change(screen.getByLabelText("월 MM"), {
      target: { value: "0.50" },
    });
    fireEvent.click(screen.getByRole("button", { name: "가배정 저장" }));
    await waitFor(() => expect(refresh).toHaveBeenCalled());
    const call = fetcher.mock.calls.find(([, init]) => init?.method === "POST");
    expect(call?.[0]).toBe("/api/temp-assignments");
    expect(JSON.parse(call?.[1]?.body as string)).toEqual({
      need_id: 11,
      dev_id: 2,
      bgn_ym: "2027-01",
      end_ym: "2027-03",
      mth_mm_qty: 0.5,
      tmpr_asgn_src_cd: "NEXT",
    });
    expect(
      await screen.findByText("가배정 변경을 반영했습니다."),
    ).toBeInTheDocument();
  });
  it("invalid MM or out-of-need period cannot be submitted", async () => {
    mountCell();
    await open();
    fireEvent.click(screen.getByRole("button", { name: "가배정" }));
    fireEvent.change(screen.getByLabelText("월 MM"), {
      target: { value: "1.01" },
    });
    expect(screen.getByRole("button", { name: "가배정 저장" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("월 MM"), {
      target: { value: "0.50" },
    });
    fireEvent.change(screen.getByLabelText("종료월"), {
      target: { value: "2027-04" },
    });
    expect(screen.getByRole("button", { name: "가배정 저장" })).toBeDisabled();
    expect(fetcher.mock.calls.some(([, init]) => init?.method === "POST")).toBe(
      false,
    );
  });
  it("retains form values and singular server error on a duplicate conflict", async () => {
    writeFailed = true;
    mountCell();
    await open();
    fireEvent.click(screen.getByRole("button", { name: "가배정" }));
    fireEvent.change(screen.getByLabelText("월 MM"), {
      target: { value: "0.50" },
    });
    fireEvent.click(screen.getByRole("button", { name: "가배정 저장" }));
    expect(
      await screen.findByText("이미 가배정되었습니다."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("월 MM")).toHaveValue(0.5);
    expect(refresh).not.toHaveBeenCalled();
  });
  it("requires confirmation to release and refreshes after DELETE", async () => {
    result.next_temp_assignments = [
      {
        id: 19,
        need_id: 11,
        dev_id: 2,
        bgn_ym: "2027-01",
        end_ym: "2027-03",
        mth_mm_qty: 0.5,
        tmpr_asgn_src_cd: "NEXT",
        mtch_scr: 100,
        status: "ACTIVE",
        asgn_id: null,
        warnings: [],
      },
    ];
    mountCell();
    await open();
    fireEvent.click(screen.getByRole("button", { name: "해제" }));
    expect(
      fetcher.mock.calls.some(([, init]) => init?.method === "DELETE"),
    ).toBe(false);
    const confirmation = screen
      .getAllByRole("dialog")
      .find((d) =>
        d.textContent?.includes("선택한 가배정을 해제하시겠습니까"),
      )!;
    fireEvent.click(within(confirmation).getByRole("button", { name: "확인" }));
    await waitFor(() => expect(refresh).toHaveBeenCalled());
    expect(
      fetcher.mock.calls.find(([, init]) => init?.method === "DELETE")?.[0],
    ).toBe("/api/temp-assignments/19");
  });
  it("preserves existing list, chance and dispatch renderers while placing recommendation under actual next", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-14T00:00:00Z"));
    render(
      <MemoryRouter>
        <WithdrawalRecommendationsScreen
          renderChance={() => <span>기존 연장 선택</span>}
          renderDispatch={() => <span>기존 파견 만료</span>}
        />
      </MemoryRouter>,
    );
    const item = await screen.findByTestId("withdrawal-row-7");
    expect(item).toHaveTextContent("기존 연장 선택");
    expect(item).toHaveTextContent("기존 파견 만료");
    expect(item).toHaveTextContent("없음");
    expect(
      await within(item).findByRole("button", { name: "추천 1곳" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "엑셀 다운로드" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "기간" })).toBeInTheDocument();
  });
});
