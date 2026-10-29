import type { CostMetadata, CostPage } from "../costprofit/client";
import {
  listPartSettlements,
  getPartSettlement,
  savePartSettlementCard,
  confirmPartSettlement,
  cancelPartSettlement,
  downloadPartSettlements,
} from "../costprofit/client";

export interface PartSettlementRow {
  bsn_part_id: number;
  bsn_part_nm: string;
  ldr_nm: string | null;
  prft_dstrb_rt: number | null;
  psn_cnt: number;
  tot_diff_amt: number | null;
  tot_psn_prft_amt: number | null;
  corp_card_amt: number;
  part_prft_amt: number | null;
  stlm_stts_cd: "DRAFT" | "CONFIRM";
  provisional: boolean;
}
export interface PartSettlementHeader {
  stlm_id: number | null;
  bsn_part_id: number;
  stlm_ym: string;
  part_std_hist_id: number | null;
  prft_dstrb_rt: number | null;
  bsn_fee_amt: number | null;
  psn_cnt: number;
  tot_sls_amt: number | null;
  tot_exp_amt: number | null;
  tot_diff_amt: number | null;
  tot_psn_prft_amt: number | null;
  corp_card_amt: number;
  part_prft_amt: number | null;
  stlm_stts_cd: "DRAFT" | "CONFIRM";
  cnfrm_at: string | null;
}
export interface PartSettlementPerson {
  dev_id: number;
  dev_nm: string;
  emp_se_cd: string;
  projects: string | null;
  mm_qty: number | null;
  mth_untprc_amt: number | null;
  slry_amt: number | null;
  bsn_fee_amt: number | null;
  rtrpay_amt: number | null;
  tot_exp_amt: number | null;
  diff_amt: number | null;
  prft_amt: number | null;
  rmrk_desc: string | null;
}
export interface PartSettlementStandard {
  part_std_hist_id: number;
  aply_bgn_ym: string;
  aply_end_ym: string | null;
  ldr_nm: string | null;
  prft_dstrb_rt: number;
  bsn_fee_amt: number;
}
export interface PartSettlementDetail extends CostMetadata {
  provisional: boolean;
  confirmation_allowed: boolean;
  bsn_part_nm: string;
  ldr_nm: string | null;
  settlement: PartSettlementHeader;
  note: string | null;
  items: PartSettlementPerson[];
  history: {
    stlm_hist_id: number;
    stlm_prcs_cd: string;
    rsn_desc: string | null;
    prcs_user_id: number | null;
    prcs_at: string;
  }[];
  standards: PartSettlementStandard[];
  months: {
    month: string;
    stlm_stts_cd: "DRAFT" | "CONFIRM";
    provisional: boolean;
  }[];
  trend: {
    dev_id: number;
    dev_nm: string;
    metric: string;
    values: (number | null)[];
    total: number | null;
  }[];
}
export type PartSettlementPage = CostPage<PartSettlementRow>;
export const listSettlements = (month: string, signal?: AbortSignal) =>
  listPartSettlements<PartSettlementRow>(month, signal);
export const getSettlement = (
  id: number,
  month: string,
  view: "MONTH" | "TREND",
  signal?: AbortSignal,
) => getPartSettlement<PartSettlementDetail>(id, month, view, signal);
export const saveSettlementCard = (
  id: number,
  month: string,
  amount: number,
  note: string,
) =>
  savePartSettlementCard<PartSettlementDetail>(id, month, {
    corp_card_amt: amount,
    note,
  });
export const confirmSettlement = (id: number, month: string) =>
  confirmPartSettlement<PartSettlementDetail>(id, month);
export const cancelSettlement = (id: number, month: string, reason: string) =>
  cancelPartSettlement<PartSettlementDetail>(id, month, reason);
export const downloadSettlements = downloadPartSettlements;
