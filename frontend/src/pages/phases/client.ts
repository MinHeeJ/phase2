import { api, readData } from "../../shared/api";

export interface ProjectPhase {
  phs_id: number;
  prj_id: number;
  phs_seq: number;
  phs_nm: string;
  wght_rt: number;
  plan_bgn_dt: string | null;
  plan_end_dt: string | null;
  cmpl_dt: string | null;
  status: "완료" | "지연" | "진행 중" | "예정";
}
export interface ProjectPhaseProject {
  prj_id: number;
  use_yn: string;
  prj_stts_cd: string;
}
export interface ProjectPhasePeriodInput {
  plan_bgn_dt: string;
  plan_end_dt: string;
}
const path = (id: number, phsId: number) =>
  `/api/projects/${encodeURIComponent(id)}/phases/${encodeURIComponent(phsId)}`;
export const updateProjectPhase = async (
  id: number,
  phsId: number,
  input: ProjectPhasePeriodInput,
) =>
  (
    await api<ProjectPhase>(path(id, phsId), {
      method: "PUT",
      body: JSON.stringify({
        plan_bgn_dt: input.plan_bgn_dt,
        plan_end_dt: input.plan_end_dt,
      }),
    })
  ).data!;
export const updateProjectPhaseCompletion = async (id: number, phsId: number) =>
  (await api<ProjectPhase>(`${path(id, phsId)}/complete`, { method: "POST" }))
    .data!;
export const updateProjectPhaseUncompletion = async (
  id: number,
  phsId: number,
) =>
  (await api<ProjectPhase>(`${path(id, phsId)}/uncomplete`, { method: "POST" }))
    .data!;
export const getProjectPhaseProgress = (id: number) =>
  readData<{ phases: ProjectPhase[] }>(
    `/api/projects/${encodeURIComponent(id)}/progress`,
  );
