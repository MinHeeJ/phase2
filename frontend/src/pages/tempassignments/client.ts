import { api, readData } from "../../shared/api";

export interface TempAssignmentWrite {
  need_id: number;
  dev_id: number;
  bgn_ym: string;
  end_ym: string;
  mth_mm_qty: number;
  tmpr_asgn_src_cd: "NEXT" | "MANUAL";
}
export interface TempAssignment extends TempAssignmentWrite {
  id: number;
  mtch_scr: number | null;
  status: "ACTIVE" | "RELEASED" | "CONVERTED";
  asgn_id: number | null;
  warnings: string[];
}
export interface TempAssignmentPage {
  items: TempAssignment[];
  total: number;
  page: number;
  size: 20 | 50 | 100;
}
export interface TempAssignmentFilters {
  need_id?: number;
  dev_id?: number;
  oppt_id?: number;
  status?: TempAssignment["status"];
  from?: string;
  to?: string;
  sort?: string;
  page?: number;
  size?: 20 | 50 | 100;
}
export interface TempAssignmentNeed {
  need_id: number;
  bgn_ym: string;
  end_ym: string;
  mth_mm_qty: number;
  psn_cnt?: number;
  label: string;
}
export interface NextSlot {
  need_id: number;
  oppt_id: number;
  oppt_nm: string;
  job_cd: string;
  grd_cd?: string | null;
  bgn_ym: string;
  end_ym?: string;
  remaining_slots: number;
  mtch_scr: number;
  score_components: {
    job: number;
    grade: number;
    gap: number;
    customer: number;
    probability: number;
  };
  waiting_days: number;
  win_prblt_rt: number;
  warnings: string[];
}
export interface NextSlotsResult {
  items: NextSlot[];
  recommendation_count: number;
  without_next_assignment_count: number;
  with_valid_slots_count: number;
  next_temp_assignments: TempAssignment[];
}
export const TEMP_ASSIGNMENTS_CHANGED = "temp-assignments-changed";
export const listTempAssignments = (filters: TempAssignmentFilters) => {
  const query = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined) query.set(key, String(value));
  });
  return readData<TempAssignmentPage>(`/api/temp-assignments?${query}`);
};
export const createTempAssignment = async (input: TempAssignmentWrite) =>
  (
    await api<TempAssignment>("/api/temp-assignments", {
      method: "POST",
      body: JSON.stringify({
        need_id: input.need_id,
        dev_id: input.dev_id,
        bgn_ym: input.bgn_ym,
        end_ym: input.end_ym,
        mth_mm_qty: input.mth_mm_qty,
        tmpr_asgn_src_cd: input.tmpr_asgn_src_cd,
      }),
    })
  ).data!;
export const releaseTempAssignment = async (id: number) =>
  (
    await api<TempAssignment>(
      `/api/temp-assignments/${encodeURIComponent(id)}`,
      { method: "DELETE" },
    )
  ).data!;
export const getWithdrawalNextSlots = (asgnId: number) =>
  readData<NextSlotsResult>(
    `/api/withdrawals/${encodeURIComponent(asgnId)}/next-slots`,
  );
