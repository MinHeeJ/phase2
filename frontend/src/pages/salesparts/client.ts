import { api, readData } from "../../shared/api";

export interface SalesPartStandard {
  part_std_hist_id: string;
  aply_bgn_ym: string;
  aply_end_ym: string | null;
  ldr_nm: string | null;
  prft_dstrb_rt: number;
  bsn_fee_amt: number;
  status: "예정" | "적용 중" | "지난 기준";
}
export interface SalesPart {
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
export interface SalesPartDetail extends SalesPart {
  standards: SalesPartStandard[];
  lastClosedMonth: string | null;
  firstAllowedMonth: string | null;
}
export interface SalesPartPage {
  items: SalesPart[];
  total: number;
  page: number;
  size: number;
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
  readData<SalesPartPage>(
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

export async function salesPartCreationMetadata(
  current: SalesPartPage,
): Promise<SalesPartDetail> {
  let hq = current.items.find((p) => p.hq_yn === "Y");
  for (let page = 1; !hq && (page - 1) * 50 < current.total; page++) {
    const result = await listSalesParts(page, 50);
    hq = result.items.find((p) => p.hq_yn === "Y");
    if (page * 50 >= result.total) break;
  }
  if (!hq) throw new Error("본사 파트를 찾을 수 없습니다. 다시 시도해 주세요.");
  return getSalesPart(hq.bsn_part_id);
}
