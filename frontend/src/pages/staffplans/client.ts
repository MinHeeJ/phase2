import { api, readData } from "../../shared/api";

export interface ProjectStaffPlanInput {
  plan_ym: string;
  job_cd: string;
  grd_cd?: string;
  need_mm_qty: number;
  expc_mth_cst_amt?: number | null;
}
export interface ProjectStaffPlan extends ProjectStaffPlanInput {
  grd_cd: string;
  need_psn_cnt: number;
  src_need_id?: number | null;
}
export interface ProjectStaffPlans {
  items: ProjectStaffPlan[];
  actual: { job_cd: string; mm_qty: number }[];
}
const path = (projectId: number) =>
  `/api/projects/${encodeURIComponent(projectId)}/staff-plans`;
export const getProjectStaffPlans = (projectId: number) =>
  readData<ProjectStaffPlans>(path(projectId));
export const saveProjectStaffPlans = async (
  projectId: number,
  items: ProjectStaffPlanInput[],
) =>
  (
    await api<ProjectStaffPlans>(path(projectId), {
      method: "PUT",
      body: JSON.stringify({
        items: items.map((row) => ({
          plan_ym: row.plan_ym,
          job_cd: row.job_cd,
          grd_cd: row.grd_cd ?? "",
          need_mm_qty: row.need_mm_qty,
          expc_mth_cst_amt: row.expc_mth_cst_amt ?? null,
        })),
      }),
    })
  ).data!;
