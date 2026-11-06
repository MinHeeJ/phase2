import { api, ApiFailure, csrfHeaders, readData } from "../../shared/api";

export type AnalysisStatus =
  | "OK"
  | "CLARIFICATION_REQUIRED"
  | "UNSUPPORTED"
  | "MODEL_UNAVAILABLE"
  | "DATA_UNAVAILABLE"
  | "PARTIAL";
export interface StructuredQuery {
  intent: string;
  baseYm?: string;
  fromYm?: string;
  toYm?: string;
  periodType?: "MONTH" | "YTD";
  sort?: string;
  limit?: number;
  filters?: Record<string, number[] | string[]>;
  workforceAssumptions?: {
    type: string;
    opportunityId?: number;
    assignmentId?: number;
    probability: number;
  }[];
  virtualCostAssumption?: {
    revenue: number;
    cost: number;
    costChangePercent: number;
    requestedMm?: number;
    durationMonths?: number;
  };
}
export interface Capability {
  intent: string;
  available: boolean;
  requiredFields: string[];
  allowedFilters: string[];
  maxMonths: number;
  maxLimit: number;
  unavailableReason: string | null;
}
export interface Capabilities {
  intents: Capability[];
  modelStatus: "준비" | "연결불가";
  suggestedQuestions: string[];
  modelUsed: false;
}
export interface Source {
  sourceService: string;
  sourceScreenIds: string[];
  baseYm: string;
  closedStatus: string;
  generatedAt: string;
}
export interface Chart {
  type: "TABLE" | "LINE" | "BAR";
  xField: string;
  yFields: string[];
}
export interface Section extends Omit<Source, "baseYm"> {
  analysisType: string;
  criteria: StructuredQuery;
  period: {
    baseYm: string;
    fromYm?: string;
    toYm?: string;
    basisDate?: string;
  };
  unit: string;
  metrics: Record<string, number | null>;
  rows: Record<string, unknown>[];
  chartSpec: Chart;
  assumptions: string[];
  warnings: string[];
  evaluations?: Record<string, unknown>[];
}
export interface Answer {
  messageId?: number;
  status: AnalysisStatus;
  interpretedQuery?: StructuredQuery;
  answer: string;
  metrics: Record<string, number | null>;
  table: Section[];
  chart: Chart[];
  sourceRefs: Source[];
  period?: Section["period"];
  closedStatus?: Source["closedStatus"];
  assumptions: string[];
  warnings: string[];
  clarification: string | null;
  modelUsed: boolean;
  fallbackUsed: boolean;
  areaErrors: { analysisType: string; code: string; message: string }[];
}
export interface Conversation {
  id: number;
  questionSummary?: string;
  answerSummary?: string;
  createdAt: string;
  updatedAt: string;
  visible: boolean;
}
export interface Feedback {
  messageId: number;
  rating: "HELPFUL" | "INACCURATE";
  reason?: string | null;
}
export interface StoredMessage {
  messageId: number;
  questionSummary: string;
  answerSummary: string;
  criteria?: StructuredQuery;
  status: AnalysisStatus;
  sourceRefs: Source[];
  feedback?: Feedback;
  createdAt: string;
}
export interface Messages {
  items: StoredMessage[];
  total: number;
  page: number;
  size: number;
  conversation: Conversation;
}
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
const root = "/api/ai/analysis";
export const getAnalysisCapabilities = (signal?: AbortSignal) =>
  readData<Capabilities>(`${root}/capabilities`, signal);
export const listAnalysisConversations = (page = 0, signal?: AbortSignal) =>
  readData<Page<Conversation>>(
    `${root}/conversations?${new URLSearchParams({ page: String(page), size: "50" })}`,
    signal,
  );
export const getAnalysisConversation = (
  id: number,
  page = 0,
  signal?: AbortSignal,
) =>
  readData<Messages>(
    `${root}/conversations/${id}?${new URLSearchParams({ page: String(page), size: "50" })}`,
    signal,
  );
export const createAnalysisConversation = async () =>
  (
    await api<Conversation>(`${root}/conversations`, {
      method: "POST",
      credentials: "same-origin",
    })
  ).data!;
export const hideAnalysisConversation = async (id: number) =>
  (
    await api<{ id: number; visible: false }>(`${root}/conversations/${id}`, {
      method: "DELETE",
      credentials: "same-origin",
    })
  ).data!;
export const createAnalysisMessage = async (
  id: number,
  input: { question: string; baseYm?: string; contextMessageId?: number },
  signal: AbortSignal,
) =>
  (
    await api<Answer>(`${root}/conversations/${id}/messages`, {
      method: "POST",
      body: JSON.stringify(input),
      credentials: "same-origin",
      signal,
    })
  ).data!;
export const createStructuredAnalysis = async (
  query: StructuredQuery,
  signal?: AbortSignal,
) =>
  (
    await api<Answer>(`${root}/structured`, {
      method: "POST",
      body: JSON.stringify(query),
      credentials: "same-origin",
      signal,
    })
  ).data!;
export const saveAnalysisFeedback = async (
  id: number,
  messageId: number,
  input: Omit<Feedback, "messageId">,
) =>
  (
    await api<Feedback>(
      `${root}/conversations/${id}/messages/${messageId}/feedback`,
      {
        method: "POST",
        body: JSON.stringify(input),
        credentials: "same-origin",
      },
    )
  ).data!;
export async function exportAnalysis(
  id: number | null,
  answer: Answer,
  query?: StructuredQuery,
) {
  const message = id !== null && answer.messageId !== undefined;
  const response = await fetch(
    message
      ? `${root}/conversations/${id}/messages/${answer.messageId}/export`
      : `${root}/structured/export`,
    {
      method: message ? "GET" : "POST",
      credentials: "same-origin",
      headers: message
        ? {}
        : { "Content-Type": "application/json", ...csrfHeaders() },
      body: message ? undefined : JSON.stringify(query),
    },
  );
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new ApiFailure(
      error.message ?? "다운로드에 실패했습니다.",
      response.status,
      error.requestId ?? null,
      error.field ?? null,
    );
  }
  if (!(response.headers.get("Content-Type") ?? "").includes("spreadsheetml"))
    throw new Error("Excel 응답을 확인할 수 없습니다.");
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = "analysis.xlsx";
  link.click();
  URL.revokeObjectURL(url);
}
