ALTER TABLE bsn.oppt ADD COLUMN IF NOT EXISTS zero_cost_evidence TEXT NULL;
ALTER TABLE bsn.oppt ADD COLUMN IF NOT EXISTS zero_cost_validation TEXT NULL;
ALTER TABLE bsn.oppt ADD COLUMN IF NOT EXISTS amount_difference_reason TEXT NULL;
COMMENT ON COLUMN bsn.oppt.zero_cost_evidence IS '실제 cst.mth_cst 원천을 식별하는 영 원가 증빙. 업로드 경로나 파일이 아니며 문자열만으로 검증되지 않는다.';
COMMENT ON COLUMN bsn.oppt.zero_cost_validation IS '기회 승인 서비스가 별도 승인 시 검증한 원천·대상 인력·노무비·경비·현재 견적의 스냅샷. 원천·필요 인력·견적 변경 시 서비스가 무효화한다.';
COMMENT ON COLUMN bsn.oppt.amount_difference_reason IS '영업 예상금액과 필요 인력 견적매출이 다른 경우 사용자가 입력한 사유.';

ALTER TABLE bsn.oppt_need ADD COLUMN IF NOT EXISTS use_yn CHAR(1) NOT NULL DEFAULT 'Y';
ALTER TABLE bsn.oppt_need ADD CONSTRAINT ck_oppt_need_use_yn_vld CHECK (use_yn IN ('Y','N'));
COMMENT ON COLUMN bsn.oppt_need.use_yn IS 'Y:사용|N:미사용';

-- Referenced needs are retained as inactive rows; temporary-assignment history must not cascade away.
ALTER TABLE asgn.tmpr_asgn DROP CONSTRAINT IF EXISTS fk_tmpr_asgn_need;
ALTER TABLE asgn.tmpr_asgn ADD CONSTRAINT fk_tmpr_asgn_need
  FOREIGN KEY (need_id) REFERENCES bsn.oppt_need (need_id) ON DELETE RESTRICT;

ALTER TABLE prj.psn_plan DROP CONSTRAINT IF EXISTS uk_psn_plan_prj_id_plan_ym_job_cd_grd_cd;
CREATE UNIQUE INDEX IF NOT EXISTS uk_psn_plan_manual_bucket
  ON prj.psn_plan (prj_id, plan_ym, job_cd, grd_cd) WHERE src_need_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_psn_plan_source_need
  ON prj.psn_plan (prj_id, plan_ym, src_need_id) WHERE src_need_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_prj_oppt_id
  ON prj.prj (oppt_id) WHERE oppt_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_tmpr_asgn_active_need_dev_period
  ON asgn.tmpr_asgn (need_id, dev_id, bgn_ym, end_ym) WHERE tmpr_asgn_stts_cd = 'ACTIVE';

INSERT INTO cmn.oprt_std (oprt_std_id, aply_bgn_ym, oprt_std_nm, num_val, unit_nm, use_plc_desc)
SELECT seed.id, '2000-01', seed.name, seed.value, seed.unit, 'UI-29 견적 점검'
FROM (VALUES
  ('QUOTE_APPROVAL_RATE', '견적 승인 기준 이익률', 15.00, '%'),
  ('QUOTE_TARGET_MARGIN', '견적 목표 이익률', 20.00, '%'),
  ('QUOTE_APPROVAL_TOLERANCE', '견적 재승인 허용 이익률 차이', 0.50, '%p')
) AS seed(id, name, value, unit)
WHERE NOT EXISTS (SELECT 1 FROM cmn.oprt_std existing WHERE existing.oprt_std_id = seed.id)
ON CONFLICT (oprt_std_id, aply_bgn_ym) DO NOTHING;
