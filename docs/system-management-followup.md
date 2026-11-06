# 시스템 관리 누락 목록 재확인 및 연락처 응답 수명 보강

## 이번 변경의 범위

누락 후보로 전달된 53개 METHOD/path는 모두 기존 production controller에 선언되어 있었다. 중복 controller/service/table/화면은 만들지 않았다. 경로 변수 이름만 다른 `/api/uploads/templates/{formType}`와 기존 `{form}`은 같은 URI 형태로 비교한다.

UI-15도 이미 `frontend/src/app/router.tsx`의 PartnersScreen → 기존 partners/UI15Screen → PartnerDrawer → BusinessContact에 연결되어 있고, 화면 루트에 `data-screen-id="SCR-UI-15"`가 존재한다. 기존 레이아웃, CSS 토큰, 메뉴 순서, API envelope, 인가, 업무 계산 및 마이그레이션은 이번 변경에서 수정하지 않았다.

## 수정 사항

1. OpenApiHandlerContractTest의 MVC slice에 실제 AuthenticationController, UserAccountsController, RolePermissionsController, MenuManagementController, NavigationController, NotificationController, BusinessContactController를 포함했다. 해당 service는 MockBean으로 대체하므로 DB/SMTP가 필요하지 않다.
2. 누락 후보 전체를 durable fixture `backend/src/test/resources/contracts/system-management-operations.txt`에 보존했다. 새로운 등록 검증은 Spring RequestMappingHandlerMapping에서 각 METHOD/path가 정확히 하나의 handler로 등록되는지 검사한다. 단순 문자열 선언 검사를 런타임 등록 검증으로 오인하지 않는다.
3. BusinessContact의 요청을 AbortController와 현재 요청 동일성 검사로 관리한다. 목적/선택 대상 변경, 상세 unmount, 세션 만료 및 권한 변경 시 요청을 취소하고 원문을 비운다. 취소를 무시하고 늦게 성공한 응답도 버린다. 서버의 목적·역할·범위 인가는 그대로 유지한다.
4. 거래처 연락처 MockMvc 시험에 허용 원문의 정확한 세 필드 응답, 선택 대상/목적 감사, 메뉴 거절 시 조회·감사 없음, 계정 정정 목적의 업무 API 사용 거절을 추가했다.
5. Vitest/jsdom 시험은 실제 UI-15 목록 → 선택 행 상세 → 목적별 연락처 조회 → 닫기/재열기를 렌더링한다. 영업/재무 조회 역할에서 수정 버튼 숨김과 거절 상태도 확인한다. 별도 컴포넌트 시험은 권한 회수·세션 만료·목적 및 대상 변경·unmount 중 지연 응답을 다룬다. 소스 문자열 비교 시험은 추가하지 않았다.
6. 실제 AppRouter를 통한 UI-15 연결 회귀와 기존 Playwright spec의 UI-15 진입/상세 연락처 흐름을 보강했다. E2E는 선택된 DB 목록 행을 사용하며 고정 업무 ID나 인증 우회가 없다.

## 변경 경로 추적

연락처 계약: T144/T145/T146/T148/T167/T168, GET /api/partners/{id}/contact?purpose=CONTRACT_SALES 또는 BILLING_RECEIPT_PAYMENT.

서버 경로: AuthenticationFilter → CurrentUser/SecurityPrincipal → BusinessContactController.getPartnerContact(long,String) → BusinessContactService.contact(boolean,long,String) → BusinessContactMapper.partner(Map) → 기존 mst.ptnr/prj.prj/sys.user_part/sys.user_role/sys.role_menu_auth/sys.menu. 실제 별칭 phone/email/masked만 응답한다. 감사는 같은 서비스 트랜잭션에서 AuthenticationMapper.audit → sys.acs_hist에 목적/대상을 기록하며 연락처 원문은 기록하지 않는다.

인가는 기존 PermissionService의 UI-15.read와 mapper의 같은 역할 내 조회/독립 연락권한/목적/범위 조건으로 수행한다. 이번 변경은 역할 허용 범위를 넓히거나 MFA/CSRF를 우회하지 않는다. MockMvc 시험의 CurrentUser와 저장소는 독립 대역이며 실제 PostgreSQL 인가 실행 증거는 아니다.

프런트 경로: AppRouter → AppShell → 기존 UI15Screen → 선택된 ptnr_id의 PartnerDrawer → BusinessContact. 원문 요청은 명시적 클릭에서만 발생한다. 변경 내용은 연락처 요청 수명에 국한된다.

## 이번 실행에서 확인한 결과

- production controller 선언을 읽는 정적 대조: 53개 필수 연산, 누락/중복 선언 0개. 이는 Spring 기동/HTTP 성공 증거가 아니다.
- git diff --check: 성공.
- 요청에 따라 Maven, npm, Docker build, dev server, Playwright 실행은 하지 않았다. 기존 문서의 과거 실행 결과를 이번 변경의 통과 근거로 사용하지 않는다.
- 상태: awaiting runner verification.

러너 검증 대상:

- backend: OpenApiHandlerContractTest, NotificationContactApiTest 및 기존 AuthenticationApiTest/AuthenticationFilterTest/ManagementApiTest/SecurityPersistenceTest/SecurityRuntimeTest.
- frontend: npm run test -- --run src/pages/shared/BusinessContact.test.tsx src/pages/partners/UI15Screen.test.tsx src/pages/security/SecurityRoutes.test.tsx src/pages/security/SecurityManagement.test.tsx
- frontend: npm run typecheck 및 npm run build.
- E2E: 기존 frontend의 npm run test:e2e -- ../e2e/tests/security-management.spec.ts.

인증 E2E는 러너가 실제 로그인/MFA 완료 후 만든 SECURITY_E2E_STORAGE_STATE를 사용한다. 거래처 원문 시나리오는 SECURITY_E2E_SALES_STORAGE_STATE와 담당 활성 거래처가 있는 격리 영업 계정을 사용한다. 상태가 없으면 해당 시나리오는 명시적으로 skip되며 통과 증거가 아니다. 공유 seed row를 변경하거나 삭제하지 않는다.

## 이번 실행에서 변경한 파일

- backend/src/test/java/com/sicost/common/OpenApiHandlerContractTest.java
- backend/src/test/java/com/sicost/notifications/NotificationContactApiTest.java
- backend/src/test/resources/contracts/system-management-operations.txt
- frontend/src/pages/shared/BusinessContact.tsx
- frontend/src/pages/shared/BusinessContact.test.tsx
- frontend/src/pages/partners/UI15Screen.test.tsx
- frontend/src/pages/security/SecurityRoutes.test.tsx
- e2e/tests/security-management.spec.ts
- docs/system-management-followup.md

시작 시 이미 존재하던 나머지 수정/미추적 파일과 삭제는 보존했다. 커밋과 푸시는 하지 않았다. 새 계정값, 비밀번호, OTP 또는 토큰을 이 문서에 기록하지 않았다.
