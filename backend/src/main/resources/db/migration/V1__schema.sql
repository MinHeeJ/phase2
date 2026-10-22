-- =====================================================================================
--  SI 프로젝트·원가 관리 시스템 — 전체 DB 스키마 (DB 명명규칙 표준 v1.1 적용판)
--  기준 문서 : 요구사항 명세서 v2 (2026-10-12), 화면 시안, DB 명명규칙 표준 v1.1
--  대상 DBMS : PostgreSQL 16 (프로파일 DBMS-PG-001: 비인용 소문자, 식별자 63바이트)
--  작성일    : 2026-10-15
--  명칭 상태 : PROPOSED — 승인된 표준 단어·약어사전이 없어 초안 사전(별도 CSV)으로 생성.
--              사전 승인 전에는 확정 명칭이 아님 (표준 2.3절·8.4절)
-- -------------------------------------------------------------------------------------
--  [명명 적용 요약]
--   · 업무 영역 = 스키마 (표준 5.1): cmn 공통 / sys 시스템 / mst 기준정보 / bsn 영업 / prj 프로젝트 /
--     asgn 투입 / hr 외부 인력 / cst 원가 / bill 청구·입금 / fin 재무·자금 / ai AI
--   · 테이블: 단수형 업무 개념, 이력은 _HIST
--   · 컬럼 도메인 접미어(5.2): _ID _NO _CD _NM _DESC _AMT _QTY _CNT _RT _DT _AT _YN _SEQ
--     + 신규 제안(승인 필요): _YM 연월, _YR 연도, _SCR 점수, _VAL 값, _ADDR 주소, _HASH 해시
--   · 공통 감사 컬럼 CREATED_AT/BY, UPDATED_AT/BY 는 공통 컬럼 예외 프로파일
--   · 제약·인덱스(5.3): PK_<TABLE>, FK_<TABLE>_<ROLE>, UK_<TABLE>_<KEY>, CK_<TABLE>_<COLUMN>_<RULE>,
--     IX_<TABLE>_<COLUMNS>, SEQ_<TABLE>_ID, VW_<ROLE>
--     CK 규칙 토큰: FMT 형식 / VLD 허용값 / RNG 범위 / PRD 기간 / REQ 필수 / ORD 선후
--   · 설계 문서상 정규형은 대문자, 실제 DDL 은 비인용 소문자 (PostgreSQL 프로파일)
--   · 업무 규칙·데이터 구조는 이전 판과 동일, 이름만 바뀜 (구→신 대응은 매핑 CSV)
-- =====================================================================================

SET client_encoding = 'UTF8';

-- 재실행용: 업무 스키마를 모두 지우고 다시 만든다 (주의: 데이터 삭제)

CREATE SCHEMA cmn;
CREATE SCHEMA sys;
CREATE SCHEMA mst;
CREATE SCHEMA bsn;
CREATE SCHEMA prj;
CREATE SCHEMA asgn;
CREATE SCHEMA hr;
CREATE SCHEMA cst;
CREATE SCHEMA bill;
CREATE SCHEMA fin;
CREATE SCHEMA ai;
COMMENT ON SCHEMA cmn IS '공통(코드·기준값)';
COMMENT ON SCHEMA sys IS '시스템(사용자·권한·로그)';
COMMENT ON SCHEMA mst IS '기준정보(거래처·영업 파트·개발자)';
COMMENT ON SCHEMA bsn IS '영업(영업 기회)';
COMMENT ON SCHEMA prj IS '프로젝트';
COMMENT ON SCHEMA asgn IS '투입';
COMMENT ON SCHEMA hr IS '외부 인력';
COMMENT ON SCHEMA cst IS '원가';
COMMENT ON SCHEMA bill IS '청구·입금';
COMMENT ON SCHEMA fin IS '재무·자금·마감';
COMMENT ON SCHEMA ai IS 'AI';

-- updated_at 자동 갱신 함수 (명명 표준 대상 외 객체: TRG_/FN_ 접두는 프로파일에 등록 필요)
CREATE FUNCTION cmn.fn_set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$;


-- =====================================================================================
-- 1. 공통코드·기준값 (UI-27, 부록 C)
-- =====================================================================================

CREATE TABLE cmn.cd_grp (
  cd_grp_id              VARCHAR(30)  NOT NULL,
  cd_grp_nm              VARCHAR(100) NOT NULL,
  cd_grp_se_cd           VARCHAR(10)  NOT NULL DEFAULT 'BIZ',
  attr1_nm               VARCHAR(50)  NULL,
  attr2_nm               VARCHAR(50)  NULL,
  attr3_nm               VARCHAR(50)  NULL,
  cd_grp_desc            VARCHAR(500) NULL,
  sort_seq               INT          NOT NULL DEFAULT 0,
  use_yn                 CHAR(1)      NOT NULL DEFAULT 'Y',
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_cd_grp PRIMARY KEY (cd_grp_id),
  CONSTRAINT ck_cd_grp_cd_grp_se_cd_vld CHECK (cd_grp_se_cd IN ('SYSTEM','BIZ')),
  CONSTRAINT ck_cd_grp_use_yn_vld CHECK (use_yn IN ('Y','N'))
);
COMMENT ON TABLE cmn.cd_grp IS '공통코드 그룹';
COMMENT ON COLUMN cmn.cd_grp.cd_grp_id IS '코드 그룹 ID (예: CONTRACT_TYPE)';
COMMENT ON COLUMN cmn.cd_grp.cd_grp_nm IS '그룹명';
COMMENT ON COLUMN cmn.cd_grp.cd_grp_se_cd IS 'SYSTEM=계산에 쓰는 시스템 코드(이름·순서만 변경), BIZ=업무 코드(자유 관리)';
COMMENT ON COLUMN cmn.cd_grp.attr1_nm IS '추가 값1 의미(예: 수당 금액)';
COMMENT ON COLUMN cmn.cd_grp.attr2_nm IS '추가 값2 의미';
COMMENT ON COLUMN cmn.cd_grp.attr3_nm IS '추가 값3 의미';
COMMENT ON COLUMN cmn.cd_grp.cd_grp_desc IS '설명';
COMMENT ON COLUMN cmn.cd_grp.sort_seq IS '정렬';
COMMENT ON COLUMN cmn.cd_grp.use_yn IS '사용 여부';

CREATE TABLE cmn.cd_dtl (
  cd_grp_id              VARCHAR(30)  NOT NULL,
  dtl_cd                 VARCHAR(30)  NOT NULL,
  cd_nm                  VARCHAR(100) NOT NULL,
  attr1_val              VARCHAR(100) NULL,
  attr2_val              VARCHAR(100) NULL,
  attr3_val              VARCHAR(100) NULL,
  sort_seq               INT          NOT NULL DEFAULT 0,
  use_yn                 CHAR(1)      NOT NULL DEFAULT 'Y',
  aply_bgn_dt            DATE         NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_cd_dtl PRIMARY KEY (cd_grp_id, dtl_cd),
  CONSTRAINT ck_cd_dtl_use_yn_vld CHECK (use_yn IN ('Y','N'))
);
COMMENT ON TABLE cmn.cd_dtl IS '공통코드 상세';
COMMENT ON COLUMN cmn.cd_dtl.cd_grp_id IS '코드 그룹';
COMMENT ON COLUMN cmn.cd_dtl.dtl_cd IS '상세 코드';
COMMENT ON COLUMN cmn.cd_dtl.cd_nm IS '표시 이름';
COMMENT ON COLUMN cmn.cd_dtl.attr1_val IS '추가 값1';
COMMENT ON COLUMN cmn.cd_dtl.attr2_val IS '추가 값2';
COMMENT ON COLUMN cmn.cd_dtl.attr3_val IS '추가 값3';
COMMENT ON COLUMN cmn.cd_dtl.sort_seq IS '정렬';
COMMENT ON COLUMN cmn.cd_dtl.use_yn IS '사용 여부(쓴 코드는 삭제 불가, 사용 중지만)';
COMMENT ON COLUMN cmn.cd_dtl.aply_bgn_dt IS '적용 시작일';

CREATE TABLE cmn.cd_hist (
  cd_hist_id             BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME cmn.seq_cd_hist_id),
  cd_grp_id              VARCHAR(30)  NOT NULL,
  dtl_cd                 VARCHAR(30)  NULL,
  cd_chg_ty_cd           VARCHAR(10)  NOT NULL,
  item_nm                VARCHAR(50)  NULL,
  bfr_val                VARCHAR(500) NULL,
  aft_val                VARCHAR(500) NULL,
  chg_user_id            BIGINT       NULL,
  chg_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_cd_hist PRIMARY KEY (cd_hist_id)
);
COMMENT ON TABLE cmn.cd_hist IS '공통코드 변경 이력 (UI-27 변경 이력 탭)';
COMMENT ON COLUMN cmn.cd_hist.cd_grp_id IS '코드 그룹';
COMMENT ON COLUMN cmn.cd_hist.dtl_cd IS '상세 코드(그룹 자체 변경이면 NULL)';
COMMENT ON COLUMN cmn.cd_hist.cd_chg_ty_cd IS 'ADD/UPDATE/DISABLE/ENABLE';
COMMENT ON COLUMN cmn.cd_hist.item_nm IS '바뀐 항목';

CREATE TABLE cmn.excel_map (
  map_id                 BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME cmn.seq_excel_map_id),
  excel_lbl_nm           VARCHAR(100) NOT NULL,
  trns_cd_grp_id         VARCHAR(30)  NOT NULL,
  trns_cd                VARCHAR(30)  NOT NULL,
  map_role_cd            VARCHAR(10)  NOT NULL DEFAULT 'MAIN',
  rmrk_desc              VARCHAR(200) NULL,
  use_yn                 CHAR(1)      NOT NULL DEFAULT 'Y',
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  CONSTRAINT pk_excel_map PRIMARY KEY (map_id),
  CONSTRAINT uk_excel_map_excel_lbl_nm_trns_cd_grp_id_trns_cd UNIQUE (excel_lbl_nm, trns_cd_grp_id, trns_cd)
);
COMMENT ON TABLE cmn.excel_map IS '엑셀 표기 대응 (부록 C.2) — 업로드 시 엑셀 글자를 코드로 변환';
COMMENT ON COLUMN cmn.excel_map.excel_lbl_nm IS '엑셀 표기(예: 업체, 정직원, 월정액)';
COMMENT ON COLUMN cmn.excel_map.trns_cd_grp_id IS '바꿀 코드 그룹';
COMMENT ON COLUMN cmn.excel_map.trns_cd IS '바꿀 코드';
COMMENT ON COLUMN cmn.excel_map.map_role_cd IS 'MAIN=주 값, SUB=보조 값(예: 보조 직무)';

CREATE TABLE cmn.oprt_std (
  oprt_std_id            VARCHAR(50)  NOT NULL,
  aply_bgn_ym            CHAR(7)      NOT NULL DEFAULT '2000-01',
  oprt_std_nm            VARCHAR(100) NOT NULL,
  num_val                DECIMAL(18,4) NULL,
  txt_val                VARCHAR(200) NULL,
  unit_nm                VARCHAR(20)  NULL,
  use_plc_desc           VARCHAR(100) NULL,
  oprt_std_desc          VARCHAR(500) NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  CONSTRAINT pk_oprt_std PRIMARY KEY (oprt_std_id, aply_bgn_ym),
  CONSTRAINT ck_oprt_std_aply_bgn_ym_fmt CHECK (aply_bgn_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);
COMMENT ON TABLE cmn.oprt_std IS '운영 기준값 (부록 C.3) — 확률·목표선·배점·알림 일수 등';
COMMENT ON COLUMN cmn.oprt_std.oprt_std_id IS '기준값 키';
COMMENT ON COLUMN cmn.oprt_std.aply_bgn_ym IS '적용 시작월(YYYY-MM). 바꾸면 새 줄 추가';
COMMENT ON COLUMN cmn.oprt_std.oprt_std_nm IS '항목명';
COMMENT ON COLUMN cmn.oprt_std.num_val IS '숫자 값';
COMMENT ON COLUMN cmn.oprt_std.txt_val IS '문자 값';
COMMENT ON COLUMN cmn.oprt_std.unit_nm IS '단위(%, 원, 일, 개월 ...)';
COMMENT ON COLUMN cmn.oprt_std.use_plc_desc IS '쓰는 곳(화면·규칙 번호)';

-- =====================================================================================
-- 2. 기준정보: 거래처·영업 파트 (UI-15)
-- =====================================================================================

CREATE TABLE mst.ptnr (
  ptnr_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME mst.seq_ptnr_id),
  ptnr_no                VARCHAR(20)  NOT NULL,
  ptnr_se_cd             VARCHAR(10)  NOT NULL,
  ptnr_nm                VARCHAR(100) NOT NULL,
  biz_reg_no             CHAR(12)     NOT NULL,
  ceo_nm                 VARCHAR(50)  NULL,
  indty_cd               VARCHAR(30)  NULL,
  chrg_nm                VARCHAR(50)  NULL,
  chrg_tel_no            VARCHAR(30)  NULL,
  chrg_email_addr        VARCHAR(100) NULL,
  txinv_email_addr       VARCHAR(100) NULL,
  bank_cd                VARCHAR(30)  NULL,
  acnt_no                VARCHAR(30)  NULL,
  dpstr_nm               VARCHAR(50)  NULL,
  rmrk_desc              VARCHAR(500) NULL,
  use_yn                 CHAR(1)      NOT NULL DEFAULT 'Y',
  upld_id                BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_ptnr PRIMARY KEY (ptnr_id),
  CONSTRAINT uk_ptnr_ptnr_no UNIQUE (ptnr_no),
  CONSTRAINT uk_ptnr_biz_reg_no UNIQUE (biz_reg_no),
  CONSTRAINT ck_ptnr_ptnr_se_cd_vld CHECK (ptnr_se_cd IN ('CUST','VEND','BOTH')),
  CONSTRAINT ck_ptnr_biz_reg_no_fmt CHECK (biz_reg_no ~ '^[0-9]{3}-[0-9]{2}-[0-9]{5}$'),
  CONSTRAINT ck_ptnr_use_yn_vld CHECK (use_yn IN ('Y','N'))
);
COMMENT ON TABLE mst.ptnr IS '거래처 (고객사·협력사). 변경 이력은 sys.data_chg_hist';
COMMENT ON COLUMN mst.ptnr.ptnr_no IS '거래처 코드(C-001)';
COMMENT ON COLUMN mst.ptnr.ptnr_se_cd IS '[PARTNER_KIND] CUST=고객사, VEND=협력사, BOTH=둘 다 (한 회사는 한 줄)';
COMMENT ON COLUMN mst.ptnr.ptnr_nm IS '상호';
COMMENT ON COLUMN mst.ptnr.biz_reg_no IS '사업자번호 000-00-00000 (변경 불가, 중복 불가)';
COMMENT ON COLUMN mst.ptnr.ceo_nm IS '대표자';
COMMENT ON COLUMN mst.ptnr.indty_cd IS '[INDUSTRY] 업종';
COMMENT ON COLUMN mst.ptnr.chrg_nm IS '담당자';
COMMENT ON COLUMN mst.ptnr.chrg_tel_no IS '연락처';
COMMENT ON COLUMN mst.ptnr.chrg_email_addr IS '담당자 이메일';
COMMENT ON COLUMN mst.ptnr.txinv_email_addr IS '세금계산서 이메일 (고객사 필수)';
COMMENT ON COLUMN mst.ptnr.bank_cd IS '[BANK] 지급 은행 (협력사 필수, 민감)';
COMMENT ON COLUMN mst.ptnr.acnt_no IS '지급 계좌번호 (협력사 필수, 민감)';
COMMENT ON COLUMN mst.ptnr.dpstr_nm IS '예금주 (협력사 필수, 민감)';
COMMENT ON COLUMN mst.ptnr.use_yn IS '사용 여부 (진행 프로젝트·소속 인력이 있으면 중지 불가)';
COMMENT ON COLUMN mst.ptnr.upld_id IS '엑셀 업로드(B.7)로 들어왔으면 업로드 ID';

CREATE TABLE mst.bsn_part (
  bsn_part_id            BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME mst.seq_bsn_part_id),
  bsn_part_nm            VARCHAR(50)  NOT NULL,
  hq_yn                  CHAR(1)      NOT NULL DEFAULT 'N',
  sort_seq               INT          NOT NULL DEFAULT 0,
  use_yn                 CHAR(1)      NOT NULL DEFAULT 'Y',
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_bsn_part PRIMARY KEY (bsn_part_id),
  CONSTRAINT uk_bsn_part_bsn_part_nm UNIQUE (bsn_part_nm),
  CONSTRAINT ck_bsn_part_hq_yn_vld CHECK (hq_yn IN ('Y','N')),
  CONSTRAINT ck_bsn_part_use_yn_vld CHECK (use_yn IN ('Y','N'))
);
COMMENT ON TABLE mst.bsn_part IS '영업 파트 (파트장·배분율·영업비는 기준 이력에 저장)';
COMMENT ON COLUMN mst.bsn_part.bsn_part_nm IS '파트명';
COMMENT ON COLUMN mst.bsn_part.hq_yn IS 'Y=본사(직접 영업): 배분율 0%·영업비 0원, 파트 이익금 정산 없음';
COMMENT ON COLUMN mst.bsn_part.use_yn IS '사용 여부 (소속 인력·진행 프로젝트가 있으면 중지 불가)';

-- =====================================================================================
-- 3. 시스템: 역할·메뉴·권한·사용자 (UI-22 ~ UI-25)
-- =====================================================================================

CREATE TABLE sys.role (
  role_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME sys.seq_role_id),
  role_nm                VARCHAR(50)  NOT NULL,
  role_desc              VARCHAR(500) NULL,
  data_scope_cd          VARCHAR(10)  NOT NULL DEFAULT 'ALL',
  snstv_inq_yn           CHAR(1)      NOT NULL DEFAULT 'N',
  part_std_chg_auth_yn   CHAR(1)      NOT NULL DEFAULT 'N',
  admin_yn               CHAR(1)      NOT NULL DEFAULT 'N',
  bltin_yn               CHAR(1)      NOT NULL DEFAULT 'N',
  copy_role_id           BIGINT       NULL,
  sort_seq               INT          NOT NULL DEFAULT 0,
  use_yn                 CHAR(1)      NOT NULL DEFAULT 'Y',
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_role PRIMARY KEY (role_id),
  CONSTRAINT uk_role_role_nm UNIQUE (role_nm),
  CONSTRAINT ck_role_data_scope_cd_vld CHECK (data_scope_cd IN ('ALL','PART','PROJECT','SELF'))
);
COMMENT ON TABLE sys.role IS '역할(권한 묶음)';
COMMENT ON COLUMN sys.role.role_nm IS '역할명';
COMMENT ON COLUMN sys.role.data_scope_cd IS '데이터 범위: ALL=전체, PART=담당 파트, PROJECT=담당 프로젝트, SELF=본인';
COMMENT ON COLUMN sys.role.snstv_inq_yn IS '민감 항목 보기(급여·지급 단가·개인별 원가·지급 계좌)';
COMMENT ON COLUMN sys.role.part_std_chg_auth_yn IS '영업 파트 기준(배분율·영업비) 변경 가능 여부';
COMMENT ON COLUMN sys.role.admin_yn IS '관리자 역할 (관리자가 0명이 되는 변경은 막음)';
COMMENT ON COLUMN sys.role.bltin_yn IS '기본 제공 역할';
COMMENT ON COLUMN sys.role.copy_role_id IS '복사해 만든 원본 역할';

CREATE TABLE sys.menu (
  menu_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME sys.seq_menu_id),
  upr_menu_id            BIGINT       NULL,
  menu_nm                VARCHAR(50)  NOT NULL,
  scrn_id                VARCHAR(10)  NULL,
  icon_nm                VARCHAR(30)  NULL,
  sort_seq               INT          NOT NULL DEFAULT 0,
  use_yn                 CHAR(1)      NOT NULL DEFAULT 'Y',
  mbl_expsr_yn           CHAR(1)      NOT NULL DEFAULT 'Y',
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_menu PRIMARY KEY (menu_id),
  CONSTRAINT uk_menu_scrn_id UNIQUE (scrn_id)
);
COMMENT ON TABLE sys.menu IS '메뉴 (UI-25). 새 메뉴는 모든 역할에 권한 없음으로 추가';
COMMENT ON COLUMN sys.menu.upr_menu_id IS '상위 메뉴(묶음)';
COMMENT ON COLUMN sys.menu.menu_nm IS '메뉴명';
COMMENT ON COLUMN sys.menu.scrn_id IS '화면 ID(UI-01 ...), 묶음이면 NULL';
COMMENT ON COLUMN sys.menu.icon_nm IS '아이콘 키';
COMMENT ON COLUMN sys.menu.sort_seq IS '순서';
COMMENT ON COLUMN sys.menu.use_yn IS '사용 여부';
COMMENT ON COLUMN sys.menu.mbl_expsr_yn IS '휴대폰 표시 여부 (PC 전용 화면은 N)';

CREATE TABLE sys.role_menu_auth (
  role_id                BIGINT       NOT NULL,
  menu_id                BIGINT       NOT NULL,
  inq_auth_yn            CHAR(1)      NOT NULL DEFAULT 'N',
  reg_auth_yn            CHAR(1)      NOT NULL DEFAULT 'N',
  mdfcn_auth_yn          CHAR(1)      NOT NULL DEFAULT 'N',
  del_auth_yn            CHAR(1)      NOT NULL DEFAULT 'N',
  upld_auth_yn           CHAR(1)      NOT NULL DEFAULT 'N',
  dwnld_auth_yn          CHAR(1)      NOT NULL DEFAULT 'N',
  cnfrm_auth_yn          CHAR(1)      NOT NULL DEFAULT 'N',
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_role_menu_auth PRIMARY KEY (role_id, menu_id)
);
COMMENT ON TABLE sys.role_menu_auth IS '역할별 메뉴 기능 권한 (UI-24)';
COMMENT ON COLUMN sys.role_menu_auth.inq_auth_yn IS '조회';
COMMENT ON COLUMN sys.role_menu_auth.reg_auth_yn IS '등록';
COMMENT ON COLUMN sys.role_menu_auth.mdfcn_auth_yn IS '수정';
COMMENT ON COLUMN sys.role_menu_auth.del_auth_yn IS '삭제(사용 중지)';
COMMENT ON COLUMN sys.role_menu_auth.upld_auth_yn IS '업로드';
COMMENT ON COLUMN sys.role_menu_auth.dwnld_auth_yn IS '다운로드';
COMMENT ON COLUMN sys.role_menu_auth.cnfrm_auth_yn IS '확정(마감·정산 확정·견적 승인 등)';

CREATE TABLE sys.user_info (
  user_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME sys.seq_user_info_id),
  lgn_id                 VARCHAR(50)  NOT NULL,
  pswd_hash              VARCHAR(255) NOT NULL,
  user_nm                VARCHAR(50)  NOT NULL,
  email_addr             VARCHAR(100) NULL,
  tel_no                 VARCHAR(30)  NULL,
  bsn_part_id            BIGINT       NULL,
  dev_id                 BIGINT       NULL,
  user_stts_cd           VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
  lgn_fail_cnt           INT          NOT NULL DEFAULT 0,
  lock_at                TIMESTAMP     NULL,
  mfa_yn                 CHAR(1)      NOT NULL DEFAULT 'N',
  pswd_chg_at            TIMESTAMP     NULL,
  pswd_chg_need_yn       CHAR(1)      NOT NULL DEFAULT 'Y',
  last_lgn_at            TIMESTAMP     NULL,
  rsgn_dt                DATE         NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_user_info PRIMARY KEY (user_id),
  CONSTRAINT uk_user_info_lgn_id UNIQUE (lgn_id),
  CONSTRAINT ck_user_info_user_stts_cd_vld CHECK (user_stts_cd IN ('ACTIVE','LOCKED','DISABLED'))
);
COMMENT ON TABLE sys.user_info IS '사용자 계정 (UI-23)';
COMMENT ON COLUMN sys.user_info.lgn_id IS '아이디';
COMMENT ON COLUMN sys.user_info.pswd_hash IS '비밀번호 해시(bcrypt/argon2)';
COMMENT ON COLUMN sys.user_info.user_nm IS '이름';
COMMENT ON COLUMN sys.user_info.email_addr IS '이메일(2단계 인증·알림 메일)';
COMMENT ON COLUMN sys.user_info.bsn_part_id IS '소속 영업 파트';
COMMENT ON COLUMN sys.user_info.dev_id IS '개발자 풀과 같은 사람이면 연결(PM 등)';
COMMENT ON COLUMN sys.user_info.user_stts_cd IS 'ACTIVE=사용, LOCKED=잠김, DISABLED=사용 중지';
COMMENT ON COLUMN sys.user_info.lgn_fail_cnt IS '연속 실패 횟수(5회면 잠금)';
COMMENT ON COLUMN sys.user_info.mfa_yn IS '2단계 인증 사용(관리자 필수)';
COMMENT ON COLUMN sys.user_info.pswd_chg_at IS '비밀번호 변경일(90일 안내)';
COMMENT ON COLUMN sys.user_info.pswd_chg_need_yn IS '다음 로그인 때 비밀번호 변경 필요';
COMMENT ON COLUMN sys.user_info.rsgn_dt IS '퇴사일(사용 중지 시 자동)';

CREATE TABLE sys.user_role (
  user_id                BIGINT       NOT NULL,
  role_id                BIGINT       NOT NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  CONSTRAINT pk_user_role PRIMARY KEY (user_id, role_id)
);
COMMENT ON TABLE sys.user_role IS '사용자-역할 (여러 개 가능)';

CREATE TABLE sys.user_part (
  user_id                BIGINT       NOT NULL,
  bsn_part_id            BIGINT       NOT NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_user_part PRIMARY KEY (user_id, bsn_part_id)
);
COMMENT ON TABLE sys.user_part IS '사용자 담당 파트 (데이터 범위=담당 파트)';

CREATE TABLE sys.auth_tkn (
  tkn_id                 BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME sys.seq_auth_tkn_id),
  user_id                BIGINT       NOT NULL,
  tkn_ty_cd              VARCHAR(10)  NOT NULL,
  tkn_hash               VARCHAR(255) NOT NULL,
  expir_at               TIMESTAMP     NOT NULL,
  use_at                 TIMESTAMP     NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_auth_tkn PRIMARY KEY (tkn_id)
);
COMMENT ON TABLE sys.auth_tkn IS '2단계 인증·비밀번호 재설정 토큰';
COMMENT ON COLUMN sys.auth_tkn.tkn_ty_cd IS 'MFA=2단계 인증 코드, RESET=비밀번호 찾기';

CREATE TABLE sys.acs_hist (
  acs_hist_id            BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME sys.seq_acs_hist_id),
  user_id                BIGINT       NULL,
  lgn_id                 VARCHAR(50)  NULL,
  acs_ty_cd              VARCHAR(30)  NOT NULL,
  scrn_id                VARCHAR(10)  NULL,
  obj_desc               VARCHAR(200) NULL,
  dtl_desc               TEXT         NULL,
  ip_addr                VARCHAR(45)  NULL,
  user_agent_desc        VARCHAR(300) NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_acs_hist PRIMARY KEY (acs_hist_id)
);
COMMENT ON TABLE sys.acs_hist IS '접근 기록 (로그인·엑셀 다운로드·마감·권한 변경 등, 보관 3년)';
COMMENT ON COLUMN sys.acs_hist.user_id IS '사용자(로그인 실패면 NULL 가능)';
COMMENT ON COLUMN sys.acs_hist.lgn_id IS '입력한 아이디';
COMMENT ON COLUMN sys.acs_hist.acs_ty_cd IS 'LOGIN/LOGIN_FAIL/LOGOUT/AUTO_LOGOUT/LOCK/UNLOCK/PW_RESET/DOWNLOAD/UPLOAD/CLOSE/REOPEN/PERM_CHANGE/SETTLE_CONFIRM/SETTLE_CANCEL ...';
COMMENT ON COLUMN sys.acs_hist.scrn_id IS '화면';
COMMENT ON COLUMN sys.acs_hist.obj_desc IS '대상(파일명, 월, 역할 등)';
COMMENT ON COLUMN sys.acs_hist.dtl_desc IS '상세(JSON 가능)';

CREATE TABLE sys.data_chg_hist (
  data_chg_hist_id       BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME sys.seq_data_chg_hist_id),
  tbl_nm                 VARCHAR(50)  NOT NULL,
  obj_rec_id             VARCHAR(50)  NOT NULL,
  data_chg_ty_cd         VARCHAR(10)  NOT NULL,
  clmn_nm                VARCHAR(50)  NULL,
  bfr_val                VARCHAR(4000)         NULL,
  aft_val                VARCHAR(4000)         NULL,
  rsn_desc               VARCHAR(500) NULL,
  chg_user_id            BIGINT       NULL,
  chg_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_data_chg_hist PRIMARY KEY (data_chg_hist_id)
);
COMMENT ON TABLE sys.data_chg_hist IS '변경 기록 (거래처·프로젝트·투입·단가·원가: 누가 언제 무엇을 바꿨는지)';
COMMENT ON COLUMN sys.data_chg_hist.tbl_nm IS '대상 테이블';
COMMENT ON COLUMN sys.data_chg_hist.obj_rec_id IS '대상 PK';
COMMENT ON COLUMN sys.data_chg_hist.data_chg_ty_cd IS 'INSERT/UPDATE/DISABLE';
COMMENT ON COLUMN sys.data_chg_hist.clmn_nm IS '바뀐 항목';
COMMENT ON COLUMN sys.data_chg_hist.rsn_desc IS '사유';

CREATE TABLE sys.ntf (
  ntf_id                 BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME sys.seq_ntf_id),
  user_id                BIGINT       NOT NULL,
  ntf_ty_cd              VARCHAR(30)  NOT NULL,
  ttl_nm                 VARCHAR(200) NOT NULL,
  msg_desc               VARCHAR(1000) NULL,
  link_scrn_id           VARCHAR(10)  NULL,
  link_param_desc        VARCHAR(200) NULL,
  email_send_yn          CHAR(1)      NOT NULL DEFAULT 'N',
  read_yn                CHAR(1)      NOT NULL DEFAULT 'N',
  read_at                TIMESTAMP     NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_ntf PRIMARY KEY (ntf_id)
);
COMMENT ON TABLE sys.ntf IS '시스템 알림 (헤더 종 아이콘)';
COMMENT ON COLUMN sys.ntf.user_id IS '받는 사람';
COMMENT ON COLUMN sys.ntf.ntf_ty_cd IS 'DUNNING/RISK_PROJECT/WITHDRAW/CASH_SHORT/UPLOAD/AI_RECOMMEND/LOW_MARGIN/QUOTE_APPROVAL ...';
COMMENT ON COLUMN sys.ntf.link_scrn_id IS '이동할 화면';
COMMENT ON COLUMN sys.ntf.link_param_desc IS '이동 파라미터';
COMMENT ON COLUMN sys.ntf.email_send_yn IS '메일도 보냈는지';

CREATE TABLE sys.upld (
  upld_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME sys.seq_upld_id),
  form_cd                VARCHAR(10)  NOT NULL,
  scrn_id                VARCHAR(10)  NOT NULL,
  obj_ym                 CHAR(7)      NULL,
  obj_key_val            VARCHAR(50)  NULL,
  file_nm                VARCHAR(255) NOT NULL,
  file_path_addr         VARCHAR(500) NULL,
  tot_row_cnt            INT          NOT NULL DEFAULT 0,
  nrml_row_cnt           INT          NOT NULL DEFAULT 0,
  err_row_cnt            INT          NOT NULL DEFAULT 0,
  wrn_row_cnt            INT          NOT NULL DEFAULT 0,
  upld_stts_cd           VARCHAR(10)  NOT NULL,
  ovwr_yn                CHAR(1)      NOT NULL DEFAULT 'N',
  upld_user_id           BIGINT       NULL,
  upld_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_upld PRIMARY KEY (upld_id),
  CONSTRAINT ck_upld_upld_stts_cd_vld CHECK (upld_stts_cd IN ('APPLIED','REJECTED'))
);
COMMENT ON TABLE sys.upld IS '엑셀 업로드 결과 (업로드 결과 창)';
COMMENT ON COLUMN sys.upld.form_cd IS '양식 번호 B.1 ~ B.11';
COMMENT ON COLUMN sys.upld.scrn_id IS '올린 화면';
COMMENT ON COLUMN sys.upld.obj_ym IS '한 달 양식의 연월';
COMMENT ON COLUMN sys.upld.obj_key_val IS '대상(턴키 배분표는 프로젝트 ID, 사업계획은 연도)';
COMMENT ON COLUMN sys.upld.file_path_addr IS '원본 저장 위치';
COMMENT ON COLUMN sys.upld.wrn_row_cnt IS '경고(엑셀 계산값과 시스템값 차이 등)';
COMMENT ON COLUMN sys.upld.upld_stts_cd IS 'APPLIED=반영, REJECTED=오류로 전체 미반영';
COMMENT ON COLUMN sys.upld.ovwr_yn IS '같은 연월 덮어쓰기 여부';

CREATE TABLE sys.upld_err (
  upld_err_id            BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME sys.seq_upld_err_id),
  upld_id                BIGINT       NOT NULL,
  row_no                 INT          NOT NULL,
  clmn_nm                VARCHAR(50)  NULL,
  err_svrt_cd            VARCHAR(10)  NOT NULL DEFAULT 'ERROR',
  input_val              VARCHAR(500) NULL,
  msg_desc               VARCHAR(500) NOT NULL,
  CONSTRAINT pk_upld_err PRIMARY KEY (upld_err_id)
);
COMMENT ON TABLE sys.upld_err IS '업로드 오류·경고 행';
COMMENT ON COLUMN sys.upld_err.row_no IS '엑셀 행 번호';
COMMENT ON COLUMN sys.upld_err.clmn_nm IS '열 제목';
COMMENT ON COLUMN sys.upld_err.err_svrt_cd IS 'ERROR/WARN';
COMMENT ON COLUMN sys.upld_err.input_val IS '입력값';
COMMENT ON COLUMN sys.upld_err.msg_desc IS '사유';

-- =====================================================================================
-- 4. 기준정보: 영업 파트 기준 이력, 개발자 풀 (UI-15, UI-02)
-- =====================================================================================

CREATE TABLE mst.bsn_part_std_hist (
  part_std_hist_id       BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME mst.seq_bsn_part_std_hist_id),
  bsn_part_id            BIGINT       NOT NULL,
  aply_bgn_ym            CHAR(7)      NOT NULL,
  aply_end_ym            CHAR(7)      NULL,
  ldr_user_id            BIGINT       NULL,
  ldr_nm                 VARCHAR(50)  NULL,
  prft_dstrb_rt          DECIMAL(6,2) NOT NULL DEFAULT 40.00,
  bsn_fee_amt            BIGINT       NOT NULL DEFAULT 1500000,
  chg_rsn_desc           VARCHAR(500) NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  CONSTRAINT pk_bsn_part_std_hist PRIMARY KEY (part_std_hist_id),
  CONSTRAINT uk_bsn_part_std_hist_bsn_part_id_aply_bgn_ym UNIQUE (bsn_part_id, aply_bgn_ym),
  CONSTRAINT ck_bsn_part_std_hist_aply_bgn_ym_fmt CHECK (aply_bgn_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT ck_bsn_part_std_hist_aply_end_ym_prd CHECK (aply_end_ym IS NULL OR aply_end_ym >= aply_bgn_ym)
);
COMMENT ON TABLE mst.bsn_part_std_hist IS '영업 파트 기준 이력 — 덮어쓰지 않고 적용 시작월부터 새 줄 추가 (정산은 그 달 적용 줄 사용)';
COMMENT ON COLUMN mst.bsn_part_std_hist.aply_bgn_ym IS '적용 시작월 (마감된 달 이전 불가)';
COMMENT ON COLUMN mst.bsn_part_std_hist.aply_end_ym IS '적용 종료월 (NULL=현재 적용 중)';
COMMENT ON COLUMN mst.bsn_part_std_hist.ldr_user_id IS '파트장(사용자)';
COMMENT ON COLUMN mst.bsn_part_std_hist.ldr_nm IS '파트장 이름(표시용)';
COMMENT ON COLUMN mst.bsn_part_std_hist.prft_dstrb_rt IS '이익 배분율(%) 기본 40';
COMMENT ON COLUMN mst.bsn_part_std_hist.bsn_fee_amt IS '자사 인력 영업비(1인·월, 원) 기본 150만';
COMMENT ON COLUMN mst.bsn_part_std_hist.chg_rsn_desc IS '기준 변경 사유';

CREATE TABLE mst.dev (
  dev_id                 BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME mst.seq_dev_id),
  mng_no                 VARCHAR(20)  NOT NULL,
  dev_nm                 VARCHAR(50)  NOT NULL,
  tel_no                 VARCHAR(30)  NULL,
  email_addr             VARCHAR(100) NULL,
  vndr_ptnr_id           BIGINT       NULL,
  pstn_cd                VARCHAR(30)  NULL,
  emp_se_cd              VARCHAR(10)  NOT NULL,
  duty_se_cd             VARCHAR(10)  NOT NULL DEFAULT 'DIRECT',
  grd_cd                 VARCHAR(30)  NULL,
  main_job_cd            VARCHAR(30)  NULL,
  bsn_part_id            BIGINT       NULL,
  dflt_untprc_amt        BIGINT       NULL,
  hire_dt                DATE         NULL,
  rsgn_dt                DATE         NULL,
  crr_smry_desc          TEXT         NULL,
  src_cand_id            BIGINT      NULL,
  rmrk_desc              VARCHAR(500) NULL,
  use_yn                 CHAR(1)      NOT NULL DEFAULT 'Y',
  upld_id                BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_dev PRIMARY KEY (dev_id),
  CONSTRAINT uk_dev_mng_no UNIQUE (mng_no),
  CONSTRAINT ck_dev_emp_se_cd_vld CHECK (emp_se_cd IN ('FT','CT','FL','OS')),
  CONSTRAINT ck_dev_duty_se_cd_vld CHECK (duty_se_cd IN ('DIRECT','INDIRECT'))
);
COMMENT ON TABLE mst.dev IS '개발자 풀 (정규·계약·프리·외주, 간접 인력 포함). 현재 상태(투입중/대기중)는 계산';
COMMENT ON COLUMN mst.dev.mng_no IS '관리번호(K-0001). 업로드에서 사람을 찾는 기준';
COMMENT ON COLUMN mst.dev.dev_nm IS '이름';
COMMENT ON COLUMN mst.dev.tel_no IS '연락처';
COMMENT ON COLUMN mst.dev.vndr_ptnr_id IS '소속 협력사(NULL=자사)';
COMMENT ON COLUMN mst.dev.pstn_cd IS '[POSITION] 직위(팀원/팀장/그룹장/이사…) — 수당 계산';
COMMENT ON COLUMN mst.dev.emp_se_cd IS '[EMP_TYPE] FT=정규직, CT=계약직, FL=프리랜서, OS=외주';
COMMENT ON COLUMN mst.dev.duty_se_cd IS '[WORK_TYPE] DIRECT=직접, INDIRECT=간접(관리·지원, 판관비)';
COMMENT ON COLUMN mst.dev.grd_cd IS '[GRADE] 등급(초급/중급/고급/특급)';
COMMENT ON COLUMN mst.dev.main_job_cd IS '[JOB] 주 직무 (보조 직무는 mst.dev_sub_job)';
COMMENT ON COLUMN mst.dev.bsn_part_id IS '소속 영업 파트(파트 이익금은 사람의 소속 파트로 모음, D-31)';
COMMENT ON COLUMN mst.dev.dflt_untprc_amt IS '기본 월 단가(참고, 실제 단가는 asgn.untprc_hist)';
COMMENT ON COLUMN mst.dev.hire_dt IS '입사일(자사) / 최초 계약일';
COMMENT ON COLUMN mst.dev.rsgn_dt IS '퇴사일';
COMMENT ON COLUMN mst.dev.crr_smry_desc IS '경력 요약(경력 카드 보충)';
COMMENT ON COLUMN mst.dev.src_cand_id IS '외부 인력 후보에서 계약해 등록했으면 후보 ID';
COMMENT ON COLUMN mst.dev.upld_id IS '처음 옮기기(B.8)';

CREATE TABLE mst.dev_sub_job (
  dev_id                 BIGINT       NOT NULL,
  job_cd                 VARCHAR(30)  NOT NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_dev_sub_job PRIMARY KEY (dev_id, job_cd)
);
COMMENT ON TABLE mst.dev_sub_job IS '개발자 보조 직무 (여러 개)';
COMMENT ON COLUMN mst.dev_sub_job.job_cd IS '[JOB] 보조 직무';

CREATE TABLE mst.dev_skl (
  dev_id                 BIGINT       NOT NULL,
  skl_cd                 VARCHAR(30)  NOT NULL,
  skl_lvl_cd             VARCHAR(10)  NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_dev_skl PRIMARY KEY (dev_id, skl_cd)
);
COMMENT ON TABLE mst.dev_skl IS '개발자 보유 스킬';
COMMENT ON COLUMN mst.dev_skl.skl_cd IS '[SKILL] 보유 스킬';
COMMENT ON COLUMN mst.dev_skl.skl_lvl_cd IS '숙련도(선택)';

CREATE TABLE mst.dev_work_stts_hist (
  work_stts_hist_id      BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME mst.seq_dev_work_stts_hist_id),
  dev_id                 BIGINT       NOT NULL,
  work_stts_cd           VARCHAR(20)  NOT NULL,
  bgn_dt                 DATE         NOT NULL,
  end_dt                 DATE         NULL,
  paid_yn                CHAR(1)      NOT NULL DEFAULT 'N',
  rmrk_desc              VARCHAR(500) NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  CONSTRAINT pk_dev_work_stts_hist PRIMARY KEY (work_stts_hist_id),
  CONSTRAINT ck_dev_work_stts_hist_end_dt_prd CHECK (end_dt IS NULL OR end_dt >= bgn_dt)
);
COMMENT ON TABLE mst.dev_work_stts_hist IS '근무 상태 이력 (휴가·육아휴직·무급휴가·퇴사 기간)';
COMMENT ON COLUMN mst.dev_work_stts_hist.work_stts_cd IS '[WORK_STATUS] VACATION=휴가, PARENTAL=육아휴직, UNPAID=무급휴가, LEAVE=휴직, RESIGN=퇴사';
COMMENT ON COLUMN mst.dev_work_stts_hist.end_dt IS '종료일(퇴사·미정이면 NULL)';
COMMENT ON COLUMN mst.dev_work_stts_hist.paid_yn IS '유급 여부(유급 휴가는 투입 프로젝트에 배분, D-7)';

-- =====================================================================================
-- 5. 영업 파이프라인 (UI-29) — 프로젝트가 기회를 참조하므로 먼저 생성
-- =====================================================================================

CREATE TABLE bsn.oppt (
  oppt_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME bsn.seq_oppt_id),
  oppt_no                VARCHAR(20)  NOT NULL,
  oppt_nm                VARCHAR(200) NOT NULL,
  cust_ptnr_id           BIGINT       NOT NULL,
  bsn_part_id            BIGINT       NULL,
  bsn_chrg_user_id       BIGINT       NULL,
  bsn_stg_cd             VARCHAR(10)  NOT NULL DEFAULT 'DISC',
  win_prblt_rt           DECIMAL(6,2) NOT NULL DEFAULT 10,
  expc_amt               BIGINT       NOT NULL DEFAULT 0,
  cntrct_ty_cd           VARCHAR(10)  NULL,
  expc_bgn_ym            CHAR(7)      NULL,
  expc_mnth_qty          INT          NULL,
  next_work_desc         VARCHAR(200) NULL,
  next_work_ddln_dt      DATE         NULL,
  oppt_stts_cd           VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
  end_dt                 DATE         NULL,
  lost_rsn_desc          VARCHAR(500) NULL,
  win_prj_id             BIGINT       NULL,
  quot_aprv_stts_cd      VARCHAR(10)  NOT NULL DEFAULT 'NONE',
  expc_sls_amt           BIGINT       NULL,
  expc_cst_amt           BIGINT       NULL,
  expc_mrgn_rt           DECIMAL(6,2) NULL,
  aprv_mrgn_rt           DECIMAL(6,2) NULL,
  aprv_user_id           BIGINT       NULL,
  aprv_at                TIMESTAMP     NULL,
  rjct_rsn_desc          VARCHAR(500) NULL,
  rmrk_desc              TEXT         NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_oppt PRIMARY KEY (oppt_id),
  CONSTRAINT uk_oppt_oppt_no UNIQUE (oppt_no),
  CONSTRAINT ck_oppt_bsn_stg_cd_vld CHECK (bsn_stg_cd IN ('DISC','PROP','NEGO','PRE','WON','LOST')),
  CONSTRAINT ck_oppt_oppt_stts_cd_vld CHECK (oppt_stts_cd IN ('OPEN','WON','LOST','DELETED')),
  CONSTRAINT ck_oppt_quot_aprv_stts_cd_vld CHECK (quot_aprv_stts_cd IN ('NONE','PENDING','APPROVED','REJECTED')),
  CONSTRAINT ck_oppt_win_prblt_rt_rng CHECK (win_prblt_rt BETWEEN 0 AND 100)
);
COMMENT ON TABLE bsn.oppt IS '영업 기회 (계약 전 사업)';
COMMENT ON COLUMN bsn.oppt.oppt_no IS '기회 코드(O-01)';
COMMENT ON COLUMN bsn.oppt.oppt_nm IS '기회명';
COMMENT ON COLUMN bsn.oppt.cust_ptnr_id IS '고객사';
COMMENT ON COLUMN bsn.oppt.bsn_part_id IS '영업 파트(수주 시 프로젝트로 그대로 넘어감)';
COMMENT ON COLUMN bsn.oppt.bsn_chrg_user_id IS '영업 담당자';
COMMENT ON COLUMN bsn.oppt.bsn_stg_cd IS '[SALES_STAGE] DISC=발굴, PROP=제안, NEGO=협상, PRE=계약 직전, WON=수주, LOST=실주';
COMMENT ON COLUMN bsn.oppt.win_prblt_rt IS '수주 확률(%) — 단계를 바꾸면 단계 기본값, 직접 수정 가능';
COMMENT ON COLUMN bsn.oppt.expc_amt IS '예상 금액(공급가)';
COMMENT ON COLUMN bsn.oppt.cntrct_ty_cd IS '[CONTRACT_TYPE] 예상 계약유형';
COMMENT ON COLUMN bsn.oppt.expc_bgn_ym IS '시작 예정월';
COMMENT ON COLUMN bsn.oppt.expc_mnth_qty IS '예상 기간(개월)';
COMMENT ON COLUMN bsn.oppt.next_work_desc IS '다음 할 일';
COMMENT ON COLUMN bsn.oppt.next_work_ddln_dt IS '다음 할 일 기한';
COMMENT ON COLUMN bsn.oppt.oppt_stts_cd IS 'OPEN=진행, WON=수주, LOST=실주, DELETED=삭제';
COMMENT ON COLUMN bsn.oppt.end_dt IS '수주·실주 처리일';
COMMENT ON COLUMN bsn.oppt.lost_rsn_desc IS '실주 사유(필수)';
COMMENT ON COLUMN bsn.oppt.win_prj_id IS '수주로 생성된 프로젝트';
COMMENT ON COLUMN bsn.oppt.quot_aprv_stts_cd IS '견적 승인: NONE=대상 아님, PENDING=승인 대기, APPROVED=승인, REJECTED=반려';
COMMENT ON COLUMN bsn.oppt.expc_sls_amt IS '견적 점검 예상 매출(저장 시점 값)';
COMMENT ON COLUMN bsn.oppt.expc_cst_amt IS '견적 점검 예상 원가 = 손해 안 보는 최소 금액';
COMMENT ON COLUMN bsn.oppt.expc_mrgn_rt IS '견적 점검 예상 이익률(%)';
COMMENT ON COLUMN bsn.oppt.aprv_mrgn_rt IS '승인 받을 때의 이익률(±0.5%p 이내 재저장은 승인 유지)';
COMMENT ON COLUMN bsn.oppt.aprv_user_id IS '승인/반려자';
COMMENT ON COLUMN bsn.oppt.aprv_at IS '승인/반려 일시';
COMMENT ON COLUMN bsn.oppt.rmrk_desc IS '비고';
COMMENT ON COLUMN bsn.oppt.updated_at IS '마지막 갱신(2주 갱신 없음 표시)';

CREATE TABLE bsn.oppt_need (
  need_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME bsn.seq_oppt_need_id),
  oppt_id                BIGINT       NOT NULL,
  job_cd                 VARCHAR(30)  NOT NULL,
  grd_cd                 VARCHAR(30)  NULL,
  psn_cnt                INT          NOT NULL DEFAULT 1,
  bgn_ym                 CHAR(7)      NOT NULL,
  mnth_qty               INT          NOT NULL DEFAULT 1,
  mth_mm_qty             DECIMAL(5,2) NOT NULL DEFAULT 1.00,
  expc_untprc_amt        BIGINT       NULL,
  psn_mth_cst_amt        BIGINT       NULL,
  min_untprc_amt         BIGINT       NULL,
  trgt_untprc_amt        BIGINT       NULL,
  sort_seq               INT          NOT NULL DEFAULT 0,
  rmrk_desc              VARCHAR(200) NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_oppt_need PRIMARY KEY (need_id),
  CONSTRAINT ck_oppt_need_bgn_ym_fmt CHECK (bgn_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);
COMMENT ON TABLE bsn.oppt_need IS '영업 기회 필요 인력 (수주 시 인원 계획으로 넘어감)';
COMMENT ON COLUMN bsn.oppt_need.job_cd IS '[JOB] 직무';
COMMENT ON COLUMN bsn.oppt_need.grd_cd IS '[GRADE] 등급';
COMMENT ON COLUMN bsn.oppt_need.psn_cnt IS '인원';
COMMENT ON COLUMN bsn.oppt_need.bgn_ym IS '시작월';
COMMENT ON COLUMN bsn.oppt_need.mnth_qty IS '개월 수';
COMMENT ON COLUMN bsn.oppt_need.mth_mm_qty IS '월 MM(1인)';
COMMENT ON COLUMN bsn.oppt_need.expc_untprc_amt IS '예상 단가(1인·월). 비우면 직무 기본 단가';
COMMENT ON COLUMN bsn.oppt_need.psn_mth_cst_amt IS '1인 월 원가(직무·등급 평균, 저장 시점 값)';
COMMENT ON COLUMN bsn.oppt_need.min_untprc_amt IS '최소 단가(=1인 월 원가)';
COMMENT ON COLUMN bsn.oppt_need.trgt_untprc_amt IS '목표 단가(이익률 20%, 만 원 올림)';

CREATE TABLE bsn.quot_aprv_hist (
  aprv_hist_id           BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME bsn.seq_quot_aprv_hist_id),
  oppt_id                BIGINT       NOT NULL,
  aprv_prcs_cd           VARCHAR(10)  NOT NULL,
  mrgn_rt                DECIMAL(6,2) NULL,
  expc_amt               BIGINT       NULL,
  opnn_desc              VARCHAR(500) NULL,
  prcs_user_id           BIGINT       NULL,
  prcs_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_quot_aprv_hist PRIMARY KEY (aprv_hist_id),
  CONSTRAINT ck_quot_aprv_hist_aprv_prcs_cd_vld CHECK (aprv_prcs_cd IN ('REQUEST','APPROVE','REJECT'))
);
COMMENT ON TABLE bsn.quot_aprv_hist IS '견적 승인 이력 (이익률 15% 미만 → 승인 대기)';
COMMENT ON COLUMN bsn.quot_aprv_hist.aprv_prcs_cd IS 'REQUEST=승인 요청, APPROVE=승인, REJECT=반려';
COMMENT ON COLUMN bsn.quot_aprv_hist.mrgn_rt IS '그때 예상 이익률';

-- =====================================================================================
-- 6. 프로젝트 (UI-01, UI-31, UI-11)
-- =====================================================================================

CREATE TABLE prj.prj (
  prj_id                 BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME prj.seq_prj_id),
  prj_no                 VARCHAR(20)  NOT NULL,
  prj_nm                 VARCHAR(200) NOT NULL,
  cust_ptnr_id           BIGINT       NULL,
  cntrct_ty_cd           VARCHAR(10)  NOT NULL,
  stlm_mthd_cd           VARCHAR(10)  NULL,
  prj_stts_cd            VARCHAR(10)  NOT NULL DEFAULT 'PLAN',
  win_prblt_rt           DECIMAL(6,2) NULL,
  cntrct_dt              DATE         NULL,
  cntrct_bgn_dt          DATE         NULL,
  cntrct_end_dt          DATE         NULL,
  prfm_bgn_dt            DATE         NULL,
  cur_end_dt             DATE         NULL,
  quot_amt               BIGINT       NULL,
  init_cntrct_amt        BIGINT       NULL,
  cur_cntrct_amt         BIGINT       NULL,
  cntrct_mm_qty          DECIMAL(8,2) NULL,
  bsn_part_id            BIGINT       NULL,
  indty_cd               VARCHAR(30)  NULL,
  duty_fld_nm            VARCHAR(100) NULL,
  pm_user_id             BIGINT       NULL,
  pm_dev_id              BIGINT       NULL,
  trgt_cst_amt           BIGINT       NULL,
  trgt_mrgn_rt           DECIMAL(6,2) NULL,
  free_sprt_yn           CHAR(1)      NOT NULL DEFAULT 'N',
  prev_prj_id            BIGINT       NULL,
  oppt_id                BIGINT       NULL,
  rmrk_desc              VARCHAR(500) NULL,
  use_yn                 CHAR(1)      NOT NULL DEFAULT 'Y',
  upld_id                BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_prj PRIMARY KEY (prj_id),
  CONSTRAINT uk_prj_prj_no UNIQUE (prj_no),
  CONSTRAINT ck_prj_cntrct_ty_cd_vld CHECK (cntrct_ty_cd IN ('TK','MO','DP','IN')),
  CONSTRAINT ck_prj_prj_stts_cd_vld CHECK (prj_stts_cd IN ('PLAN','RUN','HOLD','END','LOST')),
  CONSTRAINT ck_prj_cntrct_end_dt_prd CHECK (cntrct_end_dt IS NULL OR cntrct_bgn_dt IS NULL OR cntrct_end_dt >= cntrct_bgn_dt)
);
COMMENT ON TABLE prj.prj IS '프로젝트 (계약 단위 사업)';
COMMENT ON COLUMN prj.prj.prj_no IS '프로젝트ID(P-2601). 업로드에서 프로젝트를 찾는 기준';
COMMENT ON COLUMN prj.prj.prj_nm IS '프로젝트명(앞뒤 빈칸 제거)';
COMMENT ON COLUMN prj.prj.cust_ptnr_id IS '고객사 (내부 프로젝트는 NULL)';
COMMENT ON COLUMN prj.prj.cntrct_ty_cd IS '[CONTRACT_TYPE] TK=턴키, MO=월단위, DP=파견, IN=내부(자체 개발)';
COMMENT ON COLUMN prj.prj.stlm_mthd_cd IS '[SETTLE_METHOD] STAGE=단계별(턴키), MONTH=월 고정, DAY=일 단가, HOUR=시간 단가';
COMMENT ON COLUMN prj.prj.prj_stts_cd IS '[PROJECT_STATUS] PLAN=수주예정, RUN=진행, HOLD=보류, END=종료, LOST=실주';
COMMENT ON COLUMN prj.prj.win_prblt_rt IS '수주 확률(%) — 수주예정일 때';
COMMENT ON COLUMN prj.prj.cntrct_dt IS '계약일';
COMMENT ON COLUMN prj.prj.cntrct_bgn_dt IS '계약 시작일';
COMMENT ON COLUMN prj.prj.cntrct_end_dt IS '최초 계약 종료일';
COMMENT ON COLUMN prj.prj.prfm_bgn_dt IS '수행 시작일(늦으면 그날부터 계획)';
COMMENT ON COLUMN prj.prj.cur_end_dt IS '현재 종료일(확정된 기간 변경 반영, 자동 갱신)';
COMMENT ON COLUMN prj.prj.quot_amt IS '견적 금액';
COMMENT ON COLUMN prj.prj.init_cntrct_amt IS '최초 계약금액';
COMMENT ON COLUMN prj.prj.cur_cntrct_amt IS '현재 계약금액(확정된 계약 변경 반영, 자동 갱신)';
COMMENT ON COLUMN prj.prj.cntrct_mm_qty IS '계약 MM(턴키, 추가 계약 확정 시 가산)';
COMMENT ON COLUMN prj.prj.bsn_part_id IS '현재 영업 파트(턴키·월단위·파견 필수, NULL=미지정, 내부는 NULL=없음). 기간별은 prj.prj_part_hist';
COMMENT ON COLUMN prj.prj.indty_cd IS '[INDUSTRY] 업종';
COMMENT ON COLUMN prj.prj.duty_fld_nm IS '업무 분야(여신, 보험금 지급 등)';
COMMENT ON COLUMN prj.prj.pm_user_id IS 'PM(사용자)';
COMMENT ON COLUMN prj.prj.pm_dev_id IS 'PM(개발자 풀)';
COMMENT ON COLUMN prj.prj.trgt_cst_amt IS '목표 원가(예산) = 현재 계약금액 × (1 − 목표 마진율), 수정 가능';
COMMENT ON COLUMN prj.prj.trgt_mrgn_rt IS '목표 마진율(%)';
COMMENT ON COLUMN prj.prj.free_sprt_yn IS '무상지원 투입이 있는 프로젝트';
COMMENT ON COLUMN prj.prj.prev_prj_id IS '선행 프로젝트(운영 전환 등 후속 프로젝트 연결)';
COMMENT ON COLUMN prj.prj.oppt_id IS '수주된 영업 기회';
COMMENT ON COLUMN prj.prj.upld_id IS '처음 옮기기(B.9)';

CREATE TABLE sys.user_prj (
  user_id                BIGINT       NOT NULL,
  prj_id                 BIGINT       NOT NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_user_prj PRIMARY KEY (user_id, prj_id)
);
COMMENT ON TABLE sys.user_prj IS '사용자 담당 프로젝트 (데이터 범위=담당 프로젝트)';

CREATE TABLE prj.prj_part_hist (
  part_hist_id           BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME prj.seq_prj_part_hist_id),
  prj_id                 BIGINT       NOT NULL,
  aply_bgn_ym            CHAR(7)      NOT NULL,
  bsn_part_id            BIGINT       NULL,
  rsn_desc               VARCHAR(500) NULL,
  chg_user_id            BIGINT       NULL,
  chg_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_prj_part_hist PRIMARY KEY (part_hist_id),
  CONSTRAINT uk_prj_part_hist_prj_id_aply_bgn_ym UNIQUE (prj_id, aply_bgn_ym),
  CONSTRAINT ck_prj_part_hist_aply_bgn_ym_fmt CHECK (aply_bgn_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);
COMMENT ON TABLE prj.prj_part_hist IS '프로젝트 영업 파트 변경 이력 (마감한 달의 실적·정산은 바뀌지 않음)';
COMMENT ON COLUMN prj.prj_part_hist.aply_bgn_ym IS '적용 시작월(마감 안 된 달부터)';
COMMENT ON COLUMN prj.prj_part_hist.bsn_part_id IS '영업 파트(NULL=미지정/없음)';
COMMENT ON COLUMN prj.prj_part_hist.rsn_desc IS '변경 사유(처음 등록이면 NULL)';

CREATE TABLE prj.prj_skl (
  prj_id                 BIGINT       NOT NULL,
  skl_cd                 VARCHAR(30)  NOT NULL,
  CONSTRAINT pk_prj_skl PRIMARY KEY (prj_id, skl_cd)
);
COMMENT ON TABLE prj.prj_skl IS '프로젝트 기술 스택 (AI 추천 스킬 일치에 사용)';
COMMENT ON COLUMN prj.prj_skl.skl_cd IS '[SKILL] 기술 스택';

CREATE TABLE prj.cntrct_chg_hist (
  cntrct_chg_id          BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME prj.seq_cntrct_chg_hist_id),
  prj_id                 BIGINT       NOT NULL,
  cntrct_chg_ty_cd       VARCHAR(10)  NOT NULL,
  cntrct_chg_stts_cd     VARCHAR(10)  NOT NULL DEFAULT 'PLAN',
  chg_dt                 DATE         NOT NULL,
  bfr_cntrct_amt         BIGINT       NULL,
  aft_cntrct_amt         BIGINT       NULL,
  bfr_end_dt             DATE         NULL,
  aft_end_dt             DATE         NULL,
  incdec_mm_qty          DECIMAL(8,2) NULL,
  free_mm_qty            DECIMAL(8,2) NULL,
  rsn_desc               VARCHAR(500) NULL,
  realoc_need_yn         CHAR(1)      NOT NULL DEFAULT 'N',
  cnfrm_user_id          BIGINT       NULL,
  cnfrm_at               TIMESTAMP     NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  CONSTRAINT pk_cntrct_chg_hist PRIMARY KEY (cntrct_chg_id),
  CONSTRAINT ck_cntrct_chg_hist_cntrct_chg_ty_cd_vld CHECK (cntrct_chg_ty_cd IN ('ADD','CUT','TERM','FREE')),
  CONSTRAINT ck_cntrct_chg_hist_cntrct_chg_stts_cd_vld CHECK (cntrct_chg_stts_cd IN ('PLAN','CONFIRM'))
);
COMMENT ON TABLE prj.cntrct_chg_hist IS '계약 변경 이력 (추가 계약·감액·기간 변경·무상지원 합의)';
COMMENT ON COLUMN prj.cntrct_chg_hist.cntrct_chg_ty_cd IS '[CONTRACT_CHANGE] ADD=추가 계약, CUT=감액, TERM=기간 변경, FREE=무상지원 합의';
COMMENT ON COLUMN prj.cntrct_chg_hist.cntrct_chg_stts_cd IS 'PLAN=예정(인력 예측에만 사용), CONFIRM=확정(계약금액·MM 반영)';
COMMENT ON COLUMN prj.cntrct_chg_hist.chg_dt IS '변경일';
COMMENT ON COLUMN prj.cntrct_chg_hist.bfr_cntrct_amt IS '변경 전 계약금액';
COMMENT ON COLUMN prj.cntrct_chg_hist.aft_cntrct_amt IS '변경 후 계약금액';
COMMENT ON COLUMN prj.cntrct_chg_hist.bfr_end_dt IS '변경 전 종료일';
COMMENT ON COLUMN prj.cntrct_chg_hist.aft_end_dt IS '변경 후 종료일';
COMMENT ON COLUMN prj.cntrct_chg_hist.incdec_mm_qty IS '추가·감소 MM';
COMMENT ON COLUMN prj.cntrct_chg_hist.free_mm_qty IS '무상지원 합의 MM';
COMMENT ON COLUMN prj.cntrct_chg_hist.rsn_desc IS '사유';
COMMENT ON COLUMN prj.cntrct_chg_hist.realoc_need_yn IS '확정 후 배분표 재조정 필요 알림';

CREATE TABLE prj.psn_plan (
  psn_plan_id            BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME prj.seq_psn_plan_id),
  prj_id                 BIGINT       NOT NULL,
  plan_ym                CHAR(7)      NOT NULL,
  job_cd                 VARCHAR(30)  NOT NULL,
  grd_cd                 VARCHAR(30)  NOT NULL DEFAULT '',
  need_psn_cnt           DECIMAL(5,2) NULL,
  need_mm_qty            DECIMAL(6,2) NOT NULL,
  expc_mth_cst_amt       BIGINT       NULL,
  src_need_id            BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_psn_plan PRIMARY KEY (psn_plan_id),
  CONSTRAINT uk_psn_plan_prj_id_plan_ym_job_cd_grd_cd UNIQUE (prj_id, plan_ym, job_cd, grd_cd),
  CONSTRAINT ck_psn_plan_plan_ym_fmt CHECK (plan_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);
COMMENT ON TABLE prj.psn_plan IS '인원 계획 (월별·직무별 필요 MM) — UI-11 계획 대비 투입, UI-17 수요';
COMMENT ON COLUMN prj.psn_plan.plan_ym IS '월';
COMMENT ON COLUMN prj.psn_plan.job_cd IS '[JOB] 직무';
COMMENT ON COLUMN prj.psn_plan.grd_cd IS '[GRADE] 등급(미지정은 빈 값)';
COMMENT ON COLUMN prj.psn_plan.need_psn_cnt IS '필요 인원';
COMMENT ON COLUMN prj.psn_plan.need_mm_qty IS '필요 MM';
COMMENT ON COLUMN prj.psn_plan.expc_mth_cst_amt IS '예상 월 원가';
COMMENT ON COLUMN prj.psn_plan.src_need_id IS '영업 기회 필요 인력에서 넘어왔으면 그 ID';

CREATE TABLE prj.bill_stg (
  bill_stg_id            BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME prj.seq_bill_stg_id),
  prj_id                 BIGINT       NOT NULL,
  stg_odr_no             INT          NOT NULL,
  bill_stg_nm            VARCHAR(100) NOT NULL,
  sply_amt               BIGINT       NOT NULL,
  bill_plan_dt           DATE         NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_bill_stg PRIMARY KEY (bill_stg_id),
  CONSTRAINT uk_bill_stg_prj_id_stg_odr_no UNIQUE (prj_id, stg_odr_no)
);
COMMENT ON TABLE prj.bill_stg IS '턴키 청구 단계 (저장하면 UI-13 에 청구 예정 건 생성)';
COMMENT ON COLUMN prj.bill_stg.stg_odr_no IS '차수';
COMMENT ON COLUMN prj.bill_stg.bill_stg_nm IS '단계명(착수금·중도금·잔금 등)';
COMMENT ON COLUMN prj.bill_stg.sply_amt IS '공급가액';
COMMENT ON COLUMN prj.bill_stg.bill_plan_dt IS '청구 예정일';

CREATE TABLE prj.tk_aloc_line (
  aloc_line_id           BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME prj.seq_tk_aloc_line_id),
  prj_id                 BIGINT       NOT NULL,
  aloc_stg_cd            VARCHAR(10)  NOT NULL,
  line_ty_cd             VARCHAR(10)  NOT NULL DEFAULT 'PERSON',
  dev_id                 BIGINT       NULL,
  item_nm                VARCHAR(100) NULL,
  job_cd                 VARCHAR(30)  NULL,
  asgn_bgn_dt            DATE         NULL,
  asgn_se_cd             VARCHAR(10)  NULL,
  rmrk_desc              VARCHAR(200) NULL,
  sort_seq               INT          NOT NULL DEFAULT 0,
  upld_id                BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_tk_aloc_line PRIMARY KEY (aloc_line_id),
  CONSTRAINT ck_tk_aloc_line_aloc_stg_cd_vld CHECK (aloc_stg_cd IN ('EST','FINAL','ACTUAL')),
  CONSTRAINT ck_tk_aloc_line_line_ty_cd_vld CHECK (line_ty_cd IN ('PERSON','ITEM'))
);
COMMENT ON TABLE prj.tk_aloc_line IS '턴키 배분표 줄 (사람 × 월, 3단계). 합계가 현재 계약금액과 다르면 경고';
COMMENT ON COLUMN prj.tk_aloc_line.aloc_stg_cd IS '[ALLOC_STAGE] EST=견적, FINAL=최종 계약, ACTUAL=실제 투입계획 (가장 나중 단계 사용)';
COMMENT ON COLUMN prj.tk_aloc_line.line_ty_cd IS 'PERSON=사람, ITEM=항목(제경비·기술료·컨설팅비 — 프로젝트 매출에만 포함)';
COMMENT ON COLUMN prj.tk_aloc_line.dev_id IS '사람 줄: 개발자';
COMMENT ON COLUMN prj.tk_aloc_line.item_nm IS '항목 줄: 항목명';
COMMENT ON COLUMN prj.tk_aloc_line.job_cd IS '[JOB] 사람 줄 필수';
COMMENT ON COLUMN prj.tk_aloc_line.asgn_bgn_dt IS '투입일(사람 줄 필수)';
COMMENT ON COLUMN prj.tk_aloc_line.asgn_se_cd IS '[ASSIGN_KIND] 추가·대체투입, 이관, 무상지원';
COMMENT ON COLUMN prj.tk_aloc_line.upld_id IS '배분표 업로드(B.6)';

CREATE TABLE prj.tk_aloc_mth (
  aloc_line_id           BIGINT       NOT NULL,
  aloc_ym                CHAR(7)      NOT NULL,
  aloc_amt               BIGINT       NULL,
  mm_qty                 DECIMAL(5,2) NULL,
  CONSTRAINT pk_tk_aloc_mth PRIMARY KEY (aloc_line_id, aloc_ym),
  CONSTRAINT ck_tk_aloc_mth_aloc_ym_fmt CHECK (aloc_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);
COMMENT ON TABLE prj.tk_aloc_mth IS '턴키 배분표 월별 값 (금액·MM)';
COMMENT ON COLUMN prj.tk_aloc_mth.aloc_ym IS '월(YYYY-MM)';
COMMENT ON COLUMN prj.tk_aloc_mth.aloc_amt IS '배분 금액(턴키 개인 단가)';
COMMENT ON COLUMN prj.tk_aloc_mth.mm_qty IS '배분 MM';

CREATE TABLE prj.phs (
  phs_id                 BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME prj.seq_phs_id),
  prj_id                 BIGINT       NOT NULL,
  phs_seq                INT          NOT NULL,
  phs_nm                 VARCHAR(50)  NOT NULL,
  wght_rt                DECIMAL(6,2) NOT NULL,
  plan_bgn_dt            DATE         NULL,
  plan_end_dt            DATE         NULL,
  cmpl_dt                DATE         NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_phs PRIMARY KEY (phs_id),
  CONSTRAINT uk_phs_prj_id_phs_seq UNIQUE (prj_id, phs_seq)
);
COMMENT ON TABLE prj.phs IS '프로젝트 단계 (계획 진척률 계산, 신호등)';
COMMENT ON COLUMN prj.phs.phs_seq IS '순서';
COMMENT ON COLUMN prj.phs.phs_nm IS '단계명(착수·분석·설계·개발·테스트·오픈·종료 / 착수·인수인계·안정화·정기 운영·종료)';
COMMENT ON COLUMN prj.phs.wght_rt IS '비중(%)';
COMMENT ON COLUMN prj.phs.plan_bgn_dt IS '예정 시작일';
COMMENT ON COLUMN prj.phs.plan_end_dt IS '예정 종료일';
COMMENT ON COLUMN prj.phs.cmpl_dt IS '완료일(있으면 완료, 상태는 계산)';

CREATE TABLE prj.wkly_memo (
  memo_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME prj.seq_wkly_memo_id),
  prj_id                 BIGINT       NOT NULL,
  wrt_dt                 DATE         NOT NULL,
  wrt_user_id            BIGINT       NULL,
  actl_prgs_rt           DECIMAL(6,2) NULL,
  cmpl_work_desc         TEXT         NULL,
  plan_work_desc         TEXT         NULL,
  rqst_desc              TEXT         NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             TIMESTAMP     NULL,
  CONSTRAINT pk_wkly_memo PRIMARY KEY (memo_id),
  CONSTRAINT ck_wkly_memo_actl_prgs_rt_rng CHECK (actl_prgs_rt IS NULL OR actl_prgs_rt BETWEEN 0 AND 100)
);
COMMENT ON TABLE prj.wkly_memo IS '주간 메모 (PM 주간 상태, 14일 넘으면 주의)';
COMMENT ON COLUMN prj.wkly_memo.wrt_dt IS '작성일';
COMMENT ON COLUMN prj.wkly_memo.wrt_user_id IS '작성자';
COMMENT ON COLUMN prj.wkly_memo.actl_prgs_rt IS '실적 진척률(%) — 턴키·내부만 입력';
COMMENT ON COLUMN prj.wkly_memo.cmpl_work_desc IS '한 일';
COMMENT ON COLUMN prj.wkly_memo.plan_work_desc IS '할 일';
COMMENT ON COLUMN prj.wkly_memo.rqst_desc IS '요청 사항';

CREATE TABLE prj.issue (
  issue_id               BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME prj.seq_issue_id),
  prj_id                 BIGINT       NOT NULL,
  ttl_nm                 VARCHAR(200) NOT NULL,
  svrt_cd                VARCHAR(10)  NOT NULL DEFAULT 'MID',
  chrg_user_id           BIGINT       NULL,
  chrg_nm                VARCHAR(50)  NULL,
  ddln_dt                DATE         NULL,
  issue_stts_cd          VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
  actn_desc              TEXT         NULL,
  reg_dt                 DATE         NOT NULL,
  end_dt                 DATE         NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_issue PRIMARY KEY (issue_id),
  CONSTRAINT ck_issue_svrt_cd_vld CHECK (svrt_cd IN ('HIGH','MID','LOW')),
  CONSTRAINT ck_issue_issue_stts_cd_vld CHECK (issue_stts_cd IN ('OPEN','DOING','CLOSED'))
);
COMMENT ON TABLE prj.issue IS '프로젝트 이슈';
COMMENT ON COLUMN prj.issue.ttl_nm IS '제목';
COMMENT ON COLUMN prj.issue.svrt_cd IS '[ISSUE_SEVERITY] HIGH=높음, MID=중간, LOW=낮음';
COMMENT ON COLUMN prj.issue.chrg_user_id IS '담당자(사용자)';
COMMENT ON COLUMN prj.issue.chrg_nm IS '담당자 이름(외부 인원 등)';
COMMENT ON COLUMN prj.issue.ddln_dt IS '기한(지나면 빨간색)';
COMMENT ON COLUMN prj.issue.issue_stts_cd IS '[ISSUE_STATUS] OPEN=열림, DOING=조치 중, CLOSED=종료';
COMMENT ON COLUMN prj.issue.actn_desc IS '조치 내용';
COMMENT ON COLUMN prj.issue.reg_dt IS '등록일';
COMMENT ON COLUMN prj.issue.end_dt IS '종료일';

-- =====================================================================================
-- 7. 투입 (UI-12, UI-05, UI-28)
-- =====================================================================================

CREATE TABLE asgn.asgn (
  asgn_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME asgn.seq_asgn_id),
  asgn_no                VARCHAR(20)  NOT NULL,
  prj_id                 BIGINT       NOT NULL,
  dev_id                 BIGINT       NOT NULL,
  cntrct_dev_id          BIGINT       NULL,
  job_cd                 VARCHAR(30)  NOT NULL,
  bgn_dt                 DATE         NOT NULL,
  end_dt                 DATE         NOT NULL,
  plan_end_dt            DATE         NULL,
  asgn_rt                DECIMAL(5,2) NOT NULL DEFAULT 1.00,
  asgn_se_cd             VARCHAR(10)  NOT NULL DEFAULT 'NEW',
  untprc_base_cd         VARCHAR(10)  NOT NULL DEFAULT 'MONTH',
  ext_psblty_cd          VARCHAR(10)  NULL,
  asgn_stts_cd           VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
  rplc_asgn_id           BIGINT       NULL,
  rcmd_rslt_id           BIGINT       NULL,
  rmrk_desc              VARCHAR(500) NULL,
  upld_id                BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_asgn PRIMARY KEY (asgn_id),
  CONSTRAINT uk_asgn_asgn_no UNIQUE (asgn_no),
  CONSTRAINT ck_asgn_asgn_se_cd_vld CHECK (asgn_se_cd IN ('NEW','ADD','SUB','MOV','FREE')),
  CONSTRAINT ck_asgn_untprc_base_cd_vld CHECK (untprc_base_cd IN ('MONTH','DAY','HOUR')),
  CONSTRAINT ck_asgn_ext_psblty_cd_vld CHECK (ext_psblty_cd IS NULL OR ext_psblty_cd IN ('H','M','L')),
  CONSTRAINT ck_asgn_asgn_stts_cd_vld CHECK (asgn_stts_cd IN ('ACTIVE','ENDED','REPLACED','CANCELLED')),
  CONSTRAINT ck_asgn_end_dt_prd CHECK (end_dt >= bgn_dt)
);
COMMENT ON TABLE asgn.asgn IS '투입 이력 (누가 어디에 언제부터 언제까지). 1.0 초과·미계약 선투입은 경고만';
COMMENT ON COLUMN asgn.asgn.asgn_no IS '투입ID(A-0001)';
COMMENT ON COLUMN asgn.asgn.dev_id IS '실제 투입 인력(회사 안 집계 기준)';
COMMENT ON COLUMN asgn.asgn.cntrct_dev_id IS '계약상 인력(대체 투입일 때, 청구서 이름)';
COMMENT ON COLUMN asgn.asgn.job_cd IS '[JOB] 이 프로젝트에서 맡은 직무 1개';
COMMENT ON COLUMN asgn.asgn.bgn_dt IS '투입일';
COMMENT ON COLUMN asgn.asgn.end_dt IS '철수일(현재)';
COMMENT ON COLUMN asgn.asgn.plan_end_dt IS '처음 계획 철수일(조기 철수 비교)';
COMMENT ON COLUMN asgn.asgn.asgn_rt IS '투입률(월 MM 기본값)';
COMMENT ON COLUMN asgn.asgn.asgn_se_cd IS '[ASSIGN_KIND] NEW=신규, ADD=추가투입, SUB=대체투입, MOV=이관, FREE=무상지원(매출 0)';
COMMENT ON COLUMN asgn.asgn.untprc_base_cd IS '[RATE_BASIS] MONTH=월, DAY=일, HOUR=시간 (턴키는 배분표)';
COMMENT ON COLUMN asgn.asgn.ext_psblty_cd IS '[EXT_POSSIBILITY] H=높음, M=보통, L=낮음, NULL=미입력';
COMMENT ON COLUMN asgn.asgn.asgn_stts_cd IS 'ACTIVE=유효, ENDED=조기 철수·종료, REPLACED=교체됨, CANCELLED=취소';
COMMENT ON COLUMN asgn.asgn.rplc_asgn_id IS '교체로 생긴 배정이면 이전 배정';
COMMENT ON COLUMN asgn.asgn.rcmd_rslt_id IS 'AI 추천을 채택해 만든 배정이면 추천 결과 ID';
COMMENT ON COLUMN asgn.asgn.upld_id IS '투입 목록 업로드(B.5)';

CREATE TABLE asgn.asgn_mth (
  asgn_id                BIGINT       NOT NULL,
  work_ym                CHAR(7)      NOT NULL,
  mm_qty                 DECIMAL(5,2) NOT NULL,
  auto_mm_qty            DECIMAL(5,2) NULL,
  mnl_yn                 CHAR(1)      NOT NULL DEFAULT 'N',
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_asgn_mth PRIMARY KEY (asgn_id, work_ym),
  CONSTRAINT ck_asgn_mth_work_ym_fmt CHECK (work_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT ck_asgn_mth_mm_qty_rng CHECK (mm_qty >= 0)
);
COMMENT ON TABLE asgn.asgn_mth IS '월별 투입 상세 (투입ID × 월 × MM)';
COMMENT ON COLUMN asgn.asgn_mth.work_ym IS '월';
COMMENT ON COLUMN asgn.asgn_mth.mm_qty IS '확정 MM(소수 둘째 자리)';
COMMENT ON COLUMN asgn.asgn_mth.auto_mm_qty IS '달력일 기준 자동 계산 MM';
COMMENT ON COLUMN asgn.asgn_mth.mnl_yn IS '달별로 직접 고쳤는지';

CREATE TABLE asgn.untprc_hist (
  asgn_id                BIGINT       NOT NULL,
  aply_bgn_dt            DATE         NOT NULL,
  bill_untprc_amt        BIGINT       NULL,
  pay_untprc_amt         BIGINT       NULL,
  ovtm_untprc_amt        BIGINT       NULL,
  rsn_desc               VARCHAR(500) NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  CONSTRAINT pk_untprc_hist PRIMARY KEY (asgn_id, aply_bgn_dt)
);
COMMENT ON TABLE asgn.untprc_hist IS '단가 이력 (기간별 단가, 지난 달 숫자 불변)';
COMMENT ON COLUMN asgn.untprc_hist.aply_bgn_dt IS '적용 시작일(단가 변경 적용 시작월의 1일 등)';
COMMENT ON COLUMN asgn.untprc_hist.bill_untprc_amt IS '매출(청구) 단가 — 월/일/시간 단가, 턴키는 NULL';
COMMENT ON COLUMN asgn.untprc_hist.pay_untprc_amt IS '지급 단가(프리랜서·외주, 민감)';
COMMENT ON COLUMN asgn.untprc_hist.ovtm_untprc_amt IS '초과 근무 단가(시간 단가 파견)';

CREATE TABLE asgn.asgn_chg_hist (
  asgn_chg_id            BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME asgn.seq_asgn_chg_hist_id),
  asgn_id                BIGINT       NOT NULL,
  asgn_chg_ty_cd         VARCHAR(10)  NOT NULL,
  aply_dt                DATE         NOT NULL,
  bfr_val                VARCHAR(200) NULL,
  aft_val                VARCHAR(200) NULL,
  new_asgn_id            BIGINT       NULL,
  rsn_desc               VARCHAR(500) NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  CONSTRAINT pk_asgn_chg_hist PRIMARY KEY (asgn_chg_id),
  CONSTRAINT ck_asgn_chg_hist_asgn_chg_ty_cd_vld CHECK (asgn_chg_ty_cd IN ('REPLACE','EARLY_END','RATE','EXTEND','PERIOD'))
);
COMMENT ON TABLE asgn.asgn_chg_hist IS '투입 변경 이력 (UI-12 조회 탭)';
COMMENT ON COLUMN asgn.asgn_chg_hist.asgn_chg_ty_cd IS 'REPLACE=교체, EARLY_END=조기 철수, RATE=단가 변경, EXTEND=연장, PERIOD=기간 변경';
COMMENT ON COLUMN asgn.asgn_chg_hist.aply_dt IS '적용일';
COMMENT ON COLUMN asgn.asgn_chg_hist.new_asgn_id IS '교체로 새로 만든 배정';

CREATE TABLE asgn.tmpr_asgn (
  tmpr_asgn_id           BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME asgn.seq_tmpr_asgn_id),
  need_id                BIGINT       NOT NULL,
  dev_id                 BIGINT       NOT NULL,
  bgn_ym                 CHAR(7)      NOT NULL,
  end_ym                 CHAR(7)      NULL,
  mm_qty                 DECIMAL(5,2) NOT NULL DEFAULT 1.00,
  tmpr_asgn_src_cd       VARCHAR(10)  NOT NULL DEFAULT 'MANUAL',
  mtch_scr               DECIMAL(6,2) NULL,
  tmpr_asgn_stts_cd      VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
  asgn_id                BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_tmpr_asgn PRIMARY KEY (tmpr_asgn_id),
  CONSTRAINT ck_tmpr_asgn_tmpr_asgn_stts_cd_vld CHECK (tmpr_asgn_stts_cd IN ('ACTIVE','RELEASED','CONVERTED'))
);
COMMENT ON TABLE asgn.tmpr_asgn IS '가배정 (영업 기회 필요 인력 × 개발자, 중복 가배정은 경고)';
COMMENT ON COLUMN asgn.tmpr_asgn.need_id IS '영업 기회 필요 인력';
COMMENT ON COLUMN asgn.tmpr_asgn.bgn_ym IS '가배정 시작월';
COMMENT ON COLUMN asgn.tmpr_asgn.end_ym IS '가배정 종료월';
COMMENT ON COLUMN asgn.tmpr_asgn.mm_qty IS '월 MM';
COMMENT ON COLUMN asgn.tmpr_asgn.tmpr_asgn_src_cd IS 'MANUAL=UI-29 직접, NEXT=UI-05 다음 자리 추천, AI=UI-30';
COMMENT ON COLUMN asgn.tmpr_asgn.mtch_scr IS '다음 자리 점수(3.13) 또는 AI 점수';
COMMENT ON COLUMN asgn.tmpr_asgn.tmpr_asgn_stts_cd IS 'ACTIVE=가배정, RELEASED=해제(실주 등), CONVERTED=수주 후 실제 배정';
COMMENT ON COLUMN asgn.tmpr_asgn.asgn_id IS '실제 배정으로 바뀌었으면 투입ID';

-- =====================================================================================
-- 8. 외부 인력 후보 (UI-33)
-- =====================================================================================

CREATE TABLE hr.cand (
  cand_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME hr.seq_cand_id),
  cand_no                VARCHAR(20)  NOT NULL,
  cand_nm                VARCHAR(50)  NOT NULL,
  cand_se_cd             VARCHAR(10)  NOT NULL,
  vndr_ptnr_id           BIGINT       NULL,
  job_cd                 VARCHAR(30)  NOT NULL,
  grd_cd                 VARCHAR(30)  NULL,
  hope_untprc_amt        BIGINT       NULL,
  asgn_psbl_dt           DATE         NULL,
  cntct_stts_cd          VARCHAR(10)  NOT NULL DEFAULT 'NONE',
  last_cntct_dt          DATE         NULL,
  cand_src_cd            VARCHAR(30)  NULL,
  tel_no                 VARCHAR(30)  NULL,
  email_addr             VARCHAR(100) NULL,
  memo_desc              TEXT         NULL,
  dev_id                 BIGINT       NULL,
  use_yn                 CHAR(1)      NOT NULL DEFAULT 'Y',
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_cand PRIMARY KEY (cand_id),
  CONSTRAINT uk_cand_cand_no UNIQUE (cand_no),
  CONSTRAINT ck_cand_cand_se_cd_vld CHECK (cand_se_cd IN ('FL','OS')),
  CONSTRAINT ck_cand_cntct_stts_cd_vld CHECK (cntct_stts_cd IN ('NONE','DONE','INTV','HOLD','SIGNED'))
);
COMMENT ON TABLE hr.cand IS '외부 인력 후보 (프리랜서·외주)';
COMMENT ON COLUMN hr.cand.cand_no IS '후보 코드';
COMMENT ON COLUMN hr.cand.cand_nm IS '이름';
COMMENT ON COLUMN hr.cand.cand_se_cd IS '[EMP_TYPE] FL=프리랜서, OS=외주';
COMMENT ON COLUMN hr.cand.vndr_ptnr_id IS '소속 업체(외주 필수)';
COMMENT ON COLUMN hr.cand.job_cd IS '[JOB] 직무';
COMMENT ON COLUMN hr.cand.grd_cd IS '[GRADE] 등급';
COMMENT ON COLUMN hr.cand.hope_untprc_amt IS '희망 단가(월)';
COMMENT ON COLUMN hr.cand.asgn_psbl_dt IS '투입 가능일';
COMMENT ON COLUMN hr.cand.cntct_stts_cd IS '[CONTACT_STATUS] NONE=연락 전, DONE=연락함, INTV=면접 예정, HOLD=보류, SIGNED=계약 완료';
COMMENT ON COLUMN hr.cand.last_cntct_dt IS '최근 연락일(30일 넘으면 표시)';
COMMENT ON COLUMN hr.cand.cand_src_cd IS '[CANDIDATE_SOURCE] 출처(구인 사이트/협력사 추천/지인 추천/과거 투입)';
COMMENT ON COLUMN hr.cand.memo_desc IS '메모';
COMMENT ON COLUMN hr.cand.dev_id IS '계약해 개발자로 등록됐으면 개발자 ID';

CREATE TABLE hr.cand_skl (
  cand_id                BIGINT       NOT NULL,
  skl_cd                 VARCHAR(30)  NOT NULL,
  CONSTRAINT pk_cand_skl PRIMARY KEY (cand_id, skl_cd)
);
COMMENT ON TABLE hr.cand_skl IS '후보 기술';
COMMENT ON COLUMN hr.cand_skl.skl_cd IS '[SKILL] 기술';

CREATE TABLE hr.cand_cntct_hist (
  cntct_hist_id          BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME hr.seq_cand_cntct_hist_id),
  cand_id                BIGINT       NOT NULL,
  cntct_dt               DATE         NOT NULL,
  cntct_stts_cd          VARCHAR(10)  NOT NULL,
  memo_desc              VARCHAR(1000) NULL,
  rcrd_user_id           BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_cand_cntct_hist PRIMARY KEY (cntct_hist_id)
);
COMMENT ON TABLE hr.cand_cntct_hist IS '후보 연락 기록';
COMMENT ON COLUMN hr.cand_cntct_hist.cntct_dt IS '날짜';
COMMENT ON COLUMN hr.cand_cntct_hist.cntct_stts_cd IS '[CONTACT_STATUS] 연락 상태';
COMMENT ON COLUMN hr.cand_cntct_hist.memo_desc IS '메모';
COMMENT ON COLUMN hr.cand_cntct_hist.rcrd_user_id IS '기록한 사람';

-- =====================================================================================
-- 9. 원가 (UI-02 급여 업로드, UI-03, UI-04)
-- =====================================================================================

CREATE TABLE cst.mth_cst (
  mth_cst_id             BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME cst.seq_mth_cst_id),
  dev_id                 BIGINT       NOT NULL,
  cst_ym                 CHAR(7)      NOT NULL,
  emp_se_cd              VARCHAR(10)  NOT NULL,
  duty_se_cd             VARCHAR(10)  NOT NULL DEFAULT 'DIRECT',
  pstn_cd                VARCHAR(30)  NULL,
  bsn_part_id            BIGINT       NULL,
  slry_amt               BIGINT       NOT NULL DEFAULT 0,
  vat_amt                BIGINT       NOT NULL DEFAULT 0,
  alwnc_amt              BIGINT       NOT NULL DEFAULT 0,
  sins_cmpny_amt         BIGINT       NOT NULL DEFAULT 0,
  sins_emp_amt           BIGINT       NOT NULL DEFAULT 0,
  whtax_amt              BIGINT       NOT NULL DEFAULT 0,
  ern_tax_amt            BIGINT       NOT NULL DEFAULT 0,
  rtrpay_acml_amt        BIGINT       NOT NULL DEFAULT 0,
  indv_lbr_cst_amt       BIGINT GENERATED ALWAYS AS (
                        CASE WHEN emp_se_cd IN ('FT','CT')
                        THEN slry_amt + alwnc_amt + sins_cmpny_amt + rtrpay_acml_amt
                        ELSE slry_amt END) STORED,
  real_pay_amt           BIGINT GENERATED ALWAYS AS (
                        CASE WHEN emp_se_cd = 'OS'
                        THEN slry_amt + vat_amt
                        ELSE slry_amt + alwnc_amt - sins_emp_amt - whtax_amt - ern_tax_amt END) STORED,
  idle_cst_amt           BIGINT       NULL,
  excel_diff_desc        VARCHAR(500) NULL,
  upld_id                BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_mth_cst PRIMARY KEY (mth_cst_id),
  CONSTRAINT uk_mth_cst_dev_id_cst_ym UNIQUE (dev_id, cst_ym),
  CONSTRAINT ck_mth_cst_cst_ym_fmt CHECK (cst_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT ck_mth_cst_emp_se_cd_vld CHECK (emp_se_cd IN ('FT','CT','FL','OS'))
);
COMMENT ON TABLE cst.mth_cst IS '월별 원가 (사람별·월별, 급여 엑셀 업로드 결과 — 같은 연월 재업로드는 덮어씀, 마감 월 불가)';
COMMENT ON COLUMN cst.mth_cst.cst_ym IS '연월';
COMMENT ON COLUMN cst.mth_cst.emp_se_cd IS '[EMP_TYPE] 그 달 기준 직원구분(스냅샷)';
COMMENT ON COLUMN cst.mth_cst.duty_se_cd IS '그 달 업무 구분(INDIRECT 는 판관비)';
COMMENT ON COLUMN cst.mth_cst.pstn_cd IS '그 달 직위(스냅샷)';
COMMENT ON COLUMN cst.mth_cst.bsn_part_id IS '그 달 소속 영업 파트(스냅샷)';
COMMENT ON COLUMN cst.mth_cst.slry_amt IS '월 급여(J열). 프리랜서=그 달 지급액(원천세 전), 외주=협력사 공급가액';
COMMENT ON COLUMN cst.mth_cst.vat_amt IS '부가세(외주만, 원가 미포함)';
COMMENT ON COLUMN cst.mth_cst.alwnc_amt IS '그룹장·팀장 수당(K열)';
COMMENT ON COLUMN cst.mth_cst.sins_cmpny_amt IS '4대보험 회사분(L열)';
COMMENT ON COLUMN cst.mth_cst.sins_emp_amt IS '4대보험 본인분(M열, 원가 영향 없음)';
COMMENT ON COLUMN cst.mth_cst.whtax_amt IS '3.3% 원천세(N열, 계약직·프리랜서)';
COMMENT ON COLUMN cst.mth_cst.ern_tax_amt IS '근로소득세(새 열)';
COMMENT ON COLUMN cst.mth_cst.rtrpay_acml_amt IS '퇴직금 적립(O열, 정규직)';
COMMENT ON COLUMN cst.mth_cst.indv_lbr_cst_amt IS '개인 월 인건비(엑셀 Q열, 계산)';
COMMENT ON COLUMN cst.mth_cst.real_pay_amt IS '실지급액(지급 예정 자동 생성 기준, 계산)';
COMMENT ON COLUMN cst.mth_cst.idle_cst_amt IS '대기 원가(정규·계약, 마감 시 확정)';
COMMENT ON COLUMN cst.mth_cst.excel_diff_desc IS '엑셀 계산값(P,Q,T,U열)과 차이 경고 내용';
COMMENT ON COLUMN cst.mth_cst.upld_id IS '급여 업로드(B.1)';

CREATE TABLE cst.drct_expns (
  expns_id               BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME cst.seq_drct_expns_id),
  expns_ym               CHAR(7)      NOT NULL,
  prj_id                 BIGINT       NULL,
  dev_id                 BIGINT       NULL,
  expns_item_cd          VARCHAR(30)  NOT NULL,
  expns_amt              BIGINT       NOT NULL,
  vat_amt                BIGINT       NOT NULL DEFAULT 0,
  pay_dt                 DATE         NULL,
  rmrk_desc              VARCHAR(200) NULL,
  upld_id                BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_drct_expns PRIMARY KEY (expns_id),
  CONSTRAINT ck_drct_expns_expns_ym_fmt CHECK (expns_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT ck_drct_expns_prj_id_req CHECK (prj_id IS NOT NULL OR dev_id IS NOT NULL)
);
COMMENT ON TABLE cst.drct_expns IS '직접 경비 (팀비·프로젝트경비·출장비 등, 외주 인력비 제외)';
COMMENT ON COLUMN cst.drct_expns.expns_ym IS '월';
COMMENT ON COLUMN cst.drct_expns.prj_id IS '프로젝트 전용 경비면 프로젝트';
COMMENT ON COLUMN cst.drct_expns.dev_id IS '사람에게 붙은 경비(팀비·프로젝트경비)면 개발자 — MM 비율로 배분, 투입 없으면 대기 원가';
COMMENT ON COLUMN cst.drct_expns.expns_item_cd IS '[EXPENSE_ITEM] TEAM=팀비, PEXP=프로젝트경비, TRIP=출장비, ETC ...';
COMMENT ON COLUMN cst.drct_expns.expns_amt IS '금액(부가세 제외)';
COMMENT ON COLUMN cst.drct_expns.vat_amt IS '부가세';
COMMENT ON COLUMN cst.drct_expns.pay_dt IS '지급(예정)일 — 지급 관리 자동 생성';
COMMENT ON COLUMN cst.drct_expns.upld_id IS '급여 업로드(R·S열)로 들어왔으면';

CREATE TABLE cst.cst_aloc (
  cst_aloc_id            BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME cst.seq_cst_aloc_id),
  cst_ym                 CHAR(7)      NOT NULL,
  dev_id                 BIGINT       NOT NULL,
  prj_id                 BIGINT       NULL,
  asgn_id                BIGINT       NULL,
  aloc_ty_cd             VARCHAR(10)  NOT NULL,
  mm_qty                 DECIMAL(5,2) NULL,
  tot_mm_qty             DECIMAL(5,2) NULL,
  aloc_rt                DECIMAL(9,6) NULL,
  lbr_cst_amt            BIGINT       NOT NULL DEFAULT 0,
  drct_expns_amt         BIGINT       NOT NULL DEFAULT 0,
  sls_amt                BIGINT       NOT NULL DEFAULT 0,
  bill_untprc_amt        BIGINT       NULL,
  mm_ovr_yn              CHAR(1)      NOT NULL DEFAULT 'N',
  free_sprt_yn           CHAR(1)      NOT NULL DEFAULT 'N',
  pre_cntrct_yn          CHAR(1)      NOT NULL DEFAULT 'N',
  calc_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_cst_aloc PRIMARY KEY (cst_aloc_id),
  CONSTRAINT ck_cst_aloc_aloc_ty_cd_vld CHECK (aloc_ty_cd IN ('PROJECT','IDLE','RND','INDIRECT'))
);
COMMENT ON TABLE cst.cst_aloc IS '원가 배분 결과 (월 마감 시 확정 스냅샷 — 마감 후 숫자 불변, 작업중 월은 재계산)';
COMMENT ON COLUMN cst.cst_aloc.cst_ym IS '월';
COMMENT ON COLUMN cst.cst_aloc.prj_id IS '프로젝트(대기·간접이면 NULL)';
COMMENT ON COLUMN cst.cst_aloc.asgn_id IS '투입ID';
COMMENT ON COLUMN cst.cst_aloc.aloc_ty_cd IS 'PROJECT=배분 원가, IDLE=대기 원가, RND=연구개발비(내부), INDIRECT=간접 인건비';
COMMENT ON COLUMN cst.cst_aloc.mm_qty IS '그 프로젝트 MM';
COMMENT ON COLUMN cst.cst_aloc.tot_mm_qty IS '그 사람 그 달 MM 합계';
COMMENT ON COLUMN cst.cst_aloc.aloc_rt IS '배분 비율 = MM ÷ max(합계, 1.0)';
COMMENT ON COLUMN cst.cst_aloc.lbr_cst_amt IS '배분 인건비';
COMMENT ON COLUMN cst.cst_aloc.drct_expns_amt IS '배분 직접 경비';
COMMENT ON COLUMN cst.cst_aloc.sls_amt IS '개인 매출(단가×MM 또는 턴키 배분 금액, 무상지원 0)';
COMMENT ON COLUMN cst.cst_aloc.bill_untprc_amt IS '적용 단가';
COMMENT ON COLUMN cst.cst_aloc.mm_ovr_yn IS '1.0 초과 달 표시';
COMMENT ON COLUMN cst.cst_aloc.free_sprt_yn IS '무상지원';
COMMENT ON COLUMN cst.cst_aloc.pre_cntrct_yn IS '수주예정 프로젝트 선투입(실주 시 대기 원가로 이동)';
COMMENT ON COLUMN cst.cst_aloc.calc_at IS '계산 일시';

CREATE TABLE cst.prvmn_adj (
  adj_id                 BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME cst.seq_prvmn_adj_id),
  aply_ym                CHAR(7)      NOT NULL,
  orgnl_ym               CHAR(7)      NOT NULL,
  adj_se_cd              VARCHAR(10)  NOT NULL,
  prj_id                 BIGINT       NULL,
  dev_id                 BIGINT       NULL,
  adj_amt                BIGINT       NOT NULL,
  rsn_desc               VARCHAR(500) NOT NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  CONSTRAINT pk_prvmn_adj PRIMARY KEY (adj_id),
  CONSTRAINT ck_prvmn_adj_adj_se_cd_vld CHECK (adj_se_cd IN ('REVENUE','COST','SGA','ETC')),
  CONSTRAINT ck_prvmn_adj_orgnl_ym_ord CHECK (orgnl_ym < aply_ym)
);
COMMENT ON TABLE cst.prvmn_adj IS '전월 조정 (마감 후 정정은 이번 달 조정 줄로, 명세 3.9)';
COMMENT ON COLUMN cst.prvmn_adj.aply_ym IS '반영하는 달(이번 달)';
COMMENT ON COLUMN cst.prvmn_adj.orgnl_ym IS '원래 달(마감된 달)';
COMMENT ON COLUMN cst.prvmn_adj.adj_se_cd IS 'REVENUE=매출, COST=원가, SGA=판관비, ETC';
COMMENT ON COLUMN cst.prvmn_adj.adj_amt IS '차액(+/−) — 영업이익 계산 시 ± 반영';
COMMENT ON COLUMN cst.prvmn_adj.rsn_desc IS '사유';

-- =====================================================================================
-- 10. 청구·입금 (UI-13)
-- =====================================================================================

CREATE TABLE bill.bill (
  bill_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME bill.seq_bill_id),
  bill_no                VARCHAR(20)  NOT NULL,
  prj_id                 BIGINT       NOT NULL,
  bill_stg_id            BIGINT       NULL,
  bill_odr_no            INT          NOT NULL DEFAULT 1,
  atrb_ym                CHAR(7)      NULL,
  bill_dt                DATE         NULL,
  sply_amt               BIGINT       NOT NULL DEFAULT 0,
  vat_amt                BIGINT       NOT NULL DEFAULT 0,
  tot_amt                BIGINT GENERATED ALWAYS AS (sply_amt + vat_amt) STORED,
  bill_stts_cd           VARCHAR(10)  NOT NULL DEFAULT 'DRAFT',
  txinv_no               VARCHAR(50)  NULL,
  txinv_dt               DATE         NULL,
  amnd_yn                CHAR(1)      NOT NULL DEFAULT 'N',
  orgnl_bill_id          BIGINT       NULL,
  rcpt_plan_dt           DATE         NULL,
  dun_stop_yn            CHAR(1)      NOT NULL DEFAULT 'N',
  rmrk_desc              VARCHAR(500) NULL,
  cnfrm_user_id          BIGINT       NULL,
  cnfrm_at               TIMESTAMP     NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_bill PRIMARY KEY (bill_id),
  CONSTRAINT uk_bill_bill_no UNIQUE (bill_no),
  CONSTRAINT ck_bill_bill_stts_cd_vld CHECK (bill_stts_cd IN ('PLAN','DRAFT','CONFIRM','CANCEL'))
);
COMMENT ON TABLE bill.bill IS '청구 (고객에게 보낸 청구). 입금은 매출에 영향 없음 — 미수금·자금수지에만 사용';
COMMENT ON COLUMN bill.bill.bill_no IS '청구번호(B-2610-01)';
COMMENT ON COLUMN bill.bill.bill_stg_id IS '턴키 청구 단계';
COMMENT ON COLUMN bill.bill.bill_odr_no IS '차수';
COMMENT ON COLUMN bill.bill.atrb_ym IS '귀속월(월단위·파견 = 매출 잡는 달). 턴키는 청구일의 달';
COMMENT ON COLUMN bill.bill.bill_dt IS '청구일(턴키 매출 기준일)';
COMMENT ON COLUMN bill.bill.sply_amt IS '공급가액(마이너스 = 수정세금계산서)';
COMMENT ON COLUMN bill.bill.vat_amt IS '부가세(10% 자동, 수정 가능)';
COMMENT ON COLUMN bill.bill.tot_amt IS '합계(계산)';
COMMENT ON COLUMN bill.bill.bill_stts_cd IS '[BILL_STATUS] PLAN=청구 예정, DRAFT=작성(자동 생성), CONFIRM=확정, CANCEL=취소';
COMMENT ON COLUMN bill.bill.txinv_no IS '세금계산서 번호(발행은 범위 밖, 번호만 기록)';
COMMENT ON COLUMN bill.bill.txinv_dt IS '세금계산서 작성일';
COMMENT ON COLUMN bill.bill.amnd_yn IS '수정세금계산서(마이너스 청구)';
COMMENT ON COLUMN bill.bill.orgnl_bill_id IS '수정 대상 원 청구';
COMMENT ON COLUMN bill.bill.rcpt_plan_dt IS '입금 예정일(미수 알림 기준)';
COMMENT ON COLUMN bill.bill.dun_stop_yn IS '미수 알림 중지';
COMMENT ON COLUMN bill.bill.rmrk_desc IS '비고';

CREATE TABLE bill.bill_dtl (
  bill_dtl_id            BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME bill.seq_bill_dtl_id),
  bill_id                BIGINT       NOT NULL,
  asgn_id                BIGINT       NULL,
  cntrct_dev_id          BIGINT       NULL,
  work_ym                CHAR(7)      NOT NULL,
  mm_qty                 DECIMAL(5,2) NULL,
  untprc_amt             BIGINT       NULL,
  work_day_qty           DECIMAL(5,1) NULL,
  work_tm_qty            DECIMAL(6,1) NULL,
  ovtm_tm_qty            DECIMAL(6,1) NULL,
  ovtm_amt               BIGINT       NOT NULL DEFAULT 0,
  bill_amt               BIGINT       NOT NULL,
  rmrk_desc              VARCHAR(200) NULL,
  CONSTRAINT pk_bill_dtl PRIMARY KEY (bill_dtl_id)
);
COMMENT ON TABLE bill.bill_dtl IS '청구 상세 (인력별·월별 내역)';
COMMENT ON COLUMN bill.bill_dtl.asgn_id IS '투입ID';
COMMENT ON COLUMN bill.bill_dtl.cntrct_dev_id IS '청구서에 쓰는 계약상 인력';
COMMENT ON COLUMN bill.bill_dtl.work_ym IS '월';
COMMENT ON COLUMN bill.bill_dtl.mm_qty IS 'MM';
COMMENT ON COLUMN bill.bill_dtl.untprc_amt IS '단가';
COMMENT ON COLUMN bill.bill_dtl.work_day_qty IS '근무일수(일 단가 파견)';
COMMENT ON COLUMN bill.bill_dtl.work_tm_qty IS '정규 근무시간(시간 단가 파견)';
COMMENT ON COLUMN bill.bill_dtl.ovtm_tm_qty IS '초과 근무시간';
COMMENT ON COLUMN bill.bill_dtl.ovtm_amt IS '초과 근무 금액';
COMMENT ON COLUMN bill.bill_dtl.bill_amt IS '금액(공급가)';

CREATE TABLE bill.rcpt (
  rcpt_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME bill.seq_rcpt_id),
  bill_id                BIGINT       NOT NULL,
  rcpt_dt                DATE         NOT NULL,
  rcpt_amt               BIGINT       NOT NULL,
  rmrk_desc              VARCHAR(200) NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  CONSTRAINT pk_rcpt PRIMARY KEY (rcpt_id)
);
COMMENT ON TABLE bill.rcpt IS '입금 (청구 1건에 여러 번 분할 입금 가능)';
COMMENT ON COLUMN bill.rcpt.rcpt_dt IS '입금일';
COMMENT ON COLUMN bill.rcpt.rcpt_amt IS '입금액(부가세 포함)';

CREATE TABLE bill.dun_hist (
  dun_hist_id            BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME bill.seq_dun_hist_id),
  bill_id                BIGINT       NOT NULL,
  dun_stg_cd             CHAR(1)      NOT NULL,
  elps_day_qty           INT          NOT NULL,
  rcvr_user_id           BIGINT       NULL,
  rcvr_email_addr        VARCHAR(100) NULL,
  send_mthd_cd           VARCHAR(10)  NOT NULL,
  mnl_yn                 CHAR(1)      NOT NULL DEFAULT 'N',
  send_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  send_user_id           BIGINT       NULL,
  CONSTRAINT pk_dun_hist PRIMARY KEY (dun_hist_id),
  CONSTRAINT ck_dun_hist_dun_stg_cd_vld CHECK (dun_stg_cd IN ('1','2','3')),
  CONSTRAINT ck_dun_hist_send_mthd_cd_vld CHECK (send_mthd_cd IN ('EMAIL','SYSTEM'))
);
COMMENT ON TABLE bill.dun_hist IS '미수 알림 기록';
COMMENT ON COLUMN bill.dun_hist.dun_stg_cd IS '단계 1(7일)·2(30일)·3(60일)';
COMMENT ON COLUMN bill.dun_hist.elps_day_qty IS '보낼 때 지난 일수';
COMMENT ON COLUMN bill.dun_hist.rcvr_user_id IS '받는 사람';
COMMENT ON COLUMN bill.dun_hist.send_mthd_cd IS 'EMAIL=메일, SYSTEM=시스템 알림';
COMMENT ON COLUMN bill.dun_hist.mnl_yn IS '[다시 알림]으로 보냈는지';

CREATE TABLE bill.dspch_atnd (
  atnd_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME bill.seq_dspch_atnd_id),
  work_ym                CHAR(7)      NOT NULL,
  prj_id                 BIGINT       NOT NULL,
  dev_id                 BIGINT       NOT NULL,
  asgn_id                BIGINT       NULL,
  work_day_qty           DECIMAL(5,1) NULL,
  rglr_tm_qty            DECIMAL(6,1) NULL,
  ovtm_tm_qty            DECIMAL(6,1) NULL,
  mth_work_day_qty       DECIMAL(5,1) NULL,
  upld_id                BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_dspch_atnd PRIMARY KEY (atnd_id),
  CONSTRAINT uk_dspch_atnd_work_ym_prj_id_dev_id UNIQUE (work_ym, prj_id, dev_id),
  CONSTRAINT ck_dspch_atnd_work_ym_fmt CHECK (work_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);
COMMENT ON TABLE bill.dspch_atnd IS '파견 근태 (일·시간 단가 파견 → 청구 자동 생성)';
COMMENT ON COLUMN bill.dspch_atnd.work_ym IS '귀속월';
COMMENT ON COLUMN bill.dspch_atnd.work_day_qty IS '근무일수(일 단가 필수)';
COMMENT ON COLUMN bill.dspch_atnd.rglr_tm_qty IS '정규 근무시간(시간 단가 필수)';
COMMENT ON COLUMN bill.dspch_atnd.ovtm_tm_qty IS '초과 근무시간';
COMMENT ON COLUMN bill.dspch_atnd.mth_work_day_qty IS '그 달 근무일수(MM = 근무일수 ÷ 이 값)';
COMMENT ON COLUMN bill.dspch_atnd.upld_id IS '파견 근태 업로드(B.3)';

-- =====================================================================================
-- 11. 재무 항목·법인세 (UI-10)
-- =====================================================================================

CREATE TABLE fin.entry (
  entry_id               BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME fin.seq_entry_id),
  entry_ym               CHAR(7)      NOT NULL,
  fin_se_cd              VARCHAR(10)  NOT NULL,
  item_cd                VARCHAR(30)  NULL,
  item_nm                VARCHAR(100) NOT NULL,
  entry_amt              BIGINT       NOT NULL,
  vat_amt                BIGINT       NOT NULL DEFAULT 0,
  inout_dt               DATE         NULL,
  inout_cmpl_yn          CHAR(1)      NOT NULL DEFAULT 'N',
  rpt_yn                 CHAR(1)      NOT NULL DEFAULT 'N',
  auto_fill_yn           CHAR(1)      NOT NULL DEFAULT 'N',
  copy_entry_id          BIGINT       NULL,
  rmrk_desc              VARCHAR(500) NULL,
  upld_id                BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_entry PRIMARY KEY (entry_id),
  CONSTRAINT ck_entry_entry_ym_fmt CHECK (entry_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT ck_entry_fin_se_cd_vld CHECK (fin_se_cd IN ('SGA','NOI','NOE'))
);
COMMENT ON TABLE fin.entry IS '재무 입력 (판관비·영업외손익 월별 입력)';
COMMENT ON COLUMN fin.entry.entry_ym IS '연월';
COMMENT ON COLUMN fin.entry.fin_se_cd IS '[FIN_CATEGORY] SGA=판관비, NOI=영업외수익, NOE=영업외비용';
COMMENT ON COLUMN fin.entry.item_cd IS '[SGA_ITEM 등] 세부 항목 코드';
COMMENT ON COLUMN fin.entry.item_nm IS '세부 항목명';
COMMENT ON COLUMN fin.entry.entry_amt IS '금액(부가세 제외)';
COMMENT ON COLUMN fin.entry.vat_amt IS '부가세';
COMMENT ON COLUMN fin.entry.inout_dt IS '입출금일(넣으면 자금수지 예정·실적 자동 생성)';
COMMENT ON COLUMN fin.entry.inout_cmpl_yn IS '실제 입출금 완료';
COMMENT ON COLUMN fin.entry.rpt_yn IS '매달 반복(새 달에 지난달 금액으로 자동 채움)';
COMMENT ON COLUMN fin.entry.auto_fill_yn IS '자동 채움 상태(저장해야 확정)';
COMMENT ON COLUMN fin.entry.copy_entry_id IS '[지난달 항목 불러오기] 원본';
COMMENT ON COLUMN fin.entry.rmrk_desc IS '비고';
COMMENT ON COLUMN fin.entry.upld_id IS '재무 항목 업로드(B.2)';

CREATE TABLE fin.corp_tax_plan (
  tax_yr                 CHAR(4)      NOT NULL,
  annl_expc_amt          BIGINT       NOT NULL DEFAULT 0,
  rmrk_desc              VARCHAR(500) NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_corp_tax_plan PRIMARY KEY (tax_yr)
);
COMMENT ON TABLE fin.corp_tax_plan IS '법인세 계획 (연간 예상액). 월 법인세 = (연간 예상 − 마감 반영액) ÷ 남은 개월';
COMMENT ON COLUMN fin.corp_tax_plan.tax_yr IS '연도';
COMMENT ON COLUMN fin.corp_tax_plan.annl_expc_amt IS '연간 예상 법인세';

CREATE TABLE fin.corp_tax_expc_hist (
  expc_hist_id           BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME fin.seq_corp_tax_expc_hist_id),
  tax_yr                 CHAR(4)      NOT NULL,
  bfr_expc_amt           BIGINT       NULL,
  aft_expc_amt           BIGINT       NOT NULL,
  rsn_desc               VARCHAR(500) NULL,
  chg_user_id            BIGINT       NULL,
  chg_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_corp_tax_expc_hist PRIMARY KEY (expc_hist_id)
);
COMMENT ON TABLE fin.corp_tax_expc_hist IS '연간 예상 법인세 변경 이력';

CREATE TABLE fin.corp_tax_mth (
  tax_ym                 CHAR(7)      NOT NULL,
  tax_yr                 CHAR(4)      NOT NULL,
  corp_tax_amt           BIGINT       NOT NULL,
  cnfrm_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_corp_tax_mth PRIMARY KEY (tax_ym)
);
COMMENT ON TABLE fin.corp_tax_mth IS '법인세 월할 반영액 (마감 때 확정, 마감된 달은 그대로)';
COMMENT ON COLUMN fin.corp_tax_mth.tax_ym IS '월';
COMMENT ON COLUMN fin.corp_tax_mth.corp_tax_amt IS '그 달 반영액(원 단위 버림, 끝자리는 12월)';
COMMENT ON COLUMN fin.corp_tax_mth.cnfrm_at IS '마감 때 확정된 일시';

CREATE TABLE fin.corp_tax_pay (
  tax_pay_id             BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME fin.seq_corp_tax_pay_id),
  tax_yr                 CHAR(4)      NOT NULL,
  tax_pay_se_cd          VARCHAR(10)  NOT NULL,
  pay_plan_dt            DATE         NOT NULL,
  plan_amt               BIGINT       NULL,
  pay_dt                 DATE         NULL,
  pay_amt                BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  CONSTRAINT pk_corp_tax_pay PRIMARY KEY (tax_pay_id),
  CONSTRAINT uk_corp_tax_pay_tax_yr_tax_pay_se_cd UNIQUE (tax_yr, tax_pay_se_cd),
  CONSTRAINT ck_corp_tax_pay_tax_pay_se_cd_vld CHECK (tax_pay_se_cd IN ('FINAL','INTERIM'))
);
COMMENT ON TABLE fin.corp_tax_pay IS '법인세 납부 일정·실적 (자금수지에만 잡음)';
COMMENT ON COLUMN fin.corp_tax_pay.tax_yr IS '귀속 연도';
COMMENT ON COLUMN fin.corp_tax_pay.tax_pay_se_cd IS 'FINAL=확정 신고(3월), INTERIM=중간예납(8월)';
COMMENT ON COLUMN fin.corp_tax_pay.pay_plan_dt IS '납부일';
COMMENT ON COLUMN fin.corp_tax_pay.plan_amt IS '예정 금액(확정 신고 = 연간 예상 − 중간예납)';
COMMENT ON COLUMN fin.corp_tax_pay.pay_dt IS '실제 납부일';
COMMENT ON COLUMN fin.corp_tax_pay.pay_amt IS '실제 납부액(자금수지 법인세 납부 줄)';

-- =====================================================================================
-- 12. 사업계획·영업 파트 정산 (UI-18, UI-19)
-- =====================================================================================

CREATE TABLE fin.biz_plan (
  biz_plan_id            BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME fin.seq_biz_plan_id),
  plan_yr                CHAR(4)      NOT NULL,
  plan_ym                CHAR(7)      NOT NULL,
  bsn_part_id            BIGINT       NULL,
  trgt_sls_amt           BIGINT       NOT NULL DEFAULT 0,
  trgt_bsn_prft_amt      BIGINT       NOT NULL DEFAULT 0,
  trgt_utlz_rt           DECIMAL(6,2) NULL,
  upld_id                BIGINT       NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_biz_plan PRIMARY KEY (biz_plan_id),
  CONSTRAINT uk_biz_plan_plan_ym_bsn_part_id UNIQUE NULLS NOT DISTINCT (plan_ym, bsn_part_id),
  CONSTRAINT ck_biz_plan_plan_ym_fmt CHECK (plan_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);
COMMENT ON TABLE fin.biz_plan IS '사업계획 (연간·월별 목표)';
COMMENT ON COLUMN fin.biz_plan.plan_yr IS '연도';
COMMENT ON COLUMN fin.biz_plan.plan_ym IS '월';
COMMENT ON COLUMN fin.biz_plan.bsn_part_id IS '영업 파트(NULL=전사)';
COMMENT ON COLUMN fin.biz_plan.trgt_sls_amt IS '목표 매출';
COMMENT ON COLUMN fin.biz_plan.trgt_bsn_prft_amt IS '목표 영업이익';
COMMENT ON COLUMN fin.biz_plan.trgt_utlz_rt IS '목표 가동률(%)';
COMMENT ON COLUMN fin.biz_plan.upld_id IS '사업계획 업로드(B.10)';

CREATE TABLE fin.part_stlm (
  stlm_id                BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME fin.seq_part_stlm_id),
  bsn_part_id            BIGINT       NOT NULL,
  stlm_ym                CHAR(7)      NOT NULL,
  part_std_hist_id       BIGINT       NULL,
  prft_dstrb_rt          DECIMAL(6,2) NULL,
  bsn_fee_amt            BIGINT       NULL,
  psn_cnt                INT          NULL,
  tot_sls_amt            BIGINT       NULL,
  tot_exp_amt            BIGINT       NULL,
  tot_diff_amt           BIGINT       NULL,
  tot_psn_prft_amt       BIGINT       NULL,
  corp_card_amt          BIGINT       NOT NULL DEFAULT 0,
  part_prft_amt          BIGINT       NULL,
  stlm_stts_cd           VARCHAR(10)  NOT NULL DEFAULT 'DRAFT',
  cnfrm_user_id          BIGINT       NULL,
  cnfrm_at               TIMESTAMP     NULL,
  cncl_rsn_desc          VARCHAR(500) NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_part_stlm PRIMARY KEY (stlm_id),
  CONSTRAINT uk_part_stlm_bsn_part_id_stlm_ym UNIQUE (bsn_part_id, stlm_ym),
  CONSTRAINT ck_part_stlm_stlm_ym_fmt CHECK (stlm_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT ck_part_stlm_stlm_stts_cd_vld CHECK (stlm_stts_cd IN ('DRAFT','CONFIRM'))
);
COMMENT ON TABLE fin.part_stlm IS '영업 파트 월 정산 (계산 후 확정할 때 저장)';
COMMENT ON COLUMN fin.part_stlm.stlm_ym IS '월';
COMMENT ON COLUMN fin.part_stlm.part_std_hist_id IS '적용한 영업 파트 기준 이력';
COMMENT ON COLUMN fin.part_stlm.prft_dstrb_rt IS '적용 이익 배분율(스냅샷)';
COMMENT ON COLUMN fin.part_stlm.bsn_fee_amt IS '적용 영업비(스냅샷)';
COMMENT ON COLUMN fin.part_stlm.psn_cnt IS '인원';
COMMENT ON COLUMN fin.part_stlm.tot_sls_amt IS '월 단가 합계(매출)';
COMMENT ON COLUMN fin.part_stlm.tot_exp_amt IS '비용 합계(급여+영업비+퇴직금)';
COMMENT ON COLUMN fin.part_stlm.tot_diff_amt IS '차액 합계';
COMMENT ON COLUMN fin.part_stlm.tot_psn_prft_amt IS '인력별 이익금 합계(적자 상계)';
COMMENT ON COLUMN fin.part_stlm.corp_card_amt IS '법인카드 사용액(확정 후 읽기 전용)';
COMMENT ON COLUMN fin.part_stlm.part_prft_amt IS '파트 이익금 = 이익금 합계 − 법인카드';
COMMENT ON COLUMN fin.part_stlm.stlm_stts_cd IS 'DRAFT=작업중, CONFIRM=확정(잠금)';
COMMENT ON COLUMN fin.part_stlm.cnfrm_user_id IS '확정자';
COMMENT ON COLUMN fin.part_stlm.cnfrm_at IS '확정 일시';
COMMENT ON COLUMN fin.part_stlm.cncl_rsn_desc IS '최근 확정 취소 사유(마감한 달은 취소 불가)';

CREATE TABLE fin.part_stlm_dtl (
  stlm_dtl_id            BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME fin.seq_part_stlm_dtl_id),
  stlm_id                BIGINT       NOT NULL,
  dev_id                 BIGINT       NOT NULL,
  emp_se_cd              VARCHAR(10)  NULL,
  mm_qty                 DECIMAL(5,2) NULL,
  mth_untprc_amt         BIGINT       NOT NULL DEFAULT 0,
  slry_amt               BIGINT       NOT NULL DEFAULT 0,
  bsn_fee_amt            BIGINT       NOT NULL DEFAULT 0,
  rtrpay_amt             BIGINT       NOT NULL DEFAULT 0,
  tot_exp_amt            BIGINT       NOT NULL DEFAULT 0,
  diff_amt               BIGINT       NOT NULL DEFAULT 0,
  prft_amt               BIGINT       NOT NULL DEFAULT 0,
  rmrk_desc              VARCHAR(100) NULL,
  CONSTRAINT pk_part_stlm_dtl PRIMARY KEY (stlm_dtl_id),
  CONSTRAINT uk_part_stlm_dtl_stlm_id_dev_id UNIQUE (stlm_id, dev_id)
);
COMMENT ON TABLE fin.part_stlm_dtl IS '영업 파트 정산 인력별 내역 (기간 추이 보기의 원천)';
COMMENT ON COLUMN fin.part_stlm_dtl.emp_se_cd IS '그 달 직원구분';
COMMENT ON COLUMN fin.part_stlm_dtl.mm_qty IS 'M/M';
COMMENT ON COLUMN fin.part_stlm_dtl.mth_untprc_amt IS '월 단가 = 단가×MM(턴키는 배분 금액, 무상 0)';
COMMENT ON COLUMN fin.part_stlm_dtl.slry_amt IS '급여(그 달 실제 지급액, 시작 달은 날짜만큼)';
COMMENT ON COLUMN fin.part_stlm_dtl.bsn_fee_amt IS '영업비(외주 제외)';
COMMENT ON COLUMN fin.part_stlm_dtl.rtrpay_amt IS '퇴직금 적립(정규직)';
COMMENT ON COLUMN fin.part_stlm_dtl.tot_exp_amt IS '비용 계';
COMMENT ON COLUMN fin.part_stlm_dtl.diff_amt IS '차액 = 월 단가 − 비용';
COMMENT ON COLUMN fin.part_stlm_dtl.prft_amt IS '이익금 = 차액 × 배분율(원 단위 버림)';
COMMENT ON COLUMN fin.part_stlm_dtl.rmrk_desc IS '비고(영업비 / 영업비+퇴직금)';

CREATE TABLE fin.part_stlm_hist (
  stlm_hist_id           BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME fin.seq_part_stlm_hist_id),
  stlm_id                BIGINT       NOT NULL,
  stlm_prcs_cd           VARCHAR(10)  NOT NULL,
  rsn_desc               VARCHAR(500) NULL,
  prcs_user_id           BIGINT       NULL,
  prcs_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_part_stlm_hist PRIMARY KEY (stlm_hist_id),
  CONSTRAINT ck_part_stlm_hist_stlm_prcs_cd_vld CHECK (stlm_prcs_cd IN ('CONFIRM','CANCEL'))
);
COMMENT ON TABLE fin.part_stlm_hist IS '파트 정산 확정·취소 이력';
COMMENT ON COLUMN fin.part_stlm_hist.stlm_prcs_cd IS 'CONFIRM=확정, CANCEL=확정 취소';
COMMENT ON COLUMN fin.part_stlm_hist.rsn_desc IS '취소 사유(필수)';

-- =====================================================================================
-- 13. 지급·자금수지 (UI-20, UI-21)
-- =====================================================================================

CREATE TABLE fin.pay_odr (
  pay_ym                 CHAR(7)      NOT NULL,
  pay_odr_no             SMALLINT      NOT NULL,
  pay_dt                 DATE         NOT NULL,
  odr_nm                 VARCHAR(50)  NULL,
  CONSTRAINT pk_pay_odr PRIMARY KEY (pay_ym, pay_odr_no),
  CONSTRAINT ck_pay_odr_pay_odr_no_rng CHECK (pay_odr_no BETWEEN 1 AND 5)
);
COMMENT ON TABLE fin.pay_odr IS '월별 지급 차수 설정 (최대 5차)';
COMMENT ON COLUMN fin.pay_odr.pay_ym IS '월';
COMMENT ON COLUMN fin.pay_odr.pay_odr_no IS '차수(1~5)';
COMMENT ON COLUMN fin.pay_odr.pay_dt IS '지급일(기본 10일/15일/20일/말일)';

CREATE TABLE fin.pay (
  pay_id                 BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME fin.seq_pay_id),
  pay_ym                 CHAR(7)      NOT NULL,
  pay_odr_no             SMALLINT      NULL,
  pay_se_cd              VARCHAR(20)  NOT NULL,
  rcvr_se_cd             VARCHAR(10)  NOT NULL,
  dev_id                 BIGINT       NULL,
  ptnr_id                BIGINT       NULL,
  rcvr_nm                VARCHAR(100) NOT NULL,
  sply_amt               BIGINT       NOT NULL DEFAULT 0,
  vat_amt                BIGINT       NOT NULL DEFAULT 0,
  ddct_amt               BIGINT       NOT NULL DEFAULT 0,
  pay_amt                BIGINT       NOT NULL,
  pay_plan_dt            DATE         NOT NULL,
  pay_dt                 DATE         NULL,
  pay_stts_cd            VARCHAR(10)  NOT NULL DEFAULT 'PLAN',
  pay_src_cd             VARCHAR(10)  NOT NULL DEFAULT 'MANUAL',
  src_rec_id             BIGINT       NULL,
  cry_pay_id             BIGINT       NULL,
  bank_cd                VARCHAR(30)  NULL,
  acnt_no                VARCHAR(30)  NULL,
  dpstr_nm               VARCHAR(50)  NULL,
  rmrk_desc              VARCHAR(500) NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_pay PRIMARY KEY (pay_id),
  CONSTRAINT ck_pay_pay_stts_cd_vld CHECK (pay_stts_cd IN ('PLAN','PAID','UNPAID','CANCEL')),
  CONSTRAINT ck_pay_pay_odr_no_rng CHECK (pay_odr_no IS NULL OR pay_odr_no BETWEEN 1 AND 5)
);
COMMENT ON TABLE fin.pay IS '지급 내역 (급여·외주비·세금·경비 지급 예정 자동 생성 + 직접 입력)';
COMMENT ON COLUMN fin.pay.pay_ym IS '월';
COMMENT ON COLUMN fin.pay.pay_odr_no IS '지급 차수';
COMMENT ON COLUMN fin.pay.pay_se_cd IS '[PAY_CATEGORY] SALARY=인건비, FREELANCE=계약직·프리랜서, SERVICE=용역비(외주), UNPAID=미지급 인건비, TAX=세금(원천세·근로소득세), INSURANCE=4대보험, SALES_FEE=영업비, SEVERANCE=퇴직금·퇴직연금, EXPENSE=직접 경비, SGA=판관비, LOAN_REPAY=대여금 상환, CORP_TAX=법인세, ETC';
COMMENT ON COLUMN fin.pay.rcvr_se_cd IS 'DEV=개발자, PARTNER=협력사, AGENCY=기관(세무서·공단), ETC';
COMMENT ON COLUMN fin.pay.rcvr_nm IS '받는 곳(표시용)';
COMMENT ON COLUMN fin.pay.sply_amt IS '공급가액';
COMMENT ON COLUMN fin.pay.vat_amt IS '부가세';
COMMENT ON COLUMN fin.pay.ddct_amt IS '공제(원천세 등)';
COMMENT ON COLUMN fin.pay.pay_amt IS '지급액(부가세 포함, 자금수지 기준)';
COMMENT ON COLUMN fin.pay.pay_plan_dt IS '지급 예정일';
COMMENT ON COLUMN fin.pay.pay_dt IS '지급일(입력하면 실적)';
COMMENT ON COLUMN fin.pay.pay_stts_cd IS 'PLAN=예정, PAID=지급, UNPAID=미지급(다음으로 넘김), CANCEL';
COMMENT ON COLUMN fin.pay.pay_src_cd IS 'AUTO_COST=급여 업로드, AUTO_EXP=직접 경비, AUTO_FIN=재무 입력, AUTO_TAX=세금·보험, MANUAL=직접 입력';
COMMENT ON COLUMN fin.pay.src_rec_id IS '원천 레코드 ID(cst.mth_cst 등)';
COMMENT ON COLUMN fin.pay.cry_pay_id IS '미지급으로 넘어온 원래 지급 건';
COMMENT ON COLUMN fin.pay.bank_cd IS '[BANK] 이체 은행(민감)';
COMMENT ON COLUMN fin.pay.acnt_no IS '이체 계좌(민감)';

CREATE TABLE fin.etc_inflw (
  inflw_id               BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME fin.seq_etc_inflw_id),
  inflw_se_cd            VARCHAR(10)  NOT NULL,
  inflw_item_nm          VARCHAR(100) NOT NULL,
  inflw_amt              BIGINT       NOT NULL,
  inflw_plan_dt          DATE         NULL,
  rcpt_dt                DATE         NULL,
  rmrk_desc              VARCHAR(500) NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             BIGINT       NULL,
  CONSTRAINT pk_etc_inflw PRIMARY KEY (inflw_id),
  CONSTRAINT ck_etc_inflw_inflw_se_cd_vld CHECK (inflw_se_cd IN ('MISC','LOAN','ETC'))
);
COMMENT ON TABLE fin.etc_inflw IS '기타 수입 (매출 외 입금: 잡수익·대여금)';
COMMENT ON COLUMN fin.etc_inflw.inflw_se_cd IS 'MISC=잡수익, LOAN=대여금, ETC';
COMMENT ON COLUMN fin.etc_inflw.inflw_item_nm IS '항목';
COMMENT ON COLUMN fin.etc_inflw.inflw_amt IS '금액';
COMMENT ON COLUMN fin.etc_inflw.inflw_plan_dt IS '예정일';
COMMENT ON COLUMN fin.etc_inflw.rcpt_dt IS '입금일(실적)';

CREATE TABLE fin.cash_bal (
  bal_ym                 CHAR(7)      NOT NULL,
  bgn_bal_amt            BIGINT       NULL,
  end_bal_amt            BIGINT       NULL,
  init_yn                CHAR(1)      NOT NULL DEFAULT 'N',
  min_oprt_fund_amt      BIGINT       NULL,
  updated_at             TIMESTAMP     NULL,
  updated_by             BIGINT       NULL,
  CONSTRAINT pk_cash_bal PRIMARY KEY (bal_ym),
  CONSTRAINT ck_cash_bal_bal_ym_fmt CHECK (bal_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);
COMMENT ON TABLE fin.cash_bal IS '자금 잔액 (월초·월말 현금)';
COMMENT ON COLUMN fin.cash_bal.bal_ym IS '월';
COMMENT ON COLUMN fin.cash_bal.bgn_bal_amt IS '시작 잔액(전월이월금, 첫 달은 기초 잔액 직접 입력)';
COMMENT ON COLUMN fin.cash_bal.end_bal_amt IS '종료 잔액(이월금 = 시작 + 수입 − 지출, 마감 때 확정)';
COMMENT ON COLUMN fin.cash_bal.init_yn IS '자금수지 시작 월(기초 잔액)';
COMMENT ON COLUMN fin.cash_bal.min_oprt_fund_amt IS '최소 운영자금(미달 경고, 기본은 기준값)';

-- =====================================================================================
-- 14. 월별 손익 (마감 스냅샷 + 2025년 이관 B.11) — UI-07·08·09
-- =====================================================================================

CREATE TABLE fin.mth_pl (
  pl_ym                  CHAR(7)      NOT NULL,
  pl_src_cd              VARCHAR(10)  NOT NULL,
  sls_amt                BIGINT       NOT NULL DEFAULT 0,
  aloc_cst_amt           BIGINT       NOT NULL DEFAULT 0,
  idle_cst_amt           BIGINT       NOT NULL DEFAULT 0,
  drct_expns_amt         BIGINT       NOT NULL DEFAULT 0,
  sga_input_amt          BIGINT       NOT NULL DEFAULT 0,
  indrct_lbr_cst_amt     BIGINT       NOT NULL DEFAULT 0,
  rnd_cst_amt            BIGINT       NOT NULL DEFAULT 0,
  prvmn_adj_amt          BIGINT       NOT NULL DEFAULT 0,
  nop_incm_amt           BIGINT       NOT NULL DEFAULT 0,
  nop_exp_amt            BIGINT       NOT NULL DEFAULT 0,
  corp_tax_amt           BIGINT       NOT NULL DEFAULT 0,
  aloc_sls_amt           BIGINT       NULL,
  sls_cst_amt            BIGINT GENERATED ALWAYS AS (aloc_cst_amt + idle_cst_amt + drct_expns_amt) STORED,
  grs_prft_amt           BIGINT GENERATED ALWAYS AS (sls_amt - aloc_cst_amt - idle_cst_amt - drct_expns_amt) STORED,
  tot_sga_amt            BIGINT GENERATED ALWAYS AS (sga_input_amt + indrct_lbr_cst_amt + rnd_cst_amt) STORED,
  bsn_prft_amt           BIGINT GENERATED ALWAYS AS (sls_amt - aloc_cst_amt - idle_cst_amt - drct_expns_amt - sga_input_amt - indrct_lbr_cst_amt - rnd_cst_amt + prvmn_adj_amt) STORED,
  net_prft_amt           BIGINT GENERATED ALWAYS AS (sls_amt - aloc_cst_amt - idle_cst_amt - drct_expns_amt - sga_input_amt - indrct_lbr_cst_amt - rnd_cst_amt + prvmn_adj_amt + nop_incm_amt - nop_exp_amt - corp_tax_amt) STORED,
  upld_id                BIGINT       NULL,
  cnfrm_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  cnfrm_user_id          BIGINT       NULL,
  CONSTRAINT pk_mth_pl PRIMARY KEY (pl_ym),
  CONSTRAINT ck_mth_pl_pl_ym_fmt CHECK (pl_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT ck_mth_pl_pl_src_cd_vld CHECK (pl_src_cd IN ('CLOSE','IMPORT'))
);
COMMENT ON TABLE fin.mth_pl IS '월별 손익 (마감 확정값 / 2025년 이관값 — 전년 대비 비교용)';
COMMENT ON COLUMN fin.mth_pl.pl_ym IS '월';
COMMENT ON COLUMN fin.mth_pl.pl_src_cd IS 'CLOSE=월 마감 확정, IMPORT=전년 월별 손익 이관(B.11)';
COMMENT ON COLUMN fin.mth_pl.sls_amt IS '매출액';
COMMENT ON COLUMN fin.mth_pl.aloc_cst_amt IS '배분 원가(내부 제외)';
COMMENT ON COLUMN fin.mth_pl.idle_cst_amt IS '대기 원가';
COMMENT ON COLUMN fin.mth_pl.drct_expns_amt IS '직접 경비';
COMMENT ON COLUMN fin.mth_pl.sga_input_amt IS '판관비 입력 항목';
COMMENT ON COLUMN fin.mth_pl.indrct_lbr_cst_amt IS '간접 인건비';
COMMENT ON COLUMN fin.mth_pl.rnd_cst_amt IS '연구개발비(내부 프로젝트)';
COMMENT ON COLUMN fin.mth_pl.prvmn_adj_amt IS '전월 조정(±)';
COMMENT ON COLUMN fin.mth_pl.nop_incm_amt IS '영업외수익';
COMMENT ON COLUMN fin.mth_pl.nop_exp_amt IS '영업외비용';
COMMENT ON COLUMN fin.mth_pl.corp_tax_amt IS '법인세(월할)';
COMMENT ON COLUMN fin.mth_pl.aloc_sls_amt IS '배분 기준 매출(참고: 월단위·파견 + 턴키 배분표)';
COMMENT ON COLUMN fin.mth_pl.sls_cst_amt IS '매출원가';
COMMENT ON COLUMN fin.mth_pl.grs_prft_amt IS '매출총이익';
COMMENT ON COLUMN fin.mth_pl.tot_sga_amt IS '판관비 합계';
COMMENT ON COLUMN fin.mth_pl.bsn_prft_amt IS '영업이익';
COMMENT ON COLUMN fin.mth_pl.net_prft_amt IS '당기순이익';

CREATE TABLE fin.mth_pl_dtl (
  pl_ym                  CHAR(7)      NOT NULL,
  dim_ty_cd              VARCHAR(20)  NOT NULL,
  dim_val                VARCHAR(50)  NOT NULL,
  sls_amt                BIGINT       NOT NULL DEFAULT 0,
  cst_amt                BIGINT       NOT NULL DEFAULT 0,
  grs_prft_amt           BIGINT       NOT NULL DEFAULT 0,
  CONSTRAINT pk_mth_pl_dtl PRIMARY KEY (pl_ym, dim_ty_cd, dim_val)
);
COMMENT ON TABLE fin.mth_pl_dtl IS '월별 손익 구성 (판관비 항목·계약유형·파트·고객사별) — 이관 해는 판관비 항목만';
COMMENT ON COLUMN fin.mth_pl_dtl.dim_ty_cd IS 'SGA_ITEM=판관비 항목, CONTRACT_TYPE=계약유형별, PART=영업 파트별, CUSTOMER=고객사별';
COMMENT ON COLUMN fin.mth_pl_dtl.dim_val IS '항목 코드 / 계약유형 / 파트 ID / 거래처 ID';
COMMENT ON COLUMN fin.mth_pl_dtl.sls_amt IS '매출(구성 분석용)';
COMMENT ON COLUMN fin.mth_pl_dtl.cst_amt IS '원가 또는 판관비 금액';
COMMENT ON COLUMN fin.mth_pl_dtl.grs_prft_amt IS '매출총이익(계약유형별 기여도)';

-- =====================================================================================
-- 15. 월 마감 (UI-14)
-- =====================================================================================

CREATE TABLE fin.mth_cls (
  cls_ym                 CHAR(7)      NOT NULL,
  cls_stts_cd            VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
  cls_user_id            BIGINT       NULL,
  cls_at                 TIMESTAMP     NULL,
  rls_user_id            BIGINT       NULL,
  rls_at                 TIMESTAMP     NULL,
  rls_rsn_desc           VARCHAR(500) NULL,
  CONSTRAINT pk_mth_cls PRIMARY KEY (cls_ym),
  CONSTRAINT ck_mth_cls_cls_ym_fmt CHECK (cls_ym ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT ck_mth_cls_cls_stts_cd_vld CHECK (cls_stts_cd IN ('OPEN','CLOSED'))
);
COMMENT ON TABLE fin.mth_cls IS '월 마감 상태 (마감된 달의 데이터는 수정·삭제·업로드 불가)';
COMMENT ON COLUMN fin.mth_cls.cls_ym IS '월';
COMMENT ON COLUMN fin.mth_cls.cls_stts_cd IS '[CLOSE_STATUS] OPEN=작업중, CLOSED=마감';
COMMENT ON COLUMN fin.mth_cls.cls_user_id IS '마감자';
COMMENT ON COLUMN fin.mth_cls.cls_at IS '마감일';
COMMENT ON COLUMN fin.mth_cls.rls_user_id IS '마지막 해제자';
COMMENT ON COLUMN fin.mth_cls.rls_rsn_desc IS '마지막 해제 사유';

CREATE TABLE fin.mth_cls_hist (
  cls_hist_id            BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME fin.seq_mth_cls_hist_id),
  cls_ym                 CHAR(7)      NOT NULL,
  cls_prcs_cd            VARCHAR(10)  NOT NULL,
  rsn_desc               VARCHAR(500) NULL,
  prcs_user_id           BIGINT       NULL,
  prcs_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_mth_cls_hist PRIMARY KEY (cls_hist_id),
  CONSTRAINT ck_mth_cls_hist_cls_prcs_cd_vld CHECK (cls_prcs_cd IN ('CLOSE','REOPEN'))
);
COMMENT ON TABLE fin.mth_cls_hist IS '마감·해제 이력';
COMMENT ON COLUMN fin.mth_cls_hist.cls_prcs_cd IS 'CLOSE=마감, REOPEN=해제';
COMMENT ON COLUMN fin.mth_cls_hist.rsn_desc IS '해제 사유(필수)';

CREATE TABLE fin.mth_cls_chk (
  cls_ym                 CHAR(7)      NOT NULL,
  cls_chk_cd             VARCHAR(30)  NOT NULL,
  chk_rslt_cd            VARCHAR(10)  NOT NULL,
  issue_cnt              INT          NOT NULL DEFAULT 0,
  msg_desc               VARCHAR(500) NULL,
  chk_user_id            BIGINT       NULL,
  chk_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_mth_cls_chk PRIMARY KEY (cls_ym, cls_chk_cd),
  CONSTRAINT ck_mth_cls_chk_chk_rslt_cd_vld CHECK (chk_rslt_cd IN ('OK','WARN','FAIL'))
);
COMMENT ON TABLE fin.mth_cls_chk IS '월 마감 점검 결과 (대시보드 마감 진행 표시)';
COMMENT ON COLUMN fin.mth_cls_chk.cls_chk_cd IS '[CLOSE_CHECK] 급여 업로드, MM 1.0 초과, 파견 근태, 청구 미확정, 세금계산서 번호, 재무 항목, 파트 정산, 지급 미처리, 청구 누락';
COMMENT ON COLUMN fin.mth_cls_chk.chk_rslt_cd IS 'OK=완료, WARN=확인 필요, FAIL=미완료';
COMMENT ON COLUMN fin.mth_cls_chk.issue_cnt IS '걸린 건수';

-- =====================================================================================
-- 16. AI (UI-26, UI-30)
-- =====================================================================================

CREATE TABLE ai.qry_hist (
  qry_id                 BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME ai.seq_qry_hist_id),
  user_id                BIGINT       NOT NULL,
  qstn_desc              TEXT         NOT NULL,
  intrp_cond             JSONB         NULL,
  ans_desc               TEXT   NULL,
  ans_data               JSONB         NULL,
  data_scope             JSONB         NULL,
  prvsnl_yn              CHAR(1)      NOT NULL DEFAULT 'N',
  ans_psbl_yn            CHAR(1)      NOT NULL DEFAULT 'Y',
  evl_scr                SMALLINT      NULL,
  fbck_desc              VARCHAR(500) NULL,
  mdl_nm                 VARCHAR(100) NULL,
  rspns_ms_qty           INT          NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_qry_hist PRIMARY KEY (qry_id)
);
COMMENT ON TABLE ai.qry_hist IS 'AI 질문 기록';
COMMENT ON COLUMN ai.qry_hist.user_id IS '질문한 사용자';
COMMENT ON COLUMN ai.qry_hist.qstn_desc IS '질문';
COMMENT ON COLUMN ai.qry_hist.intrp_cond IS 'AI가 해석한 조건(기간·지표·필터)';
COMMENT ON COLUMN ai.qry_hist.ans_desc IS '답(설명)';
COMMENT ON COLUMN ai.qry_hist.ans_data IS '표·차트 데이터';
COMMENT ON COLUMN ai.qry_hist.data_scope IS '사용 데이터 범위(테이블·기간·규칙, 권한 범위)';
COMMENT ON COLUMN ai.qry_hist.prvsnl_yn IS '마감 전 데이터 사용(잠정)';
COMMENT ON COLUMN ai.qry_hist.ans_psbl_yn IS 'N=답할 수 없음';
COMMENT ON COLUMN ai.qry_hist.evl_scr IS '답변 평가(1=좋음, -1=나쁨)';
COMMENT ON COLUMN ai.qry_hist.mdl_nm IS '사용한 LLM';

CREATE TABLE ai.frcst_exec (
  frcst_exec_id          BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME ai.seq_frcst_exec_id),
  frcst_ty_cd            VARCHAR(20)  NOT NULL,
  base_ym                CHAR(7)      NOT NULL,
  frcst_mnth_qty         INT          NOT NULL DEFAULT 6,
  asmptn                 JSONB         NULL,
  scnro_yn               CHAR(1)      NOT NULL DEFAULT 'N',
  rqst_user_id           BIGINT       NULL,
  exec_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_frcst_exec PRIMARY KEY (frcst_exec_id)
);
COMMENT ON TABLE ai.frcst_exec IS '예측 실행';
COMMENT ON COLUMN ai.frcst_exec.frcst_ty_cd IS 'REVENUE=매출, OP_PROFIT=영업이익, TK_MARGIN=턴키 최종 마진율, CASH=자금 잔액, STAFF=인력 과부족, AR_RISK=미수 연체 위험';
COMMENT ON COLUMN ai.frcst_exec.base_ym IS '기준월';
COMMENT ON COLUMN ai.frcst_exec.frcst_mnth_qty IS '예측 개월 수';
COMMENT ON COLUMN ai.frcst_exec.asmptn IS '가정(가정 바꿔 보기 값)';
COMMENT ON COLUMN ai.frcst_exec.scnro_yn IS '사용자가 가정을 바꾼 실행';
COMMENT ON COLUMN ai.frcst_exec.exec_at IS '실행일';

CREATE TABLE ai.frcst_rslt (
  frcst_exec_id          BIGINT       NOT NULL,
  obj_ym                 CHAR(7)      NOT NULL,
  obj_key_val            VARCHAR(50)  NOT NULL DEFAULT '',
  low_val                DECIMAL(18,2) NULL,
  mid_val                DECIMAL(18,2) NULL,
  high_val               DECIMAL(18,2) NULL,
  actl_val               DECIMAL(18,2) NULL,
  err_rt                 DECIMAL(9,4)  NULL,
  CONSTRAINT pk_frcst_rslt PRIMARY KEY (frcst_exec_id, obj_ym, obj_key_val)
);
COMMENT ON TABLE ai.frcst_rslt IS '예측 결과값';
COMMENT ON COLUMN ai.frcst_rslt.obj_ym IS '대상 월';
COMMENT ON COLUMN ai.frcst_rslt.obj_key_val IS '대상(프로젝트 ID·직무 코드·고객사 등, 전사면 빈 값)';
COMMENT ON COLUMN ai.frcst_rslt.low_val IS '낮음';
COMMENT ON COLUMN ai.frcst_rslt.mid_val IS '기본';
COMMENT ON COLUMN ai.frcst_rslt.high_val IS '높음';
COMMENT ON COLUMN ai.frcst_rslt.actl_val IS '실제값(나중에 채움)';
COMMENT ON COLUMN ai.frcst_rslt.err_rt IS '예측 오차(%)';

CREATE TABLE ai.rcmd_rqst (
  rcmd_rqst_id           BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME ai.seq_rcmd_rqst_id),
  rqst_ty_cd             VARCHAR(10)  NOT NULL,
  prj_id                 BIGINT       NULL,
  need_id                BIGINT       NULL,
  rqst_desc              VARCHAR(1000) NULL,
  job_cd                 VARCHAR(30)  NULL,
  grd_cd                 VARCHAR(30)  NULL,
  psn_cnt                INT          NULL,
  bgn_ym                 CHAR(7)      NULL,
  mnth_qty               INT          NULL,
  cond                   JSONB         NULL,
  scr_wght               JSONB         NULL,
  rqst_user_id           BIGINT       NULL,
  rqst_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_rcmd_rqst PRIMARY KEY (rcmd_rqst_id)
);
COMMENT ON TABLE ai.rcmd_rqst IS 'AI 인력 추천 요청';
COMMENT ON COLUMN ai.rcmd_rqst.rqst_ty_cd IS 'PROJECT=프로젝트 빈자리, OPP=영업 기회 필요 인력, TEXT=말로 적음';
COMMENT ON COLUMN ai.rcmd_rqst.need_id IS '영업 기회 필요 인력';
COMMENT ON COLUMN ai.rcmd_rqst.rqst_desc IS '말로 적은 요청';
COMMENT ON COLUMN ai.rcmd_rqst.job_cd IS '[JOB] 해석된 직무';
COMMENT ON COLUMN ai.rcmd_rqst.grd_cd IS '[GRADE] 해석된 등급';
COMMENT ON COLUMN ai.rcmd_rqst.cond IS 'AI가 바꾼 조건(스킬·업종·고객 등)';
COMMENT ON COLUMN ai.rcmd_rqst.scr_wght IS '그때 적용한 배점';

CREATE TABLE ai.rcmd_rslt (
  rcmd_rslt_id           BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME ai.seq_rcmd_rslt_id),
  rcmd_rqst_id           BIGINT       NOT NULL,
  dev_id                 BIGINT       NOT NULL,
  rank_no                INT          NOT NULL,
  tot_scr                DECIMAL(6,2) NOT NULL,
  job_scr                DECIMAL(6,2) NULL,
  skl_scr                DECIMAL(6,2) NULL,
  cust_scr               DECIMAL(6,2) NULL,
  grd_scr                DECIMAL(6,2) NULL,
  achv_scr               DECIMAL(6,2) NULL,
  exp_scr                DECIMAL(6,2) NULL,
  asgn_psbl_scr          DECIMAL(6,2) NULL,
  rcmd_rsn_desc          VARCHAR(1000) NULL,
  rcmd_dcsn_cd           VARCHAR(10)  NOT NULL DEFAULT 'NONE',
  rjct_rsn_desc          VARCHAR(500) NULL,
  dcsn_user_id           BIGINT       NULL,
  dcsn_at                TIMESTAMP     NULL,
  asgn_id                BIGINT       NULL,
  tmpr_asgn_id           BIGINT       NULL,
  CONSTRAINT pk_rcmd_rslt PRIMARY KEY (rcmd_rslt_id),
  CONSTRAINT uk_rcmd_rslt_rcmd_rqst_id_dev_id UNIQUE (rcmd_rqst_id, dev_id),
  CONSTRAINT ck_rcmd_rslt_rcmd_dcsn_cd_vld CHECK (rcmd_dcsn_cd IN ('NONE','ADOPT','REJECT'))
);
COMMENT ON TABLE ai.rcmd_rslt IS 'AI 추천 기록 (점수 내역·채택 여부 — 추천 정확도 계산)';
COMMENT ON COLUMN ai.rcmd_rslt.dev_id IS '추천 개발자';
COMMENT ON COLUMN ai.rcmd_rslt.rank_no IS '순위(최대 10명)';
COMMENT ON COLUMN ai.rcmd_rslt.tot_scr IS '총점(100)';
COMMENT ON COLUMN ai.rcmd_rslt.job_scr IS '직무 일치(25)';
COMMENT ON COLUMN ai.rcmd_rslt.skl_scr IS '스킬 일치(20)';
COMMENT ON COLUMN ai.rcmd_rslt.cust_scr IS '같은 고객사·업종 경험(15)';
COMMENT ON COLUMN ai.rcmd_rslt.grd_scr IS '등급 일치(10)';
COMMENT ON COLUMN ai.rcmd_rslt.achv_scr IS '과거 성과(10)';
COMMENT ON COLUMN ai.rcmd_rslt.exp_scr IS '비용 효과(10)';
COMMENT ON COLUMN ai.rcmd_rslt.asgn_psbl_scr IS '투입 가능일(10)';
COMMENT ON COLUMN ai.rcmd_rslt.rcmd_rsn_desc IS '추천 이유(AI 설명)';
COMMENT ON COLUMN ai.rcmd_rslt.rcmd_dcsn_cd IS 'NONE=미결정, ADOPT=채택, REJECT=거절';
COMMENT ON COLUMN ai.rcmd_rslt.asgn_id IS '채택 → 배정';
COMMENT ON COLUMN ai.rcmd_rslt.tmpr_asgn_id IS '채택 → 가배정';

CREATE TABLE ai.wght_prpsl (
  prpsl_id               BIGINT GENERATED BY DEFAULT AS IDENTITY (SEQUENCE NAME ai.seq_wght_prpsl_id),
  bss_bgn_ym             CHAR(7)      NOT NULL,
  bss_end_ym             CHAR(7)      NOT NULL,
  cur_wght               JSONB         NOT NULL,
  prpsl_wght             JSONB         NOT NULL,
  bss_desc               TEXT         NULL,
  prpsl_stts_cd          VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
  dcsn_user_id           BIGINT       NULL,
  dcsn_at                TIMESTAMP     NULL,
  created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT pk_wght_prpsl PRIMARY KEY (prpsl_id),
  CONSTRAINT ck_wght_prpsl_prpsl_stts_cd_vld CHECK (prpsl_stts_cd IN ('PENDING','APPROVED','REJECTED'))
);
COMMENT ON TABLE ai.wght_prpsl IS 'AI 추천 배점 조정안';
COMMENT ON COLUMN ai.wght_prpsl.bss_bgn_ym IS '근거 기간 시작월(채택·거절 3개월)';
COMMENT ON COLUMN ai.wght_prpsl.bss_end_ym IS '근거 기간 종료월';
COMMENT ON COLUMN ai.wght_prpsl.cur_wght IS '현재 배점';
COMMENT ON COLUMN ai.wght_prpsl.prpsl_wght IS '조정안';
COMMENT ON COLUMN ai.wght_prpsl.bss_desc IS '근거 설명';
COMMENT ON COLUMN ai.wght_prpsl.prpsl_stts_cd IS 'PENDING/APPROVED/REJECTED (관리자 승인해야 cmn.oprt_std 반영)';

-- =====================================================================================
-- 외래키 (모든 테이블 생성 후)
-- =====================================================================================
ALTER TABLE cmn.cd_dtl ADD CONSTRAINT fk_cd_dtl_cd_grp FOREIGN KEY (cd_grp_id) REFERENCES cmn.cd_grp (cd_grp_id);
ALTER TABLE cmn.excel_map ADD CONSTRAINT fk_excel_map_cd_dtl FOREIGN KEY (trns_cd_grp_id, trns_cd) REFERENCES cmn.cd_dtl (cd_grp_id, dtl_cd);
ALTER TABLE sys.menu ADD CONSTRAINT fk_menu_upr_menu FOREIGN KEY (upr_menu_id) REFERENCES sys.menu (menu_id);
ALTER TABLE sys.role_menu_auth ADD CONSTRAINT fk_role_menu_auth_role FOREIGN KEY (role_id) REFERENCES sys.role (role_id) ON DELETE CASCADE;
ALTER TABLE sys.role_menu_auth ADD CONSTRAINT fk_role_menu_auth_menu FOREIGN KEY (menu_id) REFERENCES sys.menu (menu_id) ON DELETE CASCADE;
ALTER TABLE sys.user_info ADD CONSTRAINT fk_user_info_bsn_part FOREIGN KEY (bsn_part_id) REFERENCES mst.bsn_part (bsn_part_id);
ALTER TABLE sys.user_info ADD CONSTRAINT fk_user_info_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE sys.user_role ADD CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys.user_info (user_id) ON DELETE CASCADE;
ALTER TABLE sys.user_role ADD CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys.role (role_id);
ALTER TABLE sys.user_part ADD CONSTRAINT fk_user_part_user FOREIGN KEY (user_id) REFERENCES sys.user_info (user_id) ON DELETE CASCADE;
ALTER TABLE sys.user_part ADD CONSTRAINT fk_user_part_bsn_part FOREIGN KEY (bsn_part_id) REFERENCES mst.bsn_part (bsn_part_id);
ALTER TABLE sys.auth_tkn ADD CONSTRAINT fk_auth_tkn_user FOREIGN KEY (user_id) REFERENCES sys.user_info (user_id) ON DELETE CASCADE;
ALTER TABLE sys.ntf ADD CONSTRAINT fk_ntf_user FOREIGN KEY (user_id) REFERENCES sys.user_info (user_id) ON DELETE CASCADE;
ALTER TABLE sys.upld_err ADD CONSTRAINT fk_upld_err_upld FOREIGN KEY (upld_id) REFERENCES sys.upld (upld_id) ON DELETE CASCADE;
ALTER TABLE mst.bsn_part_std_hist ADD CONSTRAINT fk_bsn_part_std_hist_bsn_part FOREIGN KEY (bsn_part_id) REFERENCES mst.bsn_part (bsn_part_id);
ALTER TABLE mst.bsn_part_std_hist ADD CONSTRAINT fk_bsn_part_std_hist_ldr_user FOREIGN KEY (ldr_user_id) REFERENCES sys.user_info (user_id);
ALTER TABLE mst.dev ADD CONSTRAINT fk_dev_vndr_ptnr FOREIGN KEY (vndr_ptnr_id) REFERENCES mst.ptnr (ptnr_id);
ALTER TABLE mst.dev ADD CONSTRAINT fk_dev_bsn_part FOREIGN KEY (bsn_part_id) REFERENCES mst.bsn_part (bsn_part_id);
ALTER TABLE mst.dev ADD CONSTRAINT fk_dev_src_cand FOREIGN KEY (src_cand_id) REFERENCES hr.cand (cand_id);
ALTER TABLE mst.dev_sub_job ADD CONSTRAINT fk_dev_sub_job_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id) ON DELETE CASCADE;
ALTER TABLE mst.dev_skl ADD CONSTRAINT fk_dev_skl_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id) ON DELETE CASCADE;
ALTER TABLE mst.dev_work_stts_hist ADD CONSTRAINT fk_dev_work_stts_hist_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id) ON DELETE CASCADE;
ALTER TABLE bsn.oppt ADD CONSTRAINT fk_oppt_cust_ptnr FOREIGN KEY (cust_ptnr_id) REFERENCES mst.ptnr (ptnr_id);
ALTER TABLE bsn.oppt ADD CONSTRAINT fk_oppt_bsn_part FOREIGN KEY (bsn_part_id) REFERENCES mst.bsn_part (bsn_part_id);
ALTER TABLE bsn.oppt ADD CONSTRAINT fk_oppt_bsn_chrg_user FOREIGN KEY (bsn_chrg_user_id) REFERENCES sys.user_info (user_id);
ALTER TABLE bsn.oppt ADD CONSTRAINT fk_oppt_aprv_user FOREIGN KEY (aprv_user_id) REFERENCES sys.user_info (user_id);
ALTER TABLE bsn.oppt ADD CONSTRAINT fk_oppt_win_prj FOREIGN KEY (win_prj_id) REFERENCES prj.prj (prj_id);
ALTER TABLE bsn.oppt_need ADD CONSTRAINT fk_oppt_need_oppt FOREIGN KEY (oppt_id) REFERENCES bsn.oppt (oppt_id) ON DELETE CASCADE;
ALTER TABLE bsn.quot_aprv_hist ADD CONSTRAINT fk_quot_aprv_hist_oppt FOREIGN KEY (oppt_id) REFERENCES bsn.oppt (oppt_id) ON DELETE CASCADE;
ALTER TABLE prj.prj ADD CONSTRAINT fk_prj_cust_ptnr FOREIGN KEY (cust_ptnr_id) REFERENCES mst.ptnr (ptnr_id);
ALTER TABLE prj.prj ADD CONSTRAINT fk_prj_bsn_part FOREIGN KEY (bsn_part_id) REFERENCES mst.bsn_part (bsn_part_id);
ALTER TABLE prj.prj ADD CONSTRAINT fk_prj_pm_user FOREIGN KEY (pm_user_id) REFERENCES sys.user_info (user_id);
ALTER TABLE prj.prj ADD CONSTRAINT fk_prj_pm_dev FOREIGN KEY (pm_dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE prj.prj ADD CONSTRAINT fk_prj_prev_prj FOREIGN KEY (prev_prj_id) REFERENCES prj.prj (prj_id);
ALTER TABLE prj.prj ADD CONSTRAINT fk_prj_oppt FOREIGN KEY (oppt_id) REFERENCES bsn.oppt (oppt_id);
ALTER TABLE sys.user_prj ADD CONSTRAINT fk_user_prj_user FOREIGN KEY (user_id) REFERENCES sys.user_info (user_id) ON DELETE CASCADE;
ALTER TABLE sys.user_prj ADD CONSTRAINT fk_user_prj_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id);
ALTER TABLE prj.prj_part_hist ADD CONSTRAINT fk_prj_part_hist_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id) ON DELETE CASCADE;
ALTER TABLE prj.prj_part_hist ADD CONSTRAINT fk_prj_part_hist_bsn_part FOREIGN KEY (bsn_part_id) REFERENCES mst.bsn_part (bsn_part_id);
ALTER TABLE prj.prj_skl ADD CONSTRAINT fk_prj_skl_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id) ON DELETE CASCADE;
ALTER TABLE prj.cntrct_chg_hist ADD CONSTRAINT fk_cntrct_chg_hist_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id);
ALTER TABLE prj.psn_plan ADD CONSTRAINT fk_psn_plan_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id) ON DELETE CASCADE;
ALTER TABLE prj.psn_plan ADD CONSTRAINT fk_psn_plan_src_need FOREIGN KEY (src_need_id) REFERENCES bsn.oppt_need (need_id) ON DELETE SET NULL;
ALTER TABLE prj.bill_stg ADD CONSTRAINT fk_bill_stg_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id) ON DELETE CASCADE;
ALTER TABLE prj.tk_aloc_line ADD CONSTRAINT fk_tk_aloc_line_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id) ON DELETE CASCADE;
ALTER TABLE prj.tk_aloc_line ADD CONSTRAINT fk_tk_aloc_line_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE prj.tk_aloc_mth ADD CONSTRAINT fk_tk_aloc_mth_aloc_line FOREIGN KEY (aloc_line_id) REFERENCES prj.tk_aloc_line (aloc_line_id) ON DELETE CASCADE;
ALTER TABLE prj.phs ADD CONSTRAINT fk_phs_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id) ON DELETE CASCADE;
ALTER TABLE prj.wkly_memo ADD CONSTRAINT fk_wkly_memo_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id) ON DELETE CASCADE;
ALTER TABLE prj.wkly_memo ADD CONSTRAINT fk_wkly_memo_wrt_user FOREIGN KEY (wrt_user_id) REFERENCES sys.user_info (user_id);
ALTER TABLE prj.issue ADD CONSTRAINT fk_issue_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id) ON DELETE CASCADE;
ALTER TABLE prj.issue ADD CONSTRAINT fk_issue_chrg_user FOREIGN KEY (chrg_user_id) REFERENCES sys.user_info (user_id);
ALTER TABLE asgn.asgn ADD CONSTRAINT fk_asgn_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id);
ALTER TABLE asgn.asgn ADD CONSTRAINT fk_asgn_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE asgn.asgn ADD CONSTRAINT fk_asgn_cntrct_dev FOREIGN KEY (cntrct_dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE asgn.asgn ADD CONSTRAINT fk_asgn_rplc_asgn FOREIGN KEY (rplc_asgn_id) REFERENCES asgn.asgn (asgn_id);
ALTER TABLE asgn.asgn ADD CONSTRAINT fk_asgn_rcmd_rslt FOREIGN KEY (rcmd_rslt_id) REFERENCES ai.rcmd_rslt (rcmd_rslt_id);
ALTER TABLE asgn.asgn_mth ADD CONSTRAINT fk_asgn_mth_asgn FOREIGN KEY (asgn_id) REFERENCES asgn.asgn (asgn_id) ON DELETE CASCADE;
ALTER TABLE asgn.untprc_hist ADD CONSTRAINT fk_untprc_hist_asgn FOREIGN KEY (asgn_id) REFERENCES asgn.asgn (asgn_id) ON DELETE CASCADE;
ALTER TABLE asgn.asgn_chg_hist ADD CONSTRAINT fk_asgn_chg_hist_asgn FOREIGN KEY (asgn_id) REFERENCES asgn.asgn (asgn_id) ON DELETE CASCADE;
ALTER TABLE asgn.asgn_chg_hist ADD CONSTRAINT fk_asgn_chg_hist_new_asgn FOREIGN KEY (new_asgn_id) REFERENCES asgn.asgn (asgn_id);
ALTER TABLE asgn.tmpr_asgn ADD CONSTRAINT fk_tmpr_asgn_need FOREIGN KEY (need_id) REFERENCES bsn.oppt_need (need_id) ON DELETE CASCADE;
ALTER TABLE asgn.tmpr_asgn ADD CONSTRAINT fk_tmpr_asgn_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE asgn.tmpr_asgn ADD CONSTRAINT fk_tmpr_asgn_asgn FOREIGN KEY (asgn_id) REFERENCES asgn.asgn (asgn_id);
ALTER TABLE hr.cand ADD CONSTRAINT fk_cand_vndr_ptnr FOREIGN KEY (vndr_ptnr_id) REFERENCES mst.ptnr (ptnr_id);
ALTER TABLE hr.cand ADD CONSTRAINT fk_cand_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE hr.cand_skl ADD CONSTRAINT fk_cand_skl_cand FOREIGN KEY (cand_id) REFERENCES hr.cand (cand_id) ON DELETE CASCADE;
ALTER TABLE hr.cand_cntct_hist ADD CONSTRAINT fk_cand_cntct_hist_cand FOREIGN KEY (cand_id) REFERENCES hr.cand (cand_id) ON DELETE CASCADE;
ALTER TABLE cst.mth_cst ADD CONSTRAINT fk_mth_cst_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE cst.mth_cst ADD CONSTRAINT fk_mth_cst_bsn_part FOREIGN KEY (bsn_part_id) REFERENCES mst.bsn_part (bsn_part_id);
ALTER TABLE cst.drct_expns ADD CONSTRAINT fk_drct_expns_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id);
ALTER TABLE cst.drct_expns ADD CONSTRAINT fk_drct_expns_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE cst.cst_aloc ADD CONSTRAINT fk_cst_aloc_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE cst.cst_aloc ADD CONSTRAINT fk_cst_aloc_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id);
ALTER TABLE cst.cst_aloc ADD CONSTRAINT fk_cst_aloc_asgn FOREIGN KEY (asgn_id) REFERENCES asgn.asgn (asgn_id);
ALTER TABLE cst.prvmn_adj ADD CONSTRAINT fk_prvmn_adj_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id);
ALTER TABLE cst.prvmn_adj ADD CONSTRAINT fk_prvmn_adj_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE bill.bill ADD CONSTRAINT fk_bill_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id);
ALTER TABLE bill.bill ADD CONSTRAINT fk_bill_bill_stg FOREIGN KEY (bill_stg_id) REFERENCES prj.bill_stg (bill_stg_id);
ALTER TABLE bill.bill ADD CONSTRAINT fk_bill_orgnl_bill FOREIGN KEY (orgnl_bill_id) REFERENCES bill.bill (bill_id);
ALTER TABLE bill.bill_dtl ADD CONSTRAINT fk_bill_dtl_bill FOREIGN KEY (bill_id) REFERENCES bill.bill (bill_id) ON DELETE CASCADE;
ALTER TABLE bill.bill_dtl ADD CONSTRAINT fk_bill_dtl_asgn FOREIGN KEY (asgn_id) REFERENCES asgn.asgn (asgn_id);
ALTER TABLE bill.bill_dtl ADD CONSTRAINT fk_bill_dtl_cntrct_dev FOREIGN KEY (cntrct_dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE bill.rcpt ADD CONSTRAINT fk_rcpt_bill FOREIGN KEY (bill_id) REFERENCES bill.bill (bill_id);
ALTER TABLE bill.dun_hist ADD CONSTRAINT fk_dun_hist_bill FOREIGN KEY (bill_id) REFERENCES bill.bill (bill_id);
ALTER TABLE bill.dun_hist ADD CONSTRAINT fk_dun_hist_rcvr_user FOREIGN KEY (rcvr_user_id) REFERENCES sys.user_info (user_id);
ALTER TABLE bill.dspch_atnd ADD CONSTRAINT fk_dspch_atnd_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id);
ALTER TABLE bill.dspch_atnd ADD CONSTRAINT fk_dspch_atnd_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE bill.dspch_atnd ADD CONSTRAINT fk_dspch_atnd_asgn FOREIGN KEY (asgn_id) REFERENCES asgn.asgn (asgn_id);
ALTER TABLE fin.entry ADD CONSTRAINT fk_entry_copy_entry FOREIGN KEY (copy_entry_id) REFERENCES fin.entry (entry_id);
ALTER TABLE fin.corp_tax_expc_hist ADD CONSTRAINT fk_corp_tax_expc_hist_tax_yr FOREIGN KEY (tax_yr) REFERENCES fin.corp_tax_plan (tax_yr);
ALTER TABLE fin.corp_tax_mth ADD CONSTRAINT fk_corp_tax_mth_tax_yr FOREIGN KEY (tax_yr) REFERENCES fin.corp_tax_plan (tax_yr);
ALTER TABLE fin.biz_plan ADD CONSTRAINT fk_biz_plan_bsn_part FOREIGN KEY (bsn_part_id) REFERENCES mst.bsn_part (bsn_part_id);
ALTER TABLE fin.part_stlm ADD CONSTRAINT fk_part_stlm_bsn_part FOREIGN KEY (bsn_part_id) REFERENCES mst.bsn_part (bsn_part_id);
ALTER TABLE fin.part_stlm ADD CONSTRAINT fk_part_stlm_part_std_hist FOREIGN KEY (part_std_hist_id) REFERENCES mst.bsn_part_std_hist (part_std_hist_id);
ALTER TABLE fin.part_stlm_dtl ADD CONSTRAINT fk_part_stlm_dtl_stlm FOREIGN KEY (stlm_id) REFERENCES fin.part_stlm (stlm_id) ON DELETE CASCADE;
ALTER TABLE fin.part_stlm_dtl ADD CONSTRAINT fk_part_stlm_dtl_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE fin.part_stlm_hist ADD CONSTRAINT fk_part_stlm_hist_stlm FOREIGN KEY (stlm_id) REFERENCES fin.part_stlm (stlm_id) ON DELETE CASCADE;
ALTER TABLE fin.pay ADD CONSTRAINT fk_pay_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE fin.pay ADD CONSTRAINT fk_pay_ptnr FOREIGN KEY (ptnr_id) REFERENCES mst.ptnr (ptnr_id);
ALTER TABLE fin.pay ADD CONSTRAINT fk_pay_cry_pay FOREIGN KEY (cry_pay_id) REFERENCES fin.pay (pay_id);
ALTER TABLE fin.mth_pl_dtl ADD CONSTRAINT fk_mth_pl_dtl_pl_ym FOREIGN KEY (pl_ym) REFERENCES fin.mth_pl (pl_ym) ON DELETE CASCADE;
ALTER TABLE fin.mth_cls_hist ADD CONSTRAINT fk_mth_cls_hist_cls_ym FOREIGN KEY (cls_ym) REFERENCES fin.mth_cls (cls_ym);
ALTER TABLE fin.mth_cls_chk ADD CONSTRAINT fk_mth_cls_chk_cls_ym FOREIGN KEY (cls_ym) REFERENCES fin.mth_cls (cls_ym) ON DELETE CASCADE;
ALTER TABLE ai.qry_hist ADD CONSTRAINT fk_qry_hist_user FOREIGN KEY (user_id) REFERENCES sys.user_info (user_id);
ALTER TABLE ai.frcst_rslt ADD CONSTRAINT fk_frcst_rslt_frcst_exec FOREIGN KEY (frcst_exec_id) REFERENCES ai.frcst_exec (frcst_exec_id) ON DELETE CASCADE;
ALTER TABLE ai.rcmd_rqst ADD CONSTRAINT fk_rcmd_rqst_prj FOREIGN KEY (prj_id) REFERENCES prj.prj (prj_id);
ALTER TABLE ai.rcmd_rqst ADD CONSTRAINT fk_rcmd_rqst_need FOREIGN KEY (need_id) REFERENCES bsn.oppt_need (need_id) ON DELETE SET NULL;
ALTER TABLE ai.rcmd_rslt ADD CONSTRAINT fk_rcmd_rslt_rcmd_rqst FOREIGN KEY (rcmd_rqst_id) REFERENCES ai.rcmd_rqst (rcmd_rqst_id) ON DELETE CASCADE;
ALTER TABLE ai.rcmd_rslt ADD CONSTRAINT fk_rcmd_rslt_dev FOREIGN KEY (dev_id) REFERENCES mst.dev (dev_id);
ALTER TABLE ai.rcmd_rslt ADD CONSTRAINT fk_rcmd_rslt_asgn FOREIGN KEY (asgn_id) REFERENCES asgn.asgn (asgn_id);
ALTER TABLE ai.rcmd_rslt ADD CONSTRAINT fk_rcmd_rslt_tmpr_asgn FOREIGN KEY (tmpr_asgn_id) REFERENCES asgn.tmpr_asgn (tmpr_asgn_id);

-- =====================================================================================
-- 인덱스 (조회용 + FK 컬럼)
-- =====================================================================================
CREATE INDEX ix_cd_hist_cd_grp_id_dtl_cd_chg_at ON cmn.cd_hist (cd_grp_id, dtl_cd, chg_at);
CREATE INDEX ix_ptnr_ptnr_nm ON mst.ptnr (ptnr_nm);
CREATE INDEX ix_auth_tkn_user_id_tkn_ty_cd ON sys.auth_tkn (user_id, tkn_ty_cd);
CREATE INDEX ix_acs_hist_user_id_created_at ON sys.acs_hist (user_id, created_at);
CREATE INDEX ix_acs_hist_acs_ty_cd_created_at ON sys.acs_hist (acs_ty_cd, created_at);
CREATE INDEX ix_data_chg_hist_tbl_nm_obj_rec_id_chg_at ON sys.data_chg_hist (tbl_nm, obj_rec_id, chg_at);
CREATE INDEX ix_ntf_user_id_read_yn_created_at ON sys.ntf (user_id, read_yn, created_at);
CREATE INDEX ix_upld_form_cd_obj_ym ON sys.upld (form_cd, obj_ym);
CREATE INDEX ix_upld_err_upld_id_row_no ON sys.upld_err (upld_id, row_no);
CREATE INDEX ix_dev_dev_nm ON mst.dev (dev_nm);
CREATE INDEX ix_dev_main_job_cd_grd_cd ON mst.dev (main_job_cd, grd_cd);
CREATE INDEX ix_dev_work_stts_hist_dev_id_bgn_dt ON mst.dev_work_stts_hist (dev_id, bgn_dt);
CREATE INDEX ix_oppt_oppt_stts_cd_bsn_stg_cd ON bsn.oppt (oppt_stts_cd, bsn_stg_cd);
CREATE INDEX ix_oppt_need_job_cd_bgn_ym ON bsn.oppt_need (job_cd, bgn_ym);
CREATE INDEX ix_quot_aprv_hist_oppt_id_prcs_at ON bsn.quot_aprv_hist (oppt_id, prcs_at);
CREATE INDEX ix_prj_prj_nm ON prj.prj (prj_nm);
CREATE INDEX ix_prj_prj_stts_cd_cntrct_ty_cd ON prj.prj (prj_stts_cd, cntrct_ty_cd);
CREATE INDEX ix_cntrct_chg_hist_prj_id_chg_dt ON prj.cntrct_chg_hist (prj_id, chg_dt);
CREATE INDEX ix_psn_plan_plan_ym_job_cd ON prj.psn_plan (plan_ym, job_cd);
CREATE INDEX ix_tk_aloc_line_prj_id_aloc_stg_cd ON prj.tk_aloc_line (prj_id, aloc_stg_cd);
CREATE INDEX ix_tk_aloc_line_dev_id ON prj.tk_aloc_line (dev_id);
CREATE INDEX ix_tk_aloc_mth_aloc_ym ON prj.tk_aloc_mth (aloc_ym);
CREATE INDEX ix_wkly_memo_prj_id_wrt_dt ON prj.wkly_memo (prj_id, wrt_dt);
CREATE INDEX ix_issue_issue_stts_cd_ddln_dt ON prj.issue (issue_stts_cd, ddln_dt);
CREATE INDEX ix_asgn_prj_id_bgn_dt ON asgn.asgn (prj_id, bgn_dt);
CREATE INDEX ix_asgn_dev_id_bgn_dt_end_dt ON asgn.asgn (dev_id, bgn_dt, end_dt);
CREATE INDEX ix_asgn_end_dt ON asgn.asgn (end_dt);
CREATE INDEX ix_asgn_mth_work_ym ON asgn.asgn_mth (work_ym);
CREATE INDEX ix_asgn_chg_hist_asgn_id_aply_dt ON asgn.asgn_chg_hist (asgn_id, aply_dt);
CREATE INDEX ix_tmpr_asgn_dev_id_tmpr_asgn_stts_cd ON asgn.tmpr_asgn (dev_id, tmpr_asgn_stts_cd);
CREATE INDEX ix_cand_job_cd_grd_cd_asgn_psbl_dt ON hr.cand (job_cd, grd_cd, asgn_psbl_dt);
CREATE INDEX ix_cand_cntct_hist_cand_id_cntct_dt ON hr.cand_cntct_hist (cand_id, cntct_dt);
CREATE INDEX ix_mth_cst_cst_ym ON cst.mth_cst (cst_ym);
CREATE INDEX ix_drct_expns_expns_ym_prj_id ON cst.drct_expns (expns_ym, prj_id);
CREATE INDEX ix_drct_expns_dev_id_expns_ym ON cst.drct_expns (dev_id, expns_ym);
CREATE INDEX ix_cst_aloc_cst_ym_prj_id ON cst.cst_aloc (cst_ym, prj_id);
CREATE INDEX ix_cst_aloc_cst_ym_dev_id ON cst.cst_aloc (cst_ym, dev_id);
CREATE INDEX ix_prvmn_adj_aply_ym ON cst.prvmn_adj (aply_ym);
CREATE INDEX ix_bill_prj_id_atrb_ym ON bill.bill (prj_id, atrb_ym);
CREATE INDEX ix_bill_bill_dt ON bill.bill (bill_dt);
CREATE INDEX ix_bill_rcpt_plan_dt_bill_stts_cd ON bill.bill (rcpt_plan_dt, bill_stts_cd);
CREATE INDEX ix_bill_dtl_bill_id ON bill.bill_dtl (bill_id);
CREATE INDEX ix_bill_dtl_asgn_id_work_ym ON bill.bill_dtl (asgn_id, work_ym);
CREATE INDEX ix_rcpt_bill_id ON bill.rcpt (bill_id);
CREATE INDEX ix_rcpt_rcpt_dt ON bill.rcpt (rcpt_dt);
CREATE INDEX ix_dun_hist_bill_id_dun_stg_cd ON bill.dun_hist (bill_id, dun_stg_cd);
CREATE INDEX ix_entry_entry_ym_fin_se_cd ON fin.entry (entry_ym, fin_se_cd);
CREATE INDEX ix_corp_tax_expc_hist_tax_yr_chg_at ON fin.corp_tax_expc_hist (tax_yr, chg_at);
CREATE INDEX ix_corp_tax_mth_tax_yr ON fin.corp_tax_mth (tax_yr);
CREATE INDEX ix_part_stlm_hist_stlm_id_prcs_at ON fin.part_stlm_hist (stlm_id, prcs_at);
CREATE INDEX ix_pay_pay_ym_pay_odr_no ON fin.pay (pay_ym, pay_odr_no);
CREATE INDEX ix_pay_pay_plan_dt_pay_stts_cd ON fin.pay (pay_plan_dt, pay_stts_cd);
CREATE INDEX ix_pay_pay_dt ON fin.pay (pay_dt);
CREATE INDEX ix_etc_inflw_rcpt_dt ON fin.etc_inflw (rcpt_dt);
CREATE INDEX ix_mth_cls_hist_cls_ym_prcs_at ON fin.mth_cls_hist (cls_ym, prcs_at);
CREATE INDEX ix_qry_hist_user_id_created_at ON ai.qry_hist (user_id, created_at);
CREATE INDEX ix_frcst_exec_frcst_ty_cd_base_ym ON ai.frcst_exec (frcst_ty_cd, base_ym);
CREATE INDEX ix_rcmd_rqst_rqst_at ON ai.rcmd_rqst (rqst_at);
CREATE INDEX ix_rcmd_rslt_dev_id_rcmd_dcsn_cd ON ai.rcmd_rslt (dev_id, rcmd_dcsn_cd);
CREATE INDEX ix_excel_map_trns_cd_grp_id_trns_cd ON cmn.excel_map (trns_cd_grp_id, trns_cd);
CREATE INDEX ix_menu_upr_menu_id ON sys.menu (upr_menu_id);
CREATE INDEX ix_role_menu_auth_menu_id ON sys.role_menu_auth (menu_id);
CREATE INDEX ix_user_info_bsn_part_id ON sys.user_info (bsn_part_id);
CREATE INDEX ix_user_role_role_id ON sys.user_role (role_id);
CREATE INDEX ix_user_part_bsn_part_id ON sys.user_part (bsn_part_id);
CREATE INDEX ix_bsn_part_std_hist_ldr_user_id ON mst.bsn_part_std_hist (ldr_user_id);
CREATE INDEX ix_dev_vndr_ptnr_id ON mst.dev (vndr_ptnr_id);
CREATE INDEX ix_dev_bsn_part_id ON mst.dev (bsn_part_id);
CREATE INDEX ix_oppt_cust_ptnr_id ON bsn.oppt (cust_ptnr_id);
CREATE INDEX ix_oppt_bsn_part_id ON bsn.oppt (bsn_part_id);
CREATE INDEX ix_oppt_bsn_chrg_user_id ON bsn.oppt (bsn_chrg_user_id);
CREATE INDEX ix_oppt_aprv_user_id ON bsn.oppt (aprv_user_id);
CREATE INDEX ix_oppt_need_oppt_id ON bsn.oppt_need (oppt_id);
CREATE INDEX ix_prj_cust_ptnr_id ON prj.prj (cust_ptnr_id);
CREATE INDEX ix_prj_bsn_part_id ON prj.prj (bsn_part_id);
CREATE INDEX ix_prj_pm_user_id ON prj.prj (pm_user_id);
CREATE INDEX ix_prj_pm_dev_id ON prj.prj (pm_dev_id);
CREATE INDEX ix_prj_prev_prj_id ON prj.prj (prev_prj_id);
CREATE INDEX ix_prj_oppt_id ON prj.prj (oppt_id);
CREATE INDEX ix_user_prj_prj_id ON sys.user_prj (prj_id);
CREATE INDEX ix_prj_part_hist_bsn_part_id ON prj.prj_part_hist (bsn_part_id);
CREATE INDEX ix_psn_plan_src_need_id ON prj.psn_plan (src_need_id);
CREATE INDEX ix_wkly_memo_wrt_user_id ON prj.wkly_memo (wrt_user_id);
CREATE INDEX ix_issue_prj_id ON prj.issue (prj_id);
CREATE INDEX ix_issue_chrg_user_id ON prj.issue (chrg_user_id);
CREATE INDEX ix_asgn_cntrct_dev_id ON asgn.asgn (cntrct_dev_id);
CREATE INDEX ix_asgn_rplc_asgn_id ON asgn.asgn (rplc_asgn_id);
CREATE INDEX ix_asgn_chg_hist_new_asgn_id ON asgn.asgn_chg_hist (new_asgn_id);
CREATE INDEX ix_tmpr_asgn_need_id ON asgn.tmpr_asgn (need_id);
CREATE INDEX ix_tmpr_asgn_asgn_id ON asgn.tmpr_asgn (asgn_id);
CREATE INDEX ix_cand_vndr_ptnr_id ON hr.cand (vndr_ptnr_id);
CREATE INDEX ix_cand_dev_id ON hr.cand (dev_id);
CREATE INDEX ix_mth_cst_bsn_part_id ON cst.mth_cst (bsn_part_id);
CREATE INDEX ix_drct_expns_prj_id ON cst.drct_expns (prj_id);
CREATE INDEX ix_cst_aloc_dev_id ON cst.cst_aloc (dev_id);
CREATE INDEX ix_cst_aloc_prj_id ON cst.cst_aloc (prj_id);
CREATE INDEX ix_cst_aloc_asgn_id ON cst.cst_aloc (asgn_id);
CREATE INDEX ix_prvmn_adj_prj_id ON cst.prvmn_adj (prj_id);
CREATE INDEX ix_prvmn_adj_dev_id ON cst.prvmn_adj (dev_id);
CREATE INDEX ix_bill_bill_stg_id ON bill.bill (bill_stg_id);
CREATE INDEX ix_bill_orgnl_bill_id ON bill.bill (orgnl_bill_id);
CREATE INDEX ix_bill_dtl_cntrct_dev_id ON bill.bill_dtl (cntrct_dev_id);
CREATE INDEX ix_dun_hist_rcvr_user_id ON bill.dun_hist (rcvr_user_id);
CREATE INDEX ix_dspch_atnd_prj_id ON bill.dspch_atnd (prj_id);
CREATE INDEX ix_dspch_atnd_dev_id ON bill.dspch_atnd (dev_id);
CREATE INDEX ix_dspch_atnd_asgn_id ON bill.dspch_atnd (asgn_id);
CREATE INDEX ix_entry_copy_entry_id ON fin.entry (copy_entry_id);
CREATE INDEX ix_biz_plan_bsn_part_id ON fin.biz_plan (bsn_part_id);
CREATE INDEX ix_part_stlm_part_std_hist_id ON fin.part_stlm (part_std_hist_id);
CREATE INDEX ix_part_stlm_dtl_dev_id ON fin.part_stlm_dtl (dev_id);
CREATE INDEX ix_pay_dev_id ON fin.pay (dev_id);
CREATE INDEX ix_pay_ptnr_id ON fin.pay (ptnr_id);
CREATE INDEX ix_pay_cry_pay_id ON fin.pay (cry_pay_id);
CREATE INDEX ix_rcmd_rqst_prj_id ON ai.rcmd_rqst (prj_id);
CREATE INDEX ix_rcmd_rqst_need_id ON ai.rcmd_rqst (need_id);
CREATE INDEX ix_rcmd_rslt_asgn_id ON ai.rcmd_rslt (asgn_id);
CREATE INDEX ix_rcmd_rslt_tmpr_asgn_id ON ai.rcmd_rslt (tmpr_asgn_id);
CREATE INDEX ix_user_info_dev_id ON sys.user_info (dev_id);
CREATE INDEX ix_oppt_win_prj_id ON bsn.oppt (win_prj_id);
CREATE INDEX ix_dev_src_cand_id ON mst.dev (src_cand_id);
CREATE INDEX ix_asgn_rcmd_rslt_id ON asgn.asgn (rcmd_rslt_id);

-- updated_at 자동 갱신 트리거
CREATE TRIGGER trg_cd_grp_updated_at BEFORE UPDATE ON cmn.cd_grp FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_cd_dtl_updated_at BEFORE UPDATE ON cmn.cd_dtl FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_ptnr_updated_at BEFORE UPDATE ON mst.ptnr FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_bsn_part_updated_at BEFORE UPDATE ON mst.bsn_part FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_role_updated_at BEFORE UPDATE ON sys.role FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_menu_updated_at BEFORE UPDATE ON sys.menu FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_role_menu_auth_updated_at BEFORE UPDATE ON sys.role_menu_auth FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_user_info_updated_at BEFORE UPDATE ON sys.user_info FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_dev_updated_at BEFORE UPDATE ON mst.dev FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_oppt_updated_at BEFORE UPDATE ON bsn.oppt FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_oppt_need_updated_at BEFORE UPDATE ON bsn.oppt_need FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_prj_updated_at BEFORE UPDATE ON prj.prj FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_psn_plan_updated_at BEFORE UPDATE ON prj.psn_plan FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_bill_stg_updated_at BEFORE UPDATE ON prj.bill_stg FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_tk_aloc_line_updated_at BEFORE UPDATE ON prj.tk_aloc_line FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_phs_updated_at BEFORE UPDATE ON prj.phs FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_wkly_memo_updated_at BEFORE UPDATE ON prj.wkly_memo FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_issue_updated_at BEFORE UPDATE ON prj.issue FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_asgn_updated_at BEFORE UPDATE ON asgn.asgn FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_asgn_mth_updated_at BEFORE UPDATE ON asgn.asgn_mth FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_tmpr_asgn_updated_at BEFORE UPDATE ON asgn.tmpr_asgn FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_cand_updated_at BEFORE UPDATE ON hr.cand FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_mth_cst_updated_at BEFORE UPDATE ON cst.mth_cst FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_drct_expns_updated_at BEFORE UPDATE ON cst.drct_expns FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_bill_updated_at BEFORE UPDATE ON bill.bill FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_dspch_atnd_updated_at BEFORE UPDATE ON bill.dspch_atnd FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_entry_updated_at BEFORE UPDATE ON fin.entry FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_corp_tax_plan_updated_at BEFORE UPDATE ON fin.corp_tax_plan FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_biz_plan_updated_at BEFORE UPDATE ON fin.biz_plan FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_part_stlm_updated_at BEFORE UPDATE ON fin.part_stlm FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_pay_updated_at BEFORE UPDATE ON fin.pay FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();
CREATE TRIGGER trg_cash_bal_updated_at BEFORE UPDATE ON fin.cash_bal FOR EACH ROW EXECUTE FUNCTION cmn.fn_set_updated_at();


-- =====================================================================================
-- 뷰 (VW_<BUSINESS_ROLE>)
-- =====================================================================================

-- 청구별 미수금·경과 일수
CREATE VIEW bill.vw_bill_unrcv AS
SELECT b.bill_id,
       b.bill_no,
       b.prj_id,
       p.cust_ptnr_id,
       b.bill_dt,
       b.rcpt_plan_dt,
       b.tot_amt,
       COALESCE(r.rcpt_amt, 0)::bigint            AS rcpt_amt,
       (b.tot_amt - COALESCE(r.rcpt_amt, 0))::bigint AS unrcv_amt,
       CASE WHEN b.rcpt_plan_dt IS NOT NULL AND b.tot_amt - COALESCE(r.rcpt_amt, 0) > 0
            THEN GREATEST(CURRENT_DATE - b.rcpt_plan_dt, 0) ELSE 0 END AS elps_day_qty
FROM bill.bill b
JOIN prj.prj p ON p.prj_id = b.prj_id
LEFT JOIN (SELECT bill_id, SUM(rcpt_amt) AS rcpt_amt FROM bill.rcpt GROUP BY bill_id) r ON r.bill_id = b.bill_id
WHERE b.bill_stts_cd = 'CONFIRM';
COMMENT ON VIEW bill.vw_bill_unrcv IS '청구별 미수금·경과 일수';

-- 개발자별 월 MM 합계
CREATE VIEW asgn.vw_dev_mth_mm AS
SELECT a.dev_id,
       m.work_ym,
       SUM(m.mm_qty)                                              AS tot_mm_qty,
       COUNT(*)::integer                                          AS prj_cnt,
       (CASE WHEN SUM(m.mm_qty) > 1 THEN 'Y' ELSE 'N' END)::char(1) AS mm_ovr_yn,
       SUM(CASE WHEN p.cntrct_ty_cd IN ('MO','DP') AND a.asgn_se_cd <> 'FREE' THEN m.mm_qty ELSE 0 END) AS bill_mm_qty
FROM asgn.asgn_mth m
JOIN asgn.asgn a ON a.asgn_id = m.asgn_id AND a.asgn_stts_cd <> 'CANCELLED'
JOIN prj.prj p   ON p.prj_id = a.prj_id
GROUP BY a.dev_id, m.work_ym;
COMMENT ON VIEW asgn.vw_dev_mth_mm IS '개발자별 월 MM 합계';

-- 투입 월별 적용 단가
CREATE VIEW asgn.vw_asgn_mth_untprc AS
SELECT m.asgn_id,
       m.work_ym,
       m.mm_qty,
       r.bill_untprc_amt,
       r.pay_untprc_amt
FROM asgn.asgn_mth m
LEFT JOIN LATERAL (
  SELECT h.bill_untprc_amt, h.pay_untprc_amt
  FROM asgn.untprc_hist h
  WHERE h.asgn_id = m.asgn_id
    AND h.aply_bgn_dt <= (to_date(m.work_ym, 'YYYY-MM') + INTERVAL '1 month - 1 day')::date
  ORDER BY h.aply_bgn_dt DESC
  LIMIT 1
) r ON TRUE;
COMMENT ON VIEW asgn.vw_asgn_mth_untprc IS '투입 월별 적용 단가';

-- 영업 파트 현재 적용 기준
CREATE VIEW mst.vw_bsn_part_cur_std AS
SELECT sp.bsn_part_id, sp.bsn_part_nm, sp.hq_yn, pp.part_std_hist_id, pp.aply_bgn_ym, pp.aply_end_ym,
       pp.ldr_user_id, pp.ldr_nm, pp.prft_dstrb_rt, pp.bsn_fee_amt
FROM mst.bsn_part sp
JOIN mst.bsn_part_std_hist pp ON pp.bsn_part_id = sp.bsn_part_id
WHERE pp.aply_bgn_ym <= to_char(CURRENT_DATE, 'YYYY-MM')
  AND (pp.aply_end_ym IS NULL OR pp.aply_end_ym >= to_char(CURRENT_DATE, 'YYYY-MM'));
COMMENT ON VIEW mst.vw_bsn_part_cur_std IS '영업 파트 현재 적용 기준';


-- =====================================================================================
-- 초기 데이터 (부록 C, 5.1) — 코드값·코드그룹 ID 는 데이터이므로 그대로
-- =====================================================================================

-- 18.1 코드 그룹 -------------------------------------------------------------------
INSERT INTO cmn.cd_grp (cd_grp_id, cd_grp_nm, cd_grp_se_cd, attr1_nm, attr2_nm, sort_seq) VALUES
 ('CONTRACT_TYPE',   '계약유형',          'SYSTEM', NULL, NULL, 10),
 ('EMP_TYPE',        '직원구분',          'SYSTEM', NULL, NULL, 20),
 ('ASSIGN_KIND',     '투입 구분',         'SYSTEM', NULL, NULL, 30),
 ('ALLOC_STAGE',     '배분 단계',         'SYSTEM', NULL, NULL, 40),
 ('RATE_BASIS',      '단가 기준',         'SYSTEM', NULL, NULL, 50),
 ('WORK_STATUS',     '근무 상태',         'SYSTEM', NULL, NULL, 60),
 ('FIN_CATEGORY',    '재무 구분',         'SYSTEM', NULL, NULL, 70),
 ('BILL_STATUS',     '청구 상태',         'SYSTEM', NULL, NULL, 80),
 ('CLOSE_STATUS',    '마감 상태',         'SYSTEM', NULL, NULL, 90),
 ('SALES_STAGE',     '영업 단계',         'SYSTEM', NULL, NULL, 100),
 ('WORK_TYPE',       '업무 구분',         'SYSTEM', NULL, NULL, 110),
 ('PARTNER_KIND',    '거래처 구분',       'SYSTEM', NULL, NULL, 120),
 ('PROJECT_STATUS',  '프로젝트 상태',     'SYSTEM', NULL, NULL, 130),
 ('SETTLE_METHOD',   '정산 방식',         'SYSTEM', NULL, NULL, 140),
 ('CONTRACT_CHANGE', '계약 변경 구분',    'SYSTEM', NULL, NULL, 150),
 ('ALLOC_LINE_TYPE', '배분표 줄 종류',    'SYSTEM', NULL, NULL, 160),
 ('CLOSE_CHECK',     '월 마감 점검 항목', 'SYSTEM', NULL, NULL, 170),
 ('JOB',             '직무',              'BIZ',    '기본 단가(원)', NULL, 200),
 ('POSITION',        '직위',              'BIZ',    '수당(원/월)', NULL, 210),
 ('GRADE',           '등급',              'BIZ',    '순서', NULL, 220),
 ('SKILL',           '스킬',              'BIZ',    '관련 직무', NULL, 230),
 ('INDUSTRY',        '업종',              'BIZ',    NULL, NULL, 240),
 ('SGA_ITEM',        '판관비 항목',       'BIZ',    NULL, NULL, 250),
 ('NOI_ITEM',        '영업외수익 항목',   'BIZ',    NULL, NULL, 255),
 ('NOE_ITEM',        '영업외비용 항목',   'BIZ',    NULL, NULL, 256),
 ('EXPENSE_ITEM',    '직접 경비 항목',    'BIZ',    NULL, NULL, 260),
 ('PAY_CATEGORY',    '지급 구분',         'BIZ',    '자금수지 분류', NULL, 270),
 ('PAY_ROUND',       '지급 차수',         'BIZ',    '기본 지급일', NULL, 280),
 ('EXT_POSSIBILITY', '연장 가능성',       'BIZ',    NULL, NULL, 290),
 ('ISSUE_SEVERITY',  '이슈 중요도',       'BIZ',    NULL, NULL, 300),
 ('ISSUE_STATUS',    '이슈 상태',         'BIZ',    NULL, NULL, 310),
 ('CONTACT_STATUS',  '후보 연락 상태',    'BIZ',    NULL, NULL, 320),
 ('CANDIDATE_SOURCE','후보 출처',         'BIZ',    NULL, NULL, 330),
 ('BANK',            '은행',              'BIZ',    NULL, NULL, 340),
 ('PHASE_TK',        '진행 단계(턴키·내부)',   'BIZ', '비중(%)', NULL, 350),
 ('PHASE_OP',        '진행 단계(월단위·파견)', 'BIZ', '비중(%)', NULL, 360);

-- 18.2 상세 코드 -------------------------------------------------------------------
INSERT INTO cmn.cd_dtl (cd_grp_id, dtl_cd, cd_nm, attr1_val, sort_seq) VALUES
 ('CONTRACT_TYPE','TK','턴키',NULL,1), ('CONTRACT_TYPE','MO','월단위',NULL,2),
 ('CONTRACT_TYPE','DP','파견',NULL,3), ('CONTRACT_TYPE','IN','내부(자체 개발)',NULL,4),
 ('EMP_TYPE','FT','정규직',NULL,1), ('EMP_TYPE','CT','계약직',NULL,2),
 ('EMP_TYPE','FL','프리랜서',NULL,3), ('EMP_TYPE','OS','외주',NULL,4),
 ('ASSIGN_KIND','NEW','신규',NULL,1), ('ASSIGN_KIND','ADD','추가투입',NULL,2), ('ASSIGN_KIND','SUB','대체투입',NULL,3),
 ('ASSIGN_KIND','MOV','이관',NULL,4), ('ASSIGN_KIND','FREE','무상지원',NULL,5),
 ('ALLOC_STAGE','EST','견적',NULL,1), ('ALLOC_STAGE','FINAL','최종 계약',NULL,2), ('ALLOC_STAGE','ACTUAL','실제 투입계획',NULL,3),
 ('RATE_BASIS','MONTH','월',NULL,1), ('RATE_BASIS','DAY','일',NULL,2), ('RATE_BASIS','HOUR','시간',NULL,3),
 ('WORK_STATUS','VACATION','휴가',NULL,1), ('WORK_STATUS','PARENTAL','육아휴직',NULL,2),
 ('WORK_STATUS','UNPAID','무급휴가',NULL,3), ('WORK_STATUS','LEAVE','휴직',NULL,4), ('WORK_STATUS','RESIGN','퇴사',NULL,5),
 ('FIN_CATEGORY','SGA','판관비',NULL,1), ('FIN_CATEGORY','NOI','영업외수익',NULL,2), ('FIN_CATEGORY','NOE','영업외비용',NULL,3),
 ('BILL_STATUS','PLAN','청구 예정',NULL,1), ('BILL_STATUS','DRAFT','작성',NULL,2),
 ('BILL_STATUS','CONFIRM','확정',NULL,3), ('BILL_STATUS','CANCEL','취소',NULL,4),
 ('CLOSE_STATUS','OPEN','작업중',NULL,1), ('CLOSE_STATUS','CLOSED','마감',NULL,2),
 ('SALES_STAGE','DISC','발굴',NULL,1), ('SALES_STAGE','PROP','제안',NULL,2), ('SALES_STAGE','NEGO','협상',NULL,3),
 ('SALES_STAGE','PRE','계약 직전',NULL,4), ('SALES_STAGE','WON','수주',NULL,5), ('SALES_STAGE','LOST','실주',NULL,6),
 ('WORK_TYPE','DIRECT','직접',NULL,1), ('WORK_TYPE','INDIRECT','간접',NULL,2),
 ('PARTNER_KIND','CUST','고객사',NULL,1), ('PARTNER_KIND','VEND','협력사',NULL,2), ('PARTNER_KIND','BOTH','둘 다',NULL,3),
 ('PROJECT_STATUS','PLAN','수주예정',NULL,1), ('PROJECT_STATUS','RUN','진행',NULL,2), ('PROJECT_STATUS','HOLD','보류',NULL,3),
 ('PROJECT_STATUS','END','종료',NULL,4), ('PROJECT_STATUS','LOST','실주',NULL,5),
 ('SETTLE_METHOD','STAGE','단계별 청구',NULL,1), ('SETTLE_METHOD','MONTH','월 고정',NULL,2),
 ('SETTLE_METHOD','DAY','일 단가',NULL,3), ('SETTLE_METHOD','HOUR','시간 단가',NULL,4),
 ('CONTRACT_CHANGE','ADD','추가 계약',NULL,1), ('CONTRACT_CHANGE','CUT','감액',NULL,2),
 ('CONTRACT_CHANGE','TERM','기간 변경',NULL,3), ('CONTRACT_CHANGE','FREE','무상지원 합의',NULL,4),
 ('ALLOC_LINE_TYPE','PERSON','사람',NULL,1), ('ALLOC_LINE_TYPE','ITEM','항목',NULL,2),
 ('CLOSE_CHECK','SALARY_UPLOAD','급여 업로드',NULL,1), ('CLOSE_CHECK','MM_OVER','MM 1.0 초과자',NULL,2),
 ('CLOSE_CHECK','DISPATCH_ATT','파견 근태',NULL,3), ('CLOSE_CHECK','BILL_UNCONFIRMED','청구 미확정',NULL,4),
 ('CLOSE_CHECK','TAX_INVOICE_NO','세금계산서 번호 누락',NULL,5), ('CLOSE_CHECK','FIN_ENTRY','재무 항목',NULL,6),
 ('CLOSE_CHECK','PART_SETTLE','파트 정산',NULL,7), ('CLOSE_CHECK','PAYMENT','지급 미처리',NULL,8),
 ('CLOSE_CHECK','BILL_MISSING','청구 누락',NULL,9),
 -- 업무 코드
 ('JOB','PM','PM·사업관리','8500000',1), ('JOB','PL','PL·개발리더','8000000',2), ('JOB','PLN','기획·컨설팅','6500000',3),
 ('JOB','ARC','아키텍트','8800000',4), ('JOB','BE','백엔드 개발','6000000',5), ('JOB','FE','프론트엔드 개발','5500000',6),
 ('JOB','UX','UI/UX 디자인','5000000',7), ('JOB','PUB','퍼블리싱','4000000',8), ('JOB','QA','QA·테스트','4500000',9),
 ('JOB','DATA','데이터(DA·DBA)','6500000',10), ('JOB','OPS','운영·유지보수','4500000',11), ('JOB','AI','AI·데이터 분석','7500000',12),
 ('POSITION','STAFF','팀원','0',1), ('POSITION','TL','팀장','200000',2), ('POSITION','GL','그룹장','400000',3),
 ('POSITION','DIR','이사','0',4),
 ('GRADE','JR','초급','1',1), ('GRADE','MD','중급','2',2), ('GRADE','SR','고급','3',3), ('GRADE','EX','특급','4',4),
 ('SGA_ITEM','RENT','임대료',NULL,1), ('SGA_ITEM','COMM','통신비',NULL,2), ('SGA_ITEM','WELF','복리후생비',NULL,3),
 ('SGA_ITEM','EDU','교육훈련비',NULL,4), ('SGA_ITEM','TRAV','여비교통비',NULL,5),
 ('NOI_ITEM','INTEREST','이자수익',NULL,1), ('NOI_ITEM','MISC','잡이익',NULL,2),
 ('NOE_ITEM','INTEREST','이자비용',NULL,1), ('NOE_ITEM','MISC','잡손실',NULL,2),
 ('EXPENSE_ITEM','TEAM','팀비',NULL,1), ('EXPENSE_ITEM','PEXP','프로젝트경비',NULL,2), ('EXPENSE_ITEM','TRIP','출장비',NULL,3),
 ('EXPENSE_ITEM','ETC','기타 경비',NULL,9),
 ('PAY_CATEGORY','SALARY','인건비','인건비',1), ('PAY_CATEGORY','FREELANCE','계약직·프리랜서','계약직·프리랜서',2),
 ('PAY_CATEGORY','SERVICE','용역비(외주)','용역비',3), ('PAY_CATEGORY','UNPAID','미지급 인건비','미지급 인건비',4),
 ('PAY_CATEGORY','TAX','세금(원천세·근로소득세)','제경비',5), ('PAY_CATEGORY','INSURANCE','4대보험','제경비',6),
 ('PAY_CATEGORY','SALES_FEE','영업비','제경비',7), ('PAY_CATEGORY','SEVERANCE','퇴직금·퇴직연금','제경비',8),
 ('PAY_CATEGORY','EXPENSE','직접 경비','제경비',9), ('PAY_CATEGORY','SGA','판관비','제경비',10),
 ('PAY_CATEGORY','LOAN_REPAY','대여금 상환','제경비',11), ('PAY_CATEGORY','CORP_TAX','법인세 납부','법인세 납부',12),
 ('PAY_CATEGORY','ETC','기타','제경비',99),
 ('PAY_ROUND','1','1차','10',1), ('PAY_ROUND','2','2차','15',2), ('PAY_ROUND','3','3차','20',3),
 ('PAY_ROUND','4','4차','END',4), ('PAY_ROUND','5','5차(예비)',NULL,5),
 ('EXT_POSSIBILITY','H','높음',NULL,1), ('EXT_POSSIBILITY','M','보통',NULL,2), ('EXT_POSSIBILITY','L','낮음',NULL,3),
 ('ISSUE_SEVERITY','HIGH','높음',NULL,1), ('ISSUE_SEVERITY','MID','중간',NULL,2), ('ISSUE_SEVERITY','LOW','낮음',NULL,3),
 ('ISSUE_STATUS','OPEN','열림',NULL,1), ('ISSUE_STATUS','DOING','조치 중',NULL,2), ('ISSUE_STATUS','CLOSED','종료',NULL,3),
 ('CONTACT_STATUS','NONE','연락 전',NULL,1), ('CONTACT_STATUS','DONE','연락함',NULL,2), ('CONTACT_STATUS','INTV','면접 예정',NULL,3),
 ('CONTACT_STATUS','HOLD','보류',NULL,4), ('CONTACT_STATUS','SIGNED','계약 완료',NULL,5),
 ('CANDIDATE_SOURCE','JOBSITE','구인 사이트',NULL,1), ('CANDIDATE_SOURCE','VENDOR','협력사 추천',NULL,2),
 ('CANDIDATE_SOURCE','REFERRAL','지인 추천',NULL,3), ('CANDIDATE_SOURCE','PAST','과거 투입',NULL,4),
 ('PHASE_TK','KICKOFF','착수','5',1), ('PHASE_TK','ANALYSIS','분석','15',2), ('PHASE_TK','DESIGN','설계','20',3),
 ('PHASE_TK','DEV','개발','35',4), ('PHASE_TK','TEST','테스트','15',5), ('PHASE_TK','OPEN','오픈','5',6), ('PHASE_TK','CLOSE','종료','5',7),
 ('PHASE_OP','KICKOFF','착수','5',1), ('PHASE_OP','HANDOVER','인수인계','10',2), ('PHASE_OP','STABLE','안정화','15',3),
 ('PHASE_OP','OPERATE','정기 운영','65',4), ('PHASE_OP','CLOSE','종료','5',5);

-- 스킬 (attr1 = 주로 쓰는 직무)
INSERT INTO cmn.cd_dtl (cd_grp_id, dtl_cd, cd_nm, attr1_val, sort_seq) VALUES
 ('SKILL','JAVA','Java','BE',1), ('SKILL','SPRING','Spring','BE',2), ('SKILL','ORACLE','Oracle','DATA',3),
 ('SKILL','PYTHON','Python','BE',4), ('SKILL','NODE','Node.js','BE',5), ('SKILL','REACT','React','FE',6),
 ('SKILL','VUE','Vue','FE',7), ('SKILL','TS','TypeScript','FE',8), ('SKILL','POSTGRES','PostgreSQL','DATA',9),
 ('SKILL','MODELING','데이터 모델링','DATA',10), ('SKILL','MSA','MSA','ARC',11), ('SKILL','CLOUD','클라우드','ARC',12),
 ('SKILL','K8S','Kubernetes','ARC',13), ('SKILL','FIGMA','Figma','UX',14), ('SKILL','UXR','UX 리서치','UX',15),
 ('SKILL','HTMLCSS','HTML/CSS','PUB',16), ('SKILL','A11Y','웹 접근성','PUB',17), ('SKILL','TESTAUTO','테스트 자동화','QA',18),
 ('SKILL','PERF','성능 테스트','QA',19), ('SKILL','LINUX','Linux','OPS',20), ('SKILL','MONITOR','모니터링','OPS',21),
 ('SKILL','BATCH','배치 운영','OPS',22), ('SKILL','LLM','LLM','AI',23), ('SKILL','ML','머신러닝','AI',24),
 ('SKILL','PMO','PMO','PM',25), ('SKILL','PMGMT','사업관리','PM',26), ('SKILL','FINBIZ','금융 업무','PM',27),
 ('SKILL','REQ','요구 분석','PLN',28), ('SKILL','CONSULT','업무 컨설팅','PLN',29), ('SKILL','LEAD','설계 리딩','PL',30);

-- 업종
INSERT INTO cmn.cd_dtl (cd_grp_id, dtl_cd, cd_nm, sort_seq) VALUES
 ('INDUSTRY','FIN_BANK','금융(은행)',1), ('INDUSTRY','FIN_INS','금융(보험)',2), ('INDUSTRY','FIN_CARD','금융(카드)',3),
 ('INDUSTRY','LOGIS','물류',4), ('INDUSTRY','MFG_ELEC','제조(전자)',5), ('INDUSTRY','MFG_PHARM','제조(제약)',6),
 ('INDUSTRY','PUBLIC','공공기관',7), ('INDUSTRY','LOCAL','지자체',8), ('INDUSTRY','SI','SI·IT 서비스',9), ('INDUSTRY','SW','SW 개발',10);

-- 18.3 엑셀 표기 대응 (부록 C.2 주요 예 — 나머지 33개 표기는 운영 전 추가) -----------------
INSERT INTO cmn.excel_map (excel_lbl_nm, trns_cd_grp_id, trns_cd, map_role_cd, rmrk_desc) VALUES
 ('업체',              'EMP_TYPE',        'OS',       'MAIN', NULL),
 ('정직원',            'EMP_TYPE',        'FT',       'MAIN', NULL),
 ('정규직',            'EMP_TYPE',        'FT',       'MAIN', NULL),
 ('계약직',            'EMP_TYPE',        'CT',       'MAIN', NULL),
 ('프리랜서',          'EMP_TYPE',        'FL',       'MAIN', NULL),
 ('외주',              'EMP_TYPE',        'OS',       'MAIN', NULL),
 ('월정액',            'CONTRACT_TYPE',   'MO',       'MAIN', NULL),
 ('턴키',              'CONTRACT_TYPE',   'TK',       'MAIN', NULL),
 ('파견',              'CONTRACT_TYPE',   'DP',       'MAIN', NULL),
 ('사업관리',          'JOB',             'PM',       'MAIN', NULL),
 ('PM',                'JOB',             'PM',       'MAIN', NULL),
 ('사업관리(무상지원)','JOB',             'PM',       'MAIN', '직무 PM'),
 ('사업관리(무상지원)','ASSIGN_KIND',     'FREE',     'MAIN', '투입 구분 무상지원'),
 ('기획/디자인/퍼블',  'JOB',             'PLN',      'MAIN', '주 직무'),
 ('기획/디자인/퍼블',  'JOB',             'UX',       'SUB',  '보조 직무'),
 ('기획/디자인/퍼블',  'JOB',             'PUB',      'SUB',  '보조 직무'),
 ('협력업체',          'PARTNER_KIND',    'VEND',     'MAIN', NULL),
 ('제경비',            'ALLOC_LINE_TYPE', 'ITEM',     'MAIN', '배분표 항목 줄'),
 ('기술료',            'ALLOC_LINE_TYPE', 'ITEM',     'MAIN', '배분표 항목 줄'),
 ('컨설팅비용',        'ALLOC_LINE_TYPE', 'ITEM',     'MAIN', '배분표 항목 줄'),
 ('육아휴직',          'WORK_STATUS',     'PARENTAL', 'MAIN', '근무 상태 이력'),
 ('무급휴가',          'WORK_STATUS',     'UNPAID',   'MAIN', '근무 상태 이력'),
 ('대체',              'ASSIGN_KIND',     'SUB',      'MAIN', '개발(○○○ 대체) → 대체투입'),
 ('무상지원',          'ASSIGN_KIND',     'FREE',     'MAIN', NULL);
-- '대기중'은 근무 상태가 아니라 투입 없음(대기)으로 읽음 — 상태 이력을 만들지 않음

-- 18.4 운영 기준값 (부록 C.3 + 3장 기준값) ------------------------------------------------
INSERT INTO cmn.oprt_std (oprt_std_id, oprt_std_nm, num_val, txt_val, unit_nm, use_plc_desc) VALUES
 ('PART_PROFIT_RATE',     '파트 이익 배분율',                 40,         NULL, '%',   'UI-19, 3.6'),
 ('PART_SALES_FEE',       '영업비(1인·월)',                   1500000,    NULL, '원',  'UI-19, 3.6'),
 ('VAT_RATE',             '부가세율',                         10,         NULL, '%',   '외주·청구'),
 ('PAY_ROUND_DAYS',       '지급 차수 기본일',                 NULL, '10,15,20,END,', '일', 'UI-20'),
 ('EXT_PROB_H',           '연장 가능성 확률 - 높음',          80,         NULL, '%',   '3.10, UI-28'),
 ('EXT_PROB_M',           '연장 가능성 확률 - 보통',          50,         NULL, '%',   '3.10, UI-28'),
 ('EXT_PROB_L',           '연장 가능성 확률 - 낮음',          0,          NULL, '%',   '3.10, UI-28'),
 ('STAGE_PROB_DISC',      '영업 단계 확률 - 발굴',            10,         NULL, '%',   '3.10, UI-29'),
 ('STAGE_PROB_PROP',      '영업 단계 확률 - 제안',            30,         NULL, '%',   '3.10, UI-29'),
 ('STAGE_PROB_NEGO',      '영업 단계 확률 - 협상',            60,         NULL, '%',   '3.10, UI-29'),
 ('STAGE_PROB_PRE',       '영업 단계 확률 - 계약 직전',       90,         NULL, '%',   '3.10, UI-29'),
 ('TARGET_UTIL_RATE',     '목표 가동률',                      90,         NULL, '%',   '대시보드, UI-28'),
 ('LOW_MARGIN_GAP',       '저마진 경고(목표 대비 %p)',        5,          NULL, '%p',  '대시보드, UI-04'),
 ('MM_WARN_LIMIT',        '한 달 MM 경고',                    1.0,        NULL, 'MM',  'UI-12'),
 ('AI_W_JOB',             'AI 추천 배점 - 직무',              25,         NULL, '점',  '3.11'),
 ('AI_W_SKILL',           'AI 추천 배점 - 스킬',              20,         NULL, '점',  '3.11'),
 ('AI_W_CUSTOMER',        'AI 추천 배점 - 고객·업종',         15,         NULL, '점',  '3.11'),
 ('AI_W_GRADE',           'AI 추천 배점 - 등급',              10,         NULL, '점',  '3.11'),
 ('AI_W_PERF',            'AI 추천 배점 - 성과',              10,         NULL, '점',  '3.11'),
 ('AI_W_COST',            'AI 추천 배점 - 비용',              10,         NULL, '점',  '3.11'),
 ('AI_W_AVAIL',           'AI 추천 배점 - 가용일',            10,         NULL, '점',  '3.11'),
 ('LOGIN_LOCK_COUNT',     '로그인 잠금 실패 횟수',            5,          NULL, '회',  '5.2'),
 ('SESSION_TIMEOUT_MIN',  '자동 로그아웃',                    30,         NULL, '분',  '5.2'),
 ('PW_MIN_LENGTH',        '비밀번호 최소 길이',               8,          NULL, '자',  '5.2'),
 ('PW_CHANGE_DAYS',       '비밀번호 변경 안내 주기',          90,         NULL, '일',  '5.2'),
 ('ACCESS_LOG_KEEP_YEARS','접근 기록 보관 기간',              3,          NULL, '년',  'D-26'),
 ('SIG_DELAY_WARN',       '신호등 - 일정 늦음(주의)',         5,          NULL, '%p',  '3.12'),
 ('SIG_DELAY_RISK',       '신호등 - 일정 늦음(위험)',         10,         NULL, '%p',  '3.12'),
 ('SIG_COST_WARN',        '신호등 - 진척 대비 원가(주의)',    5,          NULL, '%p',  '3.12'),
 ('SIG_COST_RISK',        '신호등 - 진척 대비 원가(위험)',    15,         NULL, '%p',  '3.12'),
 ('SIG_STALE_DAYS',       '신호등 - 업데이트 없음',           14,         NULL, '일',  '3.12, UI-31'),
 ('BUDGET_RATE',          '예산(원가 사용률 분모) = 계약금액 ×', 80,      NULL, '%',   '3.12, UI-01 목표 원가'),
 ('BUDGET_WARN_RATE',     '예산 소진율 주황 표시',            80,         NULL, '%',   'UI-04'),
 ('QUOTE_APPROVAL_RATE',  '견적 승인 기준 이익률',            15,         NULL, '%',   '3.14, UI-29'),
 ('TARGET_MARGIN_RATE',   '목표 이익률',                      20,         NULL, '%',   '3.14, UI-32'),
 ('QUOTE_REAPPROVE_TOL',  '재승인 면제 이익률 차이',          0.5,        NULL, '%p',  '3.14'),
 ('DUN_DAYS_1',           '미수 알림 1단계',                  7,          NULL, '일',  '3.15, UI-13'),
 ('DUN_DAYS_2',           '미수 알림 2단계',                  30,         NULL, '일',  '3.15, UI-13'),
 ('DUN_DAYS_3',           '미수 알림 3단계',                  60,         NULL, '일',  '3.15, UI-13'),
 ('AR_AGING_DAYS',        '미수금 경과 구간',                 NULL, '30,60,90', '일', 'UI-13'),
 ('RENEGO_COST_RATIO',    '재협상·원인 기준(단가 대비 원가)', 80,         NULL, '%',   '3.15, UI-04, UI-32'),
 ('RENEGO_RAISE_RATE',    '재협상 인상률 가정(기본)',         5,          NULL, '%',   'UI-32'),
 ('HIRE_LEAD_MONTHS',     '채용 준비 기간',                   2,          NULL, '개월','3.15, UI-29'),
 ('HIRE_GAP_HEADCOUNT',   '채용 권장 부족 인원 기준',         0.4,        NULL, '명',  '3.15'),
 ('BILL_GAP_RATE',        '청구 누락 판정 차이율',            0.5,        NULL, '%',   '3.15'),
 ('BILL_GAP_MIN',         '청구 누락 판정 최소 차이',         10000,      NULL, '원',  '3.15'),
 ('TEAM_FEE',             '팀비(자사 인력 1인·월)',           50000,      NULL, '원',  '3.2-4'),
 ('PROJECT_EXP',          '프로젝트경비(자사 인력 1인·월)',   50000,      NULL, '원',  '3.2-4'),
 ('EMP_INS_RATE',         '고용보험 회사분(참고)',            1.15,       NULL, '%',   '3.2-1'),
 ('IND_ACC_RATE',         '산재보험(참고)',                   1.0,        NULL, '%',   '3.2-1'),
 ('WITHHOLD_RATE',        '원천세율(계약직·프리랜서)',        3.3,        NULL, '%',   '3.2-1'),
 ('DISPATCH_MAX_MONTHS',  '파견 최대 기간',                   24,         NULL, '개월','UI-05'),
 ('DISPATCH_ALERT_DAYS',  '파견 만료 알림',                   NULL, '60,30', '일', 'UI-05'),
 ('WITHDRAW_ALERT_DAYS',  '철수 알림',                        NULL, '90,30,15', '일', 'UI-05'),
 ('NEXT_SEAT_MONTHS',     '다음 자리 탐색 기간',              3,          NULL, '개월','3.13'),
 ('CAND_STALE_DAYS',      '후보 연락 경과 표시',              30,         NULL, '일',  'UI-33'),
 ('MIN_OPERATING_CASH',   '최소 운영자금',                    1000000000, NULL, '원',  '대시보드, UI-21'),
 ('FORECAST_MONTHS',      '예측 기간',                        6,          NULL, '개월','UI-17, UI-26, UI-29');

-- 18.5 영업 파트: 본사(직접 영업) ---------------------------------------------------
INSERT INTO mst.bsn_part (bsn_part_id, bsn_part_nm, hq_yn, sort_seq) VALUES (1, '본사(직접 영업)', 'Y', 999);
INSERT INTO mst.bsn_part_std_hist (bsn_part_id, aply_bgn_ym, prft_dstrb_rt, bsn_fee_amt, chg_rsn_desc)
VALUES (1, '2026-01', 0, 0, '본사 직접 영업: 배분율 0%, 영업비 0원');

-- 18.6 역할 (5.1) ------------------------------------------------------------------
INSERT INTO sys.role (role_id, role_nm, role_desc, data_scope_cd, snstv_inq_yn, part_std_chg_auth_yn, admin_yn, bltin_yn, sort_seq) VALUES
 (1, '시스템 관리자',   '모든 화면, 사용자·권한·메뉴·코드 관리',                'ALL',     'Y', 'Y', 'Y', 'Y', 1),
 (2, '경영진',          '모든 조회, 대시보드, AI 분석 (입력·수정 없음), 견적 승인', 'ALL',     'Y', 'N', 'N', 'Y', 2),
 (3, '재무 담당',       '원가·청구·입금·자금수지·재무 입력, 월 마감',           'ALL',     'Y', 'Y', 'N', 'Y', 3),
 (4, '영업 파트장',     '자기 파트 프로젝트·투입·영업 기회 등록과 조회',        'PART',    'N', 'N', 'N', 'Y', 4),
 (5, '일반 사용자(PM)', '맡은 프로젝트의 투입·인원 계획 조회와 입력',           'PROJECT', 'N', 'N', 'N', 'Y', 5);

-- 18.7 메뉴 ------------------------------------------------------------------------
INSERT INTO sys.menu (menu_id, upr_menu_id, menu_nm, scrn_id, icon_nm, sort_seq, mbl_expsr_yn) VALUES
 (1,  NULL, '대시보드',      NULL, 'grid',     1,  'Y'),
 (2,  NULL, '기준정보',      NULL, 'db',       2,  'Y'),
 (3,  NULL, '프로젝트·투입', NULL, 'folder',   3,  'Y'),
 (4,  NULL, '인력 계획',     NULL, 'calendar', 4,  'Y'),
 (5,  NULL, '매출',          NULL, 'receipt',  5,  'Y'),
 (6,  NULL, '원가·수익',     NULL, 'chart',    6,  'Y'),
 (7,  NULL, '재무·자금',     NULL, 'wallet',   7,  'Y'),
 (8,  NULL, '마감',          NULL, 'lock',     8,  'Y'),
 (9,  NULL, 'AI',            NULL, 'sparkle',  9,  'Y'),
 (10, NULL, '시스템',        NULL, 'gear',     10, 'Y');
SELECT setval('sys.seq_menu_id', 10);
INSERT INTO sys.menu (upr_menu_id, menu_nm, scrn_id, sort_seq, mbl_expsr_yn) VALUES
 (1,  '대시보드',            'UI-16', 1, 'Y'),
 (2,  '거래처·영업 파트',    'UI-15', 1, 'Y'), (2, '개발자 풀',          'UI-02', 2, 'Y'),
 (3,  '프로젝트',            'UI-01', 1, 'Y'), (3, '프로젝트 현황판',    'UI-31', 2, 'Y'),
 (3,  '투입 배정',           'UI-12', 3, 'Y'), (3, '철수 리스트',        'UI-05', 4, 'Y'),
 (3,  '인원 계획 대비 투입', 'UI-11', 5, 'Y'),
 (4,  '인력 운영 보드',      'UI-28', 1, 'Y'), (4, '인력 수요 예측',     'UI-17', 2, 'Y'),
 (4,  '영업 파이프라인',     'UI-29', 3, 'Y'), (4, '외부 인력 후보',     'UI-33', 4, 'Y'),
 (5,  '청구·입금',           'UI-13', 1, 'Y'),
 (6,  '개인 원가·수익표',    'UI-03', 1, 'Y'), (6, '프로젝트 원가 분석', 'UI-04', 2, 'Y'),
 (6,  '영업 파트 이익금',    'UI-19', 3, 'Y'), (6, '단가 재협상 대상',   'UI-32', 4, 'Y'),
 (7,  '재무 항목',           'UI-10', 1, 'N'), (7, '전체 수익 구조',     'UI-07', 2, 'Y'),
 (7,  '영업이익표',          'UI-08', 3, 'Y'), (7, '순이익표',           'UI-09', 4, 'Y'),
 (7,  '사업계획 대비 실적',  'UI-18', 5, 'Y'), (7, '지급 관리',          'UI-20', 6, 'N'),
 (7,  '자금수지표',          'UI-21', 7, 'Y'),
 (8,  '월 마감',             'UI-14', 1, 'N'),
 (9,  'AI 분석 도우미',      'UI-26', 1, 'Y'), (9, 'AI 인력 추천',       'UI-30', 2, 'Y'),
 (10, '사용자',              'UI-23', 1, 'N'), (10, '권한',              'UI-24', 2, 'N'),
 (10, '메뉴',                'UI-25', 3, 'N'), (10, '공통코드',          'UI-27', 4, 'N');

-- 18.8 역할별 기본 권한 (시안 PERM_DEF: 조회·등록·수정·삭제·업로드·다운로드·확정) ------------
INSERT INTO sys.role_menu_auth (role_id, menu_id, inq_auth_yn, reg_auth_yn, mdfcn_auth_yn, del_auth_yn, upld_auth_yn, dwnld_auth_yn, cnfrm_auth_yn)
SELECT r.role_id, m.menu_id,
       CASE WHEN d.deny THEN 'N' ELSE substr(r.p,1,1) END, CASE WHEN d.deny THEN 'N' ELSE substr(r.p,2,1) END,
       CASE WHEN d.deny THEN 'N' ELSE substr(r.p,3,1) END, CASE WHEN d.deny THEN 'N' ELSE substr(r.p,4,1) END,
       CASE WHEN d.deny THEN 'N' ELSE substr(r.p,5,1) END, CASE WHEN d.deny THEN 'N' ELSE substr(r.p,6,1) END,
       CASE WHEN d.deny THEN 'N' ELSE substr(r.p,7,1) END
FROM (VALUES (1, 'YYYYYYY'), (2, 'YNNNNYN'), (3, 'YYYYYYY'), (4, 'YYYNNYN'), (5, 'YYYNNNN')) AS r(role_id, p)
JOIN sys.menu m ON m.scrn_id IS NOT NULL
CROSS JOIN LATERAL (
  SELECT ( (r.role_id = 5 AND m.scrn_id IN ('UI-08','UI-09','UI-10','UI-20','UI-21','UI-03','UI-19','UI-23','UI-24','UI-25','UI-27','UI-14'))
        OR (r.role_id = 4 AND m.scrn_id IN ('UI-10','UI-20','UI-23','UI-24','UI-25','UI-27','UI-14'))
        OR (r.role_id = 2 AND m.scrn_id IN ('UI-23','UI-24','UI-25','UI-27')) ) AS deny
) d;
-- 경영진은 견적 승인(UI-29 확정)을 할 수 있다
UPDATE sys.role_menu_auth rp
   SET cnfrm_auth_yn = 'Y'
  FROM sys.menu m
 WHERE m.menu_id = rp.menu_id AND rp.role_id = 2 AND m.scrn_id = 'UI-29';

-- 번호를 직접 넣은 테이블의 시퀀스를 맞춘다
SELECT setval('mst.seq_bsn_part_id', (SELECT MAX(bsn_part_id) FROM mst.bsn_part));
SELECT setval('sys.seq_role_id',     (SELECT MAX(role_id) FROM sys.role));
SELECT setval('sys.seq_menu_id',     (SELECT MAX(menu_id) FROM sys.menu));

-- 18.9 최초 관리자 계정 (비밀번호 해시는 배포 시 애플리케이션에서 만든 값으로 바꾼다)
-- INSERT INTO sys.user_info (user_id, lgn_id, pswd_hash, user_nm, email_addr, mfa_yn, pswd_chg_need_yn)
-- VALUES (1, 'admin01', '<bcrypt-hash>', '관리자', 'admin@example.com', 'Y', 'Y');
-- INSERT INTO sys.user_role (user_id, role_id) VALUES (1, 1);

-- =====================================================================================
-- 끝
-- =====================================================================================
