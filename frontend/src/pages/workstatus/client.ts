import { api, readData } from "../../shared/api";
import type { PageResult } from "../../shared/types";

export type WorkStatusCode =
  | "VACATION"
  | "PARENTAL"
  | "UNPAID"
  | "LEAVE"
  | "RESIGN";
export interface WorkStatusInput {
  work_stts_cd: WorkStatusCode | "";
  bgn_dt: string;
  end_dt: string | null;
  paid_yn: "Y" | "N";
  rmrk_desc: string | null;
}
export interface WorkStatusRow extends WorkStatusInput {
  work_stts_hist_id: number;
  dev_id: number;
}
export interface WorkStatusFilters {
  status?: WorkStatusCode;
  page: number;
  size: 20 | 50 | 100;
  sort: string;
}
const path = (id: number) =>
  `/api/developers/${encodeURIComponent(id)}/work-status`;
export const listDeveloperWorkStatuses = (
  id: number,
  filters: WorkStatusFilters,
) => {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined) params.set(key, String(value));
  });
  return readData<PageResult<WorkStatusRow>>(`${path(id)}?${params}`);
};
export function workStatusPayload(input: WorkStatusInput): WorkStatusInput {
  return {
    work_stts_cd: input.work_stts_cd,
    bgn_dt: input.bgn_dt,
    end_dt: input.work_stts_cd === "RESIGN" ? null : input.end_dt || null,
    paid_yn: input.work_stts_cd === "VACATION" ? input.paid_yn : "N",
    rmrk_desc: input.rmrk_desc || null,
  };
}
export const createDeveloperWorkStatus = async (
  id: number,
  input: WorkStatusInput,
) =>
  (
    await api<WorkStatusRow>(path(id), {
      method: "POST",
      body: JSON.stringify(workStatusPayload(input)),
    })
  ).data!;
export const updateDeveloperWorkStatus = async (
  id: number,
  histId: number,
  input: WorkStatusInput,
) =>
  (
    await api<WorkStatusRow>(`${path(id)}/${encodeURIComponent(histId)}`, {
      method: "PUT",
      body: JSON.stringify(workStatusPayload(input)),
    })
  ).data!;
export const updateDeveloperWorkStatusRemoval = async (
  id: number,
  histId: number,
) =>
  (
    await api<{
      work_stts_hist_id: number;
      deleted: true;
      rsgn_dt: string | null;
    }>(`${path(id)}/${encodeURIComponent(histId)}`, { method: "DELETE" })
  ).data!;
