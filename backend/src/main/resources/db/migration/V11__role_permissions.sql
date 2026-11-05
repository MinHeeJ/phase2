-- Insert only missing built-in roles; never reset an existing role or permission.
INSERT INTO sys.role(role_id,role_nm,data_scope_cd,snstv_inq_yn,part_std_chg_auth_yn,admin_yn,bltin_yn,sort_seq)
VALUES (1,'시스템 관리자','ALL','Y','Y','Y','Y',1),
       (2,'경영진','ALL','Y','N','N','Y',2),
       (3,'재무 담당','ALL','Y','Y','N','Y',3),
       (4,'영업 파트장','PART','N','N','N','Y',4),
       (5,'일반 사용자(PM)','PROJECT','N','N','N','Y',5)
ON CONFLICT DO NOTHING;

INSERT INTO sys.role_menu_auth(role_id,menu_id,inq_auth_yn,reg_auth_yn,mdfcn_auth_yn,del_auth_yn,upld_auth_yn,dwnld_auth_yn,cnfrm_auth_yn)
SELECT r.role_id,m.menu_id,
       substr(p.actions,1,1),substr(p.actions,2,1),substr(p.actions,3,1),substr(p.actions,4,1),
       substr(p.actions,5,1),substr(p.actions,6,1),substr(p.actions,7,1)
FROM sys.role r CROSS JOIN sys.menu m
CROSS JOIN LATERAL (SELECT CASE
    WHEN r.role_id=1 AND r.bltin_yn='Y' THEN 'YYYYYYY'
    WHEN r.role_id=2 AND r.bltin_yn='Y' AND m.scrn_id NOT IN ('UI-23','UI-24','UI-25','UI-27') THEN 'YNNNNYN'
    WHEN r.role_id=3 AND r.bltin_yn='Y' AND m.scrn_id IN ('UI-03','UI-04','UI-07','UI-08','UI-09','UI-10','UI-13','UI-14','UI-18','UI-19','UI-20','UI-21') THEN 'YYYYYYY'
    WHEN r.role_id=3 AND r.bltin_yn='Y' AND m.scrn_id IN ('UI-01','UI-02','UI-15','UI-16','UI-26','UI-30','UI-31') THEN 'YNNNNYN'
    WHEN r.role_id=4 AND r.bltin_yn='Y' AND m.scrn_id IN ('UI-01','UI-02','UI-05','UI-11','UI-12','UI-15','UI-16','UI-17','UI-26','UI-28','UI-29','UI-30','UI-31','UI-33') THEN 'YYYNNYN'
    WHEN r.role_id=5 AND r.bltin_yn='Y' AND m.scrn_id IN ('UI-01','UI-02','UI-05','UI-11','UI-12','UI-16','UI-17','UI-26','UI-28','UI-29','UI-30','UI-31','UI-33') THEN 'YYYNNNN'
    ELSE 'NNNNNNN' END AS actions) p
WHERE m.scrn_id IS NOT NULL
ON CONFLICT(role_id,menu_id) DO NOTHING;

-- D16's explicit contact policy is independent of the preserved seven-action matrix.
UPDATE sys.role_menu_auth a SET contact_inq_auth_yn='Y'
FROM sys.role r,sys.menu m
WHERE r.role_id=a.role_id AND m.menu_id=a.menu_id AND r.bltin_yn='Y'
AND ((r.role_id=4 AND m.scrn_id IN ('UI-02','UI-15'))
  OR (r.role_id=5 AND m.scrn_id='UI-02')
  OR (r.role_id=3 AND m.scrn_id='UI-15'));
SELECT setval('sys.seq_role_id',GREATEST((SELECT last_value FROM sys.seq_role_id),(SELECT max(role_id) FROM sys.role)));
