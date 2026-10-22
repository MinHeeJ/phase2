Repair verification

IF-06 test repair revalidation (current repair call)
  Original Quality logs 02/03/04 show SCRAM authentication without a password;
  log 08 shows routes.spec.ts excluded from Vitest discovery. Existing working-tree
  fixes requiring all three DB variables and separating Vitest/Playwright were kept.
  DB URL checks now also reject whitespace-only settings. No runtime credentials,
  authentication policy, production service signatures, or OpenAPI fixtures changed.
  Executing the discovered route tests additionally exposed invalid ByRoleOptions
  (exact), an incorrect textbox name, and an incorrect search-result button name.
  Fixed queries to use actual accessible names and scope the result to the dialog.

  Executed locally using Java 17 / Maven 3.9.9 and host Node 22:
    mvn -B -Dtest=PersistenceIntegrationTest test: SUCCESS, 4 intentionally skipped.
    mvn -B -Dtest=WriteSideEffectsIntegrationTest test: SUCCESS, 5 intentionally skipped.
    mvn -B test with all DB variables unset: SUCCESS, 23 passed, 9 skipped.
    mvn -B test with unreachable URL only: SUCCESS, 23 passed, 9 skipped.
    npm run test -- --run tests/e2e/routes.spec.ts: 4 passed.
    npm run test -- --run: 60 passed across 7 files.
    npm run build: TypeScript and Vite production build passed.
    python -m unittest discover -s tests -v: 3 passed.
  Docker daemon is unavailable: build-stage container tests and actual PostgreSQL
  integration execution were not rerun in this repair. Earlier evidence below is
  historical, not a claim that those checks ran again. Quality artifact reports
  security_enabled=true but contains no per-rule scan coverage/details; no fresh
  security-scan or Quality PASS is claimed. Reference inputs remain read-only.

Scope
  Root Compose now declares backend/frontend build contexts directly. The previous
  infra/docker-compose.yml entry point includes that same canonical configuration.
  A networkless one-shot initializer generates a random PostgreSQL password in a
  named volume; db and backend mount it read-only. Spring ConfigTree supplies the
  datasource password. No default credential or authentication bypass was added.
  Existing application Dockerfile non-root users and port 8080 are unchanged.
  PostgreSQL has no published host port.
  Frontend nginx preserves /api/ when proxying to backend and uses a constant Host.
  Request logging is disabled; API tracing uses server-generated thread-local IDs
  cleared in a finally block, not servlet request attributes.
  Empty/null single-record repository results now consistently return 404.
  V2 month-close seed SQL is formatted without changing its data semantics.

Executed verification
  docker compose config --quiet: PASS for both root and infra entry points.
  python -m unittest discover -s tests -v: 3 passed. Checks include build discovery,
    startup without injected variables, silent random credential initialization,
    restart preservation, no infrastructure host ports, and guide/runtime agreement.
  Maven test/package with no DATABASE_URL: BUILD SUCCESS; 23 passed, 9 integration
    tests skipped as intended. Two regression tests added; no signatures changed.
  Maven test against a real isolated PostgreSQL 16.6 instance: BUILD SUCCESS;
    32 tests passed, 0 failures, 0 errors, 0 skipped.
  Frontend npm ci, npm run test -- --run, npm run build: PASS; 56 tests across 6
    files passed. The host has Node 22, so npm emitted the existing Node 20 engine
    warning. Docker build stage still uses Node 20 and contains tests via COPY . .;
    final nginx image still copies only dist.
  Actual packaged backend started against PostgreSQL using generated ConfigTree
    credentials. Runtime contract health and first_menu operations returned JSON
    with successful expectations. First-menu route was found in the returned tree.
    No response bodies, credential values, cookies, or tokens were recorded here.
  SQLFluff postgres lint on V2: PASS.
  Semgrep p/java + p/nginx: 67 rules, 11 scanned files, 0 findings, 0 scan errors.
  Broader Semgrep p/java + p/nginx + p/secrets: 109 rules run, 37 scanned files,
    0 findings, 0 scan errors. Default ignore patterns skipped 12 files; this is
    not asserted to be the exact original Quality Agent ruleset or full coverage.
  git diff --check: PASS.

Outstanding acceptance blockers (not a claimed Quality PASS)
  Docker compose up -d --build --wait was attempted but the environment has no
  Docker daemon/socket. Container builds, nginx runtime, container health checks,
  browser E2E, and the platform Quality Agent gate cannot be certified here.
  Local PostgreSQL/backend execution is additional evidence, not a replacement
  for that container-based gate.

  The concrete producer authentication contradiction was found in components:
  an unused bearerAuth security scheme survived despite the explicit unauthenticated
  policy, empty operation security, and x-security-policy stating there must be no
  security schemes. Removed only that inconsistent scheme from both main and test
  durable OpenAPI copies; added assertions prohibiting schemes and x-roles in this
  unauthenticated contract. No login or identity operation/role was invented.
  auth.required=false and the initial-login guide are preserved consistently.
  The read-only reference directory was not modified. A platform rerun must still
  establish whether its separate authoritative input contains the same stale scheme;
  the Quality Agent verdict is not claimed to be PASS based on local verification.
  No human policy choice is needed for this source-grounded correction; outbox has
  confirmations=[] for call 71eaefe9ce564f9b84fc05801ad24ebb.

Operational limits
  The password-volume initializer is for new generated-app test deployments. It
  does not rotate an existing initialized database's password. Existing deployments
  must supply their matching password file; preserve DB and credential volumes
  together. Do not delete production volumes. Flyway migrations already applied
  elsewhere require the normal checksum/version rollout process; no repair or
  production migration command was executed by this session.
