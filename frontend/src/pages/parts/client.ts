import { api, readData, ApiFailure } from "../../shared/api";

export interface Settlement {
  stlm_id: number | null;
  bsn_part_id: number;
  stlm_ym: string;
  stlm_stts_cd: "DRAFT" | "CONFIRM";
  part_std_hist_id: number | null;
  ldr_nm: string | null;
  prft_dstrb_rt: number | null;
  bsn_fee_amt: number | null;
  psn_cnt: number;
  tot_sls_amt: number;
  tot_exp_amt: number;
  tot_diff_amt: number;
  tot_psn_prft_amt: number;
  corp_card_amt: number;
  part_prft_amt: number;
  rmrk_desc: string | null;
  cnfrm_at: string | null;
}
export interface SettlementItem {
  stlm_dtl_id: number | null;
  dev_id: number;
  dev_nm: string;
  emp_se_cd: string;
  project_names: string | null;
  mm_qty: number;
  mth_untprc_amt: number;
  slry_amt: number;
  bsn_fee_amt: number;
  rtrpay_amt: number;
  tot_exp_amt: number;
  diff_amt: number;
  prft_amt: number;
  rmrk_desc: string | null;
}
export interface SettlementMeta {
  calculation_month: string;
  reference_month: string;
  closed: boolean;
  unit: string;
  warnings: string[];
}
export interface SettlementList extends SettlementMeta {
  items: {
    bsn_part_id: number;
    bsn_part_nm: string;
    settlement: Settlement;
    provisional: boolean;
    confirmation_available: boolean;
    warnings: string[];
  }[];
}
export interface SettlementDetail extends SettlementMeta {
  settlement: Settlement;
  items: SettlementItem[];
  trend: {
    dev_id: number;
    dev_nm: string;
    metric: string;
    months: { month: string; value: number; confirmed: boolean }[];
  }[];
  history: {
    stlm_hist_id: number;
    stlm_prcs_cd: string;
    rsn_desc: string | null;
    prcs_user_id: number | null;
    prcs_at: string;
  }[];
  standard_history: {
    part_std_hist_id: number;
    aply_bgn_ym: string;
    aply_end_ym: string | null;
    ldr_nm: string | null;
    prft_dstrb_rt: number;
    bsn_fee_amt: number;
  }[];
  provisional: boolean;
  confirmation_available: boolean;
}
const query = (month: string) => new URLSearchParams({ month }).toString();
const resource = (partId: number) => `/api/settlements/parts/${partId}`;
export const listPartSettlements = (month: string) =>
  api<SettlementList>(`/api/settlements/parts?${query(month)}`, {
    headers: { Accept: "application/json" },
  }).then((response) => response.data);
export const getPartSettlement = (
  partId: number,
  month: string,
  view = "MONTH",
) =>
  readData<SettlementDetail>(
    `${resource(partId)}?${query(month)}&view=${view}`,
  );
export const updatePartSettlementCard = async (
  partId: number,
  month: string,
  card: { corp_card_amt: number; rmrk_desc: string },
) =>
  (
    await api<SettlementDetail>(`${resource(partId)}/card?${query(month)}`, {
      method: "PUT",
      body: JSON.stringify(card),
    })
  ).data!;
export const confirmPartSettlement = async (partId: number, month: string) =>
  (
    await api<SettlementDetail>(`${resource(partId)}/confirm?${query(month)}`, {
      method: "POST",
    })
  ).data!;
export const cancelPartSettlement = async (
  partId: number,
  month: string,
  reason: string,
) =>
  (
    await api<SettlementDetail>(`${resource(partId)}/cancel?${query(month)}`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    })
  ).data!;
export async function downloadPartSettlements(month: string) {
  const response = await fetch(`/api/settlements/parts?${query(month)}`, {
    headers: {
      Accept:
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    },
  });
  if (!response.ok) {
    const error = await response.json();
    throw new ApiFailure(
      error.message,
      response.status,
      error.requestId,
      error.field,
      error.code,
    );
  }
  if (
    !response.headers
      .get("Content-Type")
      ?.includes(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      )
  )
    throw new ApiFailure("엑셀 파일 응답이 아닙니다.", response.status, null);
  const blob = await response.blob();
  const prefix = new Uint8Array(await blob.slice(0, 2).arrayBuffer());
  if (prefix[0] !== 80 || prefix[1] !== 75)
    throw new ApiFailure(
      "엑셀 파일을 확인할 수 없습니다.",
      response.status,
      null,
    );
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = "파트정산.xlsx";
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
