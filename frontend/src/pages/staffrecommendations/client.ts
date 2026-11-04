import { api, readData, ApiFailure } from "../../shared/api";

export type Direction = "PROJECT_TO_PERSON" | "PERSON_TO_POSITION";
export interface Criteria {
  jobCode?: string;
  startYm?: string;
  gradeCode?: string;
  skillCodes?: string[];
  customerId?: number;
  industryCode?: string;
  durationMonths?: number;
  headcount?: number;
  requestedMm?: number;
  assumedExitYm?: string;
}
export interface CareerEntry {
  projectId: number;
  projectName: string;
  startDate: string;
  endDate?: string | null;
  jobCode: string;
  skillCodes: string[];
  monthByMonthMm: { month: string; mm: number }[];
}
export interface Result {
  resultId: number;
  devId: number;
  developerName?: string;
  developerNumber?: string;
  sourceIds: number[];
  rank: number;
  score: number;
  breakdown: Record<string, number>;
  reason: string;
  evidence: string[];
  availabilityStatus: "가능" | "주의" | "확정 충돌" | "판정 불가";
  availabilityWarnings: string[];
  monthByMonthMm: {
    month: string;
    confirmedMm: number;
    activeTemporaryMm: number;
    requestedMm: number;
    warnings: string[];
  }[];
  exitDate: string | null;
  extensionLikelihood: string | null;
  earliestStartYm: string | null;
  decisionStatus: "PENDING" | "ADOPT" | "REJECT";
  rejectionReason?: string | null;
  projectId?: number;
  needId?: number;
  customerName?: string;
  jobCode?: string;
  gradeCode?: string;
  skillCodes?: string[];
  currentProjectNames?: string[];
  positionStartYm?: string;
  positionEndYm?: string;
  recommendedStartYm?: string;
  remainingHeadcount?: number;
  waitingDays?: number;
  waitingDaysEstimated?: boolean;
  opportunityProbability?: number;
  career?: CareerEntry[];
  assignmentId?: number;
  temporaryAssignmentId?: number;
  assignmentLinkStatus?: string;
}
export interface Snapshot {
  requestId: number;
  direction: Direction;
  criteria: Criteria;
  evaluatedAt: string;
  results: Result[];
  missingDataFields: string[];
  warnings: string[];
}
export interface Options {
  sources: {
    sourceId: number;
    sourceType: "PROJECT" | "OPP";
    projectId?: number;
    needId?: number;
    criteria: Criteria;
    remainingHeadcount: number;
  }[];
  developers: {
    devId: number;
    exitDate: string | null;
    assumedExitYm: string | null;
    extensionLikelihood: string | null;
    hasNextConfirmedAssignment: boolean;
  }[];
  jobCodes: string[];
  gradeCodes: string[];
  skillCodes: string[];
  defaultStartYm: string;
}
export interface Draft {
  status: string;
  criteria: Criteria;
  clarification: string | null;
  warnings: string[];
  modelUsed: boolean;
}
export interface Rate {
  numerator: number;
  denominator: number;
  rate: number | null;
}
export interface History {
  items: Snapshot[];
  total: number;
  page: number;
  size: 20 | 50 | 100;
  quality: {
    adoption: Rate;
    assignmentConversion: Rate;
    observedExtension: Rate;
    validSampleCountLastThreeMonths: number;
    warnings: string[];
  };
}
export interface HistoryQuery {
  direction?: Direction;
  search?: string;
  decisionStatus?: string;
  fromYm?: string;
  toYm?: string;
  page: number;
  size: 20 | 50 | 100;
}
const base = "/api/ai/staff-recommendations";
export const recommendationOptions = (
  query: { devId?: string; sourceId?: string } = {},
  signal?: AbortSignal,
) => {
  const params = new URLSearchParams();
  if (query.devId) params.set("devId", query.devId);
  if (query.sourceId) params.set("sourceId", query.sourceId);
  return readData<Options>(
    `/api/ai/staff-recommendation/options?${params}`,
    signal,
  );
};
export const createRecommendation = async (input: {
  direction: Direction;
  sourceId?: number;
  devId?: number;
  criteria: Criteria;
}) =>
  (
    await api<Snapshot>(`${base}/search`, {
      method: "POST",
      credentials: "same-origin",
      body: JSON.stringify(input),
    })
  ).data as Snapshot;
export const getRecommendation = (requestId: number) =>
  readData<Snapshot>(`${base}/${requestId}`);
export const decideRecommendation = async (
  resultId: number,
  direction: Direction,
  decision: "ADOPT" | "REJECT",
  reason?: string,
) =>
  (
    await api(`${base}/results/${resultId}/decision`, {
      method: "PATCH",
      credentials: "same-origin",
      body: JSON.stringify({
        direction,
        decision,
        ...(reason ? { reason } : {}),
      }),
    })
  ).data;
export const criteriaDraft = async (question: string) =>
  (
    await api<Draft>(`${base}/criteria-draft`, {
      method: "POST",
      credentials: "same-origin",
      body: JSON.stringify({ question }),
    })
  ).data as Draft;
export const recommendationHistory = (filter: HistoryQuery) => {
  const query = new URLSearchParams();
  Object.entries(filter).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  return readData<History>(`${base}/history?${query}`);
};
export async function downloadCareerCard(devId: number) {
  let response: Response;
  try {
    response = await fetch(`${base}/developers/${devId}/career-card`, {
      credentials: "same-origin",
    });
  } catch {
    throw new ApiFailure("다운로드 서버에 연결할 수 없습니다.", null, null);
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new ApiFailure(
      error.message ?? "다운로드하지 못했습니다.",
      response.status,
      error.requestId ?? null,
      error.field,
      error.code,
    );
  }
  if (!response.headers.get("Content-Type")?.includes("spreadsheetml.sheet"))
    throw new ApiFailure(
      "파일 형식을 확인할 수 없습니다.",
      response.status,
      null,
    );
  const url = URL.createObjectURL(await response.blob());
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = "career-card.xlsx";
  anchor.click();
  URL.revokeObjectURL(url);
}
