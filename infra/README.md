실행

저장소 루트에서 docker compose -f infra/docker-compose.yml up --build 를 실행합니다.
기본 프런트 포트는 3000, 백엔드 포트는 8081이며 FRONTEND_PORT와 BACKEND_PORT로 변경합니다.
PostgreSQL은 내부 서비스명 db로만 접근하며 호스트 포트를 노출하지 않습니다.
Compose의 비운영 DB 기본 설정은 로컬 개발용입니다. 운영에서는 POSTGRES_DB, POSTGRES_USER,
POSTGRES_PASSWORD를 배포 환경에서 재정의하고 운영 비밀을 저장소에 넣지 마세요.
인증은 제공하지 않으며 계정 생성이나 확인 계정 환경변수 없이 시작합니다.

데이터베이스

Flyway가 V1 원본 정규화 스냅샷과 V2 월마감 시드를 순서대로 적용합니다.
schema.sql은 같은 정규화 아티팩트의 보관본이며 spring.sql.init.mode=never로 실행하지 않습니다.
스냅샷 전체를 수동으로 재실행하는 초기화 도구가 아닙니다. 재기동 시 Flyway 이력으로 중복 적용을 방지합니다.
V1/V2를 배포한 뒤에는 수정하지 않고 다음 승인 버전부터 추가해야 합니다.
시작 시 SchemaValidator가 범위 테이블의 전체 열, 타입, NULL, 기본값, IDENTITY,
PK/FK/유일키를 확인합니다. 이 검사는 Flyway 성공만으로 검증을 대체하지 않습니다.
원본과의 차이는 실행 가능한 DROP SCHEMA 제거, CREATE TABLE/INDEX/SEQUENCE의
IF NOT EXISTS와 비동작 메타데이터 주석 추가뿐입니다. 원본 해시는 schema-origin.json에 보관합니다.

시험

빌드·Docker·API 및 브라우저 검증 실행은 Quality Gate가 담당합니다.
이 문서는 해당 검증이 통과했다는 실행 결과를 의미하지 않습니다.
백엔드 기본 시험은 DB 없이 실행됩니다. SchemaMigrationTest·SharedReadPersistenceTest·AdminPersistenceTest는 TEST_DATABASE_URL,
TEST_DATABASE_USER, TEST_DATABASE_PASSWORD가 모두 있을 때만 실제 PostgreSQL을 사용합니다.
시험 사용자는 전용 시험 서버에서 데이터베이스 생성/삭제 권한이 필요합니다.
각 시험은 template0에서 새 DB를 생성하고 V1/V2를 복원하며 종료 후 그 DB만 삭제합니다.
프로덕션 DB URL을 시험에 공급하면 안 됩니다. 시간 의존 시험은 서울 기준 2026-10의 Clock을 주입합니다.
프런트 단위 시험은 jsdom/Vitest이며 브라우저 다운로드를 하지 않습니다.
tests/e2e/ui-layout.spec.ts는 Vitest/jsdom 렌더링 시험입니다.
Playwright의 실제 폭·기하·전체 주소 시나리오는 browser-checks/layout/ui-layout.pw.ts에 보존하며
앱 기동 후 브라우저 시험 환경에서 npm run test:e2e로 별도 실행합니다.

병합 연결

frontend/src/app/main.tsx가 BrowserRouter와 AppRouter를 마운트합니다.
AppRouter는 공통 AppShell에 UI27Screen을 연결하며, 나머지 등록 화면은 다음 단계 안내를 표시합니다.
메뉴 시드의 31개 화면과 비메뉴 /UI-22 호환 주소를 유지하고 /는 /UI-16으로 이동합니다.
공통 Java API/권한/설정은 com.sicost.common 아래, 프런트 부품과 HTTP 어댑터는 src/shared 아래에 있습니다.
실패한 쓰기는 자동 재전송하지 않고 ErrorBanner의 다시 시도에 읽기 재조회를 연결한 뒤 직접 저장을 받습니다.
공통 조회는 com.sicost.shared, UI-27 관리는 com.sicost.admin의 Mapper·서비스·컨트롤러를 사용합니다.
각 기능의 실제 PostgreSQL 시험은 backend/src/test/java/com/sicost/ 아래에 있습니다.
