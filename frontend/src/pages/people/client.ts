import { api, ApiFailure } from "../../shared/api";
import { seoulMonth } from "../shared/PeriodBar";

export interface PeopleFilters {
  year: number;
  view: "TOTAL" | "AVERAGE";
  empType: string;
  partId: string;
  vendorId: string;
  contractType: string;
  job: string;
  lossOnly: boolean;
  overlapOnly: boolean;
  excludeTurnkeyPrice: boolean;
  page: number;
  size: 20 | 50 | 100;
  sort: string;
}
export const defaultPeopleFilters = (): PeopleFilters => ({
  year: Number(seoulMonth().slice(0, 4)),
  view: "TOTAL",
  empType: "",
  partId: "",
  vendorId: "",
  contractType: "",
  job: "",
  lossOnly: false,
  overlapOnly: false,
  excludeTurnkeyPrice: false,
  page: 0,
  size: 50,
  sort: "dev_id,asc",
});
export interface PeopleRow {
  dev_id: number;
  dev_nm: string;
  vendor_name: string | null;
  pstn_cd: string | null;
  emp_se_cd: string;
  bsn_part_id: number | null;
  project_names: string[];
  period: string;
  contract_types: string[];
  mm_qty: number;
  bill_untprc_amt: number | null;
  slry_amt: number;
  alwnc_amt: number;
  sins_cmpny_amt: number;
  sins_emp_amt: number;
  whtax_amt: number;
  real_pay_amt: number;
  rtrpay_acml_amt: number;
  indv_lbr_cst_amt: number;
  team_expns_amt: number;
  project_expns_amt: number;
  tot_cst_amt: number;
  sls_amt: number;
  prft_amt: number;
  mrgn_rt: number | null;
  idle_cst_amt: number;
  mm_ovr_yn: string;
  expandable: boolean;
}
export interface Contribution {
  job_cd: string;
  grd_cd: string;
  psn_cnt: number;
  contribution_amt: number | null;
}
export interface PeopleSummary {
  psn_cnt: number;
  avg_working_psn_cnt: number | null;
  avg_earning_psn_cnt: number | null;
  avg_price_amt: number | null;
  avg_cost_amt: number | null;
  avg_profit_amt: number | null;
  loss_psn_cnt: number;
  utilization_rt: number | null;
  earning_rt: number | null;
  contributions: Contribution[];
}
interface PeopleMetadata {
  calculation_month: string;
  reference_month: string;
  closed: boolean;
  unit: string;
  warnings: string[];
}
export interface PeopleList extends PeopleMetadata {
  items: PeopleRow[];
  total: number;
  page: number;
  size: 20 | 50 | 100;
  summary: PeopleSummary;
}
export interface BreakdownRow {
  cst_ym: string;
  asgn_id: number | null;
  prj_id: number | null;
  prj_nm: string | null;
  aloc_ty_cd: string;
  mm_qty: number | null;
  bill_untprc_amt: number | null;
  lbr_cst_amt: number;
  drct_expns_amt: number;
  sls_amt: number;
  prft_amt: number;
  idle_cst_amt: number;
  mm_ovr_yn: string;
}
export interface PersonBreakdown extends PeopleMetadata {
  dev_id: number;
  items: BreakdownRow[];
}
export function peopleQuery(filters: PeopleFilters) {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== "") params.set(key, String(value));
  });
  return params;
}
export const listCostPeople = async (filters: PeopleFilters) =>
  (
    await api<PeopleList>(`/api/costs/people?${peopleQuery(filters)}`, {
      headers: { Accept: "application/json" },
    })
  ).data!;
export const getCostPersonBreakdown = async (
  id: number,
  filters: Pick<PeopleFilters, "year" | "view">,
) =>
  (
    await api<PersonBreakdown>(
      `/api/costs/people/${id}/breakdown?${new URLSearchParams({ year: String(filters.year), view: filters.view })}`,
    )
  ).data!;
export async function downloadCostPeople(filters: PeopleFilters) {
  const response = await fetch(`/api/costs/people?${peopleQuery(filters)}`, {
    headers: {
      Accept:
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    },
  });
  if (!response.ok) {
    const error = await response.json();
    throw new ApiFailure(
      error.message ?? "다운로드하지 못했습니다.",
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
    throw new ApiFailure("엑셀 파일이 손상되었습니다.", response.status, null);
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = "개인원가수익.xlsx";
    link.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
