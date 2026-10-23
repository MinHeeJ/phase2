import { api, readData } from "../../shared/api";
import type { PageResult } from "../../shared/types";

export type PartnerKind = "CUST" | "VEND" | "BOTH";
export interface PartnerInput {
  ptnr_se_cd: PartnerKind | "";
  ptnr_nm: string;
  biz_reg_no?: string;
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
  biz_reg_no: string;
  use_yn: "Y" | "N";
}
export interface PartnerListItem {
  ptnr_id: string;
  ptnr_no: string;
  ptnr_se_cd: PartnerKind;
  ptnr_nm: string;
  biz_reg_no: string;
  ceo_nm: string | null;
  indty_cd: string | null;
  chrg_nm: string | null;
  chrg_tel_no: string | null;
  txinv_email_addr: string | null;
  use_yn: "Y" | "N";
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
export const partnerQueryString = (filters: PartnerFilters) => {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (key === "useYn" || value !== "") query.set(key, String(value));
  });
  return query.toString();
};
export const listPartners = (filters: PartnerFilters, signal?: AbortSignal) =>
  readData<PageResult<PartnerListItem>>(
    `/api/partners?${partnerQueryString(filters)}`,
    signal,
  );
export const getPartner = (id: string, signal?: AbortSignal) =>
  readData<PartnerDetail>(`/api/partners/${encodeURIComponent(id)}`, signal);
export const createPartner = async (value: PartnerInput) =>
  (
    await api<PartnerDetail>("/api/partners", {
      method: "POST",
      body: JSON.stringify(value),
    })
  ).data as PartnerDetail;
export const updatePartner = async (id: string, value: PartnerInput) =>
  (
    await api<PartnerDetail>(`/api/partners/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(value),
    })
  ).data as PartnerDetail;
export interface PartnerAssignment {
  projectName: string;
  developerName: string;
  affiliation: string;
  jobCode: string;
  jobName: string;
  assignmentRate: number;
  endDate: string;
}
export interface PartnerChange {
  changedAt: string;
  column: string | null;
  itemName: string;
  before: string | null;
  after: string | null;
  changeType: string;
  actorName: string;
}
export const getPartnerAssignments = (id: string, signal?: AbortSignal) =>
  readData<PartnerAssignment[]>(
    `/api/partners/${encodeURIComponent(id)}/assignments`,
    signal,
  );
export const getPartnerHistory = (id: string, signal?: AbortSignal) =>
  readData<PartnerChange[]>(
    `/api/partners/${encodeURIComponent(id)}/history`,
    signal,
  );
export const disablePartners = (ids: string[]) =>
  api<{
    disabledIds: string[];
    blocked: { id: string; name: string; reason: string }[];
  }>("/api/partners/disable", {
    method: "POST",
    body: JSON.stringify({ ids }),
  });
export const enablePartner = (id: string) =>
  api<PartnerDetail>(`/api/partners/${encodeURIComponent(id)}/enable`, {
    method: "POST",
  });
export function partnerPayload(
  value: PartnerInput,
  editing: boolean,
): PartnerInput {
  const {
    ptnr_se_cd,
    ptnr_nm,
    ceo_nm,
    indty_cd,
    chrg_nm,
    chrg_tel_no,
    rmrk_desc,
  } = value;
  return {
    ptnr_se_cd,
    ptnr_nm,
    ceo_nm,
    indty_cd,
    chrg_nm,
    chrg_tel_no,
    rmrk_desc,
    ...(!editing ? { biz_reg_no: value.biz_reg_no } : {}),
    txinv_email_addr: ptnr_se_cd === "VEND" ? null : value.txinv_email_addr,
    bank_cd: ptnr_se_cd === "CUST" ? null : value.bank_cd,
    acnt_no: ptnr_se_cd === "CUST" ? null : value.acnt_no,
    dpstr_nm: ptnr_se_cd === "CUST" ? null : value.dpstr_nm,
  };
}
