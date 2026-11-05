ALTER TABLE sys.ntf ADD COLUMN IF NOT EXISTS prj_id BIGINT REFERENCES prj.prj(prj_id),
    ADD COLUMN IF NOT EXISTS bsn_part_id BIGINT REFERENCES mst.bsn_part(bsn_part_id);
COMMENT ON COLUMN sys.ntf.prj_id IS '업무 사건 생성 서비스가 원천 프로젝트를 기록; 현재 권한 조건에서 사용하며 과거 불명확 대상은 자동 추정하지 않음';
COMMENT ON COLUMN sys.ntf.bsn_part_id IS '업무 사건 생성 서비스가 원천 담당 파트를 기록; 현재 권한 조건에서 사용하며 과거 불명확 대상은 자동 추정하지 않음';
CREATE INDEX IF NOT EXISTS ix_ntf_project ON sys.ntf(prj_id,user_id);
CREATE INDEX IF NOT EXISTS ix_ntf_part ON sys.ntf(bsn_part_id,user_id);
