SI 프로젝트·원가 관리 기반 앱

시작

저장소 루트에서 실행합니다.
    python3 infra/start.py

이 시험용 시작기는 aiops.initial-login.json을 메모리에서 읽어 별도 초기 관리자 생성에만 전달합니다.
확인 계정 슬롯 없이 실행하며, 기존 관리자와 비밀번호를 변경하지 않습니다.
계정 값은 이 안내나 터미널에 복사하지 말고 aiops.initial-login.json에서만 확인하세요.
첫 로그인은 /UI-22에서 진행하고 이메일 2차 인증 후 반드시 비밀번호를 변경하세요.
메일 미설정 상태에서도 기동은 성공하지만 관리자 첫 로그인에는 SMTP 수신 환경이 필요합니다.
시험용 기본 이메일은 배달하지 않는 주소이므로 INITIAL_ADMIN_EMAIL로 시험 SMTP 수신 주소를
초기 생성 전에 지정하세요. SPRING_MAIL_HOST 및 SICOST_MAIL_FROM 등 아래 메일 설정도 필요합니다.
운영에서는 이 시험용 시작기와 안내 파일을 사용하지 말고 별도 관리자 비밀을 공급하세요.

초기 관리자 생성을 요청하지 않는 기동·구성·빌드도 지원합니다.
    docker compose -f infra/docker-compose.yml config --quiet
    docker compose -f infra/docker-compose.yml up --build -d

프런트 기본 포트는 3000, 백엔드 기본 포트는 8081입니다.
FRONTEND_PORT와 BACKEND_PORT로 변경할 수 있습니다.
데이터베이스는 Compose 내부 서비스명 db로만 연결하며 호스트 포트를 공개하지 않습니다.
운영에서는 별도로 안전한 DB 연결 설정을 공급해야 합니다.

첫 사용과 인증 범위

시스템관리 변경으로 익명 업무 접근을 종료했습니다. /UI-22에서 로그인합니다.
최초 관리자 생성은 확인 계정과 독립적입니다. 앱 시험용 초기 로그인 값과 역할은
루트 aiops.initial-login.json에만 보관합니다. 이 값은 운영 계정으로 사용하지 마세요.
첫 기동 전에 해당 파일의 login_id와 password를 각각 INITIAL_ADMIN_LOGIN_ID,
INITIAL_ADMIN_PASSWORD 환경 변수에 설정하고 INITIAL_ADMIN_EMAIL에는 시험 SMTP에서
수신을 확인할 수 있는 이메일을 지정하세요. 운영에서는 별도 일회 비밀을 공급하세요.
INITIAL_ADMIN_* 없이도 Compose 구성·빌드·기동은 가능하지만 최초 계정이 자동 생성되지는 않습니다.
관리자가 없는 DB에서만 초기화하며 기존 관리자를 재설정하지 않습니다. 초기화 후 세 변수를 제거하고
backend 컨테이너를 재생성하세요. 앱 시험용 안내 파일도 운영 배포 산출물에서 제외하세요.

메일 설정은 SPRING_MAIL_HOST/PORT/USERNAME/PASSWORD, SICOST_MAIL_FROM,
SICOST_PUBLIC_ORIGIN으로 공급합니다. SICOST_PUBLIC_ORIGIN은 실제 HTTPS 출처입니다.
SMTP_AUTH_ENABLED와 SMTP_STARTTLS_ENABLED는 SMTP 서버 정책에 맞추세요.
시험은 외부 실메일 대신 SMTP 캡처 서버를 사용합니다. 메일 미설정에서도 기동하지만
관리자 로그인은 이메일 2차 인증 없이는 실패 닫힘이며 503을 반환합니다.
리버스 프록시에 HTTPS를 설정하세요. 세션·CSRF 쿠키는 항상 Secure/HttpOnly/SameSite=Lax입니다.
로그인 후 수신한 코드를 검증하고 최초 비밀번호 변경을 완료한 뒤 다시 로그인합니다.
첫 로그인 뒤 비밀번호를 반드시 바꾸세요. 90일 경과 정책과 달리 최초 임시 비밀번호 세션은 업무에 접근할 수 없습니다.
정상 로그인 뒤 허용된 대시보드 또는 첫 업무 메뉴로 이동합니다. UI-23/24/25는 시스템 메뉴에서 열립니다.

실행 확인 계정은 backend 시작 시 AIOPS_SMOKE_LOGIN_ID/AIOPS_SMOKE_LOGIN_PASSWORD/
AIOPS_SMOKE_ROLE_CODES가 모두 있을 때만 같은 DB에 생성/갱신됩니다. 값은 안내·로그·fixture에 기록하지 않습니다.
역할 입력은 DB의 실제 역할명(쉼표 구분) 또는 실제 숫자 역할 ID이며 별도 R09 역할을 발명하지 않습니다.
runtime-check는 MFA가 필요 없는 경영진 역할로 실제 프로젝트 목록을 조회합니다.
확인 계정 비밀번호는 플랫폼에서 만든 값을 그대로 해싱하며 사용자 비밀번호 변경 정책을 적용하지 않습니다.
사용자/초기 관리자의 비밀번호 정책과 관리자 MFA·CSRF는 그대로 유지합니다.
기동 때 공급한 확인 슬롯이 있는 실행 환경에서 앱 사용 확인:
    python3 infra/verify_runtime.py --project <격리된-compose-project>
관리자 MFA 검증은 이 단일 로그인 확인 계약과 별도로 SMTP 캡처/E2E에서 수행해야 합니다.
계정·토큰·쿠키·요청 본문을 실행 로그나 검증 보고에 기록하지 마세요.

검증

백엔드 디렉터리에서:
    mvn -B -Dtest=AdministrationApiTest,ContactApiTest,NotificationApiTest,AuthenticationApiTest test
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
