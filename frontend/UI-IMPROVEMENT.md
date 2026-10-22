# UI 개선 계약 점검

## 구현 전 체크리스트

- 공통 shell /UI-16: API 메뉴 이름·묶음·아이콘 유지, 로고 route 이동, 즐겨찾기/최근/화면 탭/검색/설정 유지. 56px 헤더, 240/64px 메뉴, PC 탭줄, 모바일 전체 메뉴. 로딩 스켈레톤·조회 오류 재시도·빈 메뉴 안내. 인증 없는 제품이므로 역할 차단 신설 금지.
- YEAR /UI-01, /UI-03, /UI-04, /UI-05, /UI-10, /UI-11, /UI-12, /UI-13, /UI-17, /UI-21, /UI-28: API 제목·breadcrumb·즐겨찾기, 화면별 연도 이동과 복귀 유지. 업무 입력/저장/삭제 없음.
- YEAR_CUM /UI-07, /UI-08, /UI-09, /UI-18: 위와 동일하며 누계월 선택·누계 문구 유지.
- MONTH /UI-14, /UI-19, /UI-20: 선택 연월로 getMonthClose 조회, CLOSED 잠금 안내, 실패 시 선택값 유지·재시도. 모바일 금지 화면은 PC 안내만 표시.
- NONE /UI-16, /UI-22, /UI-23, /UI-24, /UI-25 및 기존 원본 메뉴의 나머지 화면: 다음 단계 안내만 제공, KPI/업무 데이터를 만들지 않음. /UI-22는 기존 비메뉴 호환 주소 유지.
- /UI-27 코드: API 그룹 좌측 선택과 전체 코드 조회, SYSTEM 이름·순서만 수정, 기존 코드 식별값 읽기전용, BIZ 추가·사용 체크. 전체 목록 PUT, 오류 입력 보존·첫 오류 focus, 성공 재조회·지정 성공 메시지. 물리 삭제 없음. 제목 breadcrumb·별·그룹 추가 제공.
- /UI-27 대응: 표기·대상 불변, 주/보조·메모·사용만 PATCH. D11 권고의 기존 행별 저장/취소 유지. POST 창은 활성 코드 옵션 API 사용, 실패 입력 보존, 성공 목록 재조회. 저장 이후 dirty 해제.
- /UI-27 기준값: 현재값 읽기전용, 새 값·적용월만 PUT. 마지막 CLOSED 전체 조회 실패 시 저장 차단. 이름·단위·쓰는 곳 payload 제외. 성공 시 현재 목록과 펼친 적용 이력 갱신, 다른 key 혼입 금지.
- /UI-27 변경 이력: 읽기전용, 그룹 조회·Enter·초기화·서버 total·페이지 건수 유지. 수정/삭제/export 없음.
- 작업창: 외부 클릭 닫힘 금지, 미저장 이탈 확인, 저장 중 중복 요청 금지. 로딩/오류/빈 결과를 실제 조회 상태로 구분.

## 근거와 범위

실제 client.ts, AdminController, AdminModels, AdminService와 기존 router를 추적했다. backend/persistence는 변경하지 않는다. 기존 D4/D5/D10/D11 권고 선택을 유지한다. implementation-map.json은 제공 경로에 존재하지 않으며 confirmations.json/output-contract.json은 읽기 전용으로 소비했다.

레퍼런스 직접 조회는 프록시 HTTP 502, 직접 DNS 실패, loopback 연결 거절이다. ui-style.md 역시 접근 실패를 기록한다. 따라서 실사이트 시각/인터랙션 일치라고 주장하지 않는다. ui-design.md의 확정 제품 토큰과 기록된 shell 계층을 사용하며 ASCII 배치를 복제하지 않는다.

## 검증

실행 결과:

- `npm test -- --run`: Vitest + jsdom 13개 파일, 258개 시험 통과. 실제 컴포넌트를 렌더하여 메뉴·버튼·입력·SYSTEM 제한·미저장 보호·API payload·오류 복구를 검증했다. 소스 문자열 비교 시험은 추가하지 않았다.
- `npm run build`: TypeScript 검사 및 Vite production build 통과.
- `PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright npm run test:e2e -- --workers=1 --reporter=list`: Chromium 브라우저 시험 6개 통과. 1280/1024/375/360px에서 가로 넘침 없음, 헤더56px·메뉴240/64px·모바일 버튼44px, 메뉴 선택 후 overlay 닫힘, 접힌 메뉴 flyout, PC 전용 안내, 등록된 모든 route 및 UI-27 탭 이동을 확인했다.
- 브라우저 시험은 명시적인 transport fixture를 사용한 UI/route 검증이며 실제 PostgreSQL/API 통합 시험 통과를 의미하지 않는다. backend·DB는 수정하지 않았으며 이번 작업에서는 DB 통합 시험을 실행하지 않았다.
- 레퍼런스는 실제 Chromium `page.goto`에서도 HTTP502(빈 본문)였다. 직접 DNS, loopback, 두 연결망의 gateway 대체 경로도 실패했다. 실사이트 픽셀/인터랙션 비교는 검증하지 못했다.
- 로컬 Node22에서 프로젝트의 Node20 engines 경고가 있었으나 설치·시험·빌드는 성공했다. 프로젝트 engines/BOM/의존성/lockfile은 변경하지 않았다.

구현 후 계약 대조:

- 기존 전체 route와 UI-22 호환 주소를 유지했고 임의 업무 화면·API·데이터를 추가하지 않았다.
- 모든 화면에 공통 제목 계층, API 메뉴 이름, 별, 기간/다음 단계 안내 또는 UI-27 업무 region을 유지했다. UI-27에 빠졌던 API breadcrumb와 즐겨찾기를 연결했다.
- SYSTEM·기존 코드 식별값·대응 표기/대상·기준값 메타데이터의 불변 규칙을 유지했다. 삭제 대신 사용 체크와 기존 API payload를 보존했다.
- 스켈레톤·표 내부 빈 상태·조회 실패 재시도·빈 메뉴 원인 안내를 구분했다. 요청 중/실패를 빈 조회 결과로 표시하지 않는다.
- 저장 후 펼친 기준값 적용 이력은 다시 조회하고, 대응 행은 서버 재조회 값을 받아 dirty를 해제한다. 실패 draft와 불명확한 쓰기 재전송 방지 규칙은 유지했다.
- 추가 사람 선택은 없으며 지정 outbox에 빈 confirmations 배열을 기록했다. 기존 확인 기록의 권고안을 변경하거나 사람 승인으로 승격하지 않았다.
