import { readData } from "../../shared/api";

export interface PeopleFilters {
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
  size: number;
  sort: string;
}
export const defaultPeopleFilters: PeopleFilters = {
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
  sort: "name,asc",
};
export interface PeopleRow {
  dev_id: number;
  name: string;
  affiliation: string;
  position: string | null;
  emp_type: string;
  job: string | null;
  grade: string | null;
  projects: string;
  period: string;
  contract_types: string;
  mm: number;
  price: number | null;
  salary: number;
  allowance: number;
  insurance: number;
  retirement: number;
  labor: number;
  expenses: number;
  cost: number;
  revenue: number;
  profit: number;
  margin: number | null;
  overlap: boolean;
  on_leave: boolean;
  warnings: string[];
}
export interface PeopleData {
  items: PeopleRow[];
  total: number;
  page: number;
  size: number;
  through: string;
  summary: {
    people: number;
    active_people: number;
    paid_people: number;
    utilization: number | null;
    earning_rate: number | null;
    average_price: number | null;
    average_cost: number | null;
    average_profit: number | null;
    loss_people: number;
    revenue: number;
    cost: number;
    profit: number;
  };
  contributions: {
    dimension: string;
    name: string;
    people: number;
    per_person_profit: number;
  }[];
  warnings: string[];
}
export interface PersonEvidence {
  dev_id: number;
  month: string;
  prj_id: number | null;
  asgn_id: number | null;
  project: string;
  classification: string;
  contract_type: string | null;
  job: string | null;
  mm: number;
  price: number | null;
  labor: number;
  expenses: number;
  revenue: number | null;
  profit: number | null;
  overlap: boolean;
  free: boolean;
  pre_contract: boolean;
}
export interface PersonBreakdown {
  dev_id: number;
  through: string;
  view: string;
  items: PersonEvidence[];
  warnings: string[];
}
export function listCostPeople(
  year: number,
  filters: PeopleFilters,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ year: String(year) });
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== "") query.set(key, String(value));
  });
  return readData<PeopleData>(`/api/costs/people?${query}`, signal);
}
export function getCostPersonBreakdown(
  devId: number,
  year: number,
  view: string,
  signal?: AbortSignal,
) {
  const query = new URLSearchParams({ year: String(year), view });
  return readData<PersonBreakdown>(
    `/api/costs/people/${encodeURIComponent(devId)}/breakdown?${query}`,
    signal,
  );
}
