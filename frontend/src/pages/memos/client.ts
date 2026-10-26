import { api, readData } from "../../shared/api";

export interface ProjectMemoInput {
  actl_prgs_rt: number | null;
  cmpl_work_desc: string;
  plan_work_desc: string;
  rqst_desc: string | null;
}
export interface ProjectWeeklyMemo extends ProjectMemoInput {
  memo_id: number;
  prj_id: number;
  wrt_dt: string;
}
export interface MemoProject {
  prj_id: number;
  prj_nm: string;
  cntrct_ty_cd: "TK" | "IN" | "MO" | "DP";
  use_yn: "Y" | "N";
  prj_stts_cd: string;
}
export interface MemoProgress {
  memos: ProjectWeeklyMemo[];
  plan_prgs_rt: number;
  actl_prgs_rt: number;
}
export const readMemoProgress = (id: number) =>
  readData<MemoProgress>(`/api/projects/${encodeURIComponent(id)}/progress`);
const path = (id: number) => `/api/projects/${encodeURIComponent(id)}/memos`;
const payload = (input: ProjectMemoInput): ProjectMemoInput => ({
  actl_prgs_rt: input.actl_prgs_rt,
  cmpl_work_desc: input.cmpl_work_desc.trim(),
  plan_work_desc: input.plan_work_desc.trim(),
  rqst_desc: input.rqst_desc?.trim() || null,
});
export const saveProjectMemo = async (id: number, input: ProjectMemoInput) =>
  (
    await api<ProjectWeeklyMemo>(path(id), {
      method: "POST",
      body: JSON.stringify(payload(input)),
    })
  ).data!;
export const updateProjectMemo = async (
  id: number,
  memoId: number,
  input: ProjectMemoInput,
) =>
  (
    await api<ProjectWeeklyMemo>(`${path(id)}/${encodeURIComponent(memoId)}`, {
      method: "PUT",
      body: JSON.stringify(payload(input)),
    })
  ).data!;
