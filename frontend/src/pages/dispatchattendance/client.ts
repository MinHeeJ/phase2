import { api, readData } from "../../shared/api";

export interface DispatchAttendanceInput {
  asgn_id: number;
  dev_id: number;
  work_day_qty?: number;
  rglr_tm_qty?: number;
  ovtm_tm_qty?: number;
  mth_work_day_qty?: number;
}
export interface DispatchAttendanceRow extends DispatchAttendanceInput {
  calculated_mm: number | null;
  assignment_mm: number | null;
  price_available: boolean;
  warnings: string[];
}
export interface DispatchAttendanceResult {
  month: string;
  project_id: number;
  items: DispatchAttendanceRow[];
  warnings: string[];
}
const path = (month: string, projectId: number) =>
  `/api/dispatch-attendance?${new URLSearchParams({ month, projectId: String(projectId) })}`;
export const getDispatchAttendance = (month: string, projectId: number) =>
  readData<DispatchAttendanceResult>(path(month, projectId));
export const saveDispatchAttendance = async (
  month: string,
  projectId: number,
  items: DispatchAttendanceInput[],
) =>
  (
    await api<DispatchAttendanceResult>(path(month, projectId), {
      method: "PUT",
      body: JSON.stringify({
        items: items.map((row) =>
          Object.fromEntries(
            (
              [
                "asgn_id",
                "dev_id",
                "work_day_qty",
                "rglr_tm_qty",
                "ovtm_tm_qty",
                "mth_work_day_qty",
              ] as const
            )
              .filter((key) => row[key] != null)
              .map((key) => [key, row[key]]),
          ),
        ),
      }),
    })
  ).data!;
