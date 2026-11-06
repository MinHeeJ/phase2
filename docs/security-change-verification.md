시스템 관리 누락 연결 변경 검증 기록

구현 경계

기존 공통코드·업무 옵션·기존 내보내기 handler는 중복 생성하지 않았다.
기존 업무 계산과 V1..V11 마이그레이션 파일은 이번 작업에서 변경하지 않았다.
추가된 V12는 기존 알림의 링크 문자열을 신뢰하지 않고 범위를 검사하기 위한 정규 대상 열만 추가한다.

실제 연결

인증: AuthenticationFilter → AuthenticationController → AuthenticationService → AuthenticationMapper.xml → sys.user_info/sys.auth_tkn/sys.login_session/sys.acs_hist/sys.mail_outbox.
사용자: UserAccountsController → ManagementService → ManagementMapper.xml → 기존 sys.user_info/user_role/user_part/user_prj.
역할: RolePermissionsController → ManagementService → ManagementMapper.xml → 기존 sys.role/role_menu_auth.
메뉴: MenuManagementController/NavigationController → ManagementService/NavigationService → ManagementMapper + PermissionService → 기존 sys.menu/role_menu_auth.
알림: NotificationController → NotificationService → NotificationMapper.xml의 공통 visible predicate → sys.ntf/ntf_event_key + 현재 사용자/역할/담당 관계.
연락처: BusinessContactController → BusinessContactService → BusinessContactMapper.xml의 역할·조회·독립 연락권한·목적·범위 조건 → mst.dev/mst.ptnr + sys.acs_hist.

UI: 기존 AppRouter의 /UI-22는 업무 shell 밖에서 AuthenticationScreen을 표시한다.
/UI-23, /UI-24, /UI-25는 AuthenticationBoundary와 기존 AppShell의 실제 업무 렌더링 분기에 연결했다.
기존 DeveloperDrawer/PartnerDrawer에 BusinessContact를 추가했으며 기존 SCR-UI-02/SCR-UI-15 화면은 유지했다.
기존 UI-34와 알림 클라이언트를 재사용했다. 기존 라우터·CSS 토큰·컴포넌트 체계는 교체하지 않았다.

실행 환경과 결과

호스트에는 Maven과 프런트 의존성이 없었다. 최초 호스트 compile/typecheck는 각각 mvn/tsc not found로 실행되지 않았다.
설치나 lockfile 재생성 대신 이미 존재하는 검증 이미지의 도구와 의존성을 사용했다.
이미지에 남아 있던 앱 소스는 제거하고 현재 worktree의 src/tests/config를 읽기 전용 마운트에서 복사하여 실행했다.

1. npm run typecheck: 통과.
2. npm run build: 통과. Vite 5.4.19, 1874 modules transformed. 기존 단일 번들 크기와 혼합 import 경고는 남아 있다.
3. npm run test -- --run src/pages/security src/pages/opportunitiesops/NotificationsScreen.test.tsx src/pages/shared/AppShell.test.tsx src/app/NotificationRoutes.test.tsx: 5 files, 44 tests 통과.
4. mvn -q -Dtest=AuthenticationApiTest,AuthenticationFilterTest,ManagementApiTest,NotificationContactApiTest,AdminApiTest,SharedReadApiTest,ProductionSecurityGuardTest test: 통과. 공통코드 시험의 과거 익명 actor 기대값은 인증 fixture actor 기대값으로 전환했으며 업무값 검증은 보존했다.
5. mvn -q -Dtest=SecurityPersistenceTest test: PostgreSQL 16.9의 시험별 신규 격리 DB에서 통과. 각 DB의 Flyway V1..V12 적용, 계정/세션/일회 토큰, 목록/total/감사, 연락처 쿼리, 알림 권한 상실·복원과 원본 읽음 보존을 검증했다. 시험 DB와 임시 컨테이너·네트워크는 정리했다.
6. mvn -q -Dtest=SecurityRuntimeTest test: 실제 Spring Boot 3.3.13 기동 + PostgreSQL 16.9 + 실제 MyBatis + 전체 HTTP filter/MockMvc 경로에서 통과. MFA 전 me 401, MFA 후 관리·메뉴·알림 API 조회, CSRF 누락 403, 두 관리 Excel export, 마지막 관리자 disable 409와 DB/session rollback, logout 뒤 me 401을 확인했다. 메일은 JavaMailSender 시험 대역이므로 실제 SMTP 전달 증거는 아니다.
7. mvn -q -Dmaven.test.skip=true package: 통과. jar 안의 Spring Boot JarLauncher와 V12 리소스를 확인했다. skipTests package는 시험 실행 증거와 분리했다.
8. docker compose -f infra/docker-compose.yml config --quiet 및 git diff --check: 통과. 설정하지 않은 선택 환경 변수 경고는 발생하지만 필수 슬롯 없이 구성 검증은 성공한다.

검증 수준의 제한

전체 기존 업무 회귀, 실제 SMTP 발송/수신, 2노드 경합·세션 회수, 백업/복구 및 전체 AC-1601..1620의 운영 합격을 주장하지 않는다.
Playwright 시나리오는 e2e/tests/security-management.spec.ts에 추가했다. 이번 작업은 개발 서버를 시작하지 않았으며 브라우저 실행 증거는 없다. 관리자 브라우저 시나리오는 runner가 MFA 완료 후 격리 상태를 제공해야 한다.
자동 runtime-check 형식은 MFA/초기 비밀번호 변경 단계를 표현하지 못한다. 초기 관리자 및 관리자 확인 계정에도 이를 우회하지 않으며 단순 login→me 검사만으로 이 인증 흐름이 통과한다고 보고하지 않는다.
신규 운영 설정 및 최초 로그인 안내는 README.md와 aiops.initial-login.json에 있다. 계정 값은 이 보고서에 복사하지 않는다.
현재 입력 OpenAPI의 신규 보안 operation 전파가 미확정인 점은 유지한다. 신규 wire는 제공된 UI wire 표와 실제 기존 응답 envelope를 기준으로 구현했으며 미확정 전파를 승인 완료로 표시하지 않는다.

이번 작업에서 작성·수정한 파일

README.md
aiops.initial-login.json
aiops.runtime-check.json
infra/docker-compose.yml
backend/pom.xml
backend/src/main/java/com/sicost/common/security/ProductionSecurityGuard.java
backend/src/main/java/com/sicost/shared/SharedReadController.java
backend/src/main/java/com/sicost/authentication/AuthenticationController.java
backend/src/main/java/com/sicost/authentication/AuthenticationFilter.java
backend/src/main/java/com/sicost/authentication/AuthenticationMapper.java
backend/src/main/java/com/sicost/authentication/AuthenticationService.java
backend/src/main/java/com/sicost/authentication/SmokeAccountInitializer.java
backend/src/main/java/com/sicost/useraccounts/ManagementMapper.java
backend/src/main/java/com/sicost/useraccounts/ManagementService.java
backend/src/main/java/com/sicost/useraccounts/UserAccountsController.java
backend/src/main/java/com/sicost/rolepermissions/RolePermissionsController.java
backend/src/main/java/com/sicost/menumanagement/MenuManagementController.java
backend/src/main/java/com/sicost/menumanagement/NavigationController.java
backend/src/main/java/com/sicost/menumanagement/NavigationService.java
backend/src/main/java/com/sicost/notifications/NotificationController.java
backend/src/main/java/com/sicost/notifications/NotificationMapper.java
backend/src/main/java/com/sicost/notifications/NotificationService.java
backend/src/main/java/com/sicost/businesscontacts/BusinessContactController.java
backend/src/main/java/com/sicost/businesscontacts/BusinessContactMapper.java
backend/src/main/java/com/sicost/businesscontacts/BusinessContactService.java
backend/src/main/resources/db/migration/V12__notification_scope_targets.sql
backend/src/main/resources/mappers/authentication/AuthenticationMapper.xml
backend/src/main/resources/mappers/useraccounts/ManagementMapper.xml
backend/src/main/resources/mappers/notifications/NotificationMapper.xml
backend/src/main/resources/mappers/businesscontacts/BusinessContactMapper.xml
backend/src/test/java/com/sicost/authentication/AuthenticationApiTest.java
backend/src/test/java/com/sicost/authentication/AuthenticationFilterTest.java
backend/src/test/java/com/sicost/authentication/SecurityPersistenceTest.java
backend/src/test/java/com/sicost/authentication/SecurityRuntimeTest.java
backend/src/test/java/com/sicost/useraccounts/ManagementApiTest.java
backend/src/test/java/com/sicost/notifications/NotificationContactApiTest.java
backend/src/test/java/com/sicost/admin/AdminApiTest.java
backend/src/test/java/com/sicost/shared/SharedReadApiTest.java
backend/src/test/java/com/sicost/common/OpenApiHandlerContractTest.java
frontend/nginx.conf
frontend/tests/setup.ts
frontend/src/shared/api.ts
frontend/src/shared/types.ts
frontend/src/app/router.tsx
frontend/src/app/router.test.tsx
frontend/src/app/NotificationRoutes.test.tsx
frontend/src/pages/shared/AppShell.tsx
frontend/src/pages/shared/BusinessContact.tsx
frontend/src/pages/developers/DeveloperDrawer.tsx
frontend/src/pages/partners/PartnerDrawer.tsx
frontend/src/pages/security/client.ts
frontend/src/pages/security/Security.module.css
frontend/src/pages/security/AuthenticationScreen.tsx
frontend/src/pages/security/AuthenticationBoundary.tsx
frontend/src/pages/security/UserAccountsScreen.tsx
frontend/src/pages/security/RolePermissionsScreen.tsx
frontend/src/pages/security/MenuManagementScreen.tsx
frontend/src/pages/security/SecurityManagement.test.tsx
frontend/src/pages/security/SecurityRoutes.test.tsx
e2e/tests/security-management.spec.ts
docs/security-change-verification.md

시작 시 이미 존재했던 그 밖의 수정·미추적 파일과 삭제는 보존했다. 커밋·푸시는 수행하지 않았다.
