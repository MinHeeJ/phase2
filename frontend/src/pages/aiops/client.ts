import { api } from "../../shared/api";

export type RecommendationDirection =
  | "PROJECT_TO_PERSON"
  | "PERSON_TO_POSITION";
export interface RecommendationCriteria {
  jobCode: string;
  startYm: string;
  gradeCode?: string;
  skillCodes?: string[];
  customerId?: number;
  industryCode?: string;
  durationMonths?: number;
  headcount?: number;
  requestedMm?: number;
}
export interface RecommendationSourceOption {
  sourceId: number;
  sourceType: "PROJECT" | "OPP";
  projectId?: number;
  needId?: number;
  criteria: RecommendationCriteria;
  remainingHeadcount: number;
}
export interface RecommendationExitOption {
  devId: number;
  exitDate: string | null;
  assumedExitYm: string | null;
  extensionLikelihood: "H" | "M" | "L" | null;
  hasNextConfirmedAssignment: boolean;
}
export interface RecommendationOptions {
  sources: RecommendationSourceOption[];
  developers: RecommendationExitOption[];
  jobCodes: string[];
  gradeCodes: string[];
  skillCodes: string[];
  defaultStartYm: string;
}
export interface RecommendationOptionsQuery {
  direction?: RecommendationDirection;
  sourceId?: number;
  devId?: number;
  search?: string;
}
export type RecommendationSubmission =
  | {
      direction: "PROJECT_TO_PERSON";
      sourceId?: number;
      criteria: RecommendationCriteria;
    }
  | {
      direction: "PERSON_TO_POSITION";
      devId: number;
      criteria: { assumedExitYm?: string; requestedMm?: number };
    };

export async function getStaffRecommendationOptions(
  query: RecommendationOptionsQuery = {},
  signal?: AbortSignal,
) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== "") params.set(key, String(value));
  }
  const suffix = params.size ? `?${params}` : "";
  return (
    await api<RecommendationOptions>(
      `/api/ai/staff-recommendation/options${suffix}`,
      {
        credentials: "same-origin",
        signal,
      },
    )
  ).data!;
}
