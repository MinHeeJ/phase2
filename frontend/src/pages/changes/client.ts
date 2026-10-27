import { api, readData } from "../../shared/api";

export const changeNames = {
  START: "투입",
  EXTEND: "연장",
  EARLY_END: "조기 철수",
  RATE: "단가 변경",
  EXT_CHANCE: "연장 가능성 변경",
  PERIOD: "기간·투입 구분 변경",
  REPLACE: "교체",
  FIX: "정정",
} as const;
export type ChangeType = keyof typeof changeNames;
export type EditableChangeType = Exclude<
  ChangeType,
  "START" | "REPLACE" | "FIX"
>;
export interface AssignmentChange {
  asgn_chg_id: number;
  asgn_id: number;
  asgn_chg_ty_cd: ChangeType;
  aply_dt: string;
  bfr_val: string | null;
  aft_val: string;
  new_asgn_id: number | null;
  rsn_cd: string | null;
  rsn_nm: string | null;
  rsn_desc: string | null;
  retro_yn: "Y" | "N";
  fixed_yn: "Y" | "N";
  fix_trgt_chg_id: number | null;
  fix_trgt_aply_dt: string | null;
  fix_trgt_chg_ty_cd: ChangeType | null;
  chg_user_id: number | null;
  chg_user_nm: string | null;
  chg_at: string;
}
export interface ChangeAssignment {
  asgn_id: number;
  updated_at: string;
  bgn_dt: string;
  end_dt: string;
  asgn_se_cd: string;
  ext_psblty_cd: string | null;
  cntrct_dev_id: number | null;
  rplc_asgn_id: number | null;
  asgn_stts_cd: string;
  dev_nm?: string;
  prj_nm?: string;
  rates?: { aply_bgn_dt: string; bill_untprc_amt: number | null }[];
  warnings?: { code: string; message: string; work_ym?: string | null }[];
}
export interface ChangeContext {
  assignment: ChangeAssignment;
  contractType: string;
  readOnly?: boolean;
  projectWritable?: boolean;
}
interface CommonChange {
  updated_at: string;
  aply_dt: string;
  rsn_cd?: string | null;
  rsn_desc?: string | null;
}
export type ChangeRequest = CommonChange &
  (
    | { asgn_chg_ty_cd: "EXTEND"; end_dt: string }
    | { asgn_chg_ty_cd: "EARLY_END" }
    | { asgn_chg_ty_cd: "RATE"; work_ym: string; bill_untprc_amt: number }
    | { asgn_chg_ty_cd: "EXT_CHANCE"; ext_psblty_cd: string | null }
    | {
        asgn_chg_ty_cd: "PERIOD";
        asgn_se_cd: string;
        bgn_dt?: string;
        end_dt?: string;
        cntrct_dev_id?: number | null;
      }
  );
export interface FixRequest {
  updated_at: string;
  aft_val: string;
  rsn_cd: string;
  rsn_desc?: string | null;
}
export interface SaveResult {
  assignment: ChangeAssignment;
  change: AssignmentChange;
}
export interface FixResult {
  assignment: ChangeAssignment;
  fix: AssignmentChange;
  target: AssignmentChange;
}
const path = (id: number) =>
  `/api/assignments/${encodeURIComponent(id)}/changes`;
export const listAssignmentChanges = (id: number, type?: ChangeType) =>
  readData<{ items: AssignmentChange[]; total: number }>(
    `${path(id)}${type ? `?type=${type}` : ""}`,
  );
export const createAssignmentChange = async (
  id: number,
  input: ChangeRequest,
) =>
  (
    await api<SaveResult>(path(id), {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data!;
export const createAssignmentFix = async (
  id: number,
  chgId: number,
  input: FixRequest,
) =>
  (
    await api<FixResult>(`${path(id)}/${encodeURIComponent(chgId)}/fix`, {
      method: "POST",
      body: JSON.stringify(input),
    })
  ).data!;
