SI 프로젝트·원가 관리 기반 앱

시작

저장소 루트에서 실행합니다.
    docker compose -f infra/docker-compose.yml up --build

프런트 기본 포트는 3000, 백엔드 기본 포트는 8081입니다.
FRONTEND_PORT와 BACKEND_PORT로 변경할 수 있습니다.
데이터베이스는 Compose 내부 서비스명 db로만 연결하며 호스트 포트를 공개하지 않습니다.
운영에서는 별도로 안전한 DB 연결 설정을 공급해야 합니다.

첫 사용과 인증 범위

시스템관리 변경 이후 /api/health와 인증 진입 외 API는 로그인이 필요합니다.
로그인 화면은 /UI-22이며 사용자·권한·메뉴 관리는 /UI-23, /UI-24, /UI-25입니다.
개발용 Compose는 aiops.initial-login.json을 읽기 전용으로 마운트하며 첫 기동에만 앱 시험용 초기 관리자를 생성합니다.
앱 시험용 로그인 값은 aiops.initial-login.json에서 확인하세요. 이 파일의 값은 운영 계정이 아닙니다.
첫 로그인 전에 INITIAL_ADMIN_EMAIL과 SMTP_HOST, SMTP_PORT, SMTP_FROM 및 필요한 SMTP_USERNAME/SMTP_PASSWORD를 환경에 설정하세요.
SMTP_AUTH, SMTP_STARTTLS는 메일 서버 정책에 맞춥니다. 기본값은 각각 true입니다.
RESET 링크 발송에는 PUBLIC_ORIGIN에 사용자 브라우저가 접근하는 신뢰된 앱 origin을 설정합니다. 요청 Host를 재설정 링크의 출처로 신뢰하지 않습니다.
관리자는 이메일 2차 인증을 생략할 수 없습니다. 메일 설정 누락·발송 실패 시 503으로 로그인하지 못하며 정상 세션을 만들지 않습니다.
로그인 → 이메일 6자리 코드 확인 → 초기 비밀번호 변경 → 재로그인·MFA 순서로 첫 업무 화면에 진입합니다.
인증 앱의 초기 비밀번호는 반드시 첫 로그인 직후 변경하세요. 90일 경과는 안내만 하며 자동 잠금하지 않습니다.
운영은 prod 프로필, HTTPS, SECURE_COOKIE=true를 사용하고 시험 안내 파일 초기화를 사용하지 않습니다.
운영 최초 관리자만 INITIAL_ADMIN_LOGIN_ID/INITIAL_ADMIN_PASSWORD/INITIAL_ADMIN_EMAIL 환경 비밀로 일회 생성한 뒤 비밀을 제거합니다.
이미 존재하는 초기 관리자와 운영자가 수정한 권한은 재기동으로 덮어쓰지 않습니다.
Compose의 SECURE_COOKIE=false 기본값은 로컬 HTTP 미리보기 전용이며 운영 배포용 설정이 아닙니다.
확인 계정 세 슬롯은 시작 때 모두 있을 때만 읽습니다. 평상시에는 설정하지 않습니다.
자동 확인 계약의 관리자 경로는 MFA와 최초 비밀번호 변경을 완료해야 합니다. 단순 로그인 POST만 수행하는 검사기는 이 보안 흐름을 검증할 수 없으며 인증 우회를 추가하지 않습니다.
계정·토큰·쿠키·요청 본문을 실행 로그나 검증 보고에 기록하지 마세요.

검증

백엔드 디렉터리에서:
    mvn -B -Dtest=AdminApiTest test
    mvn -B test

기본 시험은 외부 DB 없이 실행하며 실제 DB 통합 시험은 TEST_DATABASE_URL,
TEST_DATABASE_USER, TEST_DATABASE_PASSWORD가 모두 제공된 전용 시험 환경에서만 실행합니다.
시험은 격리된 DB를 생성·삭제하므로 운영 DB를 연결하지 마세요.

프런트 디렉터리에서:
    npm ci
    npm run build
    npm run test -- --run
    npm run test -- --run tests/e2e/ui-layout.spec.ts

Vitest는 jsdom으로 실제 컴포넌트를 렌더링합니다.
실제 브라우저 폭·기하·전체 주소 검증은 browser-checks/layout에 별도로 보존합니다.
앱을 시작하고 Playwright 실행 환경에서:
    npm run test:e2e

Docker build stage 시험:
    docker build --target build -t sicost-backend:verify backend
    docker run --rm sicost-backend:verify mvn -B test
    docker build --target build -t sicost-frontend:verify frontend
    docker run --rm sicost-frontend:verify npm run test -- --run

최종 프런트 이미지는 비루트 nginx와 dist만 사용하며 시험 자원은 build stage에만 포함됩니다.
데이터베이스·스키마·시드 설명은 infra/README.md를 참조하세요.

UI-16 통합 대시보드

홈과 /UI-16은 같은 고정 탭을 사용합니다. 기준월은 한국 현재월까지 선택할 수 있습니다.
월 KPI·마감·연간 추이·파트는 선택월 기준, 미수와 경보는 오늘 현재,
인력 6개월과 자금 3개월은 오늘 다음 달부터의 전망입니다.
엑셀 다운로드는 같은 조회 경로의 DTO를 10개 시트로 내보내며 성공 시 기존 접근 이력에 DOWNLOAD 1건을 남깁니다.
조회·재시도는 마감 점검, 확정, 알림 발송이나 업무 데이터 저장을 실행하지 않습니다.
확정 월의 가동률 등 보존된 원천이 없는 값은 자료 확인 필요로 표시하며 실시간 값이나 0으로 대체하지 않습니다.

대시보드 집중 검증은 실행 담당 환경에서 다음 명령을 사용합니다.
    cd backend
    mvn -B -Dtest='Dashboard*Test,CostProjectApiTest,CashFlowApiTest,OpportunityApiTest' test
    cd ../frontend
    npm run typecheck
    npm run test -- --run tests/dashboard
    npm run build
    npm run test:e2e -- ../e2e/tests/dashboard.spec.ts

Playwright는 별도로 시작된 실제 앱을 사용하며 PLAYWRIGHT_BASE_URL로 검증 대상을 지정할 수 있습니다.
정적 검사와 런타임·DB·성능 검증은 구별합니다. 변경 경계와 검증 기록은 docs/dashboard-change-verification.md에 있습니다.
