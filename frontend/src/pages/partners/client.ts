import { api, readData } from "../../shared/api";
import type { PageResult } from "../../shared/types";

export interface PartnerInput {
  ptnr_se_cd: string;
  ptnr_nm: string;
  biz_reg_no: string;
  ceo_nm: string | null;
  indty_cd: string | null;
  chrg_nm: string | null;
  chrg_tel_no: string | null;
  rmrk_desc: string | null;
  txinv_email_addr?: string | null;
  bank_cd?: string | null;
  acnt_no?: string | null;
  dpstr_nm?: string | null;
}
export interface PartnerDetail extends PartnerInput {
  ptnr_id: string;
  ptnr_no: string;
  use_yn: "Y" | "N";
}
export interface PartnerListItem
  extends Omit<
    PartnerDetail,
    "acnt_no" | "dpstr_nm" | "bank_cd" | "rmrk_desc"
  > {
  kindName: string;
  industryName: string | null;
  paymentAccount: string | null;
  projectCount: number | null;
  assignmentPeopleCount: number;
}
export interface PartnerFilters {
  kind: string;
  useYn: string;
  q: string;
  page: number;
  size: 20 | 50 | 100;
  sort: string;
}
export const defaultPartnerFilters: PartnerFilters = {
  kind: "",
  useYn: "Y",
  q: "",
  page: 1,
  size: 50,
  sort: "ptnr_nm,asc",
};
export function partnerQuery(filters: PartnerFilters) {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) =>
    params.set(key, String(value)),
  );
  return params.toString();
}
export const listPartners = (filters: PartnerFilters) =>
  readData<PageResult<PartnerListItem>>(
    `/api/partners?${partnerQuery(filters)}`,
  );
export const getPartner = (id: string) =>
  readData<PartnerDetail>(`/api/partners/${encodeURIComponent(id)}`);
export async function createPartner(input: PartnerInput) {
  return (
    await api<PartnerDetail>("/api/partners", {
      method: "POST",
      body: JSON.stringify(partnerPayload(input)),
    })
  ).data!;
}
export async function updatePartner(id: string, input: PartnerInput) {
  const { biz_reg_no: _biz, ...body } = partnerPayload(input);
  return (
    await api<PartnerDetail>(`/api/partners/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(body),
    })
  ).data!;
}
export function partnerPayload(input: PartnerInput): PartnerInput {
  return {
    ptnr_se_cd: input.ptnr_se_cd,
    ptnr_nm: input.ptnr_nm,
    biz_reg_no: input.biz_reg_no,
    ceo_nm: input.ceo_nm || null,
    indty_cd: input.indty_cd || null,
    chrg_nm: input.chrg_nm || null,
    chrg_tel_no: input.chrg_tel_no || null,
    rmrk_desc: input.rmrk_desc || null,
    txinv_email_addr:
      input.ptnr_se_cd === "VEND" ? null : input.txinv_email_addr || null,
    bank_cd: input.ptnr_se_cd === "CUST" ? null : input.bank_cd || null,
    acnt_no: input.ptnr_se_cd === "CUST" ? null : input.acnt_no || null,
    dpstr_nm: input.ptnr_se_cd === "CUST" ? null : input.dpstr_nm || null,
  };
}
export type {
  PartnerAssignment,
  PartnerChange,
  PartnerBlock,
  PartnerDisableResult,
} from "../partnersops/client";
export {
  listPartnerAssignments,
  listPartnerHistory,
  disablePartners,
  enablePartner,
} from "../partnersops/client";
