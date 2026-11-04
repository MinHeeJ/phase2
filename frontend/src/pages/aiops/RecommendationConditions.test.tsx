import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RecommendationConditions } from "./RecommendationConditions";
import type { RecommendationOptions } from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let data: RecommendationOptions;
let fetcher: ReturnType<typeof vi.fn>;
let onRecommend: ReturnType<typeof vi.fn>;
beforeEach(() => {
  data = {
    sources: [
      {
        sourceId: 8123,
        sourceType: "PROJECT",
        projectId: 11,
        remainingHeadcount: 2,
        criteria: {
          jobCode: "BE",
          startYm: "2027-01",
          gradeCode: "SR",
          skillCodes: ["JAVA"],
          durationMonths: 3,
          headcount: 2,
          requestedMm: 0.5,
        },
      },
    ],
    developers: [
      {
        devId: 1,
        exitDate: "2026-12-31",
        assumedExitYm: null,
        extensionLikelihood: "H",
        hasNextConfirmedAssignment: true,
      },
      {
        devId: 2,
        exitDate: "2026-12-31",
        assumedExitYm: null,
        extensionLikelihood: "M",
        hasNextConfirmedAssignment: false,
      },
      {
        devId: 3,
        exitDate: "2026-12-31",
        assumedExitYm: null,
        extensionLikelihood: "L",
        hasNextConfirmedAssignment: false,
      },
      {
        devId: 4,
        exitDate: null,
        assumedExitYm: null,
        extensionLikelihood: null,
        hasNextConfirmedAssignment: false,
      },
    ],
    jobCodes: ["BE", "FE"],
    gradeCodes: ["SR"],
    skillCodes: ["JAVA"],
    defaultStartYm: "2027-01",
  };
  fetcher = vi.fn(async () => response(data));
  vi.stubGlobal("fetch", fetcher);
  onRecommend = vi.fn(async () => {});
});
afterEach(() => vi.unstubAllGlobals());
async function mountA() {
  render(
    <RecommendationConditions
      direction="PROJECT_TO_PERSON"
      onRecommend={onRecommend}
    />,
  );
  await screen.findByLabelText("직무 *");
}
async function mountB() {
  render(
    <RecommendationConditions
      direction="PERSON_TO_POSITION"
      onRecommend={onRecommend}
    />,
  );
  await screen.findByLabelText("개발자 #1 선택");
}
async function selectSource() {
  fireEvent.change(screen.getByLabelText("원천 유형"), {
    target: { value: "PROJECT" },
  });
  fireEvent.change(screen.getByLabelText("원천"), {
    target: { value: "8123" },
  });
  await screen.findByText(/현재 원천 자동 채움/);
}
describe("AI recommendation options", () => {
  it("shows loading, then uses server default month and same-origin singular API", async () => {
    render(
      <RecommendationConditions
        direction="PROJECT_TO_PERSON"
        onRecommend={onRecommend}
      />,
    );
    expect(
      screen.getByRole("status", { name: "추천 조건 불러오는 중" }),
    ).toBeInTheDocument();
    expect(await screen.findByLabelText("시작월 *")).toHaveValue("2027-01");
    expect(fetcher.mock.calls[0][0]).toBe(
      "/api/ai/staff-recommendation/options?direction=PROJECT_TO_PERSON",
    );
    expect(fetcher.mock.calls[0][1]).toMatchObject({
      credentials: "same-origin",
    });
  });
  it("revalidates source, prefills criteria, permits editing and emits exact search branch", async () => {
    await mountA();
    await selectSource();
    expect(screen.getByLabelText("기간 (개월)")).toHaveValue(3);
    expect(screen.getByLabelText("월 MM")).toHaveValue(0.5);
    fireEvent.change(screen.getByLabelText("직무 *"), {
      target: { value: "FE" },
    });
    fireEvent.click(screen.getByRole("button", { name: "추천 실행" }));
    await waitFor(() =>
      expect(onRecommend).toHaveBeenCalledWith({
        direction: "PROJECT_TO_PERSON",
        sourceId: 8123,
        criteria: { ...data.sources[0].criteria, jobCode: "FE" },
      }),
    );
    expect(
      fetcher.mock.calls.filter(([path]) =>
        String(path).includes("sourceId=8123"),
      ),
    ).toHaveLength(2);
    expect(
      await screen.findByText(
        "추천 요청을 처리했습니다. 추천·채택만으로 배정은 생성되지 않습니다.",
      ),
    ).toBeInTheDocument();
  });
  it("does not invent optional manual duration, headcount or MM", async () => {
    await mountA();
    fireEvent.change(screen.getByLabelText("직무 *"), {
      target: { value: "BE" },
    });
    fireEvent.click(screen.getByRole("button", { name: "추천 실행" }));
    await waitFor(() =>
      expect(onRecommend).toHaveBeenCalledWith({
        direction: "PROJECT_TO_PERSON",
        criteria: { jobCode: "BE", startYm: "2027-01" },
      }),
    );
  });
  it("requires job and focuses the field before any execution", async () => {
    await mountA();
    fireEvent.click(screen.getByRole("button", { name: "추천 실행" }));
    expect(screen.getByRole("alert")).toHaveTextContent("직무를 선택하세요");
    expect(screen.getByTestId("ai-job-select")).toHaveFocus();
    expect(onRecommend).not.toHaveBeenCalled();
  });
  it.each([
    ["기간 (개월)", "37", "기간은 1~36개월"],
    ["인원 (명)", "101", "인원은 1~100명"],
    ["월 MM", "0.001", "월 MM은 0.01~1.00"],
  ])("validates %s independently", async (label, value, message) => {
    await mountA();
    fireEvent.change(screen.getByLabelText("직무 *"), {
      target: { value: "BE" },
    });
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
    fireEvent.click(screen.getByRole("button", { name: "추천 실행" }));
    expect(screen.getByRole("alert")).toHaveTextContent(message);
    expect(onRecommend).not.toHaveBeenCalled();
  });
  it("preserves H/M/L/missing extension and next-confirmed rows with distinct badges", async () => {
    await mountB();
    const high = within(screen.getByTestId("ai-exit-row-1"));
    expect(high.getByText("H").className).toContain("warning");
    expect(high.getByText("있음")).toBeInTheDocument();
    expect(
      within(screen.getByTestId("ai-exit-row-2")).getByText("M").className,
    ).toContain("info");
    expect(
      within(screen.getByTestId("ai-exit-row-3")).getByText("L").className,
    ).toContain("success");
    expect(screen.getByText("미입력")).toBeInTheDocument();
    fireEvent.click(high.getByRole("radio"));
    expect(
      screen.getByText("연장 가능성 높음, 일정 확인 필요"),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "추천 실행" }));
    await waitFor(() =>
      expect(onRecommend).toHaveBeenCalledWith({
        direction: "PERSON_TO_POSITION",
        devId: 1,
        criteria: {},
      }),
    );
  });
  it("requires assumed exit month only when exit is unknown and passes it without changing sources", async () => {
    await mountB();
    fireEvent.click(screen.getByLabelText("개발자 #4 선택"));
    expect(screen.getByText(/UNKNOWN_EXIT/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "추천 실행" }));
    expect(screen.getByRole("alert")).toHaveTextContent("가정 철수월이 필요");
    expect(onRecommend).not.toHaveBeenCalled();
    fireEvent.change(screen.getByTestId("ai-assumed-exit"), {
      target: { value: "2026-12" },
    });
    fireEvent.click(screen.getByRole("button", { name: "추천 실행" }));
    await waitFor(() =>
      expect(onRecommend).toHaveBeenCalledWith({
        direction: "PERSON_TO_POSITION",
        devId: 4,
        criteria: { assumedExitYm: "2026-12" },
      }),
    );
    expect(
      fetcher.mock.calls.every(
        ([, init]) => !init.method || init.method === "GET",
      ),
    ).toBe(true);
  });
  it("renders genuine empty state without fabricated options", async () => {
    data.developers = [];
    await mountBEmpty();
    expect(
      screen.getByText("3개월 내 철수 예정자가 없습니다."),
    ).toBeInTheDocument();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
  });
  it("shows connection failure separately from empty and retries only options", async () => {
    fetcher.mockRejectedValueOnce(new Error("offline"));
    await mountBEmpty();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "서버에 연결할 수 없습니다",
    );
    expect(
      screen.queryByText("3개월 내 철수 예정자가 없습니다."),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "조건 다시 조회" }));
    await screen.findByLabelText("개발자 #1 선택");
    expect(onRecommend).not.toHaveBeenCalled();
  });
  it("production refusal hides data and execution controls", async () => {
    fetcher.mockResolvedValue(
      new Response(
        JSON.stringify({
          success: false,
          code: "FORBIDDEN",
          message: "개발 환경 전용",
          requestId: "test-request",
        }),
        { status: 403 },
      ),
    );
    await mountBEmpty();
    expect(screen.getByRole("alert")).toHaveTextContent("개발 환경 전용");
    expect(
      screen.queryByRole("button", { name: "추천 실행" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
  });
  it("source conflict blocks automatic fill and recommendation until a new choice", async () => {
    await mountA();
    fetcher.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          success: false,
          code: "CONFLICT",
          field: "sourceId",
          message: "원천 변경",
          requestId: "test-request",
        }),
        { status: 409 },
      ),
    );
    fireEvent.change(screen.getByLabelText("원천 유형"), {
      target: { value: "PROJECT" },
    });
    fireEvent.change(screen.getByLabelText("원천"), {
      target: { value: "8123" },
    });
    await screen.findByText("원천 변경");
    expect(screen.queryByText(/현재 원천 자동 채움/)).not.toBeInTheDocument();
    expect(onRecommend).not.toHaveBeenCalled();
  });
  it("read-only state permits viewing but hides execution", async () => {
    render(
      <RecommendationConditions
        direction="PERSON_TO_POSITION"
        readOnly
        onRecommend={onRecommend}
      />,
    );
    expect(await screen.findByLabelText("개발자 #1 선택")).toBeDisabled();
    expect(
      screen.queryByRole("button", { name: "추천 실행" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText(/읽기 전용/)).toBeInTheDocument();
  });
  it("cancels local edits without writes and restores source defaults", async () => {
    await mountA();
    await selectSource();
    fireEvent.change(screen.getByLabelText("기간 (개월)"), {
      target: { value: "7" },
    });
    fireEvent.click(screen.getByRole("button", { name: "조건 편집 취소" }));
    expect(screen.getByLabelText("기간 (개월)")).toHaveValue(3);
    expect(onRecommend).not.toHaveBeenCalled();
  });
  it("server field errors remain attached to the actual input", async () => {
    const { ApiFailure } = await import("../../shared/api");
    onRecommend.mockRejectedValueOnce(
      new ApiFailure(
        "기간을 확인하세요",
        400,
        "test-request",
        "criteria.durationMonths",
        "INVALID_INPUT",
      ),
    );
    await mountA();
    fireEvent.change(screen.getByLabelText("직무 *"), {
      target: { value: "BE" },
    });
    fireEvent.click(screen.getByRole("button", { name: "추천 실행" }));
    await waitFor(() =>
      expect(screen.getByTestId("ai-duration")).toHaveAttribute(
        "aria-invalid",
        "true",
      ),
    );
  });
});
async function mountBEmpty() {
  render(
    <RecommendationConditions
      direction="PERSON_TO_POSITION"
      onRecommend={onRecommend}
    />,
  );
  await waitFor(() =>
    expect(
      screen.queryByTestId("ai-conditions-loading"),
    ).not.toBeInTheDocument(),
  );
}
