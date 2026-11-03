-- Preserve V1-V7 and all existing business rows. No forecast aggregate tables.
ALTER TABLE bsn.oppt
  ADD COLUMN IF NOT EXISTS amount_difference_reason TEXT,
  ADD COLUMN IF NOT EXISTS cost_status VARCHAR(12) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN IF NOT EXISTS zero_cost_evidence TEXT,
  ADD COLUMN IF NOT EXISTS approved_quote_amount BIGINT,
  ADD COLUMN IF NOT EXISTS approval_needs_snapshot JSONB;
ALTER TABLE bsn.oppt ADD CONSTRAINT ck_oppt_cost_status_vld
  CHECK (cost_status IN ('UNKNOWN','VERIFIED','PROVEN_ZERO'));
COMMENT ON COLUMN bsn.oppt.amount_difference_reason IS '영업 예상금액과 계산 견적매출이 다른 사유.';
COMMENT ON COLUMN bsn.oppt.cost_status IS 'UNKNOWN:원가미확인|VERIFIED:원가확인|PROVEN_ZERO:실제영원가승인';
COMMENT ON COLUMN bsn.oppt.zero_cost_evidence IS '원천 원가가 실제 0원임을 검토한 증빙 설명. 견적 승인 시 저장하고 원천·필요 인력 변경 시 무효화한다.';
COMMENT ON COLUMN bsn.oppt.approved_quote_amount IS 'OpportunityService 승인 시 갱신하는 승인 견적매출 스냅샷. 영업 예상금액과 별개이다.';
COMMENT ON COLUMN bsn.oppt.approval_needs_snapshot IS 'OpportunityService 견적 저장·승인 시 갱신하는 정규화 필요 인력 스냅샷.';

-- Manual plans retain the original identity; source-linked plans retain each need.
ALTER TABLE prj.psn_plan DROP CONSTRAINT IF EXISTS uk_psn_plan_prj_id_plan_ym_job_cd_grd_cd;
CREATE UNIQUE INDEX IF NOT EXISTS uk_psn_plan_manual_identity
  ON prj.psn_plan (prj_id, plan_ym, job_cd, grd_cd) WHERE src_need_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_psn_plan_source_identity
  ON prj.psn_plan (prj_id, plan_ym, src_need_id) WHERE src_need_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_prj_source_opportunity
  ON prj.prj (oppt_id) WHERE oppt_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_tmpr_asgn_active_overlap
  ON asgn.tmpr_asgn (need_id, dev_id, bgn_ym, end_ym) WHERE tmpr_asgn_stts_cd = 'ACTIVE';
CREATE INDEX IF NOT EXISTS ix_cand_cntct_hist_chronology
  ON hr.cand_cntct_hist (cand_id, cntct_dt DESC, created_at DESC, cntct_hist_id DESC);
CREATE INDEX IF NOT EXISTS ix_cand_active_job_availability
  ON hr.cand (job_cd, asgn_psbl_dt, cntct_stts_cd) WHERE use_yn = 'Y';

-- Do not overwrite an operator's existing value or introduce an earlier override.
INSERT INTO cmn.oprt_std (oprt_std_id, aply_bgn_ym, oprt_std_nm, num_val, unit_nm, use_plc_desc)
SELECT v.id, '2000-01', v.name, v.value, v.unit, 'UI-29 견적 점검'
FROM (VALUES
  ('QUOTE_APPROVAL_RATE', '견적 승인 기준 이익률', 15.00, '%'),
  ('QUOTE_TARGET_MARGIN', '견적 목표 이익률', 20.00, '%'),
  ('QUOTE_APPROVAL_TOLERANCE', '견적 재승인 허용 변동', 0.50, '%p')
) AS v(id, name, value, unit)
WHERE NOT EXISTS (SELECT 1 FROM cmn.oprt_std s WHERE s.oprt_std_id = v.id);
