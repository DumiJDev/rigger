# CLAUDE.md — Rigger

Guidance for working in this repository. Rigger is a Docker Swarm operator that exposes
Kubernetes-like primitives (Deployment, Service, ConfigMap, Secret, HPA) with RBAC, GitOps,
and an Angular console, built as a Java 21+ / Spring Boot 4.1 Maven multi-module reactor.

## Module map

| Module | Purpose |
|---|---|
| `rigger-core` | Domain records (DeploymentSpec, ServiceSpec, ClusterSpec, RiggerIdentity, …), exceptions. No framework deps. Most complete/stable module. |
| `rigger-events` | Typed event records + `RiggerEventBus` (wraps Spring's `ApplicationEventPublisher`). |
| `rigger-manifest` | Parses/validates `rigger.io/v1` YAML manifests (`ManifestParser`, `ManifestValidator`), plus `ComposeConverter`, which `WorkloadController.apply()` routes docker-compose input through. |
| `rigger-schema` | JSON Schema (draft 2020-12) definitions per kind, validated via `ManifestSchemaValidator` before domain validation. Deliberately framework-free — `ManifestSchemaValidator` is registered as a Spring bean by `rigger-api`'s `ApiAutoConfiguration`, not annotated itself. |
| `rigger-swarm-adapter` | Talks to Docker Swarm via **docker-java 3.7.1** (not raw HTTP). `ServiceAdapter`/`NodeAdapter`/`ConfigAdapter`/`SecretAdapter`. Note that docker-java has its own `SwarmNode`/`SwarmNodeSpec` types — the adapters use those, not hand-rolled equivalents. |
| `rigger-provisioner` | SSH-based cluster provisioning (Apache Mina SSHD) — Docker install, `docker swarm init/join`, `ClusterOrchestrator` for `cluster up`/`sync`. |
| `rigger-security` | Auth (`UserStore`, `JwtTokenService`, `RiggerAuthenticationFilter`), RBAC (`RbacPolicyEngine`), secret encryption (`SecretEncryptor`, AES-256-GCM), audit (`AuditService`). |
| `rigger-store` | Spring Data JPA + SQLite (WAL mode), Flyway migrations in `db/migration/`. |
| `rigger-operator` | Reconciliation loop (virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`) running Deployment/Service/ConfigMap/Secret controllers in parallel each cycle, plus the HPA autoscaler. `SecretController` decrypts and pushes Secret values into Docker Secrets; create-only, same maturity as `ConfigMapController` (see Known Gaps). |
| `rigger-gitops` | JGit-based poll-and-apply agent (`GitOpsAgent`), bypasses HTTP/RBAC by design (trusted internal path via `ManifestApplyService`). |
| `rigger-api` | Spring MVC REST layer (`WorkloadController`, `ClusterController`, `AuthController`, `UserController`, `AuditController`). |
| `rigger-cli` | `riggerctl` — Picocli-based CLI. |
| `rigger-server` | Spring Boot fat-jar entry point (`RiggerApplication`); embeds the built console as static resources. |
| `rigger-console` | Angular 22 + Tailwind 4 + Transloco (pt/en) + dark mode. Built into `rigger-console/dist/` and copied into the server jar by Maven — see Build & run. `ng serve` for UI work. See the `angular` skill. |

## Build & run

### Java version

Builds on **any JDK 21+** — no `--enable-preview`, no pinned `JAVA_HOME`. `ReconciliationLoop`
used to run its controllers via `StructuredTaskScope` (a JDK 21 *preview* API), which forced
`--enable-preview` and broke outright whenever the machine's default JDK moved on (preview class
files are tied to one specific major version). It now uses
`Executors.newVirtualThreadPerTaskExecutor()` + `invokeAll` — same virtual-threads-in-parallel
behaviour, no preview surface. Don't reintroduce preview APIs without a deliberate decision.

The root `pom.xml`'s `maven-compiler-plugin` also sets `<parameters>true</parameters>` — required
for Spring MVC's implicit `@PathVariable`/`@RequestParam` name binding (e.g.
`@PathVariable String namespace` without an explicit `@PathVariable("namespace")`). Without it,
any endpoint relying on implicit parameter names throws at request time, not at compile time —
easy to miss until you actually call that specific endpoint.

### Full build

```bash
mvn clean verify                     # all 14 modules + builds the console into the jar
mvn clean verify -Dui.skip=true      # backend only — skips npm entirely
```

### How the console gets into the jar

`rigger-server`'s pom runs the Angular build (frontend-maven-plugin: `install-node-and-npm`,
`npm ci`, `npm run build` — all at `generate-resources`) and declares `rigger-console/dist` as a
**resource root** with `targetPath=static/ui`, so `maven-resources-plugin` copies it into
`target/classes` at `process-resources`. `UiResourceConfig` then serves it from
`classpath:/static/ui/`. Nothing is generated inside `src/`.

Details that are load-bearing, and were each found the hard way:

- **`-Dui.skip=true` for backend iteration**, especially with `spring-boot:run`. That goal forks the
  lifecycle up to `test-compile`, which *includes* `generate-resources` — so without the flag every
  restart re-runs `npm ci` (minutes on a Windows-mounted path). With the flag and no `clean`, the
  UI already in `target/classes` keeps being served. Working on the UI itself? Use `ng serve`, which
  proxies `/api` to the running server via `proxy.conf.json`.
- **The copy is a `<resources>` entry, not a `copy-resources` execution.** Only `<resources>` is
  honoured by IDE incremental builds; with an execution, `mvn clean` + Run in the IDE gives a server
  with no UI. Declaring the block also means `src/main/resources` must be re-declared explicitly.
- **Resource filtering must stay off.** Maven's default `@...@` delimiters would mangle Angular CSS
  (`@media`, `@layer`) and minified JS (`${...}`). The non-filtered-extension defaults cover
  `ico`/`png` but not `js`/`css`/`json`/`html`.
- **The Node toolchain installs to `rigger-console/node/`, not under `target/`.** Under `target/`,
  `mvn clean` fails outright — deleting Node's deeply nested npm tree on a Windows mount reports
  "Directory not empty" and needs several passes. Consequence to remember: that directory is
  platform-specific, so delete it if you ever build the same checkout from both WSL and Windows.
- **`node.version` needs its `v` prefix** (`v24.15.0`); the installer rejects it otherwise.

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
mvn spring-boot:run
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

## CI

`.github/workflows/ci.yml`, three jobs:

- **backend** — `mvn clean verify -Dui.skip=true` on JDK 21 *and* 25. The matrix is the point: this
  project dropped `StructuredTaskScope`/`--enable-preview` precisely because preview class files bind
  to one JDK major version, and a regression there would only show on the other JDK.
- **package** — full `mvn clean package`, then asserts the jar actually contains
  `static/ui/index.html` and the nested `i18n/*.json`, and that the built console references no
  remote font/CDN. Runs the console's vitest suite. This job exists because the UI build used to be
  a manual step: a fresh clone produced a UI-less jar and nothing noticed.
- **integration** — `docker swarm init`, server started **from the fat jar**, then: UI served
  correctly (including `i18n/pt.json` coming back as JSON, not the SPA shell), a `riggerctl` flow
  (dry run changes nothing → apply → pods → logs are raw lines, not SSE `data:` frames → delete),
  a convergence check that the Swarm service's version index stops climbing, and the browser
  walkthrough in `e2e/console.mjs`.

Each assertion in that list corresponds to a defect that actually shipped and was invisible to
compilation and unit tests. Keep it that way: when a runtime bug is found, add the assertion that
would have caught it.

The browser harness lives in `e2e/` with its own `package.json`, deliberately **not** in
`rigger-console` — `npm ci` there runs during every `mvn package`, and Playwright has no business
slowing that down.

What CI does **not** cover: multi-node clusters (Swarm is single-node on the runner), SSH
provisioning (`cluster up`/`sync` are never exercised), and HPA scaling under load.

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
- **Spring Boot 4 / Jackson**: on Boot 4, `spring-boot-starter-jackson` still ships **Jackson 2**
  (`com.fasterxml.jackson`, 2.21.x) — that's what Spring MVC serialises with, so the `@JsonProperty`
  annotations on the domain records stay as-is. Jackson 3 (`tools.jackson`, 3.x) is also on the
  classpath, pulled in transitively by Flyway 13 for its own internal use; the two coexist because
  they occupy different package namespaces. Don't "modernise" the domain records to `tools.jackson`
  imports — that would silently detach them from the converter Spring actually uses.
- **Flyway needs the Boot starter, not just `flyway-core`**: Boot 4 moved Flyway auto-configuration
  out of `spring-boot-autoconfigure` into a dedicated module, so `rigger-server` depends on
  `spring-boot-starter-flyway`. With bare `flyway-core` the migrations silently never run and
  startup then dies on Hibernate schema validation (`missing table [audit_log]`) against an empty
  database — a confusing failure that points at JPA rather than the real cause.
- **Database**: SQLite via Spring Data JPA + Flyway, WAL mode. Hibernate needs
  `org.hibernate.community.dialect.SQLiteDialect` (from `hibernate-community-dialects`,
  added to `rigger-server`'s pom) since SQLite has no first-party Hibernate dialect.
  JPA entities mapping to `TEXT`/`INTEGER`-typed SQLite columns (timestamps, booleans) must
  set `columnDefinition` explicitly to match — Hibernate's schema *validate* mode is strict
  about this even though SQLite itself is dynamically typed.
- **Console**: Angular 22 (standalone + signals), served at `/ui/` from the same jar and origin as
  the API — which is why no CORS configuration exists anywhere and shouldn't be added. `UiController`
  forwards route-shaped paths to `index.html` for deep links, but deliberately excludes any segment
  containing a dot: a blanket `/ui/**` forward also matches `index.html` and every hashed asset, so
  the target re-matches the mapping and recurses until the request dies with a StackOverflowError.
  Auth is the same JWT the CLI uses, held in `localStorage`; there is no refresh endpoint, so a 401
  means re-login rather than a silent renewal. The console reads `GET /auth/permissions` to decide
  which actions to offer instead of hard-coding a copy of the RBAC table.

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
  server-side tagged with that same ID. The other handlers return intentional, safe messages.
  Three client-fault cases used to fall through to that generic 500 — a request that matched no
  mapping (`NoResourceFoundException`), an unparseable body
  (`HttpMessageNotReadableException`), and a bad query parameter (`InvalidRequestException`, e.g. an
  unknown metric name) — so a caller's typo read as a server fault and logged a stack trace. They
  now map to 404/400/400. `InvalidRequestException` carries a message we author, so unlike an
  uncaught exception it is safe to return verbatim, and it is what lets a caller tell a typo from a
  genuinely empty result.

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
  endpoint (the console's GitOps page calls it; `rigger-gitops` already tracks the state,
  just needs a controller).
- `rigger-operator`'s `ServiceController.reconcile()` (MVP, Fase 2.7): resolves the target
  Deployment by selector match and republishes `LoadBalancer` ports via `EndpointSpec`/
  `PortConfig`; `ClusterIP` stays a no-op since Swarm's overlay DNS already covers it. Full
  ingress-controller-grade routing remains out of scope.
- `ConfigMapController` (Fase 2.8) now creates a new, uniquely-named Docker Config version on
  content change instead of updating in place (Configs are immutable once created). Referencing
  Deployments pick up the new version on their own next reconciliation cycle — `ServiceAdapter`
  resolves `configMapRefs` into `ContainerSpecConfig`s and folds their resolved IDs into the
  Deployment's `spec-hash` label, so a ConfigMap-only content change is enough to trigger a
  Deployment update without the Deployment spec itself changing. Orphaned versions (superseded,
  or belonging to a deleted ConfigMap) are removed once no Swarm service still references them —
  confirmed by scanning every managed service's `ContainerSpec.configs`. Note: docker-java
  3.7.1's `ConfigSpec` doesn't deserialise labels back on list/find responses, so cleanup
  recovers namespace/name by parsing the Config's own name (`rigger__{ns}__{name}__{hash}`,
  `__`-delimited since Rigger names never contain `__` — see `ConfigAdapter.parseFamilyKey`)
  rather than reading labels back.
- HPA autoscaling (Fase 2.9): `DockerStatsMetricsSource` polls per-container CPU via
  docker-java's `StatsCmd` (non-streaming, one call per running task) and averages across a
  Deployment's tasks — no Prometheus dependency. Cost scales with task count per HPA cycle
  (default 30s); clusters with many tasks per Deployment will feel this as added latency,
  not a correctness issue.
- **Metric history** is sampled server-side by `MetricsSampler` (rigger-operator) into
  `metric_samples` (`V6`), every 30s by default, and read back via
  `GET /api/v1/metrics/series?metric=&namespace=&name=&minutes=`. Server-side rather than
  per-browser so history survives a reload and every operator sees the same picture. Metric names
  are enumerated in `MetricNames` and an unknown one is a 400 — an empty array would be
  indistinguishable from "no data yet". Cluster-wide series use `"cluster"` for both namespace and
  name so every series has a full triple and one index serves every read.
  - `MetricsCollector` is the single place current values are computed. The REST endpoints and the
    sampler both use it; when they each computed the totals themselves, the number and the chart
    above it disagreed within a day.
  - `MetricsSampler.prune()` runs on its own hourly schedule (not inside the 30s sample, which
    would issue ~2900 no-op deletes a day) and trims `metric_samples` past 24h **and `events` past
    14 days** — `EventRepository.deleteOlderThan` existed unscheduled since `events` was added, so
    that table had grown without bound.
  - Volume is the product of two knobs and nothing warns you: 9 cluster series + 3 per Deployment
    every 30s is ~200k rows/day at 20 Deployments, fine in SQLite at 24h retention; a week of
    retention at 5s sampling is 8M.
- The console covers login, namespace switching, topology (graph + list), the four workload kinds,
  pods with SSE log streaming, YAML apply, cluster ops, GitOps config, audit and users. Not yet
  done there: editing a resource's YAML in place (apply is create/replace only), and charting the
  series above (Fase 4 of the console redesign).

- **Compose input** is detected server-side by content (top-level `services` map, no
  `apiVersion`/`kind`) and converted by `ComposeConverter` before anything else in
  `WorkloadController.apply()`, so the CLI and console both get it without knowing the format.
  Converted manifests carry no source YAML, so JSON-Schema validation is skipped for them — the
  converter builds domain records directly and their constructors do the validating.
- **Dry run must not persist.** `apply(dryRun=true)` stops after parse, RBAC and schema validation
  and reports each resource as `validated`. This was broken originally: `dryRun` only suppressed the
  audit payload while still saving, so a "validation" really applied and then reconciled onto Swarm.

## Fase 2 final verification — bugs found and fixed

Found via a real end-to-end smoke test (login → apply Deployment+Service+ConfigMap → get pods →
ConfigMap content change → delete), not just `mvn clean verify`. Kept here since none of them
were caught by compilation or unit tests — a reminder that reconciliation loops specifically
need runtime convergence checks, not just "does it compile":

- **Deployments re-updated on every single reconcile cycle, forever.** `ResourceDiffer.needsUpdate`
  compared `entity.getSpecJson().hashCode()` (JSON string hash) against the `rigger.io/spec-hash`
  label, which `ServiceAdapter` had set from `spec.hashCode()` (deserialized record hash) — two
  unrelated hash functions that could never agree. Fixed by having `diffDockerJava` take a
  `hashFn` parameter and `DeploymentController` pass `swarm::computeSpecHash`, so both sides use
  the exact same computation.
- **Every ordinary Deployment update silently wiped the Service's published ports.**
  `ServiceAdapter.update()` rebuilds the whole `ServiceSpec` via `buildServiceSpec()`, which never
  sets `EndpointSpec` — that's `ServiceController`'s job. Combined with the bug above (updates
  firing every cycle), this produced a permanent wipe/republish loop, bumping the Swarm service's
  version by the second on an idle cluster. Fixed by carrying over
  `existing.getSpec().getEndpointSpec()` in `update()` when present.
- **`ServiceType` only accepted Java constant names** (`CLUSTER_IP`/`LOAD_BALANCER`) while the
  README and `service.schema.json` document Kubernetes-style casing (`ClusterIP`/`LoadBalancer`) —
  any Service manifest written exactly as documented failed to parse. Fixed with a `@JsonCreator`
  on `ServiceType` accepting both spellings, case-insensitively.
- **Multi-document manifests always failed past the first document.**
  `WorkloadController.apply()` validated every parsed document against `req.manifest()` — the
  *entire* raw multi-doc YAML string — instead of that document's own text; `ManifestSchemaValidator`
  only reads the first YAML document, so anything past it got validated against the wrong schema.
  This broke the standard quick-start flow of applying Deployment+Service+ConfigMap in one file.
  Fixed by having `ManifestParser` retain each document's own text in `ParsedManifest.rawYaml()`
  and validating against that instead.

## Out of scope (do not re-litigate without a fresh decision)

- Real mTLS / client-certificate authentication.
- Ingress-controller-grade Service routing (path routing, TLS termination, vhosts) — Service
  reconciliation should stay to Swarm published-port mapping.
- GraalVM native-image packaging for `riggerctl`.
- Multi-instance/HA `rigger-server` (single-instance, SQLite-backed operator is the model).
- A RBAC administration UI (only the enforcement mechanism is in scope for hardening).
