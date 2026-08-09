# CLAUDE.md — Rigger

Guidance for working in this repository. Rigger is a Docker Swarm operator that exposes
Kubernetes-like primitives (Deployment, Service, ConfigMap, Secret, HPA) with RBAC, GitOps,
and a React UI, built as a Java 21 / Spring Boot 3.3.5 Maven multi-module reactor.

## Module map

| Module | Purpose |
|---|---|
| `rigger-core` | Domain records (DeploymentSpec, ServiceSpec, ClusterSpec, RiggerIdentity, …), exceptions. No framework deps. Most complete/stable module. |
| `rigger-events` | Typed event records + `RiggerEventBus` (wraps Spring's `ApplicationEventPublisher`). |
| `rigger-manifest` | Parses/validates `rigger.io/v1` YAML manifests (`ManifestParser`, `ManifestValidator`), plus `ComposeConverter` for docker-compose input (not yet wired into apply path — see Known Gaps). |
| `rigger-schema` | JSON Schema (draft 2020-12) definitions per kind, validated via `ManifestSchemaValidator` before domain validation. Deliberately framework-free — `ManifestSchemaValidator` is registered as a Spring bean by `rigger-api`'s `ApiAutoConfiguration`, not annotated itself. |
| `rigger-swarm-adapter` | Talks to Docker Swarm via **docker-java 3.3.6** (not raw HTTP). `ServiceAdapter`/`NodeAdapter`/`ConfigAdapter`/`SecretAdapter`. Contains a legacy hand-rolled `swarm/model/*` package predating the docker-java migration — still referenced by `ReconcilePlan` in `rigger-operator`; `DockerJavaReconcilePlan` is the real replacement (cleanup candidate, see Known Gaps). |
| `rigger-provisioner` | SSH-based cluster provisioning (Apache Mina SSHD) — Docker install, `docker swarm init/join`, `ClusterOrchestrator` for `cluster up`/`sync`. |
| `rigger-security` | Auth (`UserStore`, `JwtTokenService`, `RiggerAuthenticationFilter`), RBAC (`RbacPolicyEngine`), secret encryption (`SecretEncryptor`, AES-256-GCM), audit (`AuditService`). |
| `rigger-store` | Spring Data JPA + SQLite (WAL mode), Flyway migrations in `db/migration/`. |
| `rigger-operator` | Reconciliation loop (virtual threads via `StructuredTaskScope`) running Deployment/Service/ConfigMap/Secret controllers in parallel each cycle, plus the HPA autoscaler. `SecretController` decrypts and pushes Secret values into Docker Secrets; create-only, same maturity as `ConfigMapController` (see Known Gaps). |
| `rigger-gitops` | JGit-based poll-and-apply agent (`GitOpsAgent`), bypasses HTTP/RBAC by design (trusted internal path via `ManifestApplyService`). |
| `rigger-api` | Spring MVC REST layer (`WorkloadController`, `ClusterController`, `AuthController`, `UserController`, `AuditController`). |
| `rigger-cli` | `riggerctl` — Picocli-based CLI. |
| `rigger-server` | Spring Boot fat-jar entry point (`RiggerApplication`); embeds the built `rigger-ui` as static resources. |
| `rigger-ui` | React 18 + TypeScript + Vite + Tailwind. Built separately (npm), output copied to `rigger-server/src/main/resources/static/ui/`. |

## Build & run

### Java version — important

The project **requires JDK 21** with `--enable-preview` (uses `StructuredTaskScope`, a JDK 21
preview API, in `rigger-operator`'s `ReconciliationLoop`). If your default JDK is newer (e.g. 24/25),
preview class files from a different major version will fail to load. Point `JAVA_HOME` at a JDK 21
install for every Maven invocation:

```bash
export JAVA_HOME=/path/to/jdk-21
```

The root `pom.xml`'s `maven-compiler-plugin` also sets `<parameters>true</parameters>` — required
for Spring MVC's implicit `@PathVariable`/`@RequestParam` name binding (e.g.
`@PathVariable String namespace` without an explicit `@PathVariable("namespace")`). Without it,
any endpoint relying on implicit parameter names throws at request time, not at compile time —
easy to miss until you actually call that specific endpoint.

### Full build

```bash
mvn clean verify                     # all 14 modules, compiles + tests
cd rigger-ui && npm install && npm run build   # output goes to rigger-server/.../static/ui/ automatically
```

### Run the server locally (dev)

```bash
# One-time: Swarm must exist (dev: single local node)
docker swarm init --advertise-addr <your-ip>   # or omit --advertise-addr if unambiguous

# One-time: dev TLS keystore (rigger-server/src/main/resources/dev-keystore.p12, password "rigger-dev")
keytool -genkeypair -alias rigger -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore rigger-server/src/main/resources/dev-keystore.p12 -validity 3650 \
  -storepass rigger-dev -dname "CN=localhost"

cd rigger-server
RIGGER_ATTACH_EXISTING_SWARM=true \
mvn spring-boot:run -Dspring-boot.run.jvmArguments="--enable-preview"
```

Server comes up on `https://localhost:7433`. First boot auto-generates `RIGGER_MASTER_KEY`
(logged once, ephemeral) and, if `RIGGER_ADMIN_PASSWORD` isn't set, a one-time random admin
password — read it from the startup log (`WARN ... admin / <password>`); see Security Model.
With `SPRING_PROFILES_ACTIVE=prod`, the server refuses to start instead of generating one.

```bash
curl -sk https://localhost:7433/actuator/health
curl -sk -X POST https://localhost:7433/api/v1/auth/login \
  -H "Content-Type: application/json" -d '{"username":"admin","password":"<from-log>"}'
```

Follow `QUICK-START.md` for the full CLI flow (`riggerctl init --insecure` → `login` → `apply`).

## Architecture decisions

- **Auth model: JWT + username/password, not mTLS.** The README originally described a
  "no passwords, mTLS-only" model; the actual implementation (and the one this project
  commits to going forward) is `POST /api/v1/auth/login` issuing a short-lived JWT, verified
  per-request by `RiggerAuthenticationFilter`. `server.ssl.client-auth: want` remains set so a
  client cert *can* be presented, but nothing consumes it — `RiggerIdentity.certSerial` and the
  `identities.cert_serial` column are unused placeholders. Real mTLS is out of scope (see
  Known Gaps / Out of Scope).
- **RBAC enforcement**: `RbacPolicyEngine.authorize(ctx, action, kind)` called explicitly at the
  top of each controller method — this is the one and only enforcement mechanism. An AOP-based
  `@RiggerAuthorize` alternative was attempted and **deleted**: it required `RiggerContext` to be
  a method *parameter* (controllers build it internally from the request) and a single resource
  kind fixed at compile time (`WorkloadController.apply()` handles a mixed batch of kinds per
  call). Any new controller method touching a resource MUST start with an explicit
  `rbac.authorize(...)` call — there is no compiler-enforced safety net for this, only convention.
- **Secrets**: `SecretSpec.data` values are base64 in the YAML manifest. `WorkloadController.apply()`
  encrypts each value with `SecretEncryptor` (AES-256-GCM) before persisting to
  `resources.spec_json` — the DB only ever holds ciphertext. Reads (API/CLI/UI) always show
  `{"keys":"redacted"}`, never decrypt. The one legitimate decryption point is
  `rigger-operator`'s `SecretController`, which decrypts and pushes the real value into a Docker
  Secret via `SecretAdapter` (Swarm needs the actual usable value — it has its own independent
  at-rest encryption, unrelated to Rigger's). Audit log entries for Secret applies record
  `"<redacted-secret-data>"` instead of the spec JSON, encrypted or not.
- **Database**: SQLite via Spring Data JPA + Flyway, WAL mode. Hibernate needs
  `org.hibernate.community.dialect.SQLiteDialect` (from `hibernate-community-dialects`,
  added to `rigger-server`'s pom) since SQLite has no first-party Hibernate dialect.
  JPA entities mapping to `TEXT`/`INTEGER`-typed SQLite columns (timestamps, booleans) must
  set `columnDefinition` explicitly to match — Hibernate's schema *validate* mode is strict
  about this even though SQLite itself is dynamically typed.
- **UI build integration**: `rigger-ui`'s Vite config writes directly to
  `rigger-server/src/main/resources/static/ui/` — there is no separate copy step. `rigger-ui`
  is mid-migration from JS to TS (`allowJs: true` in `tsconfig.json`); `App.tsx`/`main.tsx` are
  canonical, pages remain `.jsx` for now.

## Security model (current state)

- **Passwords**: BCrypt (`PasswordEncoder` bean in `SecurityAutoConfiguration`, injected into
  `UserStore`) — per-user random salt. Pre-existing SHA-256+static-salt hashes were never
  migrated (clean cutover; there were no real users yet) — anyone created before this change
  must be recreated.
- **Users**: persisted via JPA (`IdentityRepository`/`IdentityEntity`, backed by the
  `identities` table + a `password_hash` column added in `V3__identities_password_hash.sql`).
  Survive restarts; verified by creating a user, restarting the server, and confirming login
  still works.
- **Bootstrap admin**: with `RIGGER_ADMIN_PASSWORD` unset — fails startup under
  `SPRING_PROFILES_ACTIVE=prod`; otherwise generates a random password, logs it once
  (`WARN ... admin / <password>`), never persists it in plaintext anywhere. Set
  `RIGGER_ADMIN_PASSWORD` explicitly for any environment that matters.
- **JWT signing key**: `RIGGER_JWT_KEY` env var, validated in `JwtTokenService`'s
  `@PostConstruct`. Default/short (<32 char) keys fail startup under the `prod` profile;
  elsewhere they're padded with a warning (dev/qa convenience only).
- **Secrets at rest**: encrypted (AES-256-GCM via `SecretEncryptor`) before being persisted —
  see Architecture Decisions above for the full data flow. Verified end-to-end: applied a
  Secret, confirmed the raw SQLite row holds ciphertext (not the original base64), confirmed
  the reconciliation loop pushes the real decrypted value into a Docker Secret (mounted a test
  container and read the value back).
- **SSH provisioning**: `RiggerSshClient` uses trust-on-first-use host-key verification
  (`DefaultKnownHostsServerKeyVerifier`, backed by `~/.rigger/known_hosts`). First connection to
  a node accepts and persists its key; a later mismatch (MITM, or a node rebuilt with a new
  host key) is rejected with `ProvisioningException`. Verified against a real SSH server.
- **Docker install channel**: `DockerSpec`'s compact constructor rejects any `channel` value
  outside `stable`/`test`/`nightly` (defense in depth: also re-checked in `DockerInstaller`
  right before shell interpolation).
- **Audit log**: append-only by convention (`AuditService` only calls `.save()`), not enforced
  at the DB layer. Secret applies record `"<redacted-secret-data>"` instead of spec JSON.
- **Error responses**: uncaught exceptions (`GlobalExceptionHandler.generic()`) return a generic
  message + correlation ID to the client; the real exception (message, stack trace) is logged
  server-side tagged with that same ID. The four other handlers (403/404/422/401) already return
  intentional, safe messages and are unchanged.

## Known gaps / roadmap

Tracked here so they read as deliberate backlog, not oversights. Security-critical items
(secret encryption, password hashing, admin/JWT hardening, SSH host-key verification, shell
injection, RBAC mechanism, generic error responses) are done — see Security model above.
Remaining gaps are feature-completion and polish:

- Missing/broken endpoints referenced by `riggerctl`/README but absent in `rigger-api`:
  `cluster up`/`cluster sync` (logic exists in `ClusterOrchestrator`, just not wired to a
  controller), pods listing, streaming logs (`riggerctl logs --follow` is broken end-to-end —
  no server endpoint, and the CLI command bypasses its own authenticated HTTP client), `DELETE`
  for Service/ConfigMap/Secret (only Deployment delete exists today), a read-only GitOps status
  endpoint (`rigger-ui`'s GitOps page already calls it; `rigger-gitops` already tracks the state,
  just needs a controller).
- `rigger-operator`'s `ServiceController.reconcile()` is an intentional no-op stub — Service
  resources are persisted but never reconciled onto Swarm. `ConfigMapController` only creates,
  never updates/deletes (Swarm Configs are immutable once attached — needs a
  create-new-version-and-swap pattern, mirroring `ResourceDiffer`'s use in `DeploymentController`).
- HPA autoscaling never scales up: `MetricsSource` is a hardcoded stub returning 0. Needs a
  polling `DockerStatsMetricsSource` using docker-java's `StatsCmd`.
- `ComposeConverter` (in `rigger-manifest`) exists but nothing detects/routes docker-compose
  input to it from `ApplyCommand`/`WorkloadController.apply()` — README's compose support claim
  is currently false.
- CLI `user approve` doesn't exist (README artifact of the old mTLS/CSR-approval design); the
  real, canonical flow is `riggerctl user create`.
- rigger-ui: no login page / JWT integration yet (pages assume an already-authenticated session);
  namespace is hardcoded to `"production"` in every page instead of a real selector.
- Cleanup candidates (do last, verify build after each): legacy `swarm/model/*` classes in
  `rigger-swarm-adapter` (superseded by docker-java types), duplicate unused CLI command classes
  in `rigger-cli/command/user/` (the real ones are static inner classes in `UserCommand`), unused
  UI dependencies (`components/ui/*` shadcn-style components, `recharts`).

## Out of scope (do not re-litigate without a fresh decision)

- Real mTLS / client-certificate authentication.
- Ingress-controller-grade Service routing (path routing, TLS termination, vhosts) — Service
  reconciliation should stay to Swarm published-port mapping.
- GraalVM native-image packaging for `riggerctl`.
- Multi-instance/HA `rigger-server` (single-instance, SQLite-backed operator is the model).
- A RBAC administration UI (only the enforcement mechanism is in scope for hardening).
