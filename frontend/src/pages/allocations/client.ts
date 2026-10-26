import { api, readData } from "../../shared/api";

export type AllocationStage = "EST" | "FINAL" | "ACTUAL";
export interface AllocationMonth {
  aloc_ym: string;
  aloc_amt: number | null;
  mm_qty: number | null;
}
export interface AllocationLine {
  line_ty_cd: "PERSON" | "ITEM";
  dev_id: number | null;
  item_nm: string | null;
  job_cd: string | null;
  asgn_bgn_dt: string | null;
  asgn_se_cd: string | null;
  rmrk_desc: string | null;
  sort_seq: number;
  months: AllocationMonth[];
}
export interface ProjectAllocations {
  aloc_stg_cd: AllocationStage;
  activeStage: AllocationStage | null;
  items: AllocationLine[];
  cur_cntrct_amt: number;
  realoc_need_yn: "Y" | "N";
}
const path = (id: number, stage?: AllocationStage) =>
  `/api/projects/${encodeURIComponent(id)}/allocations${stage ? `?stage=${stage}` : ""}`;
export const getProjectAllocations = (id: number, stage?: AllocationStage) =>
  readData<ProjectAllocations>(path(id, stage));
export const saveProjectAllocations = async (
  id: number,
  stage: AllocationStage,
  items: AllocationLine[],
) =>
  (
    await api<ProjectAllocations>(path(id, stage), {
      method: "PUT",
      body: JSON.stringify({
        items: items.map((line, index) => ({
          line_ty_cd: line.line_ty_cd,
          dev_id: line.line_ty_cd === "PERSON" ? line.dev_id : null,
          item_nm: line.line_ty_cd === "ITEM" ? line.item_nm : null,
          job_cd: line.line_ty_cd === "PERSON" ? line.job_cd : null,
          asgn_bgn_dt: line.line_ty_cd === "PERSON" ? line.asgn_bgn_dt : null,
          asgn_se_cd: line.asgn_se_cd || null,
          rmrk_desc: line.rmrk_desc || null,
          sort_seq: index,
          months: line.months.filter(
            (m) => m.aloc_amt !== null || m.mm_qty !== null,
          ),
        })),
      }),
    })
  ).data!;
