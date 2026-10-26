import { api, readData } from "../../shared/api";

export interface ProjectOption {
  id: number;
  no: string;
  name: string;
  type: string;
  custName: string | null;
}
export interface ProjectAssignment {
  name: string;
  job: string;
  beginDate: string;
  endDate: string | null;
  assignmentRate: number;
  status: string;
}
export interface ProjectHistoryEntry {
  changedAt: string;
  field: string;
  before: string | null;
  after: string | null;
  actor: string | null;
}
export interface ProjectLifecycleDetail {
  prj_id: number;
  prj_no: string;
  prj_nm: string;
  use_yn: "Y" | "N";
  [key: string]: unknown;
}
export interface ProjectExportFilter {
  year: number;
  type?: string;
  status?: string;
  part?: string;
  q?: string;
  sort?: string;
}
export interface ProjectDownloadArtifact {
  file_name: string;
  media_type: string;
  content_base64: string;
}
const path = (id: number) => `/api/projects/${encodeURIComponent(id)}`;
export const listProjectOptions = (q = "") =>
  readData<ProjectOption[]>(
    `/api/projects/options?${new URLSearchParams({ q })}`,
  );
export const listProjectAssignments = (id: number) =>
  readData<ProjectAssignment[]>(`${path(id)}/assignments`);
export const listProjectHistory = (id: number) =>
  readData<ProjectHistoryEntry[]>(`${path(id)}/history`);
export const updateProjectDisable = async (id: number) =>
  (await api<ProjectLifecycleDetail>(`${path(id)}/disable`, { method: "POST" }))
    .data!;
export const updateProjectEnable = async (id: number) =>
  (await api<ProjectLifecycleDetail>(`${path(id)}/enable`, { method: "POST" }))
    .data!;
export const getProjectExport = (filter: ProjectExportFilter) => {
  const query = new URLSearchParams();
  for (const key of ["year", "type", "status", "part", "q", "sort"] as const) {
    const value = filter[key];
    if (value !== undefined && value !== "") query.set(key, String(value));
  }
  return readData<ProjectDownloadArtifact>(`/api/projects/export?${query}`);
};
export function saveProjectWorkbook(artifact: ProjectDownloadArtifact) {
  const bytes = Uint8Array.from(atob(artifact.content_base64), (c) =>
    c.charCodeAt(0),
  );
  const url = URL.createObjectURL(
    new Blob([bytes], { type: artifact.media_type }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = artifact.file_name;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
export async function downloadProjects(filter: ProjectExportFilter) {
  saveProjectWorkbook(await getProjectExport(filter));
}
