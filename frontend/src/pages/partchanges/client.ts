import { api, readData } from "../../shared/api";

export interface ProjectPartChangeRequest {
  bsn_part_id: number;
  aply_bgn_ym: string;
  rsn_desc: string;
}
export interface ProjectPartChange {
  bsn_part_id: number | null;
  aply_bgn_ym: string;
  rsn_desc: string | null;
  chg_user_id: number | null;
  chg_at: string;
  partName: string | null;
}
export interface PartChangeProjectView {
  prj_id: number;
  prj_nm: string;
  cntrct_ty_cd: string;
  use_yn: "Y" | "N";
  bsn_part_id: number | null;
  partName?: string | null;
}
const path = (id: number) => `/api/projects/${id}/part-changes`;
export const listProjectPartChanges = (id: number) =>
  readData<ProjectPartChange[]>(path(id));
export const createProjectPartChange = async (
  id: number,
  input: ProjectPartChangeRequest,
) =>
  (
    await api<ProjectPartChange[]>(path(id), {
      method: "POST",
      body: JSON.stringify({
        bsn_part_id: input.bsn_part_id,
        aply_bgn_ym: input.aply_bgn_ym,
        rsn_desc: input.rsn_desc.trim(),
      }),
    })
  ).data!;
