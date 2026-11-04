UI-16 변경 및 검증 기록

변경 경계

GET /api/dashboard는 DashboardController.getDashboard → DashboardQueryService.getDashboard 경로다.
GET /api/dashboard/export는 DashboardExportController.exportDashboard → DashboardExportService.download → DashboardReadAdapter.read → 같은 DashboardQueryService 경로다.
엑셀 어댑터는 이미 캡처된 기준월·오늘·asOf를 전달하며 Clock을 다시 캡처하지 않는다.
두 경로는 비인증이다. UI-16.read/download와 기존 Sensitive 표시를 보존하며 로그인·역할 필터를 추가하지 않는다.
기존 UI-16 라우터와 AppShell 바인딩을 그대로 사용한다. 화면 복제나 메뉴 시드는 없다.

조회 원천 및 보존 사항

MonthlyProfitService와 BusinessPlanService: 기존 월 손익·목표·CLOSED 우선 읽기를 유지한다.
WorkforceForecastService: 기존 수급·가동률 읽기와 0.40MM 기준을 유지한다.
BillingReceivableService: 오늘 미수 원천을 유지한다.
ProjectBoardService / CostProjectService / CostAllocationService: 기존 계산기를 재사용하고 프로젝트·단계·원가 입력의 일괄 읽기를 추가한다.
AssignmentService: 해당 월 MM 초과 고유 개발자 수와 오늘 유효한 선투입 건수를 읽는다.
NextSlotService: 기존 규칙 기반 추천을 읽고 다음 배정 없는 인원과 유효 추천 인원을 반환한다.
BillingGapService / ClosingQueryService: 직전 월 점검 가능한 원천만 읽는다. 검증할 원천이 없는 경우 판정 불가다.
OpportunityService: OPEN 경량 조회, 기회별 원 단위 버림 후 합계, 앞으로 3개월 미시작 기회 최대 3건과 별도 전체 수를 제공한다.
PartSettlementService: 유효 프로젝트 파트 이력과 청구 원천을 읽고 기존 정산 이익률을 사용한다. CLOSED 매출은 기존 fin.mth_pl_dtl PART 스냅샷만 읽는다.
CashFlowService: UI-21과 대시보드가 동일 계산을 사용한다. 미래 예상 기초 잔액은 직전 예상 잔액을 잇도록 수정했다. PLAN·ACTUAL과 쓰기 동작은 별도 유지한다.
최소 운영자금은 월 잔액 기준 → 최초 잔액 기준 → 적용월 운영 기준 순으로 읽으며 결손은 null이다.
다운로드만 기존 sys.acs_hist에 1건 기록한다. 조회·재시도에는 업무 저장, 마감 점검 실행·확정, 알림 발송이 없다.

자료 완전성

기존 확정 자료에 보존되지 않은 CLOSED 가동률과 본사 정산 이익률은 실시간 재계산 또는 0으로 대체하지 않는다.
해당 값은 MISSING/PARTIAL과 null로 표시한다. 이는 승인된 안전한 결손 표현이지 전체 데이터 인수 통과를 의미하지 않는다.
프로젝트별 결손 CLOSED 원가 검증은 기존 단건 소유자 경로를 유지한다.
기존 서비스의 파트·청구 점검·추천 내부 조회 비용과 결손 원가의 단건 검증 비용을 포함한 실제 쿼리 수 및 p95는 실행 담당 환경에서 측정해야 한다.

테스트 산출물

DashboardIntegratedApiTest: 두 실제 컨트롤러, 조회 조합부, 엑셀 어댑터·공통 워크북·감사를 함께 연결한다.
비인증 조회, 4종/급함 3종 집계, 동일 DTO/XLSX 금액, Clock 단일 캡처, 감사 1건, 부분 오류 격리·민감 오류 비노출, 경보 중복 제거·주원인 동률 정렬을 다룬다.
DashboardAdditionalOwnerReadTest: 기회별 버림과 시작 기간, 추천 0건, CLOSED 파트 실시간 읽기 금지, 자금 연속성과 최소값 결손을 다룬다.
CostProjectPersistenceTest에 기존 격리 PostgreSQL 패턴으로 배치/단건 수치 동등성 검증을 추가했다.
frontend/tests/dashboard/charts-and-actions.test.tsx는 jsdom 렌더로 막대·수치 표·부족 표시, null 막대 부재, 음수 자금·표시 단위, 행 전체 키보드 이동을 다룬다.
e2e/tests/dashboard.spec.ts는 별도로 실행된 실제 앱/API를 사용한다. 홈 단일 화면, 파일, 375px, 키보드 목적지, 과거월과 현재 전망 분리를 확인한다. 응답 가로채기나 제품 더미 데이터가 없다.

실행된 검증

git diff --check: 종료 코드 0.
Tree-sitter 정적 구문 검사: Java 63개, XML 파싱 10개, TypeScript 6개, TSX 18개, 오류 없음.
기존 변경 파일도 포함한 작업 트리 정적 검사이며 Java 컴파일이나 TypeScript 타입 검사와 다르다.
MyBatis 인터페이스 7개와 record 생성자 resultMap 37개를 정적 대조: 누락/인자 개수 불일치 없음.
기존 V1~V8 마이그레이션 SHA-256 대조: 8개 모두 분석 시점 값과 동일.
파이프라인 시험의 기회별 버림 기대값은 Decimal 도구 계산으로 50000004임을 확인했다.
Maven, npm, Docker, DB, 브라우저, 서버는 요청된 코드 생성 경계에 따라 실행하지 않았다.
이 기록은 컴파일·단위시험·선택 DB·HTTP·브라우저·성능 통과 영수증이 아니다. 상태: awaiting runner verification.

이번 실행에서 생성·수정한 저장소 파일

README.md
aiops.runtime-check.json
backend/src/main/java/com/sicost/apiops/ClosingQueryService.java
backend/src/main/java/com/sicost/apiops/DashboardActionSources.java
backend/src/main/java/com/sicost/apiops/DashboardQueryService.java
backend/src/main/java/com/sicost/apiops/DashboardReadAdapter.java
backend/src/main/java/com/sicost/apiops/DashboardSections.java
backend/src/main/java/com/sicost/apiops/ProjectBoardMapper.java
backend/src/main/java/com/sicost/apiops/ProjectBoardModels.java
backend/src/main/java/com/sicost/apiops/ProjectBoardService.java
backend/src/main/java/com/sicost/apiops/ProjectCostService.java
backend/src/main/java/com/sicost/assignments/AssignmentMapper.java
backend/src/main/java/com/sicost/assignments/AssignmentService.java
backend/src/main/java/com/sicost/cashflow/CashFlowMapper.java
backend/src/main/java/com/sicost/cashflow/CashFlowModels.java
backend/src/main/java/com/sicost/cashflow/CashFlowService.java
backend/src/main/java/com/sicost/contractchanges/ProjectContractChangeMapper.java
backend/src/main/java/com/sicost/contractchanges/ProjectContractChangeService.java
backend/src/main/java/com/sicost/costallocation/CostAllocationMapper.java
backend/src/main/java/com/sicost/costallocation/CostAllocationModels.java
backend/src/main/java/com/sicost/costallocation/CostAllocationService.java
backend/src/main/java/com/sicost/costsops/RenegotiationService.java
backend/src/main/java/com/sicost/opportunities/OpportunityModels.java
backend/src/main/java/com/sicost/opportunities/OpportunityService.java
backend/src/main/java/com/sicost/parts/PartSettlementMapper.java
backend/src/main/java/com/sicost/parts/PartSettlementModels.java
backend/src/main/java/com/sicost/parts/PartSettlementService.java
backend/src/main/java/com/sicost/projects/CostProjectMapper.java
backend/src/main/java/com/sicost/projects/CostProjectModels.java
backend/src/main/java/com/sicost/projects/CostProjectService.java
backend/src/main/java/com/sicost/withdrawalsops/NextSlotModels.java
backend/src/main/java/com/sicost/withdrawalsops/NextSlotService.java
backend/src/main/resources/mappers/apiops/ProjectBoardMapper.xml
backend/src/main/resources/mappers/assignments/AssignmentMapper.xml
backend/src/main/resources/mappers/cashflow/CashFlowMapper.xml
backend/src/main/resources/mappers/contractchanges/ProjectContractChangeMapper.xml
backend/src/main/resources/mappers/costallocation/CostAllocationMapper.xml
backend/src/main/resources/mappers/parts/PartSettlementMapper.xml
backend/src/main/resources/mappers/projects/CostProjectMapper.xml
backend/src/test/java/com/sicost/apiops/DashboardAdditionalOwnerReadTest.java
backend/src/test/java/com/sicost/apiops/DashboardApiTest.java
backend/src/test/java/com/sicost/apiops/DashboardIntegratedApiTest.java
backend/src/test/java/com/sicost/apiops/DashboardOwnerReadTest.java
backend/src/test/java/com/sicost/projects/CostProjectPersistenceTest.java
frontend/src/pages/apiops/Dashboard.module.css
frontend/src/pages/dashboard/DashboardCharts.tsx
frontend/src/pages/dashboard/UI16Screen.tsx
frontend/tests/dashboard/charts-and-actions.test.tsx
e2e/tests/dashboard.spec.ts
docs/dashboard-change-verification.md

초기 작업 트리의 다른 수정·미추적 파일은 보존했다. 입력 문서와 기존 마이그레이션은 수정하지 않았다.
커밋·스테이징·패키지 설치·lockfile 생성은 수행하지 않았다.
