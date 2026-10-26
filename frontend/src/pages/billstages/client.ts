import { api, readData } from "../../shared/api";

export interface ProjectBillStageInput {
  bill_stg_id?: number;
  bill_stg_nm: string;
  sply_amt: number;
  bill_plan_dt: string;
}
export interface ProjectBillStage extends ProjectBillStageInput {
  bill_stg_id: number;
  stg_odr_no: number;
  vat_amt: number;
  billed: boolean;
}
export interface ProjectBillStages {
  items: ProjectBillStage[];
  cur_cntrct_amt: number;
  totalSupplyAmount: number;
}
const path = (projectId: number) =>
  `/api/projects/${encodeURIComponent(projectId)}/bill-stages`;
export const listProjectBillStages = (projectId: number) =>
  readData<ProjectBillStages>(path(projectId));
export const saveProjectBillStages = async (
  projectId: number,
  items: ProjectBillStageInput[],
) =>
  (
    await api<ProjectBillStages>(path(projectId), {
      method: "PUT",
      body: JSON.stringify({
        items: items.map((row) => ({
          ...(row.bill_stg_id === undefined
            ? {}
            : { bill_stg_id: row.bill_stg_id }),
          bill_stg_nm: row.bill_stg_nm,
          sply_amt: row.sply_amt,
          bill_plan_dt: row.bill_plan_dt,
        })),
      }),
    })
  ).data!;
