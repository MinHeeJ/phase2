import { api, readData } from "../../shared/api";

export interface ProjectExpenseInput {
  expns_ym: string;
  expns_item_cd: string;
  expns_amt: number | null;
  vat_amt?: number;
  pay_dt: string | null;
  rmrk_desc: string | null;
}
export interface ProjectExpense extends ProjectExpenseInput {
  expns_id: number;
  expns_amt: number;
  vat_amt: number;
}
const path = (projectId: number) =>
  `/api/projects/${encodeURIComponent(projectId)}/expenses`;
export const listProjectExpenses = (projectId: number) =>
  readData<ProjectExpense[]>(path(projectId));
export function projectExpensePayload(
  input: ProjectExpenseInput,
): ProjectExpenseInput {
  return {
    expns_ym: input.expns_ym,
    expns_item_cd: input.expns_item_cd,
    expns_amt: input.expns_amt,
    ...(input.vat_amt === undefined ? {} : { vat_amt: input.vat_amt }),
    pay_dt: input.pay_dt || null,
    rmrk_desc: input.rmrk_desc || null,
  };
}
export const createProjectExpense = async (
  projectId: number,
  input: ProjectExpenseInput,
) =>
  (
    await api<ProjectExpense>(path(projectId), {
      method: "POST",
      body: JSON.stringify(projectExpensePayload(input)),
    })
  ).data!;
export const updateProjectExpense = async (
  projectId: number,
  expenseId: number,
  input: ProjectExpenseInput,
) =>
  (
    await api<ProjectExpense>(
      `${path(projectId)}/${encodeURIComponent(expenseId)}`,
      {
        method: "PUT",
        body: JSON.stringify(projectExpensePayload(input)),
      },
    )
  ).data!;
export const updateProjectExpenseRemoval = async (
  projectId: number,
  expenseId: number,
) =>
  (
    await api<{ prj_id: number; expns_id: number }>(
      `${path(projectId)}/${encodeURIComponent(expenseId)}`,
      {
        method: "DELETE",
      },
    )
  ).data!;
