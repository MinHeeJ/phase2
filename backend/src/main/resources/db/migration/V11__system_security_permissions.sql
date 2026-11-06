-- Existing operator-edited roles and grants are never reset.
INSERT INTO sys.role(role_id,role_nm,role_desc,data_scope_cd,snstv_inq_yn,part_std_chg_auth_yn,admin_yn,bltin_yn,sort_seq)
VALUES (1,'시스템 관리자','시스템 관리와 명시적으로 부여된 업무 권한','ALL','Y','Y','Y','Y',1),
       (2,'경영진','조회와 허용 다운로드','ALL','Y','N','N','Y',2),
       (3,'재무 담당','재무 입력과 마감','ALL','Y','Y','N','Y',3),
       (4,'영업 파트장','담당 파트 운영과 안전 집계','PART','N','N','N','Y',4),
       (5,'일반 사용자(PM)','담당 프로젝트 운영','PROJECT','N','N','N','Y',5)
ON CONFLICT DO NOTHING;

-- Fill missing baseline rows only. A later newly created menu defaults to seven N.
WITH defaults(role_id,screens,actions) AS (
  VALUES
    (1,ARRAY['UI-01','UI-02','UI-03','UI-04','UI-05','UI-07','UI-08','UI-09','UI-10','UI-11','UI-12','UI-13','UI-14','UI-15','UI-16','UI-17','UI-18','UI-19','UI-20','UI-21','UI-23','UI-24','UI-25','UI-26','UI-27','UI-28','UI-29','UI-30','UI-31','UI-32','UI-33'],'YYYYYYY'),
    (2,ARRAY['UI-01','UI-02','UI-03','UI-04','UI-05','UI-07','UI-08','UI-09','UI-10','UI-11','UI-12','UI-13','UI-14','UI-15','UI-16','UI-17','UI-18','UI-19','UI-20','UI-21','UI-26','UI-28','UI-29','UI-30','UI-31','UI-32','UI-33'],'YNNNNYN'),
    (3,ARRAY['UI-03','UI-04','UI-07','UI-08','UI-09','UI-10','UI-13','UI-14','UI-18','UI-19','UI-20','UI-21'],'YYYYYYY'),
    (3,ARRAY['UI-01','UI-02','UI-05','UI-11','UI-12','UI-15','UI-16','UI-17','UI-26','UI-28','UI-29','UI-30','UI-31'],'YNNNNYN'),
    (4,ARRAY['UI-01','UI-02','UI-05','UI-11','UI-12','UI-15','UI-17','UI-28','UI-29','UI-30','UI-31','UI-33'],'YYYNNYN'),
    (4,ARRAY['UI-16','UI-26'],'YNNNNYN'),
    (5,ARRAY['UI-01','UI-05','UI-11','UI-12','UI-29','UI-31'],'YYYNNNN'),
    (5,ARRAY['UI-02','UI-16','UI-17','UI-26','UI-28','UI-30'],'YNNNNNN')
)
INSERT INTO sys.role_menu_auth(role_id,menu_id,inq_auth_yn,reg_auth_yn,mdfcn_auth_yn,del_auth_yn,upld_auth_yn,dwnld_auth_yn,cnfrm_auth_yn)
SELECT r.role_id,m.menu_id,
       substring(COALESCE(d.actions,'NNNNNNN'),1,1),substring(COALESCE(d.actions,'NNNNNNN'),2,1),
       substring(COALESCE(d.actions,'NNNNNNN'),3,1),substring(COALESCE(d.actions,'NNNNNNN'),4,1),
       substring(COALESCE(d.actions,'NNNNNNN'),5,1),substring(COALESCE(d.actions,'NNNNNNN'),6,1),
       substring(COALESCE(d.actions,'NNNNNNN'),7,1)
FROM sys.role r CROSS JOIN sys.menu m
LEFT JOIN defaults d ON d.role_id=r.role_id AND m.scrn_id=ANY(d.screens)
WHERE m.scrn_id IS NOT NULL
ON CONFLICT(role_id,menu_id) DO NOTHING;

-- These columns are new in V10, so this maps only the approved contact purposes.
-- Ordinary role/matrix editing must not change these independent flags.
UPDATE sys.role_menu_auth a SET contact_inq_auth_yn='Y'
FROM sys.menu m WHERE m.menu_id=a.menu_id AND a.updated_at IS NULL AND
 ((a.role_id IN (4,5) AND m.scrn_id='UI-02') OR
  (a.role_id IN (3,4) AND m.scrn_id='UI-15'));

INSERT INTO cmn.oprt_std(oprt_std_id,aply_bgn_ym,oprt_std_nm,txt_val,use_plc_desc,oprt_std_desc)
SELECT 'NOTIFICATION_EMAIL_ENABLED',to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul','YYYY-MM'),
       '업무 알림 메일','N','알림 메일 발송','업무 메일만 선택하며 MFA/RESET 필수 메일에는 적용하지 않는다.'
WHERE NOT EXISTS(SELECT 1 FROM cmn.oprt_std WHERE oprt_std_id='NOTIFICATION_EMAIL_ENABLED')
ON CONFLICT(oprt_std_id,aply_bgn_ym) DO NOTHING;

SELECT setval('sys.seq_role_id',GREATEST((SELECT last_value FROM sys.seq_role_id),(SELECT max(role_id) FROM sys.role)));
