UI 개선 계약 체크리스트

범위와 근거
- ui-design.md의 화면 inventory와 기존 registry/router를 대조: 명시된 24개 route 모두 존재. 기존 구현은 SQL 메뉴 31개와 메뉴 없는 /UI-22를 포함한 32개 route를 유지한다. 새 업무 화면이나 메뉴 이름을 합성하지 않는다.
- backend AdminController/AdminModels/AdminService → adminApi → UI-27 네 탭 연결을 확인했다. API 봉투, persistence 및 서버 제약은 변경하지 않는다.
- 기존 confirmations 추천: 대응 행별 저장/취소, 전체 CLOSED 범위 조회, 전체 코드 페이지 수집을 보존한다.

코딩 전 화면별 체크리스트
- /UI-01,/UI-03,/UI-04,/UI-05,/UI-10,/UI-11,/UI-12,/UI-13,/UI-17,/UI-21,/UI-28: 위치·연도 확인 / effective-period / 즐겨찾기·연도 전환 / 업무 입력·payload 없음 / 화면별 기간 유지 / 모바일 제한 유지.
- /UI-07,/UI-08,/UI-09,/UI-18: 위치·누계기간 확인 / effective-period / 연도·누계월 / 선택월과 누계 문구 동기화 / 업무 쓰기 없음.
- /UI-14,/UI-19,/UI-20: 월 상태 확인 / effective-period / 월 전환→getMonthClose / CLOSED 잠금·OPEN 표시 / 실패 시 선택 유지·재시도 / 모바일 제한 유지.
- /UI-16,/UI-22,/UI-23,/UI-24,/UI-25: 위치 확인 / 읽기 전용 자리 / 즐겨찾기·실제 route 탐색 / KPI·로그인·업무 입력 생성 금지.
- /UI-27 코드: 그룹별 비교 편집 / master-detail / 추가·취소·전체 저장·그룹 추가 / 기존 코드 불변, SYSTEM 이름·순서만 / CodeSaveRow 전체 배열 PUT / 필수 blur·첫 오류 focus / 성공 GET 재조회·토스트 / 물리 삭제 없음.
- /UI-27 대응: 관계 관리 / editable list+dialog / 대응 추가·행 저장/취소 / 기존 표기·대상 불변 / PATCH는 map_role_cd,rmrk_desc,use_yn만 / 활성 코드 API·주/보조 명시 선택 / 성공 재조회 / 실패 draft 유지.
- /UI-27 기준값: 적용월별 값 관리 / list+dialog+disclosure / 값 변경·이력 보기 / 이름·단위·쓰는 곳 읽기 전용 / num_val 또는 txt_val만 PUT / 범위·마지막 CLOSED 이후 월 / 성공 현재 목록·선택 이력 갱신.
- /UI-27 변경 이력: 읽기 전용 추적 / audit list / 그룹 조회·초기화·Enter·페이지 / 서버 total·기본50·20/50/100 / 저장·삭제·export 없음.
- 전체 shell: DB 메뉴 이름·아이콘·active / 2단 메뉴·접힘·모바일 메뉴 / 즐겨찾기10·최근5·탭8 / 미저장 보호·검색 / loading,error,empty / 권한은 현재 무인증 Can=true이며 가짜 역할 차단을 추가하지 않는다.

시각적 적용
- ui-design.md 확정 토큰: 청색 primary, 흰 surface, 회색 배경, 로컬 Pretendard, 56px header, 240/64px sidebar, 밑줄 탭, 테두리 패널, 36/44px controls.
- 표 셀은 별도 폼처럼 label 120px를 반복하지 않고 접근 가능한 label을 시각적으로 숨긴 인라인 편집기로 표시한다. 전체 코드 표와 작업 버튼은 폼의 전체 폭을 사용한다.
- 초기 loading/error를 성공한 빈 결과와 구분하고, skeleton·빈 결과·조회 오류를 각각 표시한다.

검증 제한
- 실제 레퍼런스 URL 직접 요청: 프록시 경유 empty reply, 프록시 우회 DNS 실패. ui-style.md 자체도 접근 실패 기록이며 시각 토큰을 제공하지 않는다. 레퍼런스 일치 또는 실제 인터랙션 확인 완료를 주장하지 않는다.
- implementation-map.json은 제공 경로에 없다. 나머지 code-context 사본은 읽기 전용으로 보존했다.

코딩 후 계약 대조와 실행 결과
- 모든 기존 route를 렌더링하는 Router 시험 통과. 명세 화면을 추가로 만들 필요가 없었고 업무 자리 범위는 보존했다.
- 공통 shell: 실제 Link 탐색, breadcrumb, 청색 active, 즐겨찾기 pressed, 검색 버튼·0건 안내, 알림 popover, 설정 panel, 접힌 메뉴 flyout을 적용했다. 모바일 링크 선택 시 메뉴를 닫고 태블릿은 rail로 시작한다.
- UI-27: shell과 중복되던 제목을 통합 표시하고 그룹의 이름·식별값·SYSTEM/건수를 계층화했다. 표 셀 label은 접근성 연결을 유지하면서 시각적으로 숨기며 코드 표/작업줄은 폼 전체 폭을 쓴다.
- 코드·대응·현재 기준값·적용 이력·변경 이력 모두 loading/error를 성공한 빈 결과와 구분한다. 기존 immutable 필드, SYSTEM 제한, 마감월, 미저장 확인, 서버 오류 입력 유지, 성공 재조회 및 허용 payload를 보존했다.
- npm run build: TypeScript 검사와 Vite production build 성공.
- npm test -- --run: vitest + jsdom 9개 파일, 228개 시험 모두 통과. 소스 문자열 비교 시험을 추가하지 않았다. 무인증 사용자 메뉴·버튼·입력, SYSTEM/BIZ 상태별 제약은 렌더링으로 검증하며, 명세에 없는 인증 역할을 생성하지 않았다.
- package-lock.json 생성: npm offline lock-only 성공, lockfileVersion=3. 새 UI 라이브러리나 의존성 버전 변경 없음.
- 실제 backend/DB를 기동한 end-to-end 저장 시험은 이번 호출에서 수행하지 않았다. 컴포넌트 시험의 API 응답은 시험 전용 stub이며 운영 데이터가 아니다.
- Chromium 설치도 시도했지만 TMPDIR의 64MiB 공유 메모리 공간 부족(ENOSPC)으로 실패했다. 실제 브라우저의 360/375/1024/1280px 시각 측정과 레퍼런스 일치 검증은 미완료다.
- 실행 환경 Node 22.21.1은 프로젝트 engines의 Node 20.x와 다르므로 npm lock 생성 시 EBADENGINE 경고가 있었다. 명세 버전은 변경하지 않았다.

변경 파일
frontend/src/app/AppFrame.tsx
frontend/src/app/AppFrame.module.css
frontend/src/app/router.tsx
frontend/src/app/global.css
frontend/src/app/tokens.css
frontend/src/components/Inputs.tsx
frontend/src/components/Inputs.module.css
frontend/src/components/UiKit.tsx
frontend/src/components/UiKit.module.css
frontend/src/pages/shared/SharedWorkspace.tsx
frontend/src/pages/shared/SharedScreens.module.css
frontend/src/pages/admin/CodesTab.tsx
frontend/src/pages/admin/ExcelMapsTab.tsx
frontend/src/pages/admin/ParamsTab.tsx
frontend/src/pages/admin/Ui27Screen.tsx
frontend/src/pages/admin/Admin.module.css
frontend/tests/Router.test.tsx
frontend/tests/UiImprovement.test.tsx
frontend/package-lock.json
frontend/UI-IMPROVEMENT.md
.aiops-code-context/outbox/dae95ebade3d49b2b96c37432572b517.json
