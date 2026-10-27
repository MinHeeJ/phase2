import { readData } from "../../shared/api";

export interface IssueOwnerOption {
  name: string;
}
export const getIssueOwnerOptions = (projectId: number) =>
  readData<IssueOwnerOption[]>(
    `/api/issues/owner-options?${new URLSearchParams({ projectId: String(projectId) })}`,
  );
