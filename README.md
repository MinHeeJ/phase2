# 프로젝트·원가 관리

## 시작

필수 도구는 Docker Compose V2입니다. 프런트엔드 의존성은 Node 20 및 npm 10을 사용합니다.
frontend/package-lock.json이 포함되어 있으며 프런트엔드 Dockerfile은 npm ci로 잠긴 의존성을 설치합니다.
로컬 검증에서도 frontend에서 npm ci를 사용합니다. 이미지 빌드 중 lockfile을 생성하거나 변경하지 않습니다.

frontend/src/app/main.tsx는 BrowserRouter와 ApplicationRouter를 구성하고 bootstrap.tsx의 mountApplication을 호출합니다.
SharedWorkspace는 DB 메뉴의 화면과 UI-22 직접 진입을 처리하며 /UI-27에는 실제 공통코드 관리 화면을 연결합니다.
그 외 화면은 기존 범위대로 자리 화면만 표시합니다. 저장소 루트에서 다음 명령을 실행합니다.

    python infra/validate_contexts.py
    docker compose -f infra/docker-compose.yml up --build -d

기본 공개 포트는 프런트엔드 3000, 백엔드 8080입니다. FRONTEND_PORT, BACKEND_PORT로 변경할 수 있습니다.
DB는 컨테이너 네트워크 내부에서만 접근합니다. DB_NAME, DB_USERNAME, DB_PASSWORD를 환경에서 공급하며 Compose는 개발용 기본값을 제공합니다.
운영 배포에서는 반드시 DB 접속값을 환경으로 재정의하십시오. 비밀 파일을 이미지에 넣지 않습니다.
백엔드를 직접 시작할 때는 DATABASE_URL(JDBC PostgreSQL URL), DB_USERNAME, DB_PASSWORD를 환경으로 공급합니다.

## 첫 로그인

이번 범위는 비인증 앱입니다. 초기 관리자 생성과 로그인은 없으며 확인 계정 환경 변수에도 의존하지 않습니다.
앱 사용 안내 계약은 aiops.initial-login.json에 있습니다. 원본에 포함된 계정·역할 테이블은 현재 사용하지 않습니다.

## 공통 모듈

기본 Java 패키지는 com.si.cost입니다. CostApplication은 최상위 패키지에서 구성요소를 검색합니다.
MapperScan은 common.persistence.PersistenceConfiguration에 분리되어 있고 @Mapper 인터페이스만 검색합니다.
XML 매퍼는 src/main/resources/mappers/**/*.xml에 둡니다. 서비스에 @Transactional을 선언하고 읽기는 readOnly=true를 사용하십시오.
선택 필터는 MyBatis if/where로 조건부 바인딩하고 정렬은 허용된 컬럼과 방향을 분기로 선택하십시오.

common.api.ApiResponse.ok(data)는 한 봉투를 만듭니다. 승인된 조회는 내부 조회 응답을 data에 한 번 더 담고 쓰기와 health는 ApiResponse.ok()만 반환합니다.
ApiExceptionHandler는 flat ApiError와 단일 field를 유지합니다. BusinessException으로 업무 오류를 전달하십시오.
CurrentUser, PermissionService, DataScope, @Sensitive는 common.user에 있습니다. 권한 판정/마스킹/인증을 추가하지 않습니다.

프런트엔드 공통 부품은 src/shared, 순수 표시 함수는 src/lib/format.ts, 시각 토큰은 src/app/tokens.css에 있습니다.
query는 알려진 두 조회 봉투만 해제합니다. request와 mutate는 단일 봉투를 처리합니다.
오류는 ErrorBanner에 전달하고 입력 상태를 버리지 마십시오. 오류 토스트는 제공하지 않습니다.
Can은 항상 표시하며 표의 sensitive는 메타데이터만 보존합니다.

## 마이그레이션

Spring Boot 정상 시작에서 Flyway가 V1 → V2 → V3를 적용합니다. Spring SQL 자동 초기화는 꺼져 있습니다.
V1은 example/si_cost_schema_pg16_std.sql의 전체 물리 스냅샷입니다. 허용 차이는 단일 DROP SCHEMA 줄 제거, CREATE TABLE/INDEX의 IF NOT EXISTS, 줄 끝 공백 제거입니다.
테이블·타입·키·뷰·시드·함수·트리거는 보존했습니다. V2는 2026-01~09 마감과 2026-10 작업중 시드입니다.
V3는 현재 미사용 범위·참조 의도·상태·파생 필드 주석만 보완합니다. 기존 V1/V2를 수정하지 말고 후속 변경은 다음 버전을 사용하십시오.
schema.sql은 빈 DB용 선택적 baseline 설치 파일입니다. 재실행 시 이미 설치된 baseline은 건너뜁니다. 정상 앱 기동에서는 실행하지 않습니다.

## 검증

기본 백엔드 시험은 외부 DB 없이 실행됩니다.

    cd backend
    mvn test
    mvn verify

MigrationIT는 TEST_DATABASE_URL이 없으면 건너뜁니다. 전용 빈 PostgreSQL 16 DB와 TEST_DB_USERNAME/TEST_DB_PASSWORD를 공급하면 실제 마이그레이션·메타데이터·시드·재기동 checksum을 검증합니다.
공유 DB/운영 DB를 지정하지 마십시오. 시험은 DB를 자동 삭제하지 않으므로 독립 복원은 검증 runner가 수행합니다.
실제 매퍼 업무 읽기·쓰기 통합 검증은 각 API 소유 변경이 제공하는 시험과 함께 실행해야 합니다. Flyway 성공만으로 매퍼 검증 완료를 주장하지 않습니다.

프런트엔드에서는 npm ci 이후 npm run test -- --run, npm run typecheck, npm run build를 실행합니다.
Vitest/jsdom 시험만 기본 실행 대상입니다. test:e2e는 선택적 Playwright 실행 명령이며 브라우저 설치나 E2E 시험을 기본 단위 시험에 연결하지 않습니다.

## 앱 사용 확인 계약

루트 aiops.runtime-check.json은 비인증 앱의 backend:8080, GET /api/health와 첫 화면 /UI-16의 GET /api/menus를 선언합니다.
첫 화면의 메뉴 조회는 인자가 없으며 이중 조회 봉투의 data.data 배열을 확인합니다.
로그인·identity·확인 계정·확인 계정 초기화는 비인증 명세에 없으므로 추가하지 않습니다.
Flyway의 기존 업무 초기 데이터는 확인 계정 환경 변수 없이 정상 시작 시 그대로 생성합니다.
영구 OpenAPI 시험 자원은 backend/src/test/resources/contracts/openapi.yaml이며 미사용 bearer 스키마와 첫 메뉴/전역의 빈 security 표지는 제거했습니다. API 경로·operationId·요청·응답은 보존합니다.
실제 앱 기동 후 저장소 루트에서 다음 명령으로 응답 본문·인증 정보를 출력하지 않고 확인합니다.

    python infra/check_runtime.py

포트를 변경한 로컬 환경에서는 --backend-port 및 --frontend-port 인자로 실제 공개 포트를 지정합니다.
원본 명세 입력은 읽기 전용이며 런타임과 시험에서 직접 참조하지 않습니다.

## 통합 시 확인할 사항

ui-style.md는 미검증 차단 기록만 담고 있어, 요청에서 제공한 실제 측정값과 ui-design 및 원문의 토큰 규칙을 적용했습니다. 화면의 브라우저 검증은 통합 이후에 수행합니다.
