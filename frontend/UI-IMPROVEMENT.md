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

## 레이아웃 개선 선행 조사 (T001)

이 절은 현재 소스의 정적 조사 결과다. 위의 과거 실행 결과는 이번 변경의 시험 결과나 FX-GAP 기준선으로 재사용하지 않는다. 이번 단계는 조사 문서만 변경하며 개발자 풀 목록, 공유 스타일, 화면 동작, API·권한·DB·제품 seed를 보존한다. 문서는 후속 구현자가 읽는 인계 자료이며 런타임 의존성이 아니다.

### 실제 화면과 공통 부품 경로 (OQ-UI-008)

`frontend/src/components`와 `frontend/src/screens`는 현재 작업 트리에 없다. 실제 구현은 `frontend/src/pages`와 `frontend/src/shared`에 있다. `frontend/src/app/router.tsx:577-603`의 AppRouter → AppShell.renderBusiness → DevelopersScreen/ProjectsScreen/AssignmentsScreen 경로가 운영 화면이다. `frontend/src/pages/shared/AppShell.tsx:339-359`는 활성 화면 외의 열린 탭을 hidden 처리하므로 측정할 때 숨겨진 보존 탭을 선택하면 안 된다.

| 화면 | 운영 구현과 데이터 흐름 | 작업줄·표 경계 |
| --- | --- | --- |
| SCR-UI-02 /UI-02 개발자 풀 | router.tsx:140-174 → pages/developers/UI02Screen.tsx; listDevelopers(filters) → useAdminRead → items/total | UI02Screen.tsx:306-335 toolbar 및 내부 actions, :339-451 tableScroll/table. Developers.module.css:26-44,56-83을 직접 소비한다. 목록은 변경 금지. |
| SCR-UI-01 /UI-01 프로젝트 | router.tsx의 ProjectsScreen → pages/projects/UI01Screen.tsx; listProjects(filters) → useAdminRead | UI01Screen.tsx:355-366 toolbar, :370-457 tableScroll/table. Projects.module.css:4-14는 개발자 목록 규칙을 compose한다. 선택 체크박스/일괄 선택은 없다. |
| SCR-UI-12 /UI-12 투입 배정 | router.tsx의 AssignmentsScreen → pages/assignments/UI12Screen.tsx; listAssignments(filters) → useAdminRead | UI12Screen.tsx:351-361 toolbar, :366-502 tableScroll/table. Assignments.module.css:4-14는 프로젝트 규칙을 compose한다. selectionActions의 실제 렌더 결과도 작업줄 높이에 영향을 줄 수 있다. |
| SCR-UI-02 상세 투입 이력 | router.tsx:149-154 detailTabs의 assignments → UI02Screen.tsx:472-482 DeveloperDrawer → DeveloperDrawer.tsx:253-283 선택 탭 → DeveloperAssignments({id: row.dev_id}) | pages/developersops/DeveloperReadPanels.tsx:22-128 → shared/Controls.tsx:111-179 DataTable → Controls.module.css:117-179. 목록의 수제 table과는 다른 경로다. |

CSS 의존성은 `Developers.toolbar → Projects.toolbar → Assignments.toolbar` 단방향 소비다. 두 대상 화면에 공통 간격 변경이 필요하면 Projects.module.css가 소유하며 기준 Developers.module.css로 역전파하지 않는다. `DeveloperLifecycleScreen`은 운영 UI-02 목록의 기준이 아니다. 그 화면의 DeveloperLifecycle.module.css:10-12에 있는 `margin: 16px 0`을 운영 목록 간격으로 복사하면 안 된다.

### 변경 요청과 보존 경계

- 후속 빈 결과 변경 대상은 UI12Screen.tsx:485-500의 성공 0건 td 내부뿐이다. :362-365의 loading/error 분기, td의 table-cell/colSpan(읽기 전용 12, 그 외 13), 정상 행과 선택 기능은 유지한다. reset():168-172는 assignmentDefaults(filters.year)로 filters/draft를 함께 초기화한다. 선택연도 저장 및 필터 변경 시 checked 초기화도 유지한다.
- 후속 상세 변경 대상은 DeveloperAssignments의 상태 렌더뿐이다. 현재 :90,119-121은 ASSIGN_STATUS 이름을 조회·대체한다. 승인된 변경은 이 패널에서만 조회를 제거하고 asgn_stts_cd 원문 전체를 표시하는 것이다. ACTIVE/info, 그 외 locked tone, 동일 개발자 ID·페이지·읽기 전용 값과 다른 탭은 유지한다. 전역 Badge/DataTable은 수정하지 않는다.
- Controls.module.css:68-74 배지는 padding·font·line-height를 정의한다. :117-142의 외곽 overflow:hidden / 안쪽 overflow:auto만으로 잘림 원인을 확정할 수 없다. 상태·배지·셀 및 인접 열의 실제 경계 측정이 필요하다. 상세 전용 wrapper와 DeveloperLifecycle.module.css의 국소 selector가 후속 변경 경계다.
- 프로젝트 검색·연도·정렬·페이지·미지정 안내·상세/등록 진입 및 JSON artifact 다운로드, 투입 배정 검색·초기화·선택·과거연도 읽기 전용 및 XLSX binary 다운로드는 유지한다. 일곱 참조 GET은 public이며 permission key를 실권한/로그인으로 바꾸지 않는다. 이번 조사에는 새 API·DTO·마이그레이션이나 인증 fixture가 필요하지 않다.

### FX-GAP: 변경 전 기준선 관찰 기록 (OQ-UI-007)

상태: NOT_MEASURED — awaiting runner verification. 현재 단계는 브라우저/서버/네트워크 실행을 허용하지 않는 조사 단계이며 유일한 실행 검증 경로는 지정 self_check다. 따라서 실제 /UI-02의 DOM bounding box, getComputedStyle, 데이터 응답 및 캡처를 확보하지 않았다. 누락된 관찰을 0px 또는 16px로 채우지 않으며 T001의 브라우저 기준선 인수가 완료됐다고 주장하지 않는다. 제공된 외부 레퍼런스 1280px 수치는 현재 앱의 변경 전 toolbar/table 좌표가 아니므로 대체 증거가 아니다.

| viewport 폭 | 높이 | toolbar bottom | table top | 차이 | 계산 스타일 | 캡처 |
| --- | --- | --- | --- | --- | --- | --- |
| 1280px | 미측정 | 미측정 | 미측정 | 미측정 | 미수집 | 없음 |
| 1024px | 미측정 | 미측정 | 미측정 | 미측정 | 미수집 | 없음 |
| 375px | 미측정 | 미측정 | 미측정 | 미측정 | 미수집 | 없음 |
| 360px | 미측정 | 미측정 | 미측정 | 미측정 | 미수집 | 없음 |

정적 스타일 사실(브라우저 계산값 아님):

- Developers.module.css:26-35의 toolbar는 flex, align-items:center, justify-content:space-between, gap:var(--space-3), min-height:var(--control-height)다. toolbar 자체 margin-bottom이나 다음 형제와의 수직 gap 선언은 없다. 이 내부 flex gap을 작업줄-표 사이 거리로 해석하지 않는다.
- Developers.module.css:36-44의 내부 actions는 flex-wrap:wrap 및 margin:var(--space-2) 0을 가진다. :56-62의 tableScroll에는 1px border가 있으므로 tableScroll 상단과 실제 table 상단을 구분해야 한다.
- app/tokens.css:33-58의 선언은 body 14px/20px, table 13px/20px, space-2=8px, space-3=12px, space-4=16px, control-height=36px다. 이 토큰들은 기존 값이지 새 간격의 결정값이 아니다.
- app/global.css:1-19에서 토큰을 가져오고 로컬 PretendardVariable.woff2, Malgun Gothic, sans-serif를 사용하며 border-box를 적용한다. :95-104 및 Controls.module.css:364-369의 767px 이하 규칙은 글꼴/컨트롤 크기를 바꾼다. 같은 간격이 모든 폭에서 같다고 추론하지 않는다.

후속 runner의 기록 계약: 변경 전 visible developer-workspace에서 developer-export가 속한 목록 toolbar와 바로 다음 tableScroll의 실제 table을 선택한다. document.fonts.ready 이후 toolbar.getBoundingClientRect().bottom과 table.getBoundingClientRect().top의 차이를 원값으로 기록하고 tableScroll.top도 별도 기록한다. getComputedStyle로 toolbar/내부 actions/tableScroll/table의 margin, padding, border, gap, display, min-height와 사용 토큰을 함께 저장한다. 각 폭에서 viewport 높이·실제 font·zoom·deviceScaleFactor·밀도·검색조건·행 식별자·선택상태·캡처 경로를 남겨 전후 동일 조건으로 비교한다. 값에 대한 허용 오차는 추가하지 않는다. 동일 데이터에서 정상/빈 결과 및 선택/해제 전환을 비교하고, 두 대상이 이미 기준과 같으면 불필요한 수치 변경 없이 composition을 보존한다. 실제 API 표본이 없으면 그 사실을 기록하며 transport fixture를 실DB 증거로 바꾸지 않는다.

### 시험·빌드 실행 경로

frontend/package.json:7-13에 확인된 명령(저장소 루트 기준):

- 타입 검사: `npm --prefix frontend run typecheck`
- 컴포넌트 시험: `npm --prefix frontend run test -- --run`
- 빌드: `npm --prefix frontend run build` (tsc --noEmit 뒤 vite build)
- 브라우저 시험: `npm --prefix frontend run test:e2e -- --workers=1`
- lint: script 없음. 설치하거나 성공으로 기록하지 않는다.

위 네 npm 명령은 후속 runner 소유이며 이번 조사에서 실행하지 않았다. vite.config.ts:8-13은 jsdom, tests/setup.ts, 단일 fork를 사용하고 browser-checks를 제외한다. playwright.config.ts:4-9는 browser-checks/layout 아래 **/*.pw.ts만 수집하고 Asia/Seoul을 사용한다. 기존 browser-checks/layout/ui-layout.pw.ts는 transport fixture 기반 shell/route 시험이며 개발자 목록 기준 간격을 저장하지 않는다. tests/e2e/ui-layout.spec.ts는 Vitest + Testing Library 파일이므로 브라우저 좌표·캡처 증거가 아니다. 기존 검증 기록이나 self_check PASS도 FX-GAP 실측·실제 API/DB·다운로드 인수를 대체하지 않는다.

T001 조사 환경에서는 git status/branch 조회가 `.git` 부재로 실패했다. 이는 당시 환경의 기록이며 최종 통합 worktree의 상태를 뜻하지 않는다. 당시 편집 범위는 이 조사 절과 runner 인계 JSON뿐이다.

## 프로젝트 목록 작업 영역 (SCR-UI-01)

프로젝트 다운로드 버튼에 기존 `Projects.actions` 래퍼를 적용하여 운영 개발자 목록과 동일한 toolbar → actions → button 구조를 사용한다. `Projects.toolbar/actions/tableScroll`은 `Developers.module.css`를 compose하고, 투입 배정은 계속 Projects 규칙을 compose한다. 새 margin·고정 높이·화면별 픽셀 보정은 추가하지 않으며 개발자 풀 목록과 CSS는 변경하지 않는다. 이 변경은 기존 클래스의 소비 구조만 맞춘 것으로 실제 간격 좌표 일치의 증거는 아니다.

컴포넌트 회귀 시험은 결과 있음→검색 빈 결과→조건 초기화에서 같은 작업줄·actions·표 컨테이너를 유지하는지, 기존 총 건수·서버 페이지·선택연도·초기 정렬·다운로드 필터를 보존하는지 확인한다. 다운로드 시험은 JSON artifact 응답을 사용해 파일명·MIME·Blob 내용·URL 해제와 실패 후 재시도를 검증하는 transport 시험이며 실제 API/XLSX 파일 유효성 인수가 아니다. 기존 선택 기능이 없는 프로젝트 목록에 체크박스를 추가하지 않는다.

레퍼런스 probe에서 프로젝트 메뉴의 desktop 1280px 화면은 조회됐지만 tablet/mobile 메뉴 클릭은 timeout이었다. 이는 현재 앱의 변경 전 개발자 목록 기준선이 아니다. 1280/1024/375/360px의 실제 toolbar/table 좌표·계산 스타일·캡처 및 실DB 기반 다운로드 대조는 여전히 미측정이며 후속 runner 검증 대상이다. 지정 self_check의 결과도 이 실측을 대체하지 않는다.

## 투입 배정 작업줄 회귀 (T007)

### 공통 규칙 소비와 보존

SCR-UI-12의 `Assignments.module.css` toolbar/tableScroll/table은 `Projects.module.css`를, 프로젝트의 동일 규칙은 기준 `Developers.module.css`를 compose한다. 이 단방향 소비를 유지한다. 기준선의 실제 작업줄 하단과 표 상단 좌표는 여전히 NOT_MEASURED이므로 16px 등의 새 간격, 고정 작업줄 높이, 화면별 보정값을 넣지 않았다. 정적 composition 확인을 실제 좌표 일치나 AC-502 통과로 보고하지 않는다. 개발자 풀 목록·전역 Controls·API·DB·다운로드 구현은 그대로다.

`src/pages/assignments/UI12Screen.test.tsx`의 기존 Vitest/jsdom 설정과 독립적인 beforeEach fixture를 확장했다. 실제 UI12Screen에 router와 같은 AssignmentActions 연결 함수를 전달하고 다음을 검증한다. 소스 문자열 비교나 브라우저 좌표 단언은 사용하지 않는다.

- 미선택 → 선택 → 해제 → 성공 빈 검색 → 조건 초기화에서 기존 toolbar DOM과 toolbar/tableScroll/table class 연결 유지, 서버 total 표시 및 선택 버튼 활성/비활성.
- 선택한 목록의 연장 가능성 창 열기·취소, 새 검색 시 체크 해제 및 일괄 작업 비활성화. 취소와 목록 이동은 쓰기 요청을 만들지 않는다.
- 페이지 이동, 목록 건수 변경, 열 정렬 시 기존 query와 작업줄 구조 보존.
- 과거 선택연도의 초기화·sessionStorage 유지, 배정/체크/변경·취소 숨김, 다운로드 유지.
- 정상/빈 검색 모두 적용한 year/projectId/job/warning/q/sort를 기존 XLSX binary 다운로드에 전달. page/size/선택 ID는 export query에 포함하지 않으며 서버 파일명과 Blob URL 해제를 확인한다.

실행 경로는 저장소 루트의 지정 `python3 /app/infra/hermes/runner/code_agent_detail/node/self_check.py .`뿐이다. 이 파일의 기존 시험과 추가 회귀 시험이 함께 실행된다. 다운로드 시험의 binary prefix/헤더 대역은 기존 UI12ExportButton 시험과 같은 transport 보조 방식이며 실제 XLSX 내부 행·DB 필터 정확성 검증은 아니다.

### 관찰과 남은 인수 경계

지정 ui_probe로 레퍼런스의 투입 배정 메뉴를 조회했다. desktop 1280px에서 프로젝트·투입 shell, 투입 배정 제목과 기존 조회/초기화·연도·표 열을 확인했다. mobile 375px 메뉴 클릭은 timeout이었으며 이 probe는 최종 exit code 1이다. 레퍼런스 수치는 현재 앱의 /UI-02 변경 전 기준선이 아니므로 간격 결정에 전용하지 않았다.

현재 앱의 1280/1024/375/360px별 동일 데이터·font/zoom 기준선 좌표 및 선택/빈 결과 전후 캡처, 실제 API의 빈 검색, 실제 다운로드 파일 행·필터 비교는 후속 runner 인수로 남는다. T002의 빈 결과 내부 wrapper는 최종 worktree의 UI12Screen.tsx에 병합되어 있으며 T007 작업줄 변경과 함께 소비된다. 병합된 회귀 시험 실행은 Quality Gate 소유다. 따라서 self_check 컴포넌트 시험 성공과 FX-GAP/실DB/다운로드 인수 완료는 별개다.

허용 폴더 밖의 기존 시험 파일을 수정한 이유는 운영 UI12Screen의 기존 설정·fixture·격리를 재사용하고 별도 화면·runner를 만들지 않기 위해서다. 이 문서는 foundation 인계 문서에 T007의 코드 리뷰 근거와 검증 한계를 덧붙이는 최소 공유 편집이다. 새 route/menu/API registry 등록은 필요 없다.

## 통합 인수 점검 (T008)

이번 단계는 기존 /UI-02 → /UI-01 → /UI-12 → /UI-02 운영 router 연결을 그대로 시험한다. 누적 설계의 다른 화면/모달은 D1에 따라 재생성하지 않는다. router.tsx와 apiClient.ts의 기존 연결로 병합된 컴포넌트가 소비되므로 새 등록은 필요 없다. 최종 worktree의 backend/src/test/resources/contracts/openapi.yaml은 staged .aiops-spec/contracts/openapi.yaml과 일치한다. 연산의 HTTP 입력·응답·security와 components.schemas는 Git 기준선과 같으며 UI 추적 메타데이터가 추가되어 있다. DB·migration·제품 seed·enum 및 공통 CSS를 변경하는 작업은 아니다.

최종 정적 대조에서 별도 명세 충돌도 확인했다. staged·canonical·effective OpenAPI와 시험 사본에는 기준선에 없던 미사용 components.securitySchemes.bearerAuth가 선언되어 있지만 전역/연산 security는 모두 비인증이며 aiops.runtime-check.json의 auth.required는 false다. 이는 AGENTS.md의 무인증 runtime-check 계약과 충돌한다. 인증을 신설하거나 runtime-check를 인증 필요로 바꾸지 않았고, 권위 명세를 임의 수정하지 않았다. 이 선언의 정리는 명세 소유자/Quality Gate 인계 항목이며 전체 보안 계약 무변경 또는 runtime-check 적합 판정은 하지 않는다.

허용 registry 경로 밖의 `src/app/LayoutRoutes.test.tsx` 추가는 T008의 세 영역 통합 회귀를 실제 AppRouter로 검증하기 위해 필요하다. 가장 가까운 DeveloperRoutes.test.tsx와 같은 MemoryRouter, developerServer, 응답 envelope, preferences 초기화와 fetch 격리를 재사용한다. `src/pages/assignments/UI12Screen.test.tsx`에서는 병합으로 생긴 동일 styles import 중복만 제거했다. 기존 단언은 보존한다. 이 문서 변경은 인수 결과와 미실행 사유를 남기기 위한 공유 편집이다.

추가 컴포넌트 시험은 프로젝트/투입 검색 query와 빈 결과 초기화, td/colSpan/내부 wrapper, 목록 왕복 후 개발자 행·table DOM·선택·검색 초안 보존, 실제 drawer의 상태 원문 네 종류와 인접 열, 쓰기/로그인 요청 부재를 확인한다. 프로젝트 및 투입 조회 실패는 각각 별도 시험으로 성공 빈 결과와 구분한다. 소스 문자열 비교나 jsdom 좌표 단언은 없다.

| 추가 인수 항목 | 이번 단계의 결과 / 남은 실행 경계 |
| --- | --- |
| REQ-491~495 / FX-EMPTY | 컴포넌트 시험 대상: 성공 빈 응답의 wrapper·문구·초기화. 실API 빈 검색, 두 줄/중심 좌표 및 정상 행 복귀 실측은 미실행: 실행 앱·DB·브라우저를 이 단계에서 기동하지 않음. |
| REQ-496~500 / FX-STATUS | 컴포넌트 시험 대상: routed drawer에서 ACTIVE/ENDED/REPLACED/CANCELLED 원문과 다섯 열 보존. transport fixture이며 실DB 표본·badge/cell/text bounds·폭별 인접열 잘림 증거가 아님. |
| REQ-501~504 / FX-GAP·FX-REGRESSION | 기존 Projects/Assignments 단방향 composition 보존. 기존 투입 회귀 시험과 통합 검색/초기화 시험을 지정 self_check로 실행. 1280/1024/375/360px 기준선·font/zoom·전후 좌표/캡처 및 실제 두 export 파일/필터 대조는 미실행. |
| REQ-505 / 기준 목록 | 왕복 후 같은 developer row/table DOM·선택·초안 보존을 시험. 개발자 목록 소스/CSS 무편집. 동일 데이터 브라우저 전후 캡처는 미수집이며 시각 불변 합격 아님. |
| REQ-506 / 공통 규칙 | 정적 확인: Developers → Projects → Assignments composition 유지, 전역 Badge/DataTable 무편집. 실측 간격 합격으로 승격하지 않음. |

T008의 실행 인계 경로는 저장소 루트의 지정 self_check였다. 당시 기록상 최초 실행에서 backend 대상은 PASS였고 frontend 통합 시험은 최근 본 화면과 메뉴의 같은 이름을 함께 선택한 selector 오류로 실패했다. 메뉴 그룹으로 조회 범위를 좁혀 수정했다. 이 과거 기록은 최종 통합 worktree의 실행 결과가 아니다. 최종 일관성 점검에서는 Git metadata를 사용할 수 있었으며 기존 변경사항을 보존하고 정적 계약 대조와 git diff --check만 수행했다. 빌드·Docker·API 시험 및 self_check를 실행하지 않았으며 실행 검증은 Quality Gate 소유다. 이 절의 미실행 항목은 과거 실행 기록이나 transport 시험 성공으로 대체하지 않는다.
