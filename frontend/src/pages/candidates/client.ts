import { api, readData } from "../../shared/api";
import type { DeveloperInput, DeveloperDetail } from "../developers/client";

export interface CandidateInput {
  cand_nm: string;
  cand_se_cd: string;
  vndr_ptnr_id: number | null;
  job_cd: string;
  grd_cd: string | null;
  skill: string[];
  hope_untprc_amt: number | null;
  avail_dt: string;
  cntct_stts_cd: string;
  tel_no: string | null;
  source: string | null;
  rmrk_desc: string | null;
}
export interface CandidateContact {
  cntct_dt: string;
  cntct_stts_cd: string;
  memo: string;
  user_id: number | null;
  created_at: string;
}
export interface CandidateMatchingSlot {
  need_id: number;
  oppt_id: number;
  oppt_nm: string;
  job_cd: string;
  bgn_ym: string;
  monthly_price: number;
  hope_untprc_amt: number;
  team_expense: number;
  margin_rate: number | null;
  estimated: boolean;
  warnings: string[];
}
export interface CandidateDetail extends CandidateInput {
  cand_id: number;
  cand_no: string;
  use_yn: string;
  dev_id: number | null;
  last_cntct_dt: string | null;
  created_at: string;
  contacts: CandidateContact[];
  matching_slots: CandidateMatchingSlot[];
}
export interface CandidateFilters {
  q: string;
  job: string;
  grade: string;
  skill: string;
  cand_se_cd: string;
  vndr_ptnr_id: string;
  cntct_stts_cd: string;
  avail_dt: string;
  shortage_month: string;
  uncontacted_only: boolean;
  page: number;
  size: 20 | 50 | 100;
  sort: string;
}
export interface CandidatePage {
  items: CandidateDetail[];
  page: number;
  size: 20 | 50 | 100;
  total: number;
  kpi: {
    candidate_count: number;
    interview_count: number;
    available_by_next_month_count: number;
    uncontacted_over_30_days_count: number;
  };
}
export interface CandidateShortage {
  job_cd: string;
  grd_cd?: string | null;
  first_shortage_month: string;
  maximum_shortage_mm: number;
  maximum_shortage_people: number;
  recommended_hiring_month: string;
  review_immediately: boolean;
  candidate_count: number;
}
export const defaultCandidateFilters: CandidateFilters = {
  q: "",
  job: "",
  grade: "",
  skill: "",
  cand_se_cd: "",
  vndr_ptnr_id: "",
  cntct_stts_cd: "",
  avail_dt: "",
  shortage_month: "",
  uncontacted_only: false,
  page: 0,
  size: 50,
  sort: "cand_id,asc",
};
export const emptyCandidate: CandidateInput = {
  cand_nm: "",
  cand_se_cd: "FL",
  vndr_ptnr_id: null,
  job_cd: "",
  grd_cd: null,
  skill: [],
  hope_untprc_amt: null,
  avail_dt: "",
  cntct_stts_cd: "NONE",
  tel_no: null,
  source: null,
  rmrk_desc: null,
};
export function candidatePayload(d: CandidateInput): CandidateInput {
  return {
    cand_nm: d.cand_nm.trim(),
    cand_se_cd: d.cand_se_cd,
    vndr_ptnr_id: d.cand_se_cd === "OS" ? d.vndr_ptnr_id : null,
    job_cd: d.job_cd,
    grd_cd: d.grd_cd || null,
    skill: [...d.skill],
    hope_untprc_amt: d.hope_untprc_amt,
    avail_dt: d.avail_dt,
    cntct_stts_cd: d.cntct_stts_cd,
    tel_no: d.tel_no?.trim() || null,
    source: d.source || null,
    rmrk_desc: d.rmrk_desc?.trim() || null,
  };
}
export function candidateQuery(filters: CandidateFilters) {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([k, v]) => {
    if (v !== "" && v !== false) query.set(k, String(v));
  });
  return query;
}
export const listCandidates = (filters: CandidateFilters) =>
  readData<CandidatePage>(`/api/candidates?${candidateQuery(filters)}`);
export const getCandidate = (id: number) =>
  readData<CandidateDetail>(`/api/candidates/${id}`);
export const createCandidate = async (d: CandidateInput) =>
  (
    await api<CandidateDetail>("/api/candidates", {
      method: "POST",
      body: JSON.stringify(candidatePayload(d)),
    })
  ).data!;
export const updateCandidate = async (id: number, d: CandidateInput) =>
  (
    await api<CandidateDetail>(`/api/candidates/${id}`, {
      method: "PUT",
      body: JSON.stringify(candidatePayload(d)),
    })
  ).data!;
export const getCandidateShortages = () =>
  readData<{
    items: CandidateShortage[];
    page: number;
    size: number;
    total: number;
  }>("/api/candidates/shortages");
export const createCandidateContact = async (
  id: number,
  d: Pick<CandidateContact, "cntct_dt" | "cntct_stts_cd" | "memo">,
) =>
  (
    await api<CandidateDetail>(`/api/candidates/${id}/contacts`, {
      method: "POST",
      body: JSON.stringify(d),
    })
  ).data!;
export const convertCandidate = async (id: number, developer: DeveloperInput) =>
  (
    await api<{ candidate: CandidateDetail; developer: DeveloperDetail }>(
      `/api/candidates/${id}/convert`,
      { method: "POST", body: JSON.stringify({ developer }) },
    )
  ).data!;
export const candidateWorkspaceState = (
  filters: Partial<CandidateFilters> = {},
) => ({ workspace: "candidates", candidateFilters: filters });
