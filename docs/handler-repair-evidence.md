# Existing-handler repair evidence

## Scope and preservation

The reported missing-operation candidates were checked against the existing controllers. All 22 candidates already have handlers; no duplicate endpoints, screens, tables or services were added. The template placeholder `formType` corresponds to the existing Java path variable `form`; URL semantics are unchanged.

Existing envelopes, controller/service signatures, business calculations, routes, CSS tokens, and migration history are preserved. The UI-27 common-code behavior is reused rather than reimplemented.

## Changes

- UploadController implements target-aware handler authorization. The interceptor establishes a real principal first, then invokes this policy before handler execution. Personnel requests require UI-02, partner requests UI-15, and registered upload review adapters supply their existing screen permission. Unknown operations fail closed. Existing handler checks remain enabled; this is not an anonymous or authorization bypass.
- UI-23 registration and exports explicitly use non-submit buttons. Opening registration no longer submits the search form. Downloads no longer invoke the save/reload flow or discard the selected account/draft.
- Administrator user/audit downloads propagate 401 and 403 through the same session-expired and permissions-changed events used by JSON calls. Requests retain same-origin credentials, errors retain contract metadata, and failed downloads never create a file.
- UI-27 exposes its fixed screen identity without changing its layout or styling.

## Regression targets

Backend:

- RequiredHandlerRegistrationTest registers the six actual owning controllers with Spring MVC and checks that each reported operation has exactly one matching handler. Path-variable names are normalized only for this comparison.
- UploadAuthorizationApiTest uses MockMvc, the real security filter chain, authorization service and target-aware interceptor with isolated mocked persistence/session collaborators. It covers personnel/partner validation, target-specific templates, adapter review/download separation, anonymous rejection, inquiry-only denial and CSRF denial.
- AdministrationApiTest additionally checks download denial without download permission, identical list/export filtering, unpaginated export, real XLSX approved columns and Seoul-time/actor provenance, and exclusion of credential/contact columns.

Frontend:

- AccessHistoryPanel.test.tsx renders the actual component in jsdom, checks applied search filters/page preservation during export, draft-filter non-submission, read-only controls and 401/403 propagation without file creation.
- SystemAdministration.test.tsx adds registration-without-search-submission coverage.

## Verification receipt

Executed static inspection returned:

- Candidate route census: 22 matched, no missing or duplicate candidates.
- Targeted upload policy: all 11 handlers classified; unknown operations fail closed.
- Applied baseline migrations V1-V9: byte-for-byte unchanged from HEAD.
- Targeted git diff whitespace check: exit 0.

These are static consistency checks, not compilation, behavioral test, PostgreSQL, SMTP or authenticated runtime receipts. Package managers, Maven tests, Docker builds and development servers were not run during this runner-owned code-generation phase. Status: awaiting runner verification.

## Files changed in this repair

- backend/src/main/java/com/sicost/common/security/TargetedHandlerAuthorization.java
- backend/src/main/java/com/sicost/common/security/HandlerAuthorizationInterceptor.java
- backend/src/main/java/com/sicost/uploadsops/UploadController.java
- backend/src/test/java/com/sicost/common/RequiredHandlerRegistrationTest.java
- backend/src/test/java/com/sicost/uploadsops/UploadAuthorizationApiTest.java
- backend/src/test/java/com/sicost/systemadmin/AdministrationApiTest.java
- frontend/src/pages/admin/UI27Screen.tsx
- frontend/src/pages/systemadmin/client.ts
- frontend/src/pages/systemadmin/UserManagementScreen.tsx
- frontend/src/pages/systemadmin/AccessHistoryPanel.tsx
- frontend/src/pages/systemadmin/AccessHistoryPanel.test.tsx
- frontend/src/pages/systemadmin/SystemAdministration.test.tsx
- docs/handler-repair-evidence.md
