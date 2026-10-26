import { api, readData } from "../../shared/api";

export type ContractChangeType = "ADD" | "CUT" | "TERM" | "FREE";
export interface ContractChangeInput {
  cntrct_chg_ty_cd: ContractChangeType | "";
  cntrct_chg_stts_cd: "PLAN" | "CONFIRM";
  chg_dt: string;
  amount?: number;
  incdec_mm_qty?: number | null;
  aft_end_dt?: string | null;
  free_mm_qty?: number | null;
  rsn_desc: string;
}
export interface ContractChangeRow extends Omit<ContractChangeInput, "amount"> {
  chgId: number;
  bfr_cntrct_amt: number | null;
  aft_cntrct_amt: number | null;
  bfr_end_dt: string | null;
  realoc_need_yn: "Y" | "N";
  cnfrm_user_id: number | null;
  cnfrm_at: string | null;
}
export interface ContractChanges {
  cntrct_bgn_dt: string;
  init_cntrct_amt: number | null;
  items: ContractChangeRow[];
}
export interface ContractChangeProject {
  prj_id: number;
  cntrct_ty_cd: "TK" | "MO" | "DP" | "IN";
  cur_end_dt: string;
  use_yn: "Y" | "N";
}
const path = (id: number) =>
  `/api/projects/${encodeURIComponent(id)}/contract-changes`;
export const listProjectContractChanges = (id: number) =>
  readData<ContractChanges>(path(id));
export function contractChangePayload(
  input: ContractChangeInput,
): ContractChangeInput {
  const money =
    input.cntrct_chg_ty_cd === "ADD" || input.cntrct_chg_ty_cd === "CUT";
  return {
    cntrct_chg_ty_cd: input.cntrct_chg_ty_cd,
    cntrct_chg_stts_cd: input.cntrct_chg_stts_cd,
    chg_dt: input.chg_dt,
    rsn_desc: input.rsn_desc.trim(),
    ...(money
      ? { amount: input.amount, incdec_mm_qty: input.incdec_mm_qty ?? null }
      : {}),
    ...(input.cntrct_chg_ty_cd === "TERM"
      ? { aft_end_dt: input.aft_end_dt }
      : {}),
    ...(input.cntrct_chg_ty_cd === "FREE"
      ? { free_mm_qty: input.free_mm_qty }
      : {}),
  };
}
export const createProjectContractChange = async (
  id: number,
  input: ContractChangeInput,
) =>
  (
    await api<ContractChangeRow>(path(id), {
      method: "POST",
      body: JSON.stringify(contractChangePayload(input)),
    })
  ).data!;
export const updateProjectContractChange = async (
  id: number,
  chgId: number,
  input: ContractChangeInput,
) =>
  (
    await api<ContractChangeRow>(`${path(id)}/${encodeURIComponent(chgId)}`, {
      method: "PUT",
      body: JSON.stringify(contractChangePayload(input)),
    })
  ).data!;
export const updateProjectContractChangeConfirmation = async (
  id: number,
  chgId: number,
) =>
  (
    await api<ContractChangeRow>(
      `${path(id)}/${encodeURIComponent(chgId)}/confirm`,
      { method: "POST" },
    )
  ).data!;
export const updateProjectContractChangeRemoval = async (
  id: number,
  chgId: number,
) =>
  (
    await api<{ prj_id: number; chgId: number }>(
      `${path(id)}/${encodeURIComponent(chgId)}`,
      { method: "DELETE" },
    )
  ).data!;
