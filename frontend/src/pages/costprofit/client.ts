import { api, readData, ApiFailure } from "../../shared/api";

export interface CostMetadata {
  month: string;
  status: "OPEN" | "CLOSED";
  referenceMonth: string;
  unit: "KRW";
  warnings: string[];
}
export interface CostPage<T, S = Record<string, number | null>>
  extends CostMetadata {
  items: T[];
  total: number;
  page: number;
  size: number;
  summary: S;
}
export interface CostAllocationRow {
  cst_ym: string;
  dev_id: number;
  prj_id: number | null;
  asgn_id: number | null;
  aloc_ty_cd: "PROJECT" | "IDLE" | "RND" | "INDIRECT";
  mm_qty: number | null;
  tot_mm_qty: number | null;
  aloc_rt: number | null;
  lbr_cst_amt: number | null;
  drct_expns_amt: number;
  sls_amt: number | null;
  bill_untprc_amt: number | null;
  mm_ovr_yn: "Y" | "N";
  free_sprt_yn: "Y" | "N";
  pre_cntrct_yn: "Y" | "N";
}
export interface CostAllocationResult extends CostMetadata {
  items: CostAllocationRow[];
  project_expenses: {
    expns_id: number;
    prj_id: number;
    aloc_ty_cd: string;
    drct_expns_amt: number;
  }[];
  unallocated: {
    dev_id: number;
    lbr_cst_amt: number | null;
    drct_expns_amt: number;
  }[];
}
export interface CostPaging {
  page?: number;
  size?: 20 | 50 | 100;
  sort?: string;
}
export interface CostPeopleFilters extends CostPaging {
  year: number;
  view?: "TOTAL" | "AVERAGE";
  empType?: string;
  partId?: string;
  vendorId?: string;
  contractType?: string;
  job?: string;
  lossOnly?: boolean;
  overlapOnly?: boolean;
  excludeTurnkeyPrice?: boolean;
}
export interface CostProjectFilters extends CostPaging {
  year: number;
  type?: string;
  partId?: string;
  status?: string;
  q?: string;
}
export interface CostRenegotiationFilters extends CostPaging {
  windowMonths?: 3 | 6 | 12;
  marginBelow?: 10 | 15 | 20;
  raiseRate?: number;
  empType?: string;
  projectId?: string;
}
export interface CostSimulationRequest {
  headcount_delta: number;
  monthly_cost_amt: number;
  months: number;
  monthly_price_amt?: number;
}
export interface CostSimulationResult extends CostMetadata {
  simulated: true;
  cost_delta_amt: number;
  revenue_delta_amt: number;
  profit_delta_amt: number;
}
export interface PartCardRequest {
  corp_card_amt: number;
  note?: string;
}

type Query = Record<string, string | number | boolean | undefined | null>;
export function costQuery(input: object, download = false): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(input as Query)) {
    if (
      value == null ||
      value === "" ||
      (download && (key === "page" || key === "size"))
    )
      continue;
    query.set(key, String(value));
  }
  return query.toString();
}
function pathId(value: string | number): string {
  const id = String(value);
  if (
    !/^[1-9][0-9]{0,18}$/.test(id) ||
    (typeof value === "number" && !Number.isSafeInteger(value))
  )
    throw new ApiFailure(
      "식별자를 확인하세요.",
      400,
      null,
      "id",
      "VALIDATION_ERROR",
    );
  return encodeURIComponent(id);
}
export const getCostAllocation = (
  month: string,
  projectId?: string,
  signal?: AbortSignal,
) =>
  readData<CostAllocationResult>(
    `/api/costs/allocation?${costQuery({ month, projectId })}`,
    signal,
  );
export const listCostPeople = <T>(
  filters: CostPeopleFilters,
  signal?: AbortSignal,
) => readData<CostPage<T>>(`/api/costs/people?${costQuery(filters)}`, signal);
export const getCostPersonBreakdown = <T>(
  devId: string | number,
  year: number,
  view: "TOTAL" | "AVERAGE" = "TOTAL",
  signal?: AbortSignal,
) =>
  readData<T>(
    `/api/costs/people/${pathId(devId)}/breakdown?${costQuery({ year, view })}`,
    signal,
  );
export const listCostProjects = <T>(
  filters: CostProjectFilters,
  signal?: AbortSignal,
) => readData<CostPage<T>>(`/api/costs/projects?${costQuery(filters)}`, signal);
export const getCostProject = <T>(
  id: string | number,
  year: number,
  signal?: AbortSignal,
) =>
  readData<T>(
    `/api/costs/projects/${pathId(id)}?${costQuery({ year })}`,
    signal,
  );
export const getCostProjectCauses = <T>(
  id: string | number,
  year: number,
  signal?: AbortSignal,
) =>
  readData<T>(
    `/api/costs/projects/${pathId(id)}/causes?${costQuery({ year })}`,
    signal,
  );
export async function simulateCostProject(
  id: string | number,
  input: CostSimulationRequest,
): Promise<CostSimulationResult> {
  return (
    await api<CostSimulationResult>(
      `/api/costs/projects/${pathId(id)}/simulate`,
      { method: "POST", body: JSON.stringify(input) },
    )
  ).data;
}
export const listPartSettlements = <T>(month: string, signal?: AbortSignal) =>
  readData<CostPage<T>>(
    `/api/settlements/parts?${costQuery({ month })}`,
    signal,
  );
export const getPartSettlement = <T>(
  partId: string | number,
  month: string,
  view: "MONTH" | "TREND" = "MONTH",
  signal?: AbortSignal,
) =>
  readData<T>(
    `/api/settlements/parts/${pathId(partId)}?${costQuery({ month, view })}`,
    signal,
  );
export async function savePartSettlementCard<T>(
  partId: string | number,
  month: string,
  input: PartCardRequest,
): Promise<T> {
  const body: PartCardRequest = { corp_card_amt: input.corp_card_amt };
  if (input.note !== undefined) body.note = input.note;
  return (
    await api<T>(
      `/api/settlements/parts/${pathId(partId)}/card?${costQuery({ month })}`,
      { method: "PUT", body: JSON.stringify(body) },
    )
  ).data as T;
}
export async function confirmPartSettlement<T>(
  partId: string | number,
  month: string,
): Promise<T> {
  return (
    await api<T>(
      `/api/settlements/parts/${pathId(partId)}/confirm?${costQuery({ month })}`,
      { method: "POST" },
    )
  ).data as T;
}
export async function cancelPartSettlement<T>(
  partId: string | number,
  month: string,
  reason: string,
): Promise<T> {
  return (
    await api<T>(
      `/api/settlements/parts/${pathId(partId)}/cancel?${costQuery({ month })}`,
      { method: "POST", body: JSON.stringify({ reason }) },
    )
  ).data as T;
}
export const listCostRenegotiations = <T>(
  filters: CostRenegotiationFilters,
  signal?: AbortSignal,
) =>
  readData<CostPage<T>>(
    `/api/costs/renegotiations?${costQuery(filters)}`,
    signal,
  );

const XLSX =
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
async function download(
  path: string,
): Promise<{ blob: Blob; filename: string }> {
  let response: Response;
  try {
    response = await fetch(path, { headers: { Accept: XLSX } });
  } catch {
    throw new ApiFailure("서버에 연결할 수 없습니다.", null, null);
  }
  const requestId = response.headers.get("X-Request-Id");
  if (!response.ok) {
    const failure = await response.json().catch(() => ({}));
    throw new ApiFailure(
      failure.message ?? "다운로드에 실패했습니다.",
      response.status,
      requestId,
      failure.field ?? null,
      failure.code ?? null,
    );
  }
  if (!response.headers.get("Content-Type")?.includes(XLSX))
    throw new ApiFailure(
      "엑셀 응답을 확인할 수 없습니다.",
      response.status,
      requestId,
    );
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(
    response.headers.get("Content-Disposition") ?? "",
  )?.[1];
  let filename: string;
  try {
    filename = decodeURIComponent(encoded ?? "");
  } catch {
    throw new ApiFailure(
      "파일명을 확인할 수 없습니다.",
      response.status,
      requestId,
    );
  }
  if (!filename || /[\\/\r\n\0]/.test(filename))
    throw new ApiFailure(
      "파일명을 확인할 수 없습니다.",
      response.status,
      requestId,
    );
  return { blob: await response.blob(), filename };
}
export const downloadCostPeople = (filters: CostPeopleFilters) =>
  download(`/api/costs/people?${costQuery(filters, true)}`);
export const downloadCostProjects = (filters: CostProjectFilters) =>
  download(`/api/costs/projects?${costQuery(filters, true)}`);
export const downloadPartSettlements = (month: string) =>
  download(`/api/settlements/parts?${costQuery({ month })}`);
export const downloadCostRenegotiations = (filters: CostRenegotiationFilters) =>
  download(`/api/costs/renegotiations?${costQuery(filters, true)}`);
