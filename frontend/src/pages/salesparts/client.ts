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
export interface SalesPartCreate {
  bsn_part_nm: string;
  aply_bgn_ym: string;
  ldr_nm: string;
  prft_dstrb_rt: number;
  bsn_fee_amt: number;
}
export const listSalesParts = (
  page = 1,
  size = 50,
  sort = "",
  signal?: AbortSignal,
) =>
  readData<PageResult<SalesPartListItem>>(
    `/api/sales-parts?${new URLSearchParams({ page: String(page), size: String(size), ...(sort ? { sort } : {}) })}`,
    signal,
  );
export const getSalesPart = (id: string, signal?: AbortSignal) =>
  readData<SalesPartDetail>(
    `/api/sales-parts/${encodeURIComponent(id)}`,
    signal,
  );
export const createSalesPart = async (input: SalesPartCreate) =>
  (
    await api<SalesPartDetail>("/api/sales-parts", {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data as SalesPartDetail;
