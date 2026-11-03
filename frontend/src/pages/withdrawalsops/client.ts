import { api } from "../../shared/api";

export interface NextSlot {
  need_id: number;
  oppt_id: number;
  oppt_nm: string;
  job_cd: string;
  grd_cd: string | null;
  bgn_ym: string;
  end_ym: string;
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
export interface NextTemporaryAssignment {
  id: number;
  need_id: number;
  dev_id: number;
  bgn_ym: string;
  end_ym: string;
  mth_mm_qty: number;
  tmpr_asgn_src_cd: "NEXT" | "MANUAL";
  mtch_scr: number | null;
  status: "ACTIVE" | "RELEASED" | "CONVERTED";
  asgn_id: number | null;
  warnings: string[];
}
export interface NextSlotsResult {
  items: NextSlot[];
  recommendation_count: number;
  without_next_assignment_count: number;
  with_valid_slots_count: number;
  next_temp_assignments: NextTemporaryAssignment[];
}
export async function getWithdrawalNextSlots(asgnId: number) {
  return (await api<NextSlotsResult>(`/api/withdrawals/${asgnId}/next-slots`))
    .data!;
}
export async function createNextTemporaryAssignment(input: {
  need_id: number;
  dev_id: number;
  bgn_ym: string;
  end_ym: string;
  mth_mm_qty: number;
  tmpr_asgn_src_cd: "NEXT";
}) {
  return (
    await api<NextTemporaryAssignment>("/api/temp-assignments", {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data!;
}
export async function releaseNextTemporaryAssignment(id: number) {
  return api<NextTemporaryAssignment>(`/api/temp-assignments/${id}`, {
    method: "DELETE",
  });
}
