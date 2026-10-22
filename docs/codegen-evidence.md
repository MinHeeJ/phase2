코드 생성 인계 증거

범위
  원본 SQL을 먼저 읽고 V1/V2를 배치한 다음 MyBatis 서비스/API와 UI를 작성했습니다.
  OpenAPI의 16개 operationId에 대응하는 Controller 메서드를 작성했습니다.
  주요 구현: 메뉴 트리, 월마감, 활성 선택 코드, 관리 전체 목록, BIZ 그룹 추가,
  그룹 코드 원자 저장과 SYSTEM 제한, soft disable/enable, 엑셀 대응 생성/PATCH,
  변경 이력, 현재/지정월 기준값, 적용월별 upsert와 전체 적용 이력.
  모든 업무 값은 PostgreSQL 조회이며 제품 코드에 목업 저장소를 넣지 않았습니다.

실제 정적 검사 결과
  docker compose -f infra/docker-compose.yml config --quiet: 종료 코드 0.
  POSTGRES_PASSWORD가 주입되지 않았다는 경고가 있었습니다. 값을 출력하지 않았습니다.
  원본과 V1 비교: DROP SCHEMA 한 줄 제거 이외 동일. 원본 80개 CREATE TABLE 확인.
  원본 메뉴 inventory: leaf 31개 확인. UI-22는 원본 시드에 없으며 명시 route만 지원.
  MyBatis interface 20개 메서드와 XML statement ID 집합 일치.
  XML 파싱: pom.xml, CostMapper.xml 통과.
  JSON 파싱: package.json, tsconfig.json, 런타임/첫 로그인 안내 통과.
  런타임 소스/설정에서 runner 입력 디렉터리 참조 없음 확인.
  최종 Java 구문 분석: 12개 파일 오류 0.
  최종 TSX 11개/TS 9개 구문 분석: 오류 0.
  TypeScript transpileModule 구문 변환: 20개 파일 오류 0.
  OpenAPI의 HTTP method/path 쌍 16개와 Controller 매핑 집합 정확히 일치.
  Docker 두 앱의 build context 제외 규칙/비루트 USER/published port 정적 검사 통과.
  변경 산출물 manifest 60개 경로의 존재와 중복 없음 확인.
  이것은 전체 컴파일/semantic typecheck/시험 실행을 뜻하지 않습니다.
  Pretendard v1.3.9 공식 저장소의 실제 폰트와 LICENSE를 내려받아 로컬 자산으로 배치.

작성한 시험 (실행 결과 아님)
  ApiContractTest: standalone MockMvc로 실제 Controller → 실제 CostService →
  Mockito mapper 경계. 메뉴/월/선택코드/관리 목록/쓰기/기준값/이력/페이지 계약,
  비인증 허용, 필수·길이·허용필드 검증, 중복·SYSTEM·마감 거절, not found,
  비활성 대상 대응 거절, 변경 항목/ADD/사용 상태 이력, 오류 내부정보 비노출.
  OpenAPI fixture는 ClassPathResource("contracts/openapi.yaml")로 읽습니다.
  PersistenceIntegrationTest: DATABASE_URL이 있는 격리 DB에서만 실행하도록 작성.
  실제 초기 수량, soft-disable/선택 필터/이력, 미래 기준값/복합키 upsert와
  과거 유효값 보존, 잘못된 일괄 요청의 데이터·이력 무변경 검사.
  프런트 Vitest/jsdom: 실제 렌더·입력·메뉴/권한 자리·인라인 편집·저장 payload,
  SYSTEM 입력 불변/사용 비활성, 금액/길이 경계, draft 보존/취소/이탈 확인,
  모바일 PC 안내, 검색 단축키·알림. 제품 소스의 문자열 비교 시험 없음.
  Playwright: 이미 기동된 BASE_URL에서 API 메뉴 31 route, 새로고침, 반응형,
  360px overflow, 로컬 font 네트워크를 확인하도록 작성.

실행하지 않은 항목
  요청의 코드 생성 제한에 따라 npm 등 패키지 매니저, mvn test/verify, Docker build,
  Compose up, 개발/preview 서버, Vitest/Playwright를 실행하지 않았습니다.
  실제 DB migration 성공, 서비스 healthy, API 실행, 브라우저 렌더, 테스트 통과,
  executable jar 생성 또는 전체 애플리케이션 완료를 주장하지 않습니다.
  현재 호스트 node는 v22.21.1이지만 배포 빌드 이미지는 계약대로 Node20입니다.
  현재 환경에는 Java/Maven 실행 파일이 발견되지 않았습니다.

계약 충돌과 후속 확인
  1. lockfile: 패키지 매니저 실행 금지 때문에 package-lock.json 미생성.
     후속 검증에서 실제 npm으로 생성해야 npm ci 및 frontend Docker build가 가능합니다.
  2. 디자인: 참조 URL은 curl empty reply, 직접 접근은 DNS 실패.
     ui-style.md 자체가 미완료/미관찰 문서이므로 정확한 구조/시각/animation을
     확인했다고 주장할 수 없습니다. 명세의 명시 토큰·레이아웃만 적용했습니다.
  3. 대응 편집 UI: ui-design.md가 임의 저장 trigger를 금지하므로 기존 목록을
     읽기전용으로 남겼습니다. PATCH backend는 구현했지만 해당 UI 연결은 보류입니다.
     사람 선택만 outbox에 기록했고 추천인 읽기전용 유지를 잠정 적용했습니다.
  4. 영속성: 현재 요청의 authoritative resolved MyBatis 계약을 적용했습니다.
     과거 문서의 JPA/@Table/ddl-auto=validate는 병용하지 않았습니다.
     MyBatis로 JPA validate를 충족했다고 주장하지 않습니다.
  5. 인증: tasks/OpenAPI가 명시한 비인증이 제품 정본입니다. 제공된 R09·사용자관리·
     로그인 확인 JSON 예시와 달리 인증·초기 관리자·확인 계정은 만들지 않았습니다.
     런타임 확인은 실제 첫 메뉴인 getMenus와 무인증 안내로 작성했습니다.
  6. SQL: tasks T009의 원본 복제(선두 DROP만 제거)가 일반 IF NOT EXISTS/COMMENT
     재작성 규칙보다 구체적이므로 V1을 재작성하지 않았습니다. 반복 실행 안전성은
     Flyway migration history가 담당합니다. source의 COMMENT와 미사용 업무 테이블도
     원형을 보존했으며 아직 구현하지 않은 업무 API를 추가하지 않았습니다.
  7. 미정 API 정책은 안전한 구현 선택으로 1기반/기본50/기본 정렬만 허용,
     빈 PATCH 거절, 숫자/문자 동시 입력 거절, 유효 기준값 없음 404를 적용했습니다.
     승인된 최종 업무 결정으로 승격하지 않았습니다. 선택 필터는 null SQL 조건이 아닌
     MyBatis 동적 predicate입니다. 목록은 DB 전체 결과에 서버 페이지를 적용합니다.
  8. 이력의 대응 map_id 식별 상세는 미확정입니다. 기존 그룹/코드/항목 전후값을
     기록하며 새 열·합성 식별 규약은 만들지 않았습니다. 기준값 적용월 이력과
     실제 변경 값 항목 이력을 별도 행으로 남겼습니다.

수정하지 않은 입력
  runner 입력 문서와 example 원본은 수정하지 않았습니다. 커밋/푸시하지 않았습니다.
  backend/src/test/resources/contracts/openapi.yaml은 세션 시작부터 있던 fixture이며
  수정하지 않고 main resources에 durable 사본을 복사했습니다.
