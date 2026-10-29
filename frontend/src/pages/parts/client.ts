import { readData } from "../../shared/api";

export interface SettlementPerson {
  dev_id: number;
  dev_nm: string;
  emp_se_cd: string;
  mm_qty: number;
  mth_untprc_amt: number;
  slry_amt: number | null;
  bsn_fee_amt: number;
  rtrpay_amt: number;
  tot_exp_amt: number | null;
  diff_amt: number | null;
  prft_amt: number | null;
  rmrk_desc: string;
}
export interface SettlementMonth {
  month: string;
  status: "DRAFT" | "CONFIRM";
  closed: boolean;
  provisional: boolean;
  standard: {
    part_std_hist_id: number;
    ldr_nm: string | null;
    prft_dstrb_rt: number;
    bsn_fee_amt: number;
  } | null;
  corp_card_amt: number;
  card_remark: string | null;
  part_prft_amt: number | null;
  people: SettlementPerson[];
  warnings: string[];
  history: {
    stlm_prcs_cd: string;
    rsn_desc: string | null;
    prcs_user_id: number | null;
    prcs_at: string;
  }[];
}
export interface SettlementPart {
  bsn_part_id: number;
  bsn_part_nm: string;
}
export interface SettlementSummary {
  part: SettlementPart;
  settlement: SettlementMonth;
}
export interface SettlementDetail {
  part: SettlementPart;
  view: "MONTH" | "TREND";
  months: SettlementMonth[];
}
export const listPartSettlements = (month: string, signal?: AbortSignal) =>
  readData<SettlementSummary[]>(
    `/api/settlements/parts?${new URLSearchParams({ month })}`,
    signal,
  );
export const getPartSettlement = (
  partId: number,
  month: string,
  view: "MONTH" | "TREND",
  signal?: AbortSignal,
) =>
  readData<SettlementDetail>(
    `/api/settlements/parts/${encodeURIComponent(partId)}?${new URLSearchParams({ month, view })}`,
    signal,
  );
