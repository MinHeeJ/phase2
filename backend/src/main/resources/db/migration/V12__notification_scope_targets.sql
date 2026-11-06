-- Normalized notification targets prevent trusting arbitrary historical link strings.
ALTER TABLE sys.ntf ADD COLUMN IF NOT EXISTS target_prj_id BIGINT REFERENCES prj.prj(prj_id);
ALTER TABLE sys.ntf ADD COLUMN IF NOT EXISTS target_part_id BIGINT REFERENCES mst.bsn_part(bsn_part_id);
ALTER TABLE sys.ntf ADD COLUMN IF NOT EXISTS target_rec_id BIGINT;
COMMENT ON COLUMN sys.ntf.target_rec_id IS '알림 서비스가 발생 시 기록한 화면별 업무 원천 PK 참조 의도 (FK 미선언)';
COMMENT ON COLUMN sys.ntf.target_prj_id IS '알림 서비스가 업무 사건 생성 시 기록하는 조회 범위 프로젝트. 기존 link 문자열에서 추측하지 않는다.';
COMMENT ON COLUMN sys.ntf.target_part_id IS '알림 서비스가 업무 사건 생성 시 기록하는 조회 범위 파트. 기존 link 문자열에서 추측하지 않는다.';
CREATE INDEX IF NOT EXISTS ix_ntf_scope_target ON sys.ntf(user_id,target_prj_id,target_part_id);
