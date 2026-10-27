ALTER TABLE asgn.asgn_chg_hist
  DROP CONSTRAINT ck_asgn_chg_hist_asgn_chg_ty_cd_vld;
ALTER TABLE asgn.asgn_chg_hist
  ADD CONSTRAINT ck_asgn_chg_hist_asgn_chg_ty_cd_vld
    CHECK (asgn_chg_ty_cd IN ('START','EXTEND','EARLY_END','RATE','EXT_CHANCE','PERIOD','REPLACE','FIX'));

ALTER TABLE asgn.asgn_chg_hist
  ADD COLUMN rsn_cd VARCHAR(30) NULL,
  ADD COLUMN retro_yn CHAR(1) NOT NULL DEFAULT 'N',
  ADD COLUMN fix_trgt_chg_id BIGINT NULL,
  ADD COLUMN fixed_yn CHAR(1) NOT NULL DEFAULT 'N',
  ADD COLUMN chg_user_id BIGINT NULL,
  ADD COLUMN chg_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ADD CONSTRAINT fk_asgn_chg_hist_fix_trgt
    FOREIGN KEY (fix_trgt_chg_id) REFERENCES asgn.asgn_chg_hist (asgn_chg_id),
  ADD CONSTRAINT ck_asgn_chg_hist_retro_yn_vld CHECK (retro_yn IN ('Y','N')),
  ADD CONSTRAINT ck_asgn_chg_hist_fixed_yn_vld CHECK (fixed_yn IN ('Y','N'));

COMMENT ON TABLE asgn.asgn_chg_hist IS '투입 등록·변경·교체·정정의 불변 이력. 정정 시 대상의 fixed_yn만 변경한다.';
COMMENT ON COLUMN asgn.asgn_chg_hist.asgn_chg_ty_cd IS 'START:투입|EXTEND:연장|EARLY_END:조기 철수|RATE:단가 변경|EXT_CHANCE:연장 가능성 변경|PERIOD:기간·투입 구분 변경|REPLACE:교체|FIX:정정';
COMMENT ON COLUMN asgn.asgn_chg_hist.rsn_cd IS 'cmn.cd_dtl의 ASGN_CHG_RSN 그룹 변경 사유';
COMMENT ON COLUMN asgn.asgn_chg_hist.retro_yn IS 'Y:소급 입력|N:일반 입력';
COMMENT ON COLUMN asgn.asgn_chg_hist.fix_trgt_chg_id IS '정정 대상 투입 변경 이력';
COMMENT ON COLUMN asgn.asgn_chg_hist.fixed_yn IS 'Y:정정됨|N:미정정';
COMMENT ON COLUMN asgn.asgn_chg_hist.chg_user_id IS 'sys.user_info.user_id 참조 의도 (FK 미선언)';
COMMENT ON COLUMN asgn.asgn_chg_hist.chg_at IS '변경 기록 입력 시각';

CREATE INDEX IF NOT EXISTS ix_asgn_chg_hist_asgn_id_aply_dt
  ON asgn.asgn_chg_hist (asgn_id, aply_dt);

INSERT INTO cmn.cd_grp (cd_grp_id, cd_grp_nm, cd_grp_se_cd, sort_seq, use_yn)
VALUES ('ASGN_CHG_RSN', '투입 변경 사유', 'BIZ', 295, 'Y')
ON CONFLICT (cd_grp_id) DO NOTHING;

INSERT INTO cmn.cd_dtl (cd_grp_id, dtl_cd, cd_nm, sort_seq, use_yn)
VALUES
  ('ASGN_CHG_RSN', 'CUST', '고객 요청', 1, 'Y'),
  ('ASGN_CHG_RSN', 'SCHED', '프로젝트 일정 변경', 2, 'Y'),
  ('ASGN_CHG_RSN', 'PRICE', '단가 재협상', 3, 'Y'),
  ('ASGN_CHG_RSN', 'PERSON', '인력 사정', 4, 'Y'),
  ('ASGN_CHG_RSN', 'FIXERR', '입력 오류 바로잡기', 5, 'Y'),
  ('ASGN_CHG_RSN', 'ETC', '기타', 9, 'Y')
ON CONFLICT (cd_grp_id, dtl_cd) DO NOTHING;
