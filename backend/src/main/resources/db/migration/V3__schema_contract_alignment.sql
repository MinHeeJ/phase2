CREATE OR REPLACE FUNCTION cmn.fn_set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.updated_at IS NOT DISTINCT FROM OLD.updated_at THEN
    NEW.updated_at := CURRENT_TIMESTAMP;
  END IF;
  RETURN NEW;
END;
$$;

COMMENT ON COLUMN cmn.cd_hist.cd_grp_id IS 'cmn.cd_grp.cd_grp_id 또는 PARAM 다형 식별 참조 의도 (FK 미선언)';
COMMENT ON COLUMN cmn.cd_hist.dtl_cd IS 'cmn.cd_dtl.dtl_cd 또는 cmn.oprt_std.oprt_std_id 다형 식별 참조 의도; 그룹 추가는 NULL (FK 미선언)';
COMMENT ON COLUMN cmn.cd_hist.cd_chg_ty_cd IS 'ADD:추가|UPDATE:수정|DISABLE:사용중지|ENABLE:다시사용';
COMMENT ON COLUMN cmn.excel_map.map_role_cd IS 'MAIN:주|SUB:보조';
COMMENT ON COLUMN mst.dev_work_stts_hist.work_stts_cd IS 'VACATION:휴가|PARENTAL:육아휴직|UNPAID:무급휴가|LEAVE:휴직|RESIGN:퇴사';
COMMENT ON COLUMN hr.cand_cntct_hist.cntct_stts_cd IS 'NONE:연락전|DONE:연락함|INTV:면접예정|HOLD:보류|SIGNED:계약완료';

DO $$
DECLARE r RECORD;
DECLARE description TEXT;
BEGIN
  FOR r IN
    SELECT c.oid, n.nspname, c.relname
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname IN ('cmn','sys','mst','bsn','prj','asgn','hr','cst','bill','fin','ai')
      AND c.relkind = 'r'
  LOOP
    IF (r.nspname || '.' || r.relname) NOT IN
       ('cmn.cd_grp','cmn.cd_dtl','cmn.cd_hist','cmn.excel_map','cmn.oprt_std','sys.menu','fin.mth_cls') THEN
      description := COALESCE(obj_description(r.oid), r.relname);
      EXECUTE format('COMMENT ON TABLE %I.%I IS %L', r.nspname, r.relname,
        description || '. 현재 미사용 — 추후 업무 연동 예정; 이번 설치에서는 구조와 원본 시드만 보존.');
    END IF;
  END LOOP;
  FOR r IN
    SELECT a.attname, a.attgenerated, c.oid, n.nspname, c.relname, a.attnum,
           pg_get_expr(d.adbin, d.adrelid) AS generation_expr
    FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
    WHERE n.nspname IN ('cmn','sys','mst','bsn','prj','asgn','hr','cst','bill','fin','ai')
      AND c.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped
  LOOP
    description := COALESCE(col_description(r.oid, r.attnum), r.attname);
    IF r.attname = 'use_yn' THEN
      EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L', r.nspname,r.relname,r.attname,'Y:사용|N:사용안함');
    ELSIF r.attname = 'updated_at' THEN
      EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L', r.nspname,r.relname,r.attname,
        '애플리케이션 변경 서비스가 UPDATE 때 명시 갱신; 기존 updated_at 트리거가 있는 테이블은 값 미변경 시 DB 시각 fallback');
    ELSIF r.attgenerated <> '' THEN
      EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L', r.nspname,r.relname,r.attname,
        description || '. PostgreSQL이 원천 행 INSERT/UPDATE 때 생성식으로 갱신: ' || r.generation_expr);
    ELSIF (r.attname LIKE '%\_id' ESCAPE '\' OR r.attname LIKE '%\_by' ESCAPE '\')
      AND NOT EXISTS (
        SELECT 1 FROM pg_constraint k WHERE k.conrelid = r.oid
          AND k.contype IN ('p','f') AND r.attnum = ANY(k.conkey)
      ) AND NOT (r.nspname = 'cmn' AND r.relname = 'cd_hist' AND r.attname = 'cd_grp_id') THEN
      IF r.attname LIKE '%\_by' ESCAPE '\' OR r.attname ~ '(user|ldr|chrg|pm)_id$' THEN
        description := description || '; sys.user_info.user_id 참조 의도 (FK 미선언), 현재 작성자 NULL';
      ELSE
        description := description || '; 원본 업무 식별자 참조 의도 (FK 미선언); 원본 설명의 대상과 의미 보존';
      END IF;
      EXECUTE format('COMMENT ON COLUMN %I.%I.%I IS %L',r.nspname,r.relname,r.attname,description);
    END IF;
  END LOOP;
END;
$$;
