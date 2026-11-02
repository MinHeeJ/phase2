import { api, readData } from "../../shared/api";
import type { ClosingCheck } from "../apiops/closingClient";

export interface ClosingCheckResult {
  month: string;
  status: "OPEN" | "CLOSED";
  checks: ClosingCheck[];
  checkedAt: string;
}

export interface ClosingPreview {
  month: string;
  profit: number;
  cost: number;
  tax: number;
  cash: number;
  warnings?: string[];
}
export interface ClosingResult {
  month: string;
  status: "OPEN" | "CLOSED";
  processedAt?: string;
  actor?: string;
}
export interface ClosingHistoryRow {
  month: string;
  action: "CLOSE" | "REOPEN";
  processedAt: string;
  actor?: string;
  reason?: string;
}
export interface ClosingHistoryPage {
  items: ClosingHistoryRow[];
  page: number;
  size: number;
  total: number;
}
export const previewClosing = (month: string) =>
  readData<ClosingPreview>(
    `/api/closing/preview?${new URLSearchParams({ month })}`,
  );
export const listClosingHistory = (year: number, page = 0, size = 20) =>
  readData<ClosingHistoryPage>(
    `/api/closing/history?${new URLSearchParams({ year: String(year), page: String(page), size: String(size) })}`,
  );
export const runClosingCheck = async (month: string) =>
  (
    await api<ClosingCheckResult>(
      `/api/closing/check?${new URLSearchParams({ month })}`,
      {
        method: "POST",
      },
    )
  ).data!;
export const confirmClosing = async (month: string, ackWarnings: boolean) =>
  (
    await api<ClosingResult>("/api/closing/confirm", {
      method: "POST",
      body: JSON.stringify({ month, ackWarnings }),
    })
  ).data!;
export const reopenClosing = async (month: string, reason: string) =>
  (
    await api<ClosingResult>("/api/closing/reopen", {
      method: "POST",
      body: JSON.stringify({ month, reason }),
    })
  ).data!;
