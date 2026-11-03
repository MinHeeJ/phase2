import { api, readData } from "../../shared/api";
import {
  developerPayload,
  type DeveloperInput,
  type DeveloperDetail,
} from "../developers/client";

export interface CandidateContact {
  cntct_dt: string;
  cntct_stts_cd: string;
  memo: string;
  user_id: number | null;
  created_at: string;
}
export interface CandidateContactDetail {
  cand_id: number;
  cand_no: string;
  cand_nm: string;
  cand_se_cd: "FL" | "OS";
  vndr_ptnr_id: number | null;
  job_cd: string;
  grd_cd: string | null;
  skill: string[];
  hope_untprc_amt: number;
  avail_dt: string;
  cntct_stts_cd: string;
  dev_id: number | null;
  last_cntct_dt: string | null;
  tel_no: string | null;
  use_yn: "Y" | "N";
  contacts: CandidateContact[];
}
export interface CandidateShortage {
  job_cd: string;
  grd_cd: string | null;
  maximum_shortage_mm: number;
  maximum_shortage_people: number;
  first_shortage_month: string;
  recommended_hiring_month: string;
  review_immediately: boolean;
  candidate_count: number;
}
export interface CandidateShortageFilters {
  from?: string;
  months?: number;
  year?: number;
  job?: string;
  grade?: string;
  sort?: string;
  page?: number;
  size?: 20 | 50 | 100;
}
export interface CandidateShortagePage {
  items: CandidateShortage[];
  page: number;
  size: 20 | 50 | 100;
  total: number;
}
export function getCandidateShortages(filters: CandidateShortageFilters) {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value != null && value !== "") params.set(key, String(value));
  });
  return readData<CandidateShortagePage>(`/api/candidates/shortages?${params}`);
}
export const getContactCandidate = (id: number) =>
  readData<CandidateContactDetail>(`/api/candidates/${id}`);
export async function createCandidateContact(
  id: number,
  input: Pick<CandidateContact, "cntct_dt" | "cntct_stts_cd" | "memo">,
) {
  return (
    await api<CandidateContactDetail>(`/api/candidates/${id}/contacts`, {
      method: "POST",
      body: JSON.stringify({
        cntct_dt: input.cntct_dt,
        cntct_stts_cd: input.cntct_stts_cd,
        memo: input.memo,
      }),
    })
  ).data!;
}
export async function createCandidateConversion(
  id: number,
  input: DeveloperInput,
) {
  return (
    await api<{
      candidate: CandidateContactDetail;
      developer: DeveloperDetail;
    }>(`/api/candidates/${id}/convert`, {
      method: "POST",
      body: JSON.stringify({ developer: developerPayload(input) }),
    })
  ).data!;
}
