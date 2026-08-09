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
| `rigger-operator` | Reconciliation loop (virtual threads via `StructuredTaskScope`), HPA autoscaler. |
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
(logged once, ephemeral) and bootstraps an `admin`/`admin` user (dev only — see Security Model).

```bash
curl -sk https://localhost:7433/actuator/health
curl -sk -X POST https://localhost:7433/api/v1/auth/login \
  -H "Content-Type: application/json" -d '{"username":"admin","password":"admin"}'
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
  top of each controller method. A declarative `@RiggerAuthorize` + `RbacAspect` AOP mechanism
  exists in `rigger-security` but is currently unused dead code — a future pass should either
  wire it up as the single enforcement mechanism (recommended, before adding more endpoints) or
  remove it.
- **Secrets**: `SecretSpec.data` values are base64 in the YAML manifest. `SecretEncryptor`
  (AES-256-GCM, in `rigger-security`) is fully implemented but **not yet wired** into the
  apply/read path — `WorkloadController.apply()` currently persists Secret specs unencrypted
  into `resources.spec_json`. This is the top security gap to close next (see Known Gaps).
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

- **Passwords**: hashed with a single hardcoded static salt + SHA-256 (`UserStore`) — **known
  weak**, scheduled for a BCrypt replacement. Do not treat current hashes as secure.
- **Users**: in-memory only (`ConcurrentHashMap` in `UserStore`), despite an `identities` table
  existing in the Flyway schema — all non-bootstrap users are lost on restart. Needs a JPA-backed
  `IdentityRepository` (see Known Gaps).
- **Bootstrap admin**: defaults to `admin`/`admin` with just a warning log. Override via
  `RIGGER_ADMIN_PASSWORD`. Do not run with the default in anything but local dev.
- **JWT signing key**: `RIGGER_JWT_KEY` env var; falls back to an insecure default and silently
  zero-pads short keys rather than rejecting them. Always set an explicit ≥32-byte key outside dev.
- **Secrets at rest**: **not actually encrypted yet** despite `SecretEncryptor` existing — see
  Architecture Decisions above. Treat Secret resources as plaintext-equivalent (base64) until
  this is wired up.
- **SSH provisioning**: `RiggerSshClient` accepts any host key unconditionally (no verification) —
  MITM risk during `cluster up`/`sync`. Needs trust-on-first-use host-key pinning.
- **Docker install channel**: `DockerInstaller` interpolates `DockerSpec.channel()` directly into
  a shell command run over SSH — needs allowlist validation before use.
- **Audit log**: append-only by convention (`AuditService` only calls `.save()`), not enforced at
  the DB layer. Currently passes Secret spec JSON as-is into `afterState`/`beforeState` —
  redaction needs to land alongside the secret-encryption fix.

## Known gaps / roadmap

Tracked here so they read as deliberate backlog, not oversights:

- Wire `SecretEncryptor`/`SecretAdapter` into the Secret apply/read/Swarm-push path; redact
  Secret data before writing to the audit log.
- Replace `UserStore`'s SHA-256+static-salt hashing with BCrypt; back it with a JPA
  `IdentityRepository` instead of an in-memory map.
- Fail-fast (outside dev profile) on default/short JWT signing key and default admin password.
- SSH host-key verification (trust-on-first-use) in `rigger-provisioner`.
- Allowlist `DockerSpec.channel()` before shell interpolation in `DockerInstaller`.
- Decide the fate of `@RiggerAuthorize`/`RbacAspect` (wire up or delete) before adding more
  `rigger-api` controllers.
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
