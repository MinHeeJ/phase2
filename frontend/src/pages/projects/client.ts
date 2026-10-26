import { api, readData } from "../../shared/api";
import { seoulMonth } from "../shared/PeriodBar";

export interface ProjectInput {
  prj_nm: string;
  cust_ptnr_id: number | null;
  cntrct_ty_cd: string;
  prj_stts_cd: string;
  win_prblt_rt: number | null;
  cntrct_dt: string | null;
  cntrct_bgn_dt: string;
  cntrct_end_dt: string;
  prfm_bgn_dt: string | null;
  bsn_part_id: number | null;
  stlm_mthd_cd: string | null;
  pm_dev_id: number | null;
  free_sprt_yn: string;
  quot_amt: number | null;
  init_cntrct_amt: number | null;
  trgt_mrgn_rt: number | null;
  trgt_cst_amt: number | null;
  indty_cd: string | null;
  duty_fld_nm: string | null;
  prj_skl: string[];
  prev_prj_id: number | null;
  rmrk_desc: string | null;
}
export interface ProjectListItem {
  prj_id: number;
  prj_no: string;
  prj_nm: string;
  custName: string | null;
  cntrct_ty_cd: string;
  cntrct_bgn_dt: string;
  cur_end_dt: string;
  cur_cntrct_amt: number | null;
  assignmentCount: number;
  bsn_part_id: number | null;
  partName: string | null;
  prj_stts_cd: string;
}
export type ProjectDetail = ProjectListItem &
  ProjectInput & {
    cntrct_mm_qty: number | null;
    use_yn: "Y" | "N";
    pmName: string | null;
  };
export interface ProjectFilters {
  year: number;
  type: string;
  status: string;
  part: string;
  q: string;
  page: number;
  size: 20 | 50 | 100;
  sort: string;
}
export interface ProjectPage {
  items: ProjectListItem[];
  total: number;
  page: number;
  size: number;
  unassignedCount: number;
}
export interface ProjectAssignment {
  name: string;
  job: string;
  beginDate: string;
  endDate: string | null;
  assignmentRate: number;
  status: string;
}
export interface ProjectHistory {
  changedAt: string;
  field: string;
  before: string | null;
  after: string | null;
  actor: string | null;
}
export interface ProjectPartChange {
  bsn_part_id: number | null;
  aply_bgn_ym: string;
  rsn_desc: string | null;
  partName?: string;
}
export interface ProjectOption {
  id: number;
  no: string;
  name: string;
  type: string;
  custName: string | null;
}
export const projectDefaults = (
  year = Number(seoulMonth().slice(0, 4)),
): ProjectFilters => ({
  year,
  type: "",
  status: "",
  part: "",
  q: "",
  page: 0,
  size: 50,
  sort: "cntrct_bgn_dt,desc",
});
export const emptyProject: ProjectInput = {
  prj_nm: "",
  cust_ptnr_id: null,
  cntrct_ty_cd: "",
  prj_stts_cd: "RUN",
  win_prblt_rt: null,
  cntrct_dt: null,
  cntrct_bgn_dt: "",
  cntrct_end_dt: "",
  prfm_bgn_dt: null,
  bsn_part_id: null,
  stlm_mthd_cd: null,
  pm_dev_id: null,
  free_sprt_yn: "N",
  quot_amt: null,
  init_cntrct_amt: null,
  trgt_mrgn_rt: null,
  trgt_cst_amt: null,
  indty_cd: null,
  duty_fld_nm: null,
  prj_skl: [],
  prev_prj_id: null,
  rmrk_desc: null,
};
export function projectPayload(input: ProjectInput): ProjectInput {
  const result = Object.fromEntries(
    Object.keys(emptyProject).map((key) => [
      key,
      input[key as keyof ProjectInput],
    ]),
  ) as unknown as ProjectInput;
  result.prj_nm = result.prj_nm.trim();
  result.prj_skl = [...new Set(result.prj_skl)].sort();
  for (const key of [
    "cntrct_dt",
    "prfm_bgn_dt",
    "indty_cd",
    "duty_fld_nm",
    "rmrk_desc",
  ] as const)
    result[key] = result[key]?.trim() || null;
  if (result.prj_stts_cd !== "PLAN") result.win_prblt_rt = null;
  if (result.cntrct_ty_cd === "IN") {
    result.cust_ptnr_id =
      result.bsn_part_id =
      result.quot_amt =
      result.init_cntrct_amt =
        null;
    result.trgt_cst_amt = result.trgt_mrgn_rt = null;
    result.stlm_mthd_cd = null;
  } else if (result.cntrct_ty_cd === "TK") result.stlm_mthd_cd = "STAGE";
  else result.stlm_mthd_cd ||= "MONTH";
  return result;
}
export function projectQuery(filters: ProjectFilters) {
  return new URLSearchParams(
    Object.entries(filters)
      .filter(([, v]) => v !== "")
      .map(([k, v]) => [k, String(v)]),
  ).toString();
}
export const listProjects = (filters: ProjectFilters) =>
  readData<ProjectPage>(`/api/projects?${projectQuery(filters)}`);
export const getProject = (id: number | string) =>
  readData<ProjectDetail>(`/api/projects/${encodeURIComponent(id)}`);
export const createProject = async (input: ProjectInput) =>
  (
    await api<ProjectDetail>("/api/projects", {
      method: "POST",
      body: JSON.stringify(projectPayload(input)),
    })
  ).data!;
export const updateProject = async (id: number, input: ProjectInput) => {
  const { bsn_part_id: _part, ...body } = projectPayload(input);
  return (
    await api<ProjectDetail>(`/api/projects/${id}`, {
      method: "PUT",
      body: JSON.stringify(body),
    })
  ).data!;
};
export const listProjectOptions = () =>
  readData<ProjectOption[]>("/api/projects/options");
export const listProjectAssignments = (id: number) =>
  readData<ProjectAssignment[]>(`/api/projects/${id}/assignments`);
export const listProjectHistory = (id: number) =>
  readData<ProjectHistory[]>(`/api/projects/${id}/history`);
export const listProjectPartChanges = (id: number) =>
  readData<ProjectPartChange[]>(`/api/projects/${id}/part-changes`);
export const listProjectContractChanges = (id: number) =>
  readData<{ items: { cntrct_chg_stts_cd: string }[] }>(
    `/api/projects/${id}/contract-changes`,
  );
export async function exportProjects(filters: ProjectFilters) {
  const { page: _page, size: _size, ...query } = filters;
  const params = new URLSearchParams(
    Object.entries(query)
      .filter(([, v]) => v !== "")
      .map(([k, v]) => [k, String(v)]),
  );
  const artifact = await readData<{
    file_name: string;
    media_type: string;
    content_base64: string;
  }>(`/api/projects/export?${params}`);
  const bytes = Uint8Array.from(atob(artifact.content_base64), (c) =>
    c.charCodeAt(0),
  );
  const url = URL.createObjectURL(
    new Blob([bytes], { type: artifact.media_type }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = artifact.file_name;
  link.click();
  URL.revokeObjectURL(url);
}
