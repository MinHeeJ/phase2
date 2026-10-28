import { ApiFailure, readData } from "./api";

export async function getMmGapTolerance(): Promise<number> {
  const rows =
    await readData<{ oprt_std_id: string; num_val: number | null }[]>(
      "/api/params",
    );
  const value = rows.find(
    (row) => row.oprt_std_id === "PLAN_GAP_TOLERANCE",
  )?.num_val;
  if (value == null || !Number.isFinite(value) || value < 0)
    throw new ApiFailure(
      "계획 대비 투입 기준값을 확인하세요.",
      null,
      null,
      "month",
    );
  return value;
}
