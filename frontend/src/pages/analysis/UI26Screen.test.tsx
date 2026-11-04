import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UI26Screen from "./UI26Screen";
import type {
  Answer,
  Capabilities,
  Conversation,
  StoredMessage,
} from "./client";

const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let fetcher: ReturnType<typeof vi.fn>;
let conversations: Conversation[];
let messages: StoredMessage[];
let answer: Answer;
let capabilities: Capabilities;
let fail: boolean;
let holdMessage: boolean;
const mount = () =>
  render(
    <MemoryRouter>
      <UI26Screen />
    </MemoryRouter>,
  );

beforeEach(() => {
  conversations = [];
  messages = [];
  fail = false;
  holdMessage = false;
  capabilities = {
    modelStatus: "연결불가",
    modelUsed: false,
    suggestedQuestions: [
      "이번 달 적자가 큰 프로젝트 3개",
      "프로젝트 손익 추이",
      "월 재무 현황",
      "오늘 미수 위험",
      "인력 수급",
      "마감 상태",
    ],
    intents: [
      {
        intent: "PROJECT_PROFIT_RANKING",
        available: true,
        requiredFields: ["baseYm"],
        allowedFilters: ["projectIds"],
        maxMonths: 36,
        maxLimit: 20,
        unavailableReason: null,
      },
      {
        intent: "WHAT_IF_PROJECT_COST",
        available: true,
        requiredFields: ["virtualCostAssumption"],
        allowedFilters: [],
        maxMonths: 36,
        maxLimit: 20,
        unavailableReason: null,
      },
      {
        intent: "WHAT_IF_WORKFORCE",
        available: true,
        requiredFields: ["workforceAssumptions"],
        allowedFilters: [],
        maxMonths: 36,
        maxLimit: 20,
        unavailableReason: null,
      },
    ],
  };
  answer = {
    status: "OK",
    answer: "서버 검증 답변 <script>실행하지 않음</script>",
    interpretedQuery: { intent: "PROJECT_PROFIT_RANKING", baseYm: "2026-11" },
    metrics: { revenue: 100, cost: 0, profit: 100 },
    table: [
      {
        analysisType: "PROJECT_PROFIT_RANKING",
        criteria: { intent: "PROJECT_PROFIT_RANKING" },
        period: {
          baseYm: "2026-11",
          fromYm: "2026-11",
          toYm: "2026-11",
          basisDate: "2026-11-04",
        },
        sourceService: "BillingRevenueQuery",
        sourceScreenIds: ["UI-04"],
        closedStatus: "OPEN",
        generatedAt: "2026-11-04T09:00:00+09:00",
        unit: "KRW",
        metrics: { revenue: 100, cost: 0, profit: 100 },
        rows: [
          {
            projectId: 41,
            revenue: 100,
            cost: 0,
            profit: 100,
            marginPercent: null,
          },
        ],
        chartSpec: {
          type: "BAR",
          xField: "projectId",
          yFields: ["revenue", "cost", "profit"],
        },
        assumptions: [],
        warnings: [],
      },
    ],
    chart: [
      {
        type: "BAR",
        xField: "projectId",
        yFields: ["revenue", "cost", "profit"],
      },
    ],
    sourceRefs: [
      {
        sourceService: "BillingRevenueQuery",
        sourceScreenIds: ["UI-04"],
        baseYm: "2026-11",
        closedStatus: "OPEN",
        generatedAt: "2026-11-04T09:00:00+09:00",
      },
    ],
    assumptions: [],
    warnings: [],
    clarification: null,
    modelUsed: false,
    fallbackUsed: true,
    areaErrors: [],
  };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (fail)
      return new Response(
        JSON.stringify({
          success: false,
          code: "SERVICE_UNAVAILABLE",
          message: "필수 원천 조회 실패",
          requestId: "test-request",
        }),
        { status: 503 },
      );
    if (url.endsWith("/capabilities")) return response(capabilities);
    if (url.endsWith("/structured")) return response(answer);
    if (url.endsWith("/feedback")) {
      const body = JSON.parse(init!.body as string);
      messages[0].feedback = { messageId: 72, ...body };
      return response(messages[0].feedback);
    }
    if (url.endsWith("/messages") && init?.method === "POST") {
      if (holdMessage)
        return new Promise<Response>((_, reject) =>
          init.signal?.addEventListener("abort", () =>
            reject(new DOMException("Aborted", "AbortError")),
          ),
        );
      messages = [
        {
          messageId: 72,
          questionSummary: "마스킹 질문",
          answerSummary: "안전 요약",
          criteria: { intent: "PROJECT_PROFIT_RANKING" },
          status: "OK",
          sourceRefs: answer.sourceRefs,
          createdAt: "2026-11-04T09:00:00+09:00",
        },
      ];
      return response({ ...answer, messageId: 72 });
    }
    if (url.endsWith("/conversations") && init?.method === "POST") {
      const row = {
        id: 63,
        createdAt: "2026-11-04T09:00:00+09:00",
        updatedAt: "2026-11-04T09:00:00+09:00",
        visible: true,
      };
      conversations = [row];
      return response(row);
    }
    if (init?.method === "DELETE") {
      conversations = [];
      return response({ id: 63, visible: false });
    }
    if (/\/conversations\?/.test(url))
      return response({
        items: conversations,
        total: conversations.length,
        page: 0,
        size: 50,
      });
    if (/\/conversations\/63\?/.test(url))
      return response({
        items: messages,
        total: messages.length,
        page: 0,
        size: 50,
        conversation: conversations[0],
      });
    throw new Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
async function start() {
  mount();
  await screen.findByText(
    "추천 질문을 선택하거나 정형 분석으로 실제 자료를 조회하세요.",
  );
}
async function structured() {
  await start();
  fireEvent.click(screen.getByRole("button", { name: "정형 분석" }));
  fireEvent.click(screen.getByRole("button", { name: "분석 실행" }));
  return screen.findByTestId("analysis-answer");
}
async function send() {
  await start();
  fireEvent.change(screen.getByLabelText("질문"), {
    target: { value: "이번 달 적자가 큰 프로젝트 3개" },
  });
  fireEvent.click(screen.getByRole("button", { name: "전송" }));
  return screen.findByTestId("analysis-answer");
}
describe("SCR-UI-26", () => {
  it("renders unavailable model, empty history and API question buttons without automatically submitting", async () => {
    await start();
    expect(screen.getByText("연결불가")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "최근 대화 펼치기" }));
    expect(screen.getByText("표시할 대화가 없습니다.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "월 재무 현황" }));
    expect(screen.getByLabelText("질문")).toHaveValue("월 재무 현황");
    expect(fetcher.mock.calls.some(([, init]) => init?.method === "POST")).toBe(
      false,
    );
  });
  it("rejects blank and 1001-character drafts and retains them", async () => {
    await start();
    fireEvent.click(screen.getByRole("button", { name: "전송" }));
    expect(screen.getByRole("alert")).toHaveTextContent("1~1000");
    fireEvent.change(screen.getByLabelText("질문"), {
      target: { value: "가".repeat(1001) },
    });
    fireEvent.click(screen.getByRole("button", { name: "전송" }));
    expect(screen.getByLabelText("질문")).toHaveValue("가".repeat(1001));
    expect(fetcher.mock.calls.some(([, init]) => init?.method === "POST")).toBe(
      false,
    );
  });
  it("runs structured without creating chat and renders escaped text and identical chart values", async () => {
    const card = await structured();
    expect(card).toHaveTextContent("<script>실행하지 않음</script>");
    expect(card.querySelector("script")).toBeNull();
    const row = screen.getByTestId("analysis-row-0-0");
    expect(within(row).getByText("0")).toBeInTheDocument();
    expect(within(row).getByText("–")).toBeInTheDocument();
    expect(screen.getByRole("img")).toBeInTheDocument();
    expect(
      fetcher.mock.calls
        .filter(([, init]) => init?.method === "POST")
        .map(([url]) => url),
    ).toEqual(["/api/ai/analysis/structured"]);
    expect(
      screen.getByRole("link", { name: "원본 화면 UI-04" }),
    ).toBeInTheDocument();
  });
  it("creates then reads owner conversation and stores selected API message reference", async () => {
    await send();
    expect(
      fetcher.mock.calls.some(([url]) => url.includes("/conversations/63?")),
    ).toBe(true);
    expect(screen.getByTestId("analysis-message-72")).toHaveTextContent(
      "안전 요약",
    );
    expect(screen.getByLabelText("질문")).toHaveValue("");
  });
  it("shows feedback only on message answers and reads back latest rating", async () => {
    await send();
    fireEvent.click(screen.getByTestId("analysis-feedback-open"));
    fireEvent.change(screen.getByLabelText("평가 선택"), {
      target: { value: "INACCURATE" },
    });
    fireEvent.click(screen.getByRole("button", { name: "평가 제출" }));
    await screen.findByText(/최신 평가: INACCURATE/);
    expect(messages).toHaveLength(1);
  });
  it("retains existing rating when 501-character reason is rejected", async () => {
    await send();
    fireEvent.click(screen.getByTestId("analysis-feedback-open"));
    fireEvent.change(screen.getByLabelText("평가 선택"), {
      target: { value: "HELPFUL" },
    });
    fireEvent.change(screen.getByLabelText("평가 사유"), {
      target: { value: "가".repeat(501) },
    });
    fireEvent.click(screen.getByRole("button", { name: "평가 제출" }));
    expect(screen.getByRole("alert")).toHaveTextContent("500자");
    expect(fetcher.mock.calls.some(([url]) => url.endsWith("/feedback"))).toBe(
      false,
    );
  });
  it("requires inline confirmation to hide and leaves retained summary policy clear", async () => {
    await send();
    fireEvent.click(screen.getByRole("button", { name: "최근 대화 펼치기" }));
    fireEvent.click(screen.getByRole("button", { name: "대화 표시 삭제" }));
    expect(
      fetcher.mock.calls.some(([, init]) => init?.method === "DELETE"),
    ).toBe(false);
    expect(
      screen.getByTestId("analysis-delete-confirmation"),
    ).toHaveTextContent("물리삭제와 다릅니다");
    fireEvent.click(screen.getByRole("button", { name: "표시 삭제 확인" }));
    await waitFor(() =>
      expect(
        screen.queryByTestId("analysis-message-72"),
      ).not.toBeInTheDocument(),
    );
  });
  it("preserves draft on cancellation and ignores late output", async () => {
    holdMessage = true;
    await start();
    fireEvent.change(screen.getByLabelText("질문"), {
      target: { value: "중단할 질문" },
    });
    fireEvent.click(screen.getByRole("button", { name: "전송" }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => url.endsWith("/messages")),
      ).toBe(true),
    );
    fireEvent.click(screen.getByRole("button", { name: "중단" }));
    expect(screen.getByLabelText("질문")).toHaveValue("중단할 질문");
    expect(screen.queryByTestId("analysis-answer")).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("완료 경합");
  });
  it("renders partial successful values alongside failed area", async () => {
    answer.status = "PARTIAL";
    answer.areaErrors = [
      {
        analysisType: "FORECAST_PROFIT",
        code: "SERVICE_UNAVAILABLE",
        message: "평가 원천 실패",
      },
    ];
    const card = await structured();
    expect(card).toHaveTextContent("일부 영역 조회 실패");
    expect(card).toHaveTextContent("평가 원천 실패");
    expect(screen.getByTestId("analysis-row-0-0")).toBeInTheDocument();
  });
  it("renders clarification/model/unsupported/no-data distinctly", async () => {
    answer.status = "CLARIFICATION_REQUIRED";
    answer.clarification = "프로젝트와 기간을 입력해 주세요.";
    await structured();
    expect(screen.getByTestId("analysis-answer")).toHaveTextContent(
      "질문 조건 확인 필요",
    );
    expect(screen.getByTestId("analysis-answer")).toHaveTextContent(
      "프로젝트와 기간",
    );
  });
  it("does not turn failed requests into empty data", async () => {
    fail = true;
    mount();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "필수 원천 조회 실패",
    );
    expect(
      screen.queryByText("표시할 대화가 없습니다."),
    ).not.toBeInTheDocument();
    fail = false;
    fireEvent.click(screen.getByRole("button", { name: "다시 조회" }));
    await screen.findByText("연결불가");
  });
  it("caps workforce assumptions at three and exposes no payroll tools", async () => {
    await start();
    fireEvent.click(screen.getByRole("button", { name: "정형 분석" }));
    fireEvent.change(screen.getByLabelText("허용 의도"), {
      target: { value: "WHAT_IF_WORKFORCE" },
    });
    for (let i = 0; i < 3; i++) {
      fireEvent.click(
        screen.getByRole("button", { name: "인력 가정 추가 (최대 3개)" }),
      );
      await waitFor(() =>
        expect(
          screen.getByTestId(`analysis-assumption-${i}`),
        ).toBeInTheDocument(),
      );
    }
    expect(
      screen.getByRole("button", { name: "인력 가정 추가 (최대 3개)" }),
    ).toBeDisabled();
    expect(screen.queryByLabelText("급여")).not.toBeInTheDocument();
  });
  it("exports the displayed answer criteria, not unsaved edited form conditions", async () => {
    vi.stubGlobal(
      "URL",
      class extends URL {
        static createObjectURL = vi.fn(() => "blob:analysis-test");
        static revokeObjectURL = vi.fn();
      },
    );
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    const normal = fetcher.getMockImplementation()!;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      url.endsWith("/structured/export")
        ? Promise.resolve(
            new Response("workbook", {
              headers: {
                "Content-Type":
                  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
              },
            }),
          )
        : normal(url, init),
    );
    await structured();
    fireEvent.change(screen.getByTestId("analysis-limit"), {
      target: { value: "9" },
    });
    fireEvent.change(screen.getByTestId("analysis-base-month"), {
      target: { value: "2027-02" },
    });
    fireEvent.click(screen.getByTestId("analysis-excel"));
    await screen.findByText(/현재 원천을 재조회한 Excel을 다운로드했습니다/);
    const exported = fetcher.mock.calls.find(([url]) =>
      url.endsWith("/structured/export"),
    )!;
    expect(JSON.parse(String(exported[1]?.body))).toMatchObject({
      intent: "PROJECT_PROFIT_RANKING",
      baseYm: "2026-11",
      limit: 5,
    });
  });
  it("does not start a message when creation of its conversation completes after cancellation", async () => {
    let resolveCreation!: (response: Response) => void;
    const normal = fetcher.getMockImplementation()!;
    fetcher.mockImplementation((url: string, init?: RequestInit) =>
      url.endsWith("/conversations") && init?.method === "POST"
        ? new Promise<Response>((resolve) => {
            resolveCreation = resolve;
          })
        : normal(url, init),
    );
    await start();
    fireEvent.change(screen.getByLabelText("질문"), {
      target: { value: "취소할 질문" },
    });
    fireEvent.click(screen.getByTestId("analysis-send"));
    await waitFor(() => expect(resolveCreation).toBeTypeOf("function"));
    fireEvent.click(screen.getByTestId("analysis-stop"));
    resolveCreation(response({ id: 63, visible: true }));
    await waitFor(() =>
      expect(screen.getByTestId("analysis-send")).toBeEnabled(),
    );
    expect(screen.getByLabelText("질문")).toHaveValue("취소할 질문");
    expect(fetcher.mock.calls.some(([url]) => url.endsWith("/messages"))).toBe(
      false,
    );
  });
  it("reports question and feedback drafts to the existing shell navigation guard", async () => {
    const dirty = vi.fn();
    const view = render(
      <MemoryRouter>
        <UI26Screen onDirtyChange={dirty} />
      </MemoryRouter>,
    );
    await screen.findByText("연결불가");
    fireEvent.change(screen.getByLabelText("질문"), {
      target: { value: "미저장 질문" },
    });
    expect(dirty).toHaveBeenLastCalledWith(true);
    fireEvent.change(screen.getByLabelText("질문"), { target: { value: "" } });
    expect(dirty).toHaveBeenLastCalledWith(false);
    view.unmount();
    expect(dirty).toHaveBeenLastCalledWith(false);
  });
  it("does not submit a stale context ID from an incomplete message page", async () => {
    await send();
    const normal = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) => {
      const result = await normal(url, init);
      if (/\/conversations\/63\?/.test(url)) {
        const body = await result.json();
        body.data.total = 100;
        return response(body.data);
      }
      return result;
    });
    fireEvent.click(screen.getByTestId("analysis-collapse"));
    fireEvent.click(screen.getByTestId("analysis-open-63"));
    await waitFor(() =>
      expect(screen.queryByTestId("analysis-answer")).not.toBeInTheDocument(),
    );
    fireEvent.change(screen.getByTestId("analysis-question"), {
      target: { value: "현재 자료를 다시 조회해 주세요" },
    });
    fireEvent.click(screen.getByTestId("analysis-send"));
    await screen.findByTestId("analysis-answer");
    const requests = fetcher.mock.calls.filter(
      ([url, init]) => url.endsWith("/messages") && init?.method === "POST",
    );
    expect(
      JSON.parse(String(requests[requests.length - 1][1]?.body)),
    ).not.toHaveProperty("contextMessageId");
  });
});
