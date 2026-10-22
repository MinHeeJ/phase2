프로젝트·원가 관리 — 기반-1

구성
  backend: Java 17 / Spring Boot 3.3 / Spring MVC / MyBatis / Flyway
  frontend: React 18 / TypeScript / Vite 5 / React Router / Zustand
  DB: PostgreSQL 16
  배포: docker-compose.yml (db-credentials → db → backend → frontend)
  infra/docker-compose.yml은 같은 루트 Compose를 include합니다 (Compose 2.20 이상).

첫 로그인
  이 단계는 명세에 따라 인증 없이 사용합니다. 초기 관리자 계정이나 비밀번호는
  만들지 않습니다. 브라우저에서 /로 진입하면 /UI-16으로 이동합니다.
  공통코드는 /UI-27에서 관리합니다. 역할 테이블의 원본 초기 데이터는 보존하지만
  현재 사용자·권한 자리 외 실제 로그인/권한 판정은 구현하지 않습니다.
  앱 사용 확인 안내: aiops.initial-login.json, aiops.runtime-check.json.

실행 준비
  Node 20와 npm 10을 사용합니다. frontend/package-lock.json이 포함되어 있으며
  npm ci로 고정 의존성을 설치합니다.

  별도 환경변수 없이 시험 앱을 시작할 수 있습니다. db-credentials가 최초 시작에
  난수 DB 비밀번호를 전용 volume에 원자적으로 생성합니다. DB와 backend는 같은
  파일을 읽기 전용으로 공유하며 비밀번호를 출력하지 않습니다. 평문 기본값,
  인증 우회, DB host port 공개는 사용하지 않습니다. POSTGRES_DB, POSTGRES_USER,
  FRONTEND_PORT, BACKEND_PORT를 환경변수로 바꿀 수 있습니다.
  기본 앱 포트는 frontend 3000, backend 8080이며 DB host port는 노출하지 않습니다.

  저장소 루트에서 실행:
    docker compose config --quiet
    docker compose up -d --build --wait
    docker compose ps
  기존 DB volume의 비밀번호를 변경하는 기능은 아닙니다. 이미 초기화한 DB를
  연결할 때는 해당 배포의 비밀 관리 방식으로 동일한 비밀번호 파일을 제공하세요.
  DB volume과 자격증명 volume은 함께 보존하고 운영 volume을 삭제하지 마세요.

  브라우저로 frontend published port에 접속합니다. nginx가 상대 /api 요청을
  backend로 프록시하고 /UI-03 등의 직접 진입/새로고침을 처리합니다.
  backend /api/health는 JSON status=UP과 X-Request-Id를 반환합니다.
  일상 기동에 AIOPS_SMOKE_* 값은 사용하지 않습니다.
  nginx는 요청 URL·쿠키·본문이 로그에 남지 않도록 access/error 로그를 저장하지
  않습니다. API 오류 추적은 서버가 생성한 X-Request-Id로 수행합니다.

데이터와 마이그레이션
  example/si_cost_schema_pg16_std.sql은 원본입니다. V1__schema.sql은 DROP SCHEMA
  한 줄만 제거한 사본입니다. 11개 스키마, 80개 테이블, 원본 제약/인덱스/뷰/초기값을
  보존합니다. V2는 명세의 2026-01~10 월마감 10행입니다. Flyway가 정상 Spring Boot
  시작 중 적용하며 운영 volume을 삭제할 필요가 없습니다. 이후 변경은 V3부터
  추가하고 이미 배포한 migration을 변경하지 마세요.
  원본 복제 계약을 우선하여 V1의 CREATE IF NOT EXISTS 추가/COMMENT 재작성은
  하지 않았습니다. V1 자체의 반복 실행 대신 Flyway checksum/version으로 관리합니다.
  입력값/기간/코드/메뉴는 실제 DB를 사용하며 프런트에 업무 더미 데이터가 없습니다.

검증 명령
  루트에서 (Python 3와 Docker Compose CLI 필요, Docker 데몬은 불필요):
    python -m unittest discover -s tests -v
  backend에서:
    mvn test
  기본 MockMvc 계약 시험은 외부 DB 없이 서비스와 mapper 경계 대역으로 실행합니다.
  실제 PostgreSQL 시험은 DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD를 모두
  주입한 별도 빈 시험 DB에서만 실행합니다. 세 변수 중 하나라도 없거나 인증 값이
  비어 있으면 Spring 컨텍스트 생성 전에 건너뜁니다. 품질 실행기가 URL만 주입한
  build 이미지에서는 DB에 연결하지 않습니다. Compose의 비밀번호 파일은 런타임용이며
  통합 시험에는 별도 시험 DB의 인증 환경변수를 명시해야 합니다.
  URL만 주입되는 품질 실행 환경의 회귀 확인 (backend에서):
    env -u DATABASE_USER -u DATABASE_PASSWORD DATABASE_URL=jdbc:postgresql://127.0.0.1:1/unreachable mvn -B test
  이 명령도 외부 DB 접속 없이 계약 시험을 실행하고 실제 DB 시험은 건너뜁니다.
  실제 DB 시험으로 초기 수량, soft-disable, 선택 필터, 적용월 upsert,
  오류 전후 값/이력 보존을 확인합니다. 운영 DB를 시험 DB로 사용하지 마세요.

  frontend에서:
    npm ci
    npm run typecheck
    npm run test -- --run
    npm run build
  tests/e2e/routes.spec.ts는 Vitest + jsdom에서 라우트·메뉴 동작을 확인합니다.
  npm run test:e2e는 tests/e2e/*.pw.ts를 실행하며 이미 기동된 앱의 BASE_URL을 환경변수로 받습니다.
  필요하면 후속 검증 환경에서 Playwright 브라우저를 설치합니다.
  E2E 스크립트는 서버를 자동 시작하지 않습니다.

  품질 에이전트 빌드 단계:
    docker build --target build -t si-cost-backend-check:1.0.0 backend
    docker run --rm si-cost-backend-check:1.0.0 mvn test
    docker build --target build -t si-cost-frontend-check:1.0.0 frontend
    docker run --rm si-cost-frontend-check:1.0.0 npm run test -- --run
  Dockerfile 안에서는 시험을 실행하지 않습니다. 테스트/도구/소스는 build 단계에만
  포함되고 최종 이미지는 비루트 JRE 또는 비루트 nginx로 실행됩니다.

계약과 남은 확인
  docs/ui-contract-checklist.md: 화면별 구현 체크리스트 및 명세의 UI 제약.
  docs/codegen-evidence.md: 실제 수행한 정적 검사, 수행하지 않은 검증, 미해결 항목.
  docs/changed-files.txt: 생성/변경 파일 목록.
  OpenAPI 정본의 durable 사본은 backend/src/main/resources/contracts/openapi.yaml,
  시험 사본은 backend/src/test/resources/contracts/openapi.yaml입니다.
  Swagger UI는 추가하지 않았습니다. 핵심 계약 구현과 fixture를 우선했습니다.
