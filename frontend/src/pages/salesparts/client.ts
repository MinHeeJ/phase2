import { api, readData } from "../../shared/api";
import type { PageResult } from "../../shared/types";

export interface SalesPartStandard {
  part_std_hist_id: string;
  aply_bgn_ym: string;
  aply_end_ym: string | null;
  ldr_nm: string | null;
  prft_dstrb_rt: number;
  bsn_fee_amt: number;
  status: "예정" | "적용 중" | "지난 기준";
}
export interface SalesPartListItem {
  bsn_part_id: string;
  bsn_part_nm: string;
  hq_yn: "Y" | "N";
  use_yn: "Y" | "N";
  sort_seq: number;
  referenceMonth: string;
  currentStandard: SalesPartStandard | null;
  scheduledChanges: SalesPartStandard[];
  peopleCount: number;
  projectCount: number;
}
export interface SalesPartDetail extends SalesPartListItem {
  standards: SalesPartStandard[];
  lastClosedMonth: string | null;
  firstAllowedMonth: string | null;
}
export interface SalesPartDraft {
  bsn_part_nm: string;
  aply_bgn_ym: string;
  ldr_nm: string;
  prft_dstrb_rt: string;
  bsn_fee_amt: string;
}
export const listSalesParts = (page = 1, size = 50, sort = "") =>
  readData<PageResult<SalesPartListItem>>(
    `/api/sales-parts?${new URLSearchParams({ page: String(page), size: String(size), ...(sort ? { sort } : {}) })}`,
  );
export const getSalesPart = (id: string) =>
  readData<SalesPartDetail>(`/api/sales-parts/${encodeURIComponent(id)}`);
export async function createSalesPart(input: SalesPartDraft) {
  if (
    !/^\d+(?:\.\d{1,2})?$/.test(input.prft_dstrb_rt) ||
    Number(input.prft_dstrb_rt) > 100 ||
    !/^[1-9]\d*$/.test(input.bsn_fee_amt) ||
    BigInt(input.bsn_fee_amt) > 9223372036854775807n
  )
    throw new Error("올바른 값을 입력하세요.");
  const { bsn_fee_amt, ...rest } = input;
  const body = JSON.stringify({
    ...rest,
    prft_dstrb_rt: Number(input.prft_dstrb_rt),
  });
  return (
    await api<SalesPartDetail>("/api/sales-parts", {
      method: "POST",
      body: `${body.slice(0, -1)},"bsn_fee_amt":${bsn_fee_amt}}`,
    })
  ).data as SalesPartDetail;
}
