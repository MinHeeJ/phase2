import { api, readData } from "../../shared/api";

export interface OpportunityNeedInput {
  need_id?: number;
  job_cd: string;
  grd_cd: string | null;
  psn_cnt: number;
  bgn_ym: string;
  mth_cnt: number;
  mth_mm_qty: number;
  expc_untprc_amt: number | null;
  rmrk_desc: string | null;
}
export interface OpportunityWrite {
  oppt_nm: string;
  cust_ptnr_id: number | null;
  bsn_part_id: number | null;
  owner_user_id: number | null;
  bsn_stg_cd: string;
  win_prblt_rt: number;
  expc_amt: number;
  cntrct_ty_cd: string;
  bgn_ym: string;
  mth_cnt: number;
  next_action: string | null;
  next_action_dt: string | null;
  rmrk_desc: string | null;
  needs: OpportunityNeedInput[];
  amount_difference_reason?: string;
}
export interface OpportunityNeed extends OpportunityNeedInput {
  need_id: number;
  psn_mth_cst_amt: number;
  min_untprc_amt: number;
  trgt_untprc_amt: number;
  warnings: string[];
}
export interface OpportunityQuote {
  expc_sls_amt: number;
  expc_cst_amt: number;
  expc_mrgn_rt: number | null;
  minimum_amount: number;
  amount_difference: number;
  amount_difference_rate: number | null;
  cost_status: "UNKNOWN" | "VERIFIED" | "PROVEN_ZERO";
  approval_state: string;
  approved_quote_amount: number | null;
  warnings: string[];
}
export interface OpportunityDetail extends OpportunityWrite {
  oppt_id: number;
  oppt_no: string;
  oppt_stts_cd: "OPEN" | "WON" | "LOST" | "DELETED";
  needs: OpportunityNeed[];
  quote: OpportunityQuote;
  approval_history: {
    action: string;
    opinion: string | null;
    user_id: number | null;
    created_at: string;
    expc_mrgn_rt: number | null;
  }[];
  win_prj_id: number | null;
  updated_at: string | null;
  created_at: string;
  stale_warning: boolean;
  warnings: string[];
}
export interface OpportunityPage {
  items: OpportunityDetail[];
  page: number;
  size: 20 | 50 | 100;
  total: number;
  kpi: {
    open_count: number;
    expected_amount: number;
    weighted_amount: number;
    needed_people: number;
    stale_count: number;
  };
}
export interface OpportunityFilters {
  q: string;
  oppt_stts_cd: string;
  bsn_stg_cd: string;
  cust_ptnr_id: string;
  bsn_part_id: string;
  from: string;
  to: string;
  sort: string;
  page: number;
  size: 20 | 50 | 100;
}
export const opportunityFilters: OpportunityFilters = {
  q: "",
  oppt_stts_cd: "OPEN",
  bsn_stg_cd: "",
  cust_ptnr_id: "",
  bsn_part_id: "",
  from: "",
  to: "",
  sort: "oppt_id,asc",
  page: 0,
  size: 50,
};
export function opportunityQuery(filters: Partial<OpportunityFilters>) {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== "" && value != null) query.set(key, String(value));
  });
  return query;
}
export const getOpportunities = (
  filters: Partial<OpportunityFilters>,
  signal?: AbortSignal,
) =>
  readData<OpportunityPage>(
    `/api/opportunities?${opportunityQuery(filters)}`,
    signal,
  );
export async function getClosedOpportunities(
  filters: OpportunityFilters,
): Promise<OpportunityPage> {
  const readStatus = async (status: string) => {
    const first = await getOpportunities({
      ...filters,
      oppt_stts_cd: status,
      bsn_stg_cd: "",
      page: 0,
      size: 100,
    });
    const items = [...first.items];
    for (let page = 1; page * 100 < first.total; page++) {
      const next = await getOpportunities({
        ...filters,
        oppt_stts_cd: status,
        bsn_stg_cd: "",
        page,
        size: 100,
      });
      items.push(...next.items);
    }
    return { ...first, items };
  };
  const [won, lost] = await Promise.all([
    readStatus("WON"),
    readStatus("LOST"),
  ]);
  const [key, direction] = filters.sort.split(",");
  const items = [...won.items, ...lost.items].sort((a, b) => {
    const x = a[key as keyof OpportunityDetail],
      y = b[key as keyof OpportunityDetail];
    if (x == null && y != null) return 1;
    if (y == null && x != null) return -1;
    const compared =
      typeof x === "number" && typeof y === "number"
        ? x - y
        : String(x ?? "").localeCompare(String(y ?? ""), "ko");
    return (
      (direction === "desc" ? -compared : compared) || a.oppt_id - b.oppt_id
    );
  });
  return {
    items: items.slice(
      filters.page * filters.size,
      (filters.page + 1) * filters.size,
    ),
    page: filters.page,
    size: filters.size,
    total: won.total + lost.total,
    kpi: {
      open_count: 0,
      expected_amount: won.kpi.expected_amount + lost.kpi.expected_amount,
      weighted_amount: won.kpi.weighted_amount + lost.kpi.weighted_amount,
      needed_people: won.kpi.needed_people + lost.kpi.needed_people,
      stale_count: 0,
    },
  };
}
export const getOpportunity = (id: number) =>
  readData<OpportunityDetail>(`/api/opportunities/${id}`);
export const createOpportunity = async (input: OpportunityWrite) =>
  (
    await api<OpportunityDetail>("/api/opportunities", {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data!;
export const updateOpportunity = async (id: number, input: OpportunityWrite) =>
  (
    await api<OpportunityDetail>(`/api/opportunities/${id}`, {
      method: "PUT",
      body: JSON.stringify(input),
    })
  ).data!;
export function opportunityPayload(row: OpportunityWrite): OpportunityWrite {
  return {
    oppt_nm: row.oppt_nm.trim(),
    cust_ptnr_id: row.cust_ptnr_id,
    bsn_part_id: row.bsn_part_id,
    owner_user_id: row.owner_user_id,
    bsn_stg_cd: row.bsn_stg_cd,
    win_prblt_rt: row.win_prblt_rt,
    expc_amt: row.expc_amt,
    cntrct_ty_cd: row.cntrct_ty_cd,
    bgn_ym: row.bgn_ym,
    mth_cnt: row.mth_cnt,
    next_action: row.next_action?.trim() || null,
    next_action_dt: row.next_action_dt || null,
    rmrk_desc: row.rmrk_desc?.trim() || null,
    ...(row.amount_difference_reason?.trim()
      ? { amount_difference_reason: row.amount_difference_reason.trim() }
      : {}),
    needs: row.needs.map((n) => ({
      ...(n.need_id ? { need_id: n.need_id } : {}),
      job_cd: n.job_cd,
      grd_cd: n.grd_cd || null,
      psn_cnt: n.psn_cnt,
      bgn_ym: n.bgn_ym,
      mth_cnt: n.mth_cnt,
      mth_mm_qty: n.mth_mm_qty,
      expc_untprc_amt: n.expc_untprc_amt,
      rmrk_desc: n.rmrk_desc?.trim() || null,
    })),
  };
}
export const opportunityStages = [
  { code: "DISC", name: "발굴", probability: 10 },
  { code: "PROP", name: "제안", probability: 30 },
  { code: "NEGO", name: "협상", probability: 60 },
  { code: "PRE", name: "계약 직전", probability: 90 },
];
export interface OpportunityForecast {
  items: {
    month: string;
    project_revenue: number;
    opportunity_revenue: number;
    weighted_total: number;
    all_won_total: number;
  }[];
  kpi: {
    weighted_total: number;
    opportunity_total: number;
    all_won_total: number;
    average_demand_mm: number;
    average_supply_mm: number;
    hiring_job_count: number;
  };
  workforce: {
    months: string[];
    message: string | null;
    items: {
      month: string;
      job_cd: string;
      grd_cd: string | null;
      supply_mm: number;
      demand_mm: number;
      warnings: string[];
    }[];
  };
}
export const getOpportunityForecast = () =>
  readData<OpportunityForecast>("/api/opportunities/forecast");
