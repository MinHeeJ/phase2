import { api, readData, ApiFailure } from "../../shared/api";

export interface AssignmentWarning {
  code: string;
  message: string;
  work_ym?: string | null;
}
export interface AssignmentVersion {
  asgn_id: number;
  updated_at: string;
}
export interface AssignmentSelection extends AssignmentVersion {
  asgn_no: string;
  dev_id: number;
  dev_nm: string;
  prj_id: number;
  bgn_dt: string;
  end_dt: string;
  asgn_stts_cd: string;
  cntrct_dev_nm?: string | null;
  warnings: AssignmentWarning[];
}
export interface AssignmentDetail extends AssignmentSelection {
  prj_nm: string;
  mng_no: string;
  job_cd: string;
  asgn_rt: number;
  asgn_se_cd: string;
  ext_psblty_cd: string | null;
  current_month_mm_qty: number;
  person_month_mm_qty: number;
  year_mm_qty: number;
  plan_end_dt: string;
  rmrk_desc: string | null;
  bill_untprc_amt: number | null;
  untprc_base_cd: string | null;
}
export interface AssignmentFilter {
  year: number;
  projectId?: number;
  job?: string;
  warning?: string;
  q?: string;
  sort?: string;
}
export interface BatchItem {
  asgn_id: number;
  updated_at: string | null;
  status: "APPLIED" | "SKIPPED" | "EXCLUDED";
  code: string | null;
  message: string;
}
export interface BatchResult {
  items: BatchItem[];
}
export interface ReplacementInput {
  updated_at: string;
  aply_dt: string;
  dev_id: number;
  rsn_cd: string;
  rsn_desc: string | null;
}
export interface ReplacementResult {
  previous_assignment: AssignmentDetail;
  new_assignment: AssignmentDetail;
  changes: unknown[];
}
export interface AssignmentProposal {
  prj_id: number;
  dev_id: number;
  job_cd: string;
  bgn_dt: string;
  end_dt: string;
  asgn_rt: number;
  asgn_se_cd: string;
  bill_untprc_amt?: number | null;
  untprc_base_cd?: string | null;
  cntrct_dev_id?: number | null;
  ext_psblty_cd?: string | null;
  rmrk_desc?: string | null;
  rsn_cd?: string;
  rsn_desc?: string | null;
}
export interface AssignmentPreview {
  warnings: AssignmentWarning[];
  months: {
    work_ym: string;
    auto_mm_qty: number;
    mm_qty: number;
    mnl_yn: string;
    person_month_mm_qty: number;
    closed_yn: string;
  }[];
}
export const assignmentQuery = (filter: AssignmentFilter) => {
  const query = new URLSearchParams();
  for (const key of [
    "year",
    "projectId",
    "job",
    "warning",
    "q",
    "sort",
  ] as const) {
    const value = filter[key];
    if (value !== undefined && value !== "") query.set(key, String(value));
  }
  return query.toString();
};
const versions = (rows: AssignmentVersion[]) =>
  rows.map(({ asgn_id, updated_at }) => ({ asgn_id, updated_at }));
export const saveAssignmentCancellation = async (rows: AssignmentVersion[]) =>
  (
    await api<BatchResult>("/api/assignments/cancel", {
      method: "POST",
      body: JSON.stringify({
        ids: rows.map((r) => r.asgn_id),
        versions: versions(rows),
      }),
    })
  ).data!;
export const saveAssignmentExtChance = async (
  rows: AssignmentVersion[],
  value: string | null,
) =>
  (
    await api<BatchResult>("/api/assignments/ext-chance", {
      method: "POST",
      body: JSON.stringify({
        ids: rows.map((r) => r.asgn_id),
        versions: versions(rows),
        value,
      }),
    })
  ).data!;
export const createAssignmentReplacement = async (
  id: number,
  input: ReplacementInput,
) =>
  (
    await api<ReplacementResult>(
      `/api/assignments/${encodeURIComponent(id)}/replace`,
      {
        method: "POST",
        body: JSON.stringify(input),
      },
    )
  ).data!;
export const checkAssignments = async (
  input: AssignmentProposal,
  signal?: AbortSignal,
) =>
  (
    await api<AssignmentPreview>("/api/assignments/check", {
      method: "POST",
      signal,
      body: JSON.stringify(input),
    })
  ).data!;
export const getAssignment = (id: number) =>
  readData<AssignmentDetail>(`/api/assignments/${encodeURIComponent(id)}`);
export const listAssignments = (filter: AssignmentFilter, page = 0) =>
  readData<{
    items: AssignmentDetail[];
    total: number;
    page: number;
    size: number;
  }>(`/api/assignments?${assignmentQuery(filter)}&page=${page}&size=50`);
export async function downloadAssignments(filter: AssignmentFilter) {
  let response: Response;
  try {
    response = await fetch(
      `/api/assignments/export?${assignmentQuery(filter)}`,
    );
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  if (!response.ok) {
    const error = await response.json();
    throw new ApiFailure(
      error.message ?? "다운로드하지 못했습니다.",
      response.status,
      error.requestId ?? null,
      error.field,
      error.code,
    );
  }
  if (
    response.headers.get("Content-Type")?.split(";")[0] !==
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
  )
    throw new ApiFailure("엑셀 파일 응답이 아닙니다.", response.status, null);
  const blob = await response.blob();
  const prefix = new Uint8Array(await blob.slice(0, 2).arrayBuffer());
  if (prefix[0] !== 80 || prefix[1] !== 75)
    throw new ApiFailure(
      "엑셀 파일을 확인할 수 없습니다.",
      response.status,
      null,
    );
  const encoded = response.headers
    .get("Content-Disposition")
    ?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  let name: string;
  try {
    name = decodeURIComponent(encoded ?? "");
  } catch {
    throw new ApiFailure("파일명을 확인하세요.", response.status, null);
  }
  if (!new RegExp(`^투입배정_${filter.year}_[0-9]{8}\\.xlsx$`).test(name))
    throw new ApiFailure("파일명을 확인하세요.", response.status, null);
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    link.dataset.testid = "assignment-download-file";
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
