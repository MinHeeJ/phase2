SI 프로젝트·원가 관리 기반 앱

시작

저장소 루트에서 실행합니다.
    docker compose -f infra/docker-compose.yml up --build

프런트 기본 포트는 3000, 백엔드 기본 포트는 8081입니다.
FRONTEND_PORT와 BACKEND_PORT로 변경할 수 있습니다.
데이터베이스는 Compose 내부 서비스명 db로만 연결하며 호스트 포트를 공개하지 않습니다.
운영에서는 별도로 안전한 DB 연결 설정을 공급해야 합니다.

첫 사용과 인증 범위

승인 요구사항 REQ-001·REQ-002에 따라 이번 단계는 인증 없이 사용합니다.
시작 후 /UI-16으로 진입하고 업무 메뉴에서 공통코드 관리(/UI-27)를 선택합니다.
초기 관리자 생성, 로그인, 비밀번호 변경과 확인 계정 초기화는 이번 단계에 없습니다.
CurrentUser와 PermissionService는 향후 연결을 위한 명세상 고정 어댑터이며 실제 인증이 아닙니다.
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
