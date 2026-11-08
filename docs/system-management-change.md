시스템 관리 변경 구현 기록

범위와 보존

기계적 누락 목록을 실제 Controller 및 Router와 대조했다. 기존 공통코드, 개발자 상태/옵션/다운로드,
프로젝트 옵션/다운로드, 업로드 검증/양식, 공통 메뉴/월마감/코드 조회 handler는 재생성하지 않았다.
기존 계산과 V1~V10 SQL 파일을 수정하지 않았다. 기존 Router/AppShell 및 CSS 변수/Controls를 재사용했다.
UI-06 독립 메뉴 또는 알림 생성 REST endpoint는 추가하지 않았다.

추가된 수직 경로

1. AdministrationController → AdministrationService → AdministrationMapper/XML → 기존 sys 테이블.
   사용자 조회/등록/부분 수정/역할/담당/중지/해제/RESET/감사/엑셀, 역할 조회/생성/수정/복사/7권한,
   메뉴 조회/추가/부분 수정/전체 정렬과 선택 업무 메일 채널을 연결했다.
   admin_yn과 해당 화면 action은 AuthorizationService가 함께 검사한다.
   마지막 관리자 감소는 sys.role 관리자 anchor 행 잠금과 같은 트랜잭션의 재조회로 보호한다.
   역할/메뉴 권한은 각 요청마다 DB에서 다시 조회한다. 계정 역할/담당/상태 변경은 대상 세션을 폐기한다.
   권한 및 메뉴 변경 저장은 PERM_CHANGE를 함께 남긴다. 감사 내려받기는 현재 필터와 출처를 사용한다.

2. ContactController → ContactService → BusinessContactPolicy/ContactMapper → mst.dev/mst.ptnr와 담당 연결.
   원문 projection 전 역할+범위+업무 목적을 검사하고 CONTACT_VIEW 감사 실패 시 응답하지 않는다.
   일반 개발자/거래처 JSON의 연락처는 ContactResponseProjection으로 기본 마스킹한다.
   UI-02/15의 기존 Drawer에만 원문 보기 영역을 추가했다. 원문 닫기/선택 변경/권한 상실은 원문을 지운다.

3. AuthenticationController/Service 및 NavigationService는 기존 SessionService/AuthenticationProof를 사용한다.
   인증 proof와 사전 MFA context는 분리해 해시로 저장하며 V11만 추가했다.
   OTP/RESET 원문은 메모리에서만 SMTP에 전달하며 hash 발급 커밋 후 발송하고 SMTP 성공 뒤에만 완료한다.
   관리자 초기화와 확인 계정 초기화는 별개이며 기본 자격증명을 코드에 내장하지 않았다.
   로그인 화면은 AppShell 밖에 있으며 서버 쿠키/CSRF, MFA, 비밀번호 변경/재설정을 연결했다.
   runtime-check는 실제 경영진 역할로 인증 후 프로젝트 목록을 읽는다. 임의 R09 역할을 만들지 않았다.

4. NotificationController/Service/Mapper는 기존 UI-34 API client에 연결된다.
   list/count/read/read-all은 수신자와 현재 대상 권한의 같은 SQL 조건을 사용한다.
   GET은 읽음 상태를 바꾸지 않는다. 타인 ID는 404, 본인 권한 상실 ID는 403이다.
   기존 프런트 계약대로 알림 page는 0부터, 관리자 목록 page는 1부터다.

화면 계약

UI-23: 이름/아이디/파트/역할/상태 검색, 선택 사용자 우측 상세, 등록/기본정보/역할/담당 별도 저장,
        중지·잠금해제·RESET 영향 확인, 접근 기록 탭과 검색 조건 엑셀.
UI-24: 역할 목록·속성·특권 제거 복사, 7권한 편집, 조회 N 조합 거절, 변경 사유 및 전후 비교 저장.
UI-25: 실제 메뉴 트리와 속성, screenId 불변, 순서 미리보기/취소/저장, 비활성 영향 확인 및 복구 보호.
UI-02/15: 기존 화면과 상세를 재사용하는 독립 연락처 열람 경로. 별도 CRUD 화면을 만들지 않았다.
관리 화면은 375px에서 세로로 배치되고 큰 표는 내부 스크롤을 사용한다.

계약 해석

제공된 설계의 OpenAPI 전파 보류를 기존 endpoint 부재로 오인하지 않았다.
기존 업무 계약은 그대로 보존하며 추가 wire는 packaged contracts/security-openapi.yaml에 기록했다.
추가 wire 필드 및 선택 메일 채널의 기존 cmn.oprt_std 매핑은 제공된 분석의 D3/D16/D24 선택을 따른다.
Swagger UI 의존성은 추가하지 않았다. 추가 계약은 정본의 승인 상태를 소급 변경하는 문서가 아니다.

검증 상태

실행 소유권: runner. 이번 codegen에서는 Maven/npm/브라우저/Docker/SMTP/실DB를 실행하지 않았다.
정적 확인: 새 MyBatis XML 파싱 및 Java interface statement 일치, JSON 파싱, git diff --check.
정적 확인은 컴파일·MockMvc·PostgreSQL materialization·SMTP 수신·실사용 UI 통과 증거가 아니다.
JUnit/MockMvc, PostgreSQL 전용 격리 DB 시험, Vitest/jsdom 렌더링 시험, Playwright spec을 작성했다.
상태: awaiting runner verification. 운영 배포 또는 AC-1601~1620 전체 통과를 주장하지 않는다.

집중 시험 대상

backend/src/test/java/com/sicost/systemadmin/AdministrationApiTest.java
backend/src/test/java/com/sicost/systemadmin/AdministrationPersistenceTest.java
backend/src/test/java/com/sicost/contacts/ContactApiTest.java
backend/src/test/java/com/sicost/authentication/AuthenticationApiTest.java
backend/src/test/java/com/sicost/notifications/NotificationApiTest.java
frontend/src/pages/systemadmin/SystemAdministration.test.tsx
frontend/src/pages/contacts/BusinessContactPanel.test.tsx
e2e/tests/system-administration.spec.ts
e2e/tests/business-contacts.spec.ts

PostgreSQL 시험은 TEST_DATABASE_URL/TEST_DATABASE_USER/TEST_DATABASE_PASSWORD가 있는 전용 환경에서
각 시험마다 새 DB를 만들고 그 DB만 삭제한다. 공유 seed 사용자/역할을 수정하는 시험이 아니다.
Playwright 관리 시험은 MFA 및 최초 비밀번호 변경을 완료한 SECURITY_ADMIN_STORAGE_STATE,
연락처 시험은 담당 자료가 준비된 SECURITY_SALES_STORAGE_STATE를 사용한다. 세션 파일은 커밋하지 않는다.
자격증명이나 OTP를 이 기록에 복사하지 않는다. 최초 로그인 안내는 aiops.initial-login.json과 README.md다.
