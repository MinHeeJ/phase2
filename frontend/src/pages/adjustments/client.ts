import { api, readData } from "../../shared/api";

export type AdjustmentType = "REVENUE" | "COST" | "SGA" | "ETC";
export interface AdjustmentRow {
  id: number;
  originalMonth: string;
  applyMonth: string;
  type: AdjustmentType;
  amount: number;
  reason: string;
  status: "ACTIVE" | "CANCELLED";
  createdAt?: string;
  cancelledAt?: string | null;
  originalAdjustmentId?: number | null;
}
export interface AdjustmentInput {
  originalMonth: string;
  applyMonth: string;
  type: AdjustmentType;
  amount: number;
  reason: string;
  projectId?: number;
  developerId?: number;
  originalAdjustmentId?: number;
}
export interface AdjustmentPage {
  items: AdjustmentRow[];
  page: number;
  size: number;
  total: number;
}
export interface AdjustmentFilters {
  year: number;
  month?: string;
  page: number;
  size: number;
}
export function listClosingAdjustments(filters: AdjustmentFilters) {
  const query = new URLSearchParams({
    year: String(filters.year),
    page: String(filters.page),
    size: String(filters.size),
  });
  if (filters.month) query.set("month", filters.month);
  return readData<AdjustmentPage>(`/api/closing/adjustments?${query}`);
}
export async function createClosingAdjustment(input: AdjustmentInput) {
  return (
    await api<AdjustmentRow>("/api/closing/adjustments", {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data;
}
export async function cancelClosingAdjustment(id: number, reason: string) {
  return (
    await api<AdjustmentRow>(`/api/closing/adjustments/${id}/cancel`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    })
  ).data;
}
