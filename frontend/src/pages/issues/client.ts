import { api, readData } from "../../shared/api";

export type IssueStatus = "OPEN" | "DOING" | "CLOSED";
export type IssueSeverity = "HIGH" | "MID" | "LOW";
export interface IssueInput {
  ttl_nm: string;
  svrt_cd: IssueSeverity;
  chrg_nm: string | null;
  ddln_dt: string;
  issue_stts_cd: IssueStatus;
  actn_desc: string | null;
}
export interface IssueRow extends IssueInput {
  issue_id: number;
  prj_id: number;
  reg_dt: string;
  end_dt: string | null;
}
export interface IssueListRow extends IssueRow {
  prj_nm: string;
}
export interface IssueProject {
  prj_id: number;
  prj_nm: string;
  use_yn: string;
  prj_stts_cd: string;
}
export interface IssueFilters {
  status: "OPEN_DOING" | "OVERDUE" | "CLOSED";
  severity: IssueSeverity | "";
  projectId: string;
  page: number;
  size: 20 | 50 | 100;
}
export interface IssuesData {
  items: IssueListRow[];
  total: number;
  page: number;
  size: number;
  open_count: number;
}
export const listIssues = (filters: IssueFilters) => {
  const params = new URLSearchParams(
    Object.entries(filters)
      .filter(([, v]) => v !== "")
      .map(([k, v]) => [k, String(v)]),
  );
  return readData<IssuesData>(`/api/issues?${params}`);
};
export const listProjectIssues = (projectId: number) =>
  readData<{ items: IssueRow[]; open_count: number }>(
    `/api/projects/${projectId}/issues`,
  );
export const getIssueOwnerOptions = (projectId: number) =>
  readData<{ name: string }[]>(
    `/api/issues/owner-options?projectId=${projectId}`,
  );
export function issuePayload(input: IssueInput): IssueInput {
  return {
    ttl_nm: input.ttl_nm,
    svrt_cd: input.svrt_cd,
    chrg_nm: input.chrg_nm,
    ddln_dt: input.ddln_dt,
    issue_stts_cd: input.issue_stts_cd,
    actn_desc: input.actn_desc,
  };
}
export const createIssue = async (
  projectId: number | null,
  input: IssueInput,
) =>
  (
    await api<IssueRow>("/api/issues", {
      method: "POST",
      body: JSON.stringify({ prj_id: projectId, ...issuePayload(input) }),
    })
  ).data!;
export const updateIssue = async (issueId: number, input: IssueInput) =>
  (
    await api<IssueRow>(`/api/issues/${issueId}`, {
      method: "PUT",
      body: JSON.stringify(issuePayload(input)),
    })
  ).data!;

// Board eligibility is owned by getProjectBoard, not by the shared issue write API.
export async function issueBoardProjects(): Promise<
  { prj_id: number; prj_nm: string }[]
> {
  const projects = new Map<number, { prj_id: number; prj_nm: string }>();
  let page = 0,
    received = 0;
  for (;;) {
    const data = await readData<{
      items: { prj_id: number; prj_nm: string }[];
      total: number;
    }>(`/api/project-board?page=${page}&size=100`);
    if (!data.items.length && received < data.total)
      throw new Error("프로젝트 목록을 모두 불러오지 못했습니다.");
    data.items.forEach((row) =>
      projects.set(row.prj_id, { prj_id: row.prj_id, prj_nm: row.prj_nm }),
    );
    received += data.items.length;
    if (received >= data.total) break;
    page++;
  }
  return [...projects.values()].sort(
    (a, b) => a.prj_nm.localeCompare(b.prj_nm, "ko") || a.prj_id - b.prj_id,
  );
}
export function issueToday(now = new Date()): string {
  const parts = new Intl.DateTimeFormat("en", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);
  return ["year", "month", "day"]
    .map((type) => parts.find((part) => part.type === type)!.value)
    .join("-");
}
export function issueDefaults(): IssueInput {
  const date = new Date(`${issueToday()}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + 7);
  return {
    ttl_nm: "",
    svrt_cd: "MID",
    chrg_nm: null,
    ddln_dt: date.toISOString().slice(0, 10),
    issue_stts_cd: "OPEN",
    actn_desc: null,
  };
}
export const issueWritable = (project: IssueProject) =>
  project.use_yn === "Y" &&
  ["PLAN", "RUN", "HOLD"].includes(project.prj_stts_cd);
