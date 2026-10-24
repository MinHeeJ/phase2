import { api, readData } from "../../shared/api";
import type { SalesPartCreate, SalesPartDetail } from "../salesparts/client";

export type SalesPartStandardSave = Omit<SalesPartCreate, "bsn_part_nm">;
export interface SalesPartOption {
  id: string;
  name: string;
  hq: boolean;
}
const path = (id: string) => `/api/sales-parts/${encodeURIComponent(id)}`;
export const listSalesPartOptions = () =>
  readData<SalesPartOption[]>("/api/sales-parts/options");
export const saveSalesPartStandard = async (
  id: string,
  input: SalesPartStandardSave,
) =>
  (
    await api<SalesPartDetail>(`${path(id)}/standards`, {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data!;
export const updateSalesPartName = async (id: string, bsn_part_nm: string) =>
  (
    await api<SalesPartDetail>(`${path(id)}/name`, {
      method: "PUT",
      body: JSON.stringify({ bsn_part_nm }),
    })
  ).data!;
export const saveSalesPartDisable = async (id: string) =>
  (await api<SalesPartDetail>(`${path(id)}/disable`, { method: "POST" })).data!;
export const saveSalesPartEnable = async (id: string) =>
  (await api<SalesPartDetail>(`${path(id)}/enable`, { method: "POST" })).data!;
export const salesPartExportColumns = [
  "bsn_part_nm",
  "currentStandard.ldr_nm",
  "currentStandard.prft_dstrb_rt",
  "currentStandard.bsn_fee_amt",
  "currentStandard.aply_bgn_ym",
  "scheduledChanges",
  "peopleCount",
  "projectCount",
  "use_yn",
] as const;
