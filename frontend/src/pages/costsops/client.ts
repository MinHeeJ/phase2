import { api, ApiFailure, readData } from "../../shared/api";

export interface RenegotiationFilters {
  windowMonths: number;
  marginBelow: number;
  raiseRate: number | "";
  empType: string;
  projectId: string;
  page: number;
  size: 20 | 50 | 100;
  sort: string;
}
export const renegotiationDefaults: RenegotiationFilters = {
  windowMonths: 6,
  marginBelow: 15,
  raiseRate: "",
  empType: "",
  projectId: "",
  page: 0,
  size: 50,
  sort: "end_dt,asc",
};
export interface RenegotiationRow {
  asgn_id: number;
  dev_id: number;
  dev_nm: string;
  emp_se_cd: string;
  prj_id: number;
  prj_nm: string;
  end_dt: string;
  days_remaining: number;
  mm_qty: number | null;
  bill_untprc_amt: number | null;
  allocated_cost_amt: number;
  margin_rt: number | null;
  target_price_amt: number | null;
  required_raise_rt: number | null;
  assumed_annual_profit_increase_amt: number | null;
  target_annual_profit_increase_amt: number | null;
}
export interface RenegotiationResult {
  calculation_month: string;
  reference_month: string;
  closed: boolean;
  unit: string;
  warnings: string[];
  items: RenegotiationRow[];
  total: number;
  page: number;
  size: number;
  target_margin_rate: number | null;
  raise_rate: number;
  summary: {
    psn_cnt: number;
    weighted_margin_rt: number | null;
    assumed_annual_profit_increase_amt: number | null;
    target_annual_profit_increase_amt: number | null;
  };
}
export interface AllocationRow {
  cst_ym: string;
  dev_id: number | null;
  asgn_id: number | null;
  prj_id: number | null;
  aloc_ty_cd: string;
  mm_qty: number | null;
  tot_mm_qty: number | null;
  aloc_rt: number | null;
  lbr_cst_amt: number;
  drct_expns_amt: number;
  sls_amt: number;
  bill_untprc_amt: number | null;
  mm_ovr_yn: string;
  free_sprt_yn: string;
  pre_cntrct_yn: string;
}
export interface AllocationResult {
  calculation_month: string;
  reference_month: string;
  closed: boolean;
  unit: string;
  warnings: string[];
  items: AllocationRow[];
  unallocated_cost_amt: number;
}
export function renegotiationQuery(filters: RenegotiationFilters) {
  return new URLSearchParams(
    Object.entries(filters)
      .filter(([, value]) => value !== "")
      .map(([key, value]) => [key, String(value)]),
  ).toString();
}
export const listCostRenegotiations = (filters: RenegotiationFilters) =>
  api<RenegotiationResult>(
    `/api/costs/renegotiations?${renegotiationQuery(filters)}`,
    { headers: { Accept: "application/json" } },
  ).then((response) => response.data);
export const getCostAllocation = (month: string, projectId?: number) => {
  const query = new URLSearchParams({ month });
  if (projectId != null) query.set("projectId", String(projectId));
  return readData<AllocationResult>(`/api/costs/allocation?${query}`);
};
export async function downloadCostRenegotiations(
  filters: RenegotiationFilters,
) {
  const response = await fetch(
    `/api/costs/renegotiations?${renegotiationQuery(filters)}`,
    {
      headers: {
        Accept:
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      },
    },
  );
  if (!response.ok) {
    const error = await response.json();
    throw new ApiFailure(
      error.message ?? "다운로드하지 못했습니다.",
      response.status,
      error.requestId ?? null,
      error.field ?? null,
      error.code ?? null,
    );
  }
  if (!response.headers.get("Content-Type")?.includes("spreadsheetml.sheet")) {
    throw new ApiFailure(
      "엑셀 파일을 확인할 수 없습니다.",
      response.status,
      null,
    );
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = "단가재협상.xlsx";
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
