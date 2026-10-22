SI 원가관리 공통 기반

구성
  Java 17 / Spring Boot 3.3.13 / blocking Spring MVC / MyBatis XML Mapper / Flyway
  Node 20 / React 18 / Vite 5 / TypeScript / CSS Modules
  PostgreSQL 16, backend:8080, non-root nginx frontend:8080

시작
  DB_PASSWORD는 실행자가 환경으로 공급하며, 공급하지 않으면 로컬 개발용 기본값(dev-only-password)이 쓰인다. 운영에서는 반드시 환경으로 덮어쓴다. 저장소의 .env 또는 운영 비밀을 읽어 기본값을 만들지 않는다.
  DB_NAME과 DB_USERNAME의 기본값은 sicost다. 운영 환경에서는 데이터베이스 이름, 사용자와 비밀번호를 별도로 공급한다.
  docker compose -f infra/docker-compose.yml up --build
  기본 공개 포트는 frontend 3000, backend 8080이다. FRONTEND_PORT와 BACKEND_PORT로 변경할 수 있다.
  DB에는 호스트 포트를 공개하지 않는다. PostgreSQL 최초 실행에는 DB_PASSWORD가 쓰이며, 값을 공급하지 않으면 개발용 기본값이 적용된다.
  backend는 Compose의 DB_URL, DB_USERNAME, DB_PASSWORD를 사용한다. 직접 실행할 때도 같은 환경변수를 제공한다.

마이그레이션
  Spring Boot 시작 과정에서 Flyway V1 → V2 → V3를 적용한다. schema.sql은 자동 초기화를 끈 참조 snapshot이다.
  V1은 원본 전체 11스키마/80테이블을 보존하며 DROP SCHEMA 제거와 CREATE TABLE/INDEX/독립 SEQUENCE의 IF NOT EXISTS 추가만 허용한다.
  V2는 지정된 2026년 월마감 10행, V3는 설명과 updated_at fallback 정합화다. 배포 후 V1/V2를 수정하지 않는다.
  별도 SchemaContractVerifier가 실제 PostgreSQL 컬럼·타입·길이·정밀도·NULL·IDENTITY·PK/FK·created_at 기본값을 검증한다.

첫 로그인
  무인증 애플리케이션이다. 로그인, 초기 관리자 생성, 계정 및 비밀번호가 필요하지 않다.
  CurrentUser/PermissionService/DataScope/@Sensitive는 승인된 향후 정책 자리이며 실제 역할 조회나 마스킹을 하지 않는다.

시험
  인계 검증 명령은 저장소 루트에서 다음 하나다.
  python3 /app/infra/hermes/runner/code_agent_detail/node/self_check.py .
  Maven verify에는 Surefire와 Failsafe가 구성되어 있다. 실제 PostgreSQL 통합 시험은 TEST_DATABASE_URL, TEST_DATABASE_USERNAME, TEST_DATABASE_PASSWORD가 공급될 때만 실행한다.
  통합 시험은 해당 연결의 CREATE DATABASE 권한으로 시험마다 별도 임시 DB를 만들고 V1~V3를 복원한 뒤 삭제한다. 제품 DB를 TEST_DATABASE_URL로 지정하지 않는다.
  프런트 단위 시험은 Vitest/jsdom이다. test:e2e는 Playwright 실행 진입점이며 현재 브라우저 E2E 시험 파일은 없다.

검증 결과와 통합 경계
  이전 기반 단계의 기록은 self_check.py 단위 시험 PASS 및 sql_oracle_check 모듈/DB 연결 환경 미제공으로 SQL·실제 DB 검사를 건너뛴 상태다. 이 기록은 현재 통합본의 검증 결과가 아니며 마이그레이션/Compose healthy 검증 완료를 뜻하지 않는다.
  통합된 API는 health·메뉴·월마감·활성 코드·월별 기준값 조회와 /api/admin 아래 코드 그룹·전체 코드·엑셀 표기 대응·기준값 적용월·변경 이력이다.
  프런트 main.tsx는 공통 router를 마운트한다. SQL 메뉴의 31개 화면과 메뉴에 없는 /UI-22 직접 접근 자리를 제공하며 UI-27만 업무 기능을 구현한다.
  목록 기본 건수와 좁게 보기는 공통 si. 설정을 사용한다. 변경 이력은 명세의 초기 50건을 유지하며 전체 코드 저장과 적용 이력은 모든 페이지를 수집한다.
  읽기 전용 staged 명세와 backend 시험 OpenAPI는 보존했다. 조회 응답의 중첩 봉투 선언과 미사용 bearerAuth는 실제 단일 봉투·무인증 구현과 불일치하며 계약 소유자의 정합화가 필요하다.

보안 수리와 앱 사용 확인 경계
  RequestIdFilter는 길이 및 ASCII allowlist를 통과한 요청 ID만 응답에 사용하며, 허용되지 않은 문자가 하나라도 있으면 서버 UUID로 대체한다. 요청 본문·인증 헤더·쿠키는 기록하지 않는다.
  마이그레이션 시험의 행 수 SQL은 고정 리터럴이다. 시험 DB의 CREATE/DROP은 생성된 이름 형식만 허용하고 식별자를 인용한다. 실제 PostgreSQL 회귀 시험은 독립 DB에서 수행한다.
  빌드와 시험은 Dockerfile 밖에서 실행한다. backend build stage에서 mvn -B verify, frontend build stage에서 npm run test -- --run을 실행한다. 최종 nginx 이미지에는 dist만 포함한다.
  앱 사용 확인은 아직 APP_RUNTIME_CONTRACT_INVALID / auth_required_by_spec로 차단된다. OpenAPI의 모든 연산은 security=[]이고 로그인 연산·x-roles가 없지만 components.securitySchemes.bearerAuth 선언이 남아 있다. 검사기는 미사용 선언도 인증 요구로 판정한다.
  auth.required=true로만 바꾸거나 존재하지 않는 로그인·identity·역할·시험 계정을 만들지 않는다. 계약 소유자가 원본의 미사용 securitySchemes를 제거하고 durable fixture를 동기화한 뒤 앱 사용 확인을 다시 실행해야 한다. 별도 인증 도입은 명세 변경 승인이 필요하다.
  backend 및 nginx 경유 /api/health·/api/menus의 실제 JSON 조회 성공은 별도 런타임 진단이며, 위 계약 게이트의 합격을 뜻하지 않는다. 전체 Quality 판정은 원본 계약 정합화 후 다시 받아야 한다.
