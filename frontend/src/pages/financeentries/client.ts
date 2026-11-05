import { api } from "../../shared/api";
import type { FinanceEntryInput } from "../entries/client";

export interface FinanceEntriesPreview {
  row: number;
  severity: string;
  entry: FinanceEntryInput | null;
}
export interface FinanceEntriesUploadResult {
  upload_id: string;
  error_count: number;
  warning_count: number;
  items: {
    field: string;
    message: string;
    row: number;
    severity: string;
    raw_value?: string | null;
  }[];
  row_count: number;
  can_apply: boolean;
  applied_count: number;
  overwrite_count: number;
  rows: FinanceEntriesPreview[];
  overwrite_notice: string;
}
export async function validateFinanceEntriesUpload(
  file: File,
): Promise<FinanceEntriesUploadResult> {
  const body = new FormData();
  body.append("file", file);
  return (
    await api<FinanceEntriesUploadResult>(
      "/api/uploads/finance-entries/validate",
      {
        method: "POST",
        body,
      },
    )
  ).data;
}
export const applyFinanceEntriesUpload = async (
  upload_id: string,
  confirm: boolean,
) =>
  (
    await api<FinanceEntriesUploadResult>(
      "/api/uploads/finance-entries/apply",
      {
        method: "POST",
        body: JSON.stringify({ upload_id, confirm }),
      },
    )
  ).data!;
