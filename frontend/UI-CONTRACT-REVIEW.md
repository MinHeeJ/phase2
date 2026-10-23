# UI Contract 느슨한 점검 결과

## 범위와 근거

- `.aiops-spec/ui-design.md`의 화면 inventory, Action Mapping, Field Behavior, 상태·검증·기간 제약을 현재 `frontend/src`와 비교했다. ASCII 위치·간격·컬럼 순서는 수정 기준으로 삼지 않았다.
- backend/API/persistence와 명세 사본은 변경하지 않았다. API 오류는 현재 계약의 단일 `field`/`message`에 매핑했다. 존재하지 않는 `fields` 배열을 만들지 않았다.
- 인계 지도 버전/SHA/task ID는 제공되지 않았다. 지정된 `.aiops-spec/code-context/implementation-map.json`은 실제로 없으며 파일명 검색에서도 발견되지 않았다. 지도 기반의 완전 추적을 주장하지 않는다.
- `confirmations.json`, `output-contract.json`, `spec-review-acceptance.json`을 읽었다. 기존 추천 결정에 따른 현재 구현을 유지하며 새 사람 선택은 만들지 않았다. 이번 outbox의 `confirmations`는 빈 배열이다. 품질 수용을 기존 결정별 사람 승인으로 해석하지 않았다.

## 화면 계약 체크리스트와 현재 대응

모든 자리 화면은 공통 shell의 API 메뉴 이름/부모, 제목, 즐겨찾기와 탐색을 사용한다. 업무 생성·수정·삭제 CTA를 추가하지 않는다. 화면 ID 목록은 UI 설계의 확인 가능한 inventory를 기준으로 아래에 묶었다. 현재 registry의 추가 원본 메뉴 주소도 삭제하거나 합성하지 않았다.

| 화면 / route | 목표·archetype·주요 동작 | 필드·payload·상태 계약 | 점검 결과 |
| --- | --- | --- | --- |
| /UI-01, /UI-03, /UI-04, /UI-05, /UI-10, /UI-11, /UI-12, /UI-13, /UI-17, /UI-21, /UI-28 | YEAR 자리; 연도 이동·화면별 기간 기억·즐겨찾기 | 업무 입력/쓰기 payload 없음; 메뉴 loading/error/empty; UI-10 모바일 PC 안내 | 기존 공통 구현 유지 |
| /UI-07, /UI-08, /UI-09, /UI-18 | YEAR_CUM 자리; 연도·누계월 변경 | 선택월과 누계 문구 연동; 업무 쓰기 없음 | 기존 구현 유지 |
| /UI-14, /UI-19, /UI-20 | MONTH 자리; 조회월 선택·이전/다음 월 | getMonthClose 재조회; CLOSED 띠/OPEN 배지; 조회 실패 시 선택 유지·재시도; UI-14/20 모바일 제한 | 빠진 이전/다음 월 CTA 보강; 연도 경계·달력 양끝 차단 포함 |
| /UI-16 | 앱 탐색·설정; 읽기 전용 대시보드 자리 | API 메뉴명·부모 사용; 즐겨찾기/최근/개인설정; 탭·미저장·검색; 업무 카드/로그인 없음 | 기존 구현 유지 |
| /UI-22, /UI-23, /UI-24, /UI-25 | NONE 자리; 제목·다음 단계 안내 | 기간/업무 입력 없음; UI-22 호환 주소; UI-23/24/25 모바일 PC 안내 | 기존 구현 유지; UI-22 정책은 기존 D4 추천 유지 |
| /UI-27 코드 | 그룹 선택→인라인 편집→그룹 전체 저장 | 기존 dtl_cd 불변; SYSTEM 이름/순서만 편집; BIZ 사용/추가값/날짜; 전체 페이지 확보 후 PUT; 실패 draft 보존·성공 재조회 | 기존 제한 유지; 서버 행 필드 오류 포커스·접근성 연결 보강 |
| /UI-27 그룹 추가 창 | 그룹 코드/이름·추가값 이름 입력→POST | 종류·순서·사용·audit 직접입력 없음; pending 차단·실패 창 유지·성공 그룹 재조회 | 기존 구현 유지 |
| /UI-27 엑셀 대응 탭/추가 창 | 목록·활성 코드 선택·추가·제한 수정 | 기존 표기/대상 불변; PATCH는 주/보조·메모·사용만; 추가 시 그룹 변경은 코드 선택 초기화; 성공 재조회 | 기존 D11 추천의 행별 저장/취소 유지; 새 trigger를 정하지 않음 |
| /UI-27 기준값 탭/값 변경 창/적용 이력 | 현재값 읽기→값·월 입력→저장→목록/펼친 이력 갱신 | 선택 key/month 경로; 종류별 값 하나만 전송; 이름/단위/쓰는 곳 비편집; 전체 마감 경계 조회 실패 시 차단 | 값 blur 검증·값 오류 포커스, 서버 month 오류 인라인/접근성 연결 보강 |
| /UI-27 변경 이력 | 그룹 조건·조회/초기화·페이지 | 읽기 전용; PARAM/기본 사용자 표시·서울 일시·서버 total | 기존 구현 유지 |

현재 인증/역할 차단은 없는 계약이다. 역할을 임의 생성하지 않고 비인증 사용자의 메뉴·버튼 노출 및 SYSTEM/BIZ 업무 제약을 렌더링 시험으로 확인했다. 모바일 제한은 인증 거절과 구분한다.

## 작은 변경

1. `PeriodBar`: 이전/다음 월 버튼, 연도 경계 이동, 0001-01/9999-12 끝 버튼 차단. 기존 선택 기억·상태 조회·실패 재시도 경로 재사용.
2. `CodeTab`: 현재 코드 region 안에서 서버가 지목한 행 필드로 포커스. 오류 문구를 입력의 `aria-describedby`와 연결. 저장 payload/편집 가능 필드는 변경하지 않음.
3. `ParamsTab`: 값 입력 blur 검증, 유효하지 않은 값 저장 시 해당 입력 focus, 서버 `field=month` 오류를 적용월 옆에 표시하고 접근성 연결. 실패 시 값·월·창 유지.
4. `AdminWorkDialog`: 요청 종료로 입력이 다시 활성화된 다음 서버 필드 포커스를 적용. pending 중 disabled 입력에 focus하려는 경계 보강.
5. `TextInput`: 기존 blur 처리와 함께 호출할 수 있는 선택적 onBlur 콜백만 추가.

## 남은 gap / 비차단 한계

- 지정된 implementation-map.json이 없어 지도 버전·해시·task 단위 완전성을 확인하지 못했다. 명세를 고치거나 가짜 지도를 만들지 않았다.
- 기존 D4/D5/D10/D11 등은 confirmations.json에서 `provisional_pending`이다. UI-22 호환 주소, 전체 달력 범위 마감 조회, 메모리 작업 상태/검색, 대응 행별 저장/취소는 기존 추천대로 유지했다. 이번 작업이 사람 승인을 대신하지 않는다.
- 코드 표의 제한 길이 입력에는 공통 TextInput과 달리 글자 수 카운터가 없다. 그룹 코드 형식의 클라이언트 blur 검증, 일부 필수 오류의 정확한 지정 문구도 추가 보강 여지가 있다. 현재 서버 검증은 제거하지 않았다.
- 대응 기존 행의 메모에는 필드 오류가 연결되지만 주/보조·사용 오류는 상위 오류 띠 중심이다. 모든 필드의 포커스·인라인 오류 완전성을 주장하지 않는다.
- 기준값의 새 값과 적용월이 동시에 잘못된 경우 공통 창의 native `:invalid` 검사와 사용자 정의 검증의 우선순위는 추가 UX 점검 여지가 있다. 새 값 하나의 범위 오류와 서버 적용월 오류는 이번 시험으로 확인했다.
- 이번 변경에 API/schema에 없는 데이터나 mutation이 필요하지 않았으며 새 API·필드를 꾸며내지 않았다. 이력 외 페이지 UI 등 기존 추천 구현은 대규모 재작성 대상으로 삼지 않았다.
- Vitest의 API 응답 대체는 컴포넌트 시험 경계에서만 사용한다. 실 DB 저장·API 통합·브라우저 픽셀/모바일 크기 측정을 이번 실행에서 검증한 것으로 주장하지 않는다.

## 실행 결과

- 변경 전 `npm test -- --run`: 13 files / 258 tests 통과.
- 신규 렌더링 회귀 시험으로 빠진 동작 실패를 확인 후 구현. 비인증 메뉴 조회의 중복 항목 선택과 네트워크 실패 시나리오는 시험 자체를 수정했다.
- `npm test -- --run tests/UiContractDrift.test.tsx`: 7 tests 통과.
- `npm run typecheck`: 통과.
- 최종 `npm test -- --run`: 14 files / 265 tests 통과.
- `npm run build`: TypeScript 검사 및 Vite 프로덕션 빌드 통과.
- 새 시험은 소스 파일 읽기/문자열 비교/raw import 없이 React를 jsdom에 렌더링하고 역할·label·focus·사용자 동작·요청 payload를 확인한다.
- React Router future flag 및 실행 환경의 UNDICI 실험 경고가 있었으나 시험/빌드 실패는 없었다.

## 이번 변경 파일

- frontend/src/pages/shared/PeriodBar.tsx
- frontend/src/pages/admin/CodeTab.tsx
- frontend/src/pages/admin/ParamsTab.tsx
- frontend/src/pages/admin/AdminDialogs.tsx
- frontend/src/shared/Inputs.tsx
- frontend/tests/UiContractDrift.test.tsx
- frontend/UI-CONTRACT-REVIEW.md
- .aiops-code-context/outbox/3c71191079a249eeac06aeb3f04ce6be.json
