import { api } from "../../shared/api";

export interface BusinessPlanPreview {
  row: number;
  severity: string;
  plan: {
    month: string;
    partId: number;
    revenue: number;
    profit: number;
    utilization: number;
  } | null;
}
export interface BusinessPlanUploadResult {
  upload_id: string;
  error_count: number;
  items: { field: string; message: string; row: number; severity: string }[];
  row_count: number;
  can_apply: boolean;
  applied_count: number;
  rows: BusinessPlanPreview[];
  overwrite_notice: string;
}
export async function validateBusinessPlanUpload(
  file: File,
): Promise<BusinessPlanUploadResult> {
  const body = new FormData();
  body.append("file", file);
  return (
    await api<BusinessPlanUploadResult>("/api/uploads/business-plan/validate", {
      method: "POST",
      body,
    })
  ).data;
}
export const applyBusinessPlanUpload = async (
  upload_id: string,
  confirm: boolean,
) =>
  (
    await api<BusinessPlanUploadResult>("/api/uploads/business-plan/apply", {
      method: "POST",
      body: JSON.stringify({ upload_id, confirm }),
    })
  ).data!;
