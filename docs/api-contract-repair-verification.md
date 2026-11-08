# API contract repair verification

## Changes

- Added explicit MockMvc requests for previously uncovered authentication and administration operations, with response-body assertions, validation/not-found checks, mapper mutations, audit calls and session revocation checks.
- Exposed literal paths for recommendation options/history/criteria/career-card, business contacts, notification read, adjustment cancellation and opportunity rejection requests.
- Added upload validation boundary tests with literal POST paths for business plan, prior profit, finance entries and dispatch attendance. These verify error envelopes and that binding failures do not invoke business operations.
- Upload contract assertions read `ClassPathResource("contracts/openapi.yaml")`. Dispatch attendance upload remains an additional controller test: that operation is not present in this fixture, so no fixture obligation was invented.
- Updated affected legacy MVC slices to import the real security chain and supply mocked session/authorization dependencies. Their business assertions run as an authenticated user; production authorization was not disabled or modified. Recommendation ownership tests now distinguish authenticated users rather than treating a new servlet session as a different owner.
- Made mapper mocks return null for missing administration/notification rows, matching the persistence adapter instead of Mockito's default empty Map.
- Replaced the generic cost download path interpolation with explicit existing endpoint branches. No backend endpoint was added.

## Executed verification

Java/Maven ran in an isolated copy of the current backend using the available Maven/JDK build image. Frontend verification ran in a separate clean copy using the existing dependency installation; no dependencies or lockfiles were changed.

Backend command:

    mvn -B -Dtest=AuthenticationApiTest,AdministrationApiTest,ContactApiTest,NotificationApiTest,OpportunityLifecycleApiTest,UploadValidationContractTest,RecommendationOptionsApiTest,StaffRecommendationApiTest,AdjustmentApiTest test

Result: 254 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS.

Frontend commands:

    npm run typecheck
    npm run test -- --run src/pages/costsops/CostWorkbook.test.tsx
    npm run build

Results: typecheck passed; 51 tests passed in one test file; production build passed. React Router future-flag and Vite chunk-size/mixed-import warnings remain.

`git diff --check` passed.

A source-only comparison against the submitted missing-operation list found literal request calls for all 33 reported operations, with no remaining unmatched operation. This comparison is not the original strict validator and does not establish vendor obligation coverage.

## Verification limits

The original runner-owned strict static gate was not re-executed. The complete vendor-obligation warning set is not certified as resolved. This run executed MVC/service tests with mocked persistence dependencies, not a live database acceptance test or the entire repository test suite. Existing runtime-contract files, initial-login artifacts, migrations and production security behavior were not changed by this repair.

## Files changed by this repair

- backend/src/test/java/com/sicost/adjustments/AdjustmentApiTest.java
- backend/src/test/java/com/sicost/aiops/RecommendationOptionsApiTest.java
- backend/src/test/java/com/sicost/authentication/AuthenticationApiTest.java
- backend/src/test/java/com/sicost/common/UploadValidationContractTest.java
- backend/src/test/java/com/sicost/contacts/ContactApiTest.java
- backend/src/test/java/com/sicost/notifications/NotificationApiTest.java
- backend/src/test/java/com/sicost/opportunitiesops/OpportunityLifecycleApiTest.java
- backend/src/test/java/com/sicost/staffrecommendations/StaffRecommendationApiTest.java
- backend/src/test/java/com/sicost/support/AuthenticatedMvc.java
- backend/src/test/java/com/sicost/systemadmin/AdministrationApiTest.java
- frontend/src/pages/costsops/costWorkbook.ts
- docs/api-contract-repair-verification.md
