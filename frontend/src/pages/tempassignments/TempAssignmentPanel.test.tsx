import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TempAssignmentPanel } from "./TempAssignmentPanel";
import { WithdrawalTempAssignments } from "./WithdrawalTempAssignments";
import type {
  TempAssignment,
  TempAssignmentNeed,
  NextSlotsResult,
} from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const need: TempAssignmentNeed = {
  need_id: 11,
  bgn_ym: "2026-11",
  end_ym: "2027-01",
  mth_mm_qty: 0.4,
  psn_cnt: 2,
  label: "선택 기회 · 백엔드 개발",
};
const original: TempAssignment = {
  id: 71,
  need_id: 11,
  dev_id: 21,
  bgn_ym: "2026-11",
  end_ym: "2027-01",
  mth_mm_qty: 0.4,
  tmpr_asgn_src_cd: "MANUAL",
  mtch_scr: null,
  status: "ACTIVE",
  asgn_id: null,
  warnings: [],
};
let rows: TempAssignment[];
let readError: boolean;
let writeError: {
  status: number;
  code: string;
  message: string;
  field: string | null;
} | null;
let fetcher: ReturnType<typeof vi.fn>;
let recommendations: NextSlotsResult;
const changed = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  rows = [{ ...original }];
  readError = false;
  writeError = null;
  changed.mockClear();
  recommendations = {
    recommendation_count: 1,
    without_next_assignment_count: 2,
    with_valid_slots_count: 1,
    next_temp_assignments: [],
    items: [
      {
        need_id: 11,
        oppt_id: 31,
        oppt_nm: "선택 기회",
        job_cd: "BE",
        grd_cd: "MD",
        bgn_ym: "2026-11",
        end_ym: "2027-01",
        remaining_slots: 1,
        mtch_scr: 90,
        score_components: {
          job: 40,
          grade: 20,
          gap: 15,
          customer: 10,
          probability: 5,
        },
        waiting_days: 2,
        win_prblt_rt: 60,
        warnings: [],
      },
    ],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST" || init?.method === "DELETE") {
      if (writeError)
        return new Response(
          JSON.stringify({
            success: false,
            meta: {},
            ...writeError,
            requestId: "temp-request",
          }),
          { status: writeError.status },
        );
      if (init.method === "DELETE") {
        const id = Number(url.split("/").pop());
        rows = rows.map((r) =>
          r.id === id ? { ...r, status: "RELEASED" } : r,
        );
        recommendations.next_temp_assignments =
          recommendations.next_temp_assignments.filter((r) => r.id !== id);
        return response(rows.find((r) => r.id === id));
      }
      const input = JSON.parse(init.body as string);
      const row = {
        ...input,
        id: 72,
        status: "ACTIVE",
        mtch_scr: input.tmpr_asgn_src_cd === "NEXT" ? 90 : null,
        asgn_id: null,
        warnings: ["2026-11 확정+가배정 MM 1.10 (1.00 초과)"],
      };
      rows = [...rows, row];
      recommendations.next_temp_assignments = [row];
      return response(row);
    }
    if (url.startsWith("/api/temp-assignments?")) {
      if (readError) throw new Error("offline");
      return response({ items: rows, page: 0, size: 50, total: rows.length });
    }
    if (url.startsWith("/api/developers/options?"))
      return response([
        {
          id: 21,
          name: "선택 인력",
          mngNo: "K-0021",
          mainJob: "BE",
          empType: "FT",
        },
      ]);
    if (url === "/api/withdrawals/41/next-slots") {
      if (readError) throw new Error("offline");
      return response(recommendations);
    }
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const panel = (open = true) =>
  render(<TempAssignmentPanel need={need} open={open} onChanged={changed} />);
const writes = () =>
  fetcher.mock.calls.filter((c) => ["POST", "DELETE"].includes(c[1]?.method));
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
async function form() {
  fireEvent.click(await screen.findByTestId("temp-assignment-add"));
  await screen.findByRole("option", { name: "선택 인력 (K-0021)" });
  change("개발자", "21");
}
const save = () => fireEvent.click(screen.getByTestId("temp-assignment-save"));

describe("temporary assignment need-row panel", () => {
  it("renders separate overlay, source and actual-metric exclusion guidance", async () => {
    panel();
    await screen.findByTestId("temp-assignment-row-71");
    expect(screen.getByText("직접 선택")).toBeVisible();
    expect(
      screen.getByText(/실제 MM·다음 배정·청구·원가에는 포함되지 않습니다/),
    ).toBeVisible();
    expect(fetcher.mock.calls[0][0]).toContain("need_id=11");
  });
  it("renders loading without editable stale records", () => {
    fetcher.mockImplementation(() => new Promise(() => {}));
    panel();
    expect(screen.getByRole("status", { name: "불러오는 중" })).toBeVisible();
    expect(screen.queryByTestId("temp-assignment-add")).not.toBeInTheDocument();
  });
  it("renders empty list with no invented rows", async () => {
    rows = [];
    panel();
    expect(
      await screen.findByText("조건에 맞는 결과가 없습니다"),
    ).toBeVisible();
  });
  it("failed read offers retry and prevents writing stale data", async () => {
    readError = true;
    panel();
    await screen.findByRole("alert");
    expect(screen.queryByTestId("temp-assignment-add")).not.toBeInTheDocument();
    readError = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByTestId("temp-assignment-row-71");
  });
  it.each(["RELEASED", "CONVERTED"] as const)(
    "%s cannot be released",
    async (status) => {
      rows[0].status = status;
      panel();
      await screen.findByTestId("temp-assignment-row-71");
      expect(
        screen.queryByTestId("temp-assignment-release-71"),
      ).not.toBeInTheDocument();
    },
  );
  it("closed opportunity is read-only but keeps history", async () => {
    panel(false);
    await screen.findByTestId("temp-assignment-row-71");
    expect(screen.queryByTestId("temp-assignment-add")).not.toBeInTheDocument();
    expect(
      screen.queryByTestId("temp-assignment-release-71"),
    ).not.toBeInTheDocument();
    expect(screen.getByText(/기록은 읽기만/)).toBeVisible();
  });
  it("manual selection saves only writable wire fields and refreshes DB layers", async () => {
    panel();
    await form();
    save();
    await waitFor(() => expect(changed).toHaveBeenCalledTimes(1));
    expect(JSON.parse(writes()[0][1].body)).toEqual({
      need_id: 11,
      dev_id: 21,
      bgn_ym: "2026-11",
      end_ym: "2027-01",
      mth_mm_qty: 0.4,
      tmpr_asgn_src_cd: "MANUAL",
    });
    expect(
      await screen.findByText("2026-11 확정+가배정 MM 1.10 (1.00 초과)"),
    ).toBeVisible();
    expect(screen.getByText("가배정을 저장했습니다.")).toBeVisible();
    expect(
      screen.queryByTestId("temp-assignment-form"),
    ).not.toBeInTheDocument();
    expect(
      fetcher.mock.calls.filter((c) =>
        c[0].startsWith("/api/temp-assignments?"),
      ),
    ).toHaveLength(2);
  });
  it.each(["0.01", "1.00"])("accepts boundary MM %s", async (mm) => {
    panel();
    await form();
    change("월 MM", mm);
    save();
    await waitFor(() => expect(changed).toHaveBeenCalled());
    expect(JSON.parse(writes()[0][1].body).mth_mm_qty).toBe(Number(mm));
  });
  it.each(["0", "1.01", "0.001", "-1", ""])(
    "rejects MM %s without write",
    async (mm) => {
      panel();
      await form();
      change("월 MM", mm);
      save();
      expect(
        await screen.findByText("월 MM은 0.01~1.00으로 입력하세요."),
      ).toBeVisible();
      expect(screen.getByLabelText("월 MM")).toHaveFocus();
      expect(writes()).toHaveLength(0);
    },
  );
  it("rejects reversed months retaining draft", async () => {
    panel();
    await form();
    change("종료월", "2026-10");
    save();
    expect(
      await screen.findByText("종료월은 시작월 이후여야 합니다."),
    ).toBeVisible();
    expect(screen.getByLabelText("종료월")).toHaveFocus();
    expect(writes()).toHaveLength(0);
  });
  it("requires a real selected developer", async () => {
    panel();
    await form();
    change("개발자", "");
    save();
    expect(await screen.findByText("입력값을 확인하세요.")).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it("server duplicate error retains draft and request id", async () => {
    writeError = {
      status: 409,
      code: "DUPLICATE_TEMP_ASSIGNMENT",
      message: "이미 겹치는 가배정이 있습니다.",
      field: "dev_id",
    };
    panel();
    await form();
    save();
    await screen.findByText("문의 번호 temp-request");
    expect(screen.getByLabelText("개발자")).toHaveFocus();
    expect(screen.getByLabelText("월 MM")).toHaveValue("0.4");
    expect(changed).not.toHaveBeenCalled();
  });
  it("uncertain server write blocks resubmit until read recovery", async () => {
    writeError = {
      status: 500,
      code: "INTERNAL_ERROR",
      message: "저장 실패",
      field: null,
    };
    panel();
    await form();
    save();
    await screen.findByText("저장 실패");
    expect(screen.getByTestId("temp-assignment-save")).toBeDisabled();
    writeError = null;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() =>
      expect(screen.getByTestId("temp-assignment-save")).toBeEnabled(),
    );
    expect(writes()).toHaveLength(1);
  });
  it("release requires confirmation and preserves original record", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("temp-assignment-release-71"));
    expect(writes()).toHaveLength(0);
    fireEvent.click(screen.getByTestId("temp-assignment-release-confirm"));
    await waitFor(() => expect(changed).toHaveBeenCalledTimes(1));
    expect(writes()[0][0]).toBe("/api/temp-assignments/71");
    expect(writes()[0][1].body).toBeUndefined();
    expect(await screen.findByTestId("temp-assignment-row-71")).toBeVisible();
    expect(
      screen.queryByTestId("temp-assignment-release-71"),
    ).not.toBeInTheDocument();
  });
  it("cancel release makes no write", async () => {
    panel();
    fireEvent.click(await screen.findByTestId("temp-assignment-release-71"));
    fireEvent.click(screen.getByTestId("temp-assignment-release-cancel"));
    expect(writes()).toHaveLength(0);
  });
  it("shows all warnings using shared warning marks without disabling save", async () => {
    rows[0].warnings = [
      "필요 인원을 초과한 가배정입니다.",
      "다른 필요 인력과 가배정 기간이 겹칩니다.",
      "휴직 기간 가배정입니다.",
      "퇴사 기간 가배정입니다.",
    ];
    panel();
    for (const text of rows[0].warnings)
      expect(await screen.findByText(text)).toBeVisible();
    expect(screen.getByTestId("temp-assignment-add")).toBeEnabled();
    expect(screen.getAllByText("경고")).toHaveLength(4);
  });
});

describe("withdrawal recommendation temporary assignments", () => {
  const renderRecommendations = () =>
    render(
      <WithdrawalTempAssignments asgnId={41} devId={21} onChanged={changed} />,
    );
  it("empty recommendations hide recommendation CTA", async () => {
    recommendations.items = [];
    recommendations.recommendation_count = 0;
    renderRecommendations();
    expect(await screen.findByText("맞는 자리 없음")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: /추천/ }),
    ).not.toBeInTheDocument();
  });
  it("opens score breakdown and creates NEXT without submitting server score", async () => {
    renderRecommendations();
    fireEvent.click(await screen.findByRole("button", { name: "추천 1곳" }));
    const dialog = await screen.findByTestId(
      "withdrawal-recommendation-dialog",
    );
    expect(within(dialog).getByText("90점")).toBeVisible();
    expect(within(dialog).getByText(/직무 40/)).toBeVisible();
    fireEvent.click(screen.getByTestId("recommendation-assign-11"));
    await waitFor(() =>
      expect(screen.getByTestId("temp-assignment-save")).toBeEnabled(),
    );
    change("월 MM", "0.40");
    save();
    await waitFor(() => expect(changed).toHaveBeenCalled());
    expect(JSON.parse(writes()[0][1].body)).toEqual({
      need_id: 11,
      dev_id: 21,
      bgn_ym: "2026-11",
      end_ym: "2027-01",
      mth_mm_qty: 0.4,
      tmpr_asgn_src_cd: "NEXT",
    });
    expect(await screen.findByText("다음 가배정")).toBeVisible();
  });
  it("full need cannot be selected as recommendation", async () => {
    recommendations.items[0].remaining_slots = 0;
    renderRecommendations();
    fireEvent.click(await screen.findByRole("button", { name: "추천 1곳" }));
    expect(screen.getByTestId("recommendation-assign-11")).toBeDisabled();
  });
  it("missing end month is explicit, never fabricated", async () => {
    recommendations.items[0].end_ym = undefined;
    renderRecommendations();
    fireEvent.click(await screen.findByRole("button", { name: "추천 1곳" }));
    expect(screen.getByTestId("recommendation-assign-11")).toBeDisabled();
    expect(screen.getByText("종료월 자료가 없습니다.")).toBeVisible();
  });
  it("can release existing next overlay even without new recommendations", async () => {
    recommendations.recommendation_count = 0;
    recommendations.items = [];
    recommendations.next_temp_assignments = [
      { ...original, tmpr_asgn_src_cd: "NEXT", mtch_scr: 90 },
    ];
    renderRecommendations();
    fireEvent.click(await screen.findByTestId("withdrawal-temp-release-71"));
    expect(writes()).toHaveLength(0);
    fireEvent.click(screen.getByTestId("withdrawal-temp-release-confirm"));
    await waitFor(() => expect(changed).toHaveBeenCalled());
    expect(
      screen.queryByTestId("withdrawal-temp-row-71"),
    ).not.toBeInTheDocument();
  });
  it("failed recommendation read is visible and retryable", async () => {
    readError = true;
    renderRecommendations();
    await screen.findByRole("alert");
    readError = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await screen.findByRole("button", { name: "추천 1곳" });
  });
});
