SI 프로젝트·원가 관리 기반 앱

시작

저장소 루트에서 실행합니다.
    docker compose -f infra/docker-compose.yml up --build

프런트 기본 포트는 3000, 백엔드 기본 포트는 8081입니다.
FRONTEND_PORT와 BACKEND_PORT로 변경할 수 있습니다.
데이터베이스는 Compose 내부 서비스명 db로만 연결하며 호스트 포트를 공개하지 않습니다.
운영에서는 별도로 안전한 DB 연결 설정을 공급해야 합니다.

첫 사용과 인증 범위

시스템관리 변경부터 업무 API와 화면에는 실제 서버 세션 인증이 필요합니다.
공개 진입 화면은 /UI-22이며 관리자는 반드시 이메일 MFA를 거칩니다.
초기 관리자용 앱 시험 계정 안내는 aiops.initial-login.json에 있습니다.
비운영 Compose는 이 안내 파일을 읽기 전용으로 마운트합니다. 비밀번호를 이미지에 포함하지 않습니다.
첫 기동 전에 SICOST_INITIAL_ADMIN_EMAIL을 실제 수신 가능한 주소로 공급하고 SMTP_HOST,
SMTP_PORT, SMTP_FROM, 필요한 경우 SMTP_USERNAME/SMTP_PASSWORD 및 SMTP_STARTTLS를 공급하세요.
SMTP와 이메일 없이도 앱은 기동하지만 관리자 MFA는 503으로 실패 닫힘 처리됩니다.
이때 로그인할 수 있는 것처럼 안내하거나 MFA를 생략하지 않습니다.
APP_PUBLIC_ORIGIN은 RESET 메일 링크에 쓰는 배포 출처입니다. 프런트 API 호출은 상대경로만 사용합니다.
안내 파일 기반 초기화는 비운영에서만 사용합니다. 운영은 SICOST_INITIAL_ADMIN_LOGIN_ID,
SICOST_INITIAL_ADMIN_PASSWORD, SICOST_INITIAL_ADMIN_EMAIL을 비밀 관리자로 일회 공급합니다.
이미 관리자 역할을 가진 계정이 있으면 초기화는 기존 계정을 덮어쓰지 않습니다.
첫 로그인 순서: /UI-22에서 안내 계정으로 로그인 → 수신한 MFA 확인 → 비밀번호 변경 → 다시 로그인/MFA.
최초 비밀번호 변경 전에는 업무 화면을 열 수 없습니다. 초기화 후 운영 일회 비밀은 제거하세요.
확인 계정의 AIOPS_SMOKE_* 세 값은 초기 관리자와 독립적이며 세 값이 모두 있을 때만 DB에 반영합니다.
평상시 시작·빌드·Compose 설정 검증에는 확인 계정 값이 필요하지 않습니다.
계정·토큰·쿠키·요청 본문을 실행 로그나 검증 보고에 기록하지 마세요.

인증 앱 사용 확인

이미지는 확인 계정 환경 변수 없이 먼저 빌드합니다.
    docker compose -f infra/docker-compose.yml build backend frontend
    python infra/verify_runtime.py --start --project sicost-smoke-check

대상을 지정하지 않고 python infra/verify_runtime.py를 실행해도 매번 새 이름의 격리
프로젝트를 기동해 검사합니다. 이 기본 실행은 기존 앱의 계정이나 안내 계정을 사용하지
않으며 확인 계정 슬롯은 메모리에서 생성해 기동 시에만 전달합니다.

--start는 비어 있는 별도 Compose 프로젝트만 허용합니다. 매 실행 임시 확인 계정을
메모리에서 만들고 세 환경 변수로 백엔드 기동 시에만 전달합니다. 안내 계정은 읽지 않습니다.
기동·공개 JSON health·CSRF를 포함한 로그인·역할 identity·첫 업무 목록·nginx 프록시와
화면 경로를 확인하고, 성공·실패 모두 해당 시험 프로젝트와 시험 볼륨을 제거합니다.
계정·응답·쿠키·토큰은 출력하거나 임시 파일에 저장하지 않습니다.
기존 전용 시험 프로젝트 확인은 실행 환경에 동일한 세 슬롯을 공급한 뒤 --start 없이
실행합니다. 누락되거나 일부만 주어진 슬롯은 실패로 처리하며 안내 계정으로 대체하지 않습니다.

검증

백엔드 디렉터리에서:
    mvn -B -Dtest=AuthenticationApiTest,SystemManagementApiTest,NotificationApiTest,SecurityBoundaryApiTest test
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
