import { api, readData } from "../../shared/api";
import type { DeveloperDetail, DeveloperInput } from "../developers/client";

export interface CandidateInput {
  cand_nm: string;
  cand_se_cd: "FL" | "OS";
  vndr_ptnr_id: number | null;
  job_cd: string;
  grd_cd: string | null;
  skill: string[];
  hope_untprc_amt: number;
  avail_dt: string;
  cntct_stts_cd: string;
  tel_no: string | null;
  source: string | null;
  rmrk_desc: string | null;
}
export interface ContactInput {
  cntct_dt: string;
  cntct_stts_cd: string;
  memo: string;
}
export interface CandidateDetail extends CandidateInput {
  cand_id: number;
  cand_no: string;
  use_yn: "Y" | "N";
  dev_id: number | null;
  last_cntct_dt: string | null;
  created_at: string;
  contacts: (ContactInput & { user_id: number | null; created_at: string })[];
  matching_slots: {
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
  }[];
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
  sort: string;
  page: number;
  size: 20 | 50 | 100;
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
export const candidateDefaults: CandidateFilters = {
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
  sort: "cand_id,asc",
  page: 0,
  size: 50,
};
export const emptyCandidate: CandidateInput = {
  cand_nm: "",
  cand_se_cd: "FL",
  vndr_ptnr_id: null,
  job_cd: "",
  grd_cd: null,
  skill: [],
  hope_untprc_amt: 0,
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
export function query(values: object) {
  const q = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== "" && value != null) q.set(key, String(value));
  });
  return q;
}
export const listCandidates = (filters: CandidateFilters) =>
  readData<CandidatePage>(`/api/candidates?${query(filters)}`);
export const getCandidate = (id: number) =>
  readData<CandidateDetail>(`/api/candidates/${id}`);
export const saveCandidate = async (draft: CandidateInput, id?: number) =>
  (
    await api<CandidateDetail>(
      id === undefined ? "/api/candidates" : `/api/candidates/${id}`,
      {
        method: id === undefined ? "POST" : "PUT",
        body: JSON.stringify(candidatePayload(draft)),
      },
    )
  ).data;
export const addContact = async (id: number, input: ContactInput) =>
  (
    await api<CandidateDetail>(`/api/candidates/${id}/contacts`, {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data;
export const convertCandidate = async (id: number, developer: DeveloperInput) =>
  (
    await api<{ candidate: CandidateDetail; developer: DeveloperDetail }>(
      `/api/candidates/${id}/convert`,
      { method: "POST", body: JSON.stringify({ developer }) },
    )
  ).data;
