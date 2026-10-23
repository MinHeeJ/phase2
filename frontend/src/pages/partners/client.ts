import { api, readData } from "../../shared/api";
import type { PageResult } from "../../shared/types";
export interface PartnerInput {
  ptnr_se_cd: string;
  ptnr_nm: string;
  biz_reg_no?: string;
  ceo_nm?: string | null;
  indty_cd?: string | null;
  chrg_nm?: string | null;
  chrg_tel_no?: string | null;
  rmrk_desc?: string | null;
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
    "bank_cd" | "acnt_no" | "dpstr_nm" | "rmrk_desc"
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
const path = (id: string) => `/api/partners/${encodeURIComponent(id)}`;
export const listPartners = (filters: PartnerFilters, signal?: AbortSignal) =>
  readData<PageResult<PartnerListItem>>(
    `/api/partners?${new URLSearchParams(Object.entries(filters).map(([k, v]) => [k, String(v)]))}`,
    signal,
  );
export const getPartner = (id: string, signal?: AbortSignal) =>
  readData<PartnerDetail>(path(id), signal);
export const createPartner = async (input: PartnerInput) =>
  (
    await api<PartnerDetail>("/api/partners", {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data!;
export const updatePartner = async (id: string, input: PartnerInput) =>
  (
    await api<PartnerDetail>(path(id), {
      method: "PUT",
      body: JSON.stringify(input),
    })
  ).data!;
export const listPartnerAssignments = (id: string, signal?: AbortSignal) =>
  readData<PartnerAssignment[]>(`${path(id)}/assignments`, signal);
export const listPartnerHistory = (id: string, signal?: AbortSignal) =>
  readData<PartnerChange[]>(`${path(id)}/history`, signal);
export const disablePartners = (ids: string[]) =>
  api<{
    disabledIds: string[];
    blocked: { id: string; name: string; reason: string }[];
  }>("/api/partners/disable", {
    method: "POST",
    body: JSON.stringify({ ids }),
  });
export const enablePartner = (id: string) =>
  api(`${path(id)}/enable`, { method: "POST" });
export function partnerPayload(
  draft: PartnerInput,
  edit: boolean,
): PartnerInput {
  const { biz_reg_no, ...values } = draft;
  return {
    ...values,
    ...(edit ? {} : { biz_reg_no }),
    txinv_email_addr:
      draft.ptnr_se_cd === "VEND" ? null : draft.txinv_email_addr,
    bank_cd: draft.ptnr_se_cd === "CUST" ? null : draft.bank_cd,
    acnt_no: draft.ptnr_se_cd === "CUST" ? null : draft.acnt_no,
    dpstr_nm: draft.ptnr_se_cd === "CUST" ? null : draft.dpstr_nm,
  };
}
