# UI Contract 느슨한 점검 및 소규모 보강

## 범위와 근거

- 정본: `.aiops-spec/ui-design.md`의 route inventory, Action Mapping, UI 계약, 데이터 바인딩, 상태/권한 제약.
- 참조: `.aiops-spec/contracts/openapi.yaml`, 현재 frontend 소스와 테스트, 읽기 전용 `.aiops-spec/code-context/confirmations.json`, `output-contract.json`, `spec-review-acceptance.json`.
- 요청에 명시된 `.aiops-spec/code-context/implementation-map.json`은 없었다. `.aiops-code-context`에서도 동명 파일을 찾지 못했다. 지도 버전/담당 task를 만들어 채우지 않고 실제 소스 및 계약에 근거해 진행했다.
- 명세 사본, backend API/persistence, 의존성 및 lockfile은 수정하지 않았다. ASCII 배치의 위치/간격/컬럼 순서는 변경 기준으로 삼지 않았다.
- 미확정 항목은 파이프라인 실패 조건으로 추가하지 않았다.

## 화면별 기능 체크리스트와 처리

| 대상/route | 목적·실제 region | CTA/필드/payload/성공/제약 체크 | 결과 |
| --- | --- | --- | --- |
| 공통 shell, `/` 및 inventory `/UI-*` | API 메뉴 → 제목/breadcrumb → 기간/본문; 메뉴/즐겨찾기/최근/탭 탐색 | `/`→`/UI-16`, 메뉴 API 이름 사용, 설정, Ctrl+K/Esc, 미저장 이탈, 모바일 제한, 가짜 로그인/역할 차단 금지 | 기존 구조 유지; 기존 렌더 시험 재실행 |
| YEAR: UI-01/03/04/05/10/11/12/13/17/21/28 | 위치와 연도 선택, 안내 본문 | 연도 CTA, 화면별 선택 기억; 업무 필드/쓰기 없음; mobile=false는 PC 안내 | 기존 구현 유지 |
| YEAR_CUM: UI-07/08/09/18 | 연도/누계월과 안내 본문 | 누계 문구, 선택 기억; 업무 데이터/입력 생성 금지 | 기존 구현 유지 |
| MONTH: UI-14/19/20 | 월 선택 → API 상태/잠금 안내 | 마감/작업중, 오류 재시도·값 보존; mobile=false 제한 | 기존 구현 유지 |
| NONE: UI-16/22/23/24/25 | 제목과 다음 단계 안내 | 기간/로그인/업무 입력 없음; PC 제한 및 사용자가 누르는 대시보드 링크 | 기존 구현 유지 |
| UI-27 코드 | 그룹 패널 → 전체 코드 표 → 저장/취소 | 기존 코드 불변, SYSTEM은 이름/순서만, 동적 attr 열, BIZ 사용 토글/추가, 전체 목록 PUT, 오류 draft 유지, 성공 동일 그룹 | 기존 제약 유지; 그룹 목록 재조회 시 편집 컴포넌트 유지 |
| UI-27 그룹 추가 M3 | 작업창 필수/길이/패턴 입력 | BIZ·순서는 서버 결정, 추가 확인 없음, 실패 창/입력 유지·첫 오류 포커스, 성공 목록 재조회·기존 선택 보존 | 재조회 시 기존 컴포넌트 unmount를 막아 선택 초기화 수정; 재활성화 이후 서버 오류 입력 포커스 |
| UI-27 대응 목록/M4 | 읽기 전용 대응 관계 표 → 추가 작업창 | 그룹 API/활성 코드 API, 그룹 변경 시 코드 초기화, 주/보조 명시 선택, POST 허용 필드만, 성공 창 닫기/재조회 | 그룹·코드·주/보조·메모의 서버 field 오류를 해당 입력에 연결; 코드 조회 loading/error/retry, 늦은 이전 그룹 응답 무시, 실패 후 포커스 보강 |
| UI-27 기준값/M5 | 현재값 표 → 새 값/적용월 → 하단 적용 이력 | 이름/단위/쓰는 곳 비편집, 선택 key/month 경로, num_val 또는 txt_val만 PUT, 마감월 제한, 0 값 보존, 실패 입력 유지 | 숫자 범위·필수 검증을 blur/저장 시 인라인 표시; 실패 후 활성 입력에 포커스 보강 |
| UI-27 변경 이력 | 그룹 조건 → 읽기 전용 표 → 서버 total 페이지 | 그룹 조회/초기화/Enter, 20/50/100, 변경자/상태 변환, 수정·삭제/export 없음 | 기존 구현 유지 |

현재 명세는 누구나·비인증, Can=true다. 실제로 존재하지 않는 ADMIN/USER 역할을 생성하지 않았다. 기존 권한 자리 read/create/update/delete의 노출 시험과 SYSTEM/BIZ 상태별 입력·버튼 시험, 모바일 PC 제한 시험을 그대로 실행했다.

## 수정 내용

1. `CommonCodes.tsx`: 그룹 목록 갱신 중 기존 데이터가 있으면 CodeEditor를 유지한다. 그룹 추가/재조회로 선택이 첫 그룹으로 되돌아가던 문제를 보강했다.
2. `Mappings.tsx`: 서버의 단일 `ApiError.field`를 그룹/코드/주·보조/메모 입력의 오류 문구, aria-invalid, aria-describedby로 연결한다. 코드 조회 실패 재시도는 GET만 수행한다. 조회 중/실패 시 저장을 막는다.
3. `services/api.ts`: useCodes의 loading/retry 상태를 제공하고, abort된 이전 그룹의 늦은 성공 응답을 무시한다.
4. `Common.tsx` 및 `Parameters.tsx`: 기존 native required/min/max/pattern 검증을 인라인 상태에 연결한다. 기준값 새 값에는 계약 문구 `올바른 값을 입력하세요.`를 사용한다. 서버 검증은 그대로 유지한다.
5. M3/M4/M5 서버 오류 포커스는 busy 해제 후 effect에서 처리해 disabled 상태에서 focus가 유실되지 않게 한다.

OpenAPI/현재 서버는 단일 `field`를 사용한다. `fields` 배열이나 배열 행 위치 문법을 추측해 새 API 계약을 만들지 않았다.

## 남은 gap 및 검증 한계

- 기존 대응 PATCH의 확정/취소 UX는 여전히 미정이다(OQ-UI-004/REQ-119). 인계된 추천대로 기존 목록을 읽기 전용으로 두었다. 임의 행 저장 버튼이나 blur PATCH를 추가하지 않았다. 같은 질문/선택지/추천을 origin=code, provisional_pending으로 이번 outbox에 기록했다. 사람 승인으로 표시하지 않았다.
- 원문 inventory는 24개 식별 화면이고 전체 31개 검증은 별도 항목이다. 이번 기존 route 렌더 시험은 UI-27 및 문서의 23개 자리 화면을 다룬다. 실제 DB 메뉴 31개 전체와 명칭/묶음/아이콘 일치를 이번 시험으로 인증하지 않는다.
- 검색 세부 동작, 탭 초과 경계/문구, 기간 영속 범위는 OQ-UI-003이 남아 있다. 현재 구현된 로컬 동작을 확대하거나 새 API로 대체하지 않았다.
- 기준값 창은 기존 전체 기간 month-close 조회와 native min 및 서버 검증을 유지했다. 전체 마감/9999-12와 같은 범위 끝 경계 및 운영 DB의 완전한 마감 정보는 별도 검증이 필요하다. 일부 월 조회만으로 마지막 CLOSED를 합성하지 않았다.
- 코드 행 오류의 정확한 행 위치는 현재 공개 ApiError.field 계약에 정의되지 않았다. 행 위치를 추측해 붙이지 않았으며, 모든 행/추가값의 서버 오류를 정확한 셀에 연결했다고 주장하지 않는다.
- 선택 상자/메모의 로컬 blur 검증과 모든 폼의 세부 문구까지 전면 통일한 작업은 아니다. 이번에는 명백히 빠진 서버 필드 오류 및 기준값 유효성 표시를 보강했다.
- 기존 M3/M4/M5의 코드 저장 외 성공 토스트, 코드 탭 외에서 그룹 창 취소 시 복귀 탭, 이력 외 페이지 UI 등은 이번 작은 수정 범위에서 유지했다. 관련 계약 해석/정리가 필요하며 전면 준수 완료로 선언하지 않는다.
- 실제 DB/API 연동 인수, 브라우저 픽셀/반응형 크기 측정, 모든 기간·상태 전이의 E2E는 이번에 실행하지 않았다. jsdom은 레이아웃 측정 근거가 아니다.

## 실행 검증

frontend 디렉토리에서 실행:

- 변경 전 `npm test -- --run`: 5개 파일, 43개 시험 통과.
- 최종 `npm test -- --run`: 6개 파일, 56개 시험 통과.
- 최종 `npm run build`: `tsc --noEmit` 및 Vite 5.4.14 production build 성공.
- 신규 `tests/ui-contract-repair.test.tsx`: 13개 렌더/상호작용 시험. 서버 field 오류·포커스·입력 유지, GET 재시도, 늦은 응답 무시, 그룹 선택 보존, 생성 성공 재조회, native 유효성 표시, payload 제한을 확인한다.
- 기존 시험도 비인증/권한 자리, BIZ/SYSTEM, 모바일 제한, route, 미저장 보호를 계속 검증한다.
- 시험은 React Testing Library + user-event + vitest/jsdom으로 실행한다. 격리된 HTTP 응답 fixture만 사용하며 제품 코드에 시험 데이터를 추가하지 않았다. fs/readFileSync/소스 raw import 기반 문자열 비교 시험은 없다.
- Node UNDICI experimental 및 React Router v7 future flag 경고가 출력됐으나 시험/빌드 실패는 없었다.

## 이번에 변경/생성한 파일

- frontend/src/components/Common.tsx
- frontend/src/features/common-codes/CommonCodes.tsx
- frontend/src/features/common-codes/Mappings.tsx
- frontend/src/features/common-codes/Parameters.tsx
- frontend/src/services/api.ts
- frontend/tests/ui-contract-repair.test.tsx
- frontend/UI-CONTRACT-REVIEW.md
- .aiops-code-context/outbox/1ccaa056e2414317b23e32a52df1da0a.json

빌드 파생물 frontend/dist는 재생성됐으며 gitignore 대상이다. 커밋/푸시는 하지 않았다.
