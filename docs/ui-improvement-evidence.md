UI improvement evidence — call 61fe263778c1499693304a9597cee2a3

Scope and sources
- Read ui-design.md (including its UI contracts, field behavior, action mapping, states and constraints), ui-style.md, data-model.md field contract, OpenAPI schema excerpts, frontend shell/features/client, backend CostController and CostService.
- The requested code-context/implementation-map.json does not exist. Read confirmations.json and output-contract.json; left those snapshots unchanged.
- Existing confirmation for mapping PATCH trigger remains provisional_pending. Kept the recommended read-only existing mapping list; no new save trigger, blur mutation, or backend change. This call adds no new human-choice questions; its outbox confirmations is [].
- Route inventory and concise before/after contract comparison: docs/ui-contract-checklist.md.

Implemented
- Shared shell: blue active hierarchy, icon breadcrumbs, favorite guidance, dashboard direct navigation without redundant child submenu, title action placement, route-active accessibility, mobile collapsed-menu restoration.
- Shared presentation: fixed product color tokens, local font, control/focus/hover transitions, reduced-motion support, designed skeleton and empty states, bordered error strip, bounded table scrolling, form label/control alignment, full-width multiline mapping memo.
- Code editing: preserve existing immutable identifiers, keep new duplicate draft identifiers editable, clear unrelated rows on a failed group switch, disable unsafe add/save, inline required-field errors.
- Parameter editing: last-closed-month loading/error/retry and disabled saving until loaded; inline API field errors. Version request sequencing prevents late responses replacing another selected key.
- History: clear stale errors on a new query and label PARAM as 기준값.
- No new UI library, no changes to backend API or persistence, no physical delete, no authentication added.

Executed verification
- frontend: npm run build — PASS (TypeScript + Vite production build).
- frontend: npm test -- --run — PASS, 5 test files, 43 tests.
- Added 28 jsdom rendered-component cases, covering the 23 specified non-business routes plus UI-27 action/route behavior, new-code identity, failed group switching, month-close recovery, version-response races. Existing tests retain SYSTEM/BIZ constraints, successful full-group PUT, error draft preservation, dirty-navigation guard, mobile restrictions, permission adapter and real input interaction.
- Component tests use isolated HTTP responses; these are not live PostgreSQL integration results. No source-text/string comparison tests were added.
- Explicit changed-source trailing-whitespace scan — PASS. npm package-lock.json generated. No commit or push.
- Runtime warnings: installed Node 22 differs from package engines Node 20; build/tests nevertheless pass. Existing React Router v7 future-flag warnings remain.

Limitations (not completed claims)
- Exact reference URL retried through the configured proxy: curl (52) Empty reply from server. Direct no-proxy: curl (6) Could not resolve host. localhost:18999: connection refused. No reference DOM, screenshot or interaction observation obtained. ui-style.md itself records unavailable observations. Applied ui-design.md's explicit product tokens, not fabricated reference measurements; visual parity is unverified.
- Browser launch initially failed because Chromium was not installed. Playwright Chromium installation was attempted but failed ENOSPC; diagnostic df showed TMPDIR on a 64MiB shared-memory mount. No rendered-browser measurements/screenshots were produced.
- Docker CLI exists but daemon connection fails. Live Compose/PostgreSQL API verification was not run. Existing backend persistence is untouched.
- Existing Excel mapping edit-confirmation UX remains a human decision recorded in the read-only confirmations snapshot; creation remains connected to the existing API.

Files changed in this call (repository-relative; excludes ignored build output/node_modules)
frontend/src/app/App.tsx
frontend/src/app/App.module.css
frontend/src/app/tokens.css
frontend/src/components/Common.tsx
frontend/src/features/common-codes/CommonCodes.tsx
frontend/src/features/common-codes/CodeEditor.tsx
frontend/src/features/common-codes/Mappings.tsx
frontend/src/features/common-codes/Parameters.tsx
frontend/src/features/common-codes/History.tsx
frontend/src/features/common-codes/codes.css
frontend/tests/ui-contract.test.tsx
frontend/package-lock.json
docs/ui-contract-checklist.md
docs/ui-improvement-evidence.md
.aiops-code-context/outbox/61fe263778c1499693304a9597cee2a3.json
