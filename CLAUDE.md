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

### Scheduling and thread budget

Five `@Scheduled` methods share one Spring pool: `ReconciliationLoop.reconcile` (15s),
`HpaController.reconcile` (30s), `MetricsSampler.sample` (30s), `MetricsSampler.prune` (1h),
`GitOpsAgent.poll` (60s). Boot's default pool size is **1**, so `application.yaml` sets
`spring.task.scheduling.pool.size` (default 5, `RIGGER_SCHEDULER_POOL_SIZE`) — one thread per
method, so none can delay another.

Not cosmetic. HPA and metrics ticks block on one Docker `statsCmd` per running container (~2s each,
measured), so with 4 Deployments × 2 replicas the reconcile loop was observed running every 24–26s
against its configured 15s, purely from queueing. Forced A/B on the same jar at a 10s interval:
pool=1 → mean 17.35s, 19 of 20 ticks late; pool=5 → mean 10.54s, 0 of 15 late. Thread dumps confirm
two scheduler threads blocked in Docker I/O simultaneously, which one thread cannot do.
**If you add a `@Scheduled` method, raise the pool size with it.**

Raising the pool made a latent SQLite problem reachable: one writer at a time means a concurrent
write fails immediately with `SQLITE_BUSY`, which never happened while the jobs took turns.
`StoreAutoConfiguration` now sets `busyTimeout(5000)`, and `MetricsSampler`'s `saveAll` is guarded so
a contended write costs one round rather than the whole method.

`spring.threads.virtual.enabled` is deliberately **not** set: it swaps the pool for a
`SimpleAsyncTaskScheduler`, making the size setting silently inert, and on JDK 21–23 a virtual thread
blocking inside `synchronized` pins its carrier — which both sqlite-jdbc and docker-java's HttpClient
do. Concurrency where it matters is already explicit via `newVirtualThreadPerTaskExecutor()`.

### Container image

`docker build -t rigger:local .` at the repo root. Multi-stage on purpose: the build stage runs the
full Maven build including the console and then **asserts** `BOOT-INF/classes/static/ui/index.html`
is in the jar, so a fresh clone cannot produce a UI-less image the way copying `target/*.jar` could.
It uses `-Dmaven.test.skip=true`, not `-DskipTests` — the latter still compiles test sources, so a
checkout with a broken test would fail an image build that uses nothing from them.

Runtime is `eclipse-temurin:21-jre-jammy` (glibc, because sqlite-jdbc loads a bundled native
library), non-root uid/gid 10001, `/var/lib/rigger` as a volume with `RIGGER_DB_PATH` pointing into
it, and `-XX:MaxRAMPercentage=70` rather than a fixed `-Xmx`: under `--memory 1g` the JVM chose a
718 MiB heap where the same JVM on a 7.4 GB host chose 1.85 GB. Reaching Swarm needs
`-v /var/run/docker.sock:/var/run/docker.sock --group-add "$(stat -c %g /var/run/docker.sock)"`,
since the process is non-root. Not covered by CI yet — built and run by hand.

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

- **`RiggerApplication` carries `@ComponentScan(basePackages = "io.rigger")`, which overrides every
  module's own bean registration.** Any `@Component` anywhere under `io.rigger` is registered by the
  application's own scan, so a `@ConditionalOnProperty` or an exclude filter in that module's
  `@AutoConfiguration` **cannot** suppress it. Found the hard way wiring `rigger.operator.enabled`:
  the idiomatic conditional-import version compiled, started, and did nothing — the loops still
  ticked five times in 60 seconds with the flag off. `OperatorAutoConfiguration` therefore removes
  the three loop bean definitions in a `BeanFactoryPostProcessor`, which is the only mechanism a
  single module can rely on while that blanket scan exists. Narrow the scan and this can go back to
  `@ConditionalOnProperty` on the three classes.
- **`rigger.operator.enabled=false` stops reconciliation, HPA and metrics sampling only.** The REST
  API, console, live metrics endpoints and GitOps keep working. `@EnableScheduling` stays
  unconditional on purpose: gating it would also stop `GitOpsAgent.poll`, which is registered
  unconditionally so GitOps can be switched on from the console without a restart. Verified: 0
  reconcile ticks and 0 new `metric_samples` rows over 70s, with the API and `/ui/` still serving.
  This property previously existed and had **no callers at all** — a flag that silently does nothing
  is worse than no flag.

- **Ingress is fields on the Service kind** (`spec.ingress.host/path/tls`), not a new kind. Honoured
  only for `LoadBalancer`; `ManifestValidator` rejects it on `ClusterIP` rather than ignoring it.
  `ServiceSpec` keeps an explicit 3-argument constructor so `ComposeConverter` still compiles.
- **Rigger provisions Traefik itself.** `TraefikController` runs in the reconciliation loop,
  sequentially and first, because it creates the overlay network the workload controllers attach to.
  Traefik is built as a raw Swarm service rather than a Rigger Deployment because it needs the Docker
  socket bind-mounted and a manager constraint — and `DeploymentSpec` deliberately has **no volumes
  field**: adding one would let any namespace-scoped DEPLOYER bind-mount `/var/run/docker.sock` and
  own the cluster. Volumes are a legitimate future feature, but they need an allowlist and an RBAC
  design of their own, not a side entrance opened to bootstrap an ingress.
- **The controller must never carry `rigger.io/managed=true`.** `DeploymentController` deletes managed
  services with no store row, so it would be garbage-collected within 15s. It carries
  `rigger.io/component=ingress-controller` instead, and there is a comment on the constant saying so
  because the next person will "helpfully" add it.
- **Traefik v3.6 is the practical minimum, and this is the single most valuable thing learned here.**
  Traefik ≤ 3.5 pins Docker API version 1.24 in its Swarm provider and cannot negotiate it; Engine 29
  requires ≥ 1.40. Traefik still starts, still reports `1/1`, still answers HTTP — and discovers
  nothing, so every host returns 404. `DOCKER_API_VERSION` does not help; the pin is in Traefik's
  code. Measured against Engine 29.6.1: v3.4 fails, v3.5 fails, v3.6 works. **Every other check was
  green while routing was completely dead** — the overlay network, the label set, the attachment by
  ID, the replica count. Only `curl -H 'Host: …' http://127.0.0.1/` through Traefik found it, which
  is also the only thing that catches a v2 label spelling (`traefik.docker.*` fails the same silent
  way). Use `traefik.swarm.*` and `providers.swarm` only.
- **Single writer per Swarm service.** `ServiceController` no longer writes anything — it only warns
  about Services selecting nothing. `ServiceAdapter.updatePublishedPorts` is deleted and the
  `existing.getEndpointSpec()` graft in `update()` is gone. Published ports, Traefik labels and the
  network attachment all originate inside `buildServiceSpec()` from a `ServiceBinding` resolved
  **once per cycle** by `ServiceBindingResolver` and shared between the hash lambda and the
  create/update call. Two controllers writing the same service concurrently is what wiped published
  ports before; the same race would have made Traefik labels vanish at random.
- **Binding resolution is deterministic by contract, not by tidiness.** Service→Deployment matches and
  duplicate ingress-host claims are both resolved by sorted tie-break, because an unstable spec-hash
  makes the Swarm version index climb forever. That bug and its opposite — a hash that omits the
  binding, so a change never applies — each pass the other's test, which is why CI asserts
  convergence in **both** directions.
- `ResourceDiffer`'s unused 2-argument `diff()` overload is **deleted**; it hashed with a divergent
  `spec.hashCode()`. There is no 2-argument `computeSpecHash` either. Two entry points to a hash
  function is exactly how reconciliation broke once already.
- `buildLabels()` now applies `metadata.labels` **first**, so `rigger.io/*` and `traefik.*` always
  win. A user label named `rigger.io/spec-hash` could previously freeze reconciliation.
- **Ingress hosts are cluster-wide while RBAC is namespaced**, so `WorkloadController.apply()` refuses
  a host already claimed by a Service in another namespace, and `ServiceBindingResolver` also lets the
  lowest-sorting claim win at reconcile time. Without this, a DEPLOYER in one namespace could hijack
  another team's hostname with an ordinary apply. That check is a plain store query and deliberately
  **not** routed through RBAC: the caller must be told the host is taken, and by whom, without gaining
  read access to the namespace holding it.
- Adding an ingress to a Service **restarts that app's tasks**, because changing
  `TaskTemplate.Networks` makes Swarm recreate them. Attachment happens only when an ingress is
  configured, so enabling the feature does not restart every workload in the cluster.

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
  controller), and streaming logs (`riggerctl logs --follow` is broken end-to-end — no server
  endpoint, and the CLI command bypasses its own authenticated HTTP client).
  Pods listing, the read-only GitOps status endpoint, and `DELETE` for **all four** workload kinds
  now exist and are runtime-verified (`WorkloadController` has `@DeleteMapping` for deployments,
  services, configmaps and secrets; deleting the resource also removes the backing Docker
  Config/Secret). This bullet claimed otherwise for a while after they landed — when you close a
  gap, delete it from here, or the next person plans around a limitation that no longer exists.
- **Two Rigger servers must never share one Swarm.** `ResourceDiffer` treats any service carrying
  `rigger.io/managed=true` that has no matching row in *its own* database as an orphan and deletes
  it, cluster-wide, on the first reconciliation cycle. Two instances with separate databases
  therefore delete each other's services in a loop, each recreating its own. This is consistent
  with the single-instance model (see Out of scope) but the failure mode is silent and looks like
  random service churn — it cost real debugging time during a parallel-agent session. A
  namespace-scoped or instance-scoped orphan filter is what would make it safe.
- **`env[].valueFrom` is validated and then silently dropped.** `ServiceAdapter.buildServiceSpec`
  filters `e -> e.value() != null`, so a `configMapKeyRef`/`secretKeyRef` env var parses, passes
  validation, applies successfully, and never reaches the container. `secretRefs` on a Deployment
  likewise mounts nothing — nothing in `rigger-swarm-adapter` reads it. Both are documented in
  `examples/README.md` as known limitations; neither should be advertised as working.
- **The JSON schemas document shapes the parser rejects.** `deployment.schema.json` describes
  `resources.limits.{cpu,memory}` and `strategy.type`, but `ResourceRequirements` is flat
  (`cpuLimit`/`memoryLimit`/`cpuReserved`/`memoryReserved`) and `RollingUpdateStrategy` has no
  `type` component — and `ManifestParser` uses a bare ObjectMapper with `FAIL_ON_UNKNOWN_PROPERTIES`
  on. So a manifest can pass schema validation and then fail to parse. This is what made the README
  example wrong for months. Someone has to decide which side moves; until then the flat form is the
  one that works.
- `/actuator/prometheus` is real now — `micrometer-registry-prometheus` was missing from every pom
  while `application.yaml` advertised the endpoint, so it 404'd. Cost measured: +2.1 MB in the jar,
  +7.7 MiB RSS, ~230 series / 41.7 KB in 53 ms per scrape. It is **authenticated**
  (`SecurityAutoConfiguration` permits only `/actuator/health`), so a Prometheus server cannot
  actually scrape it: JWTs last 15 minutes and there is no refresh endpoint. Making it scrapeable
  means a scraper-specific credential or a separate unpublished management port — not widening the
  filter chain. Until then it is a debugging endpoint you reach with an admin token.
- **A server booting against an empty store deletes every rigger-managed Swarm service** — 9 in one
  observed run. Correct orphan reconciliation, hazardous as a first-boot default; deserves a guard or
  at least a loud warning. Related to the two-servers-on-one-Swarm hazard above, and the same root
  cause: orphan detection is cluster-wide and database-relative.
- `DELETE` can return a generic 500 on `SQLITE_BUSY` (`CannotAcquireLockException`) when
  reconciliation is writing at the same moment. It succeeds on retry. Deserves a retry or a 409
  rather than reading as a server fault.
- `ServiceController` no longer writes to Swarm at all — see the single-writer decision above.
  Published ports now flow through the Deployment path from a resolved `ServiceBinding`; the
  controller only warns about Services whose selector matches nothing. `ClusterIP` remains a no-op
  because Swarm's overlay DNS already covers it.
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
- **The converter reports what it cannot carry across, and refuses the losses that matter.** It used
  to read only `image`, `deploy.replicas`, `environment` (map form only) and `ports` (short form
  only), and drop everything else in silence — including `volumes`, `command`, `healthcheck`,
  `networks` and `labels`. Worse, `configs.*.file` produced a ConfigMap whose value was the
  *filename*, `environment` in list form yielded zero variables, and a long-form `ports` entry threw
  and vanished. Every field is now either converted or named in a structured report, with a severity
  rule that is the whole design:
  - **ERROR** — the workload would run but would not be the workload described (no image, no data,
    no config, the wrong process, or a value only the user's filesystem holds). These **block the
    apply with a 422** listing every offending path; `?force=true` is the single explicit override.
  - **WARNING** — same workload, less supervision or topology (healthcheck, placement, labels).
  - **INFO** — translated rather than literal (a published host port becoming a LoadBalancer).
  Blocking is the point: a warning nobody reads is exactly why `volumes:` was being dropped.
- **Seeing a conversion** — `POST /api/v1/namespaces/{ns}/convert` returns the generated YAML plus
  the report and persists nothing; `riggerctl convert -f docker-compose.yml > out.yaml` puts YAML on
  stdout and the report on stderr, exiting non-zero when blocked, so the redirect stays clean. The
  endpoint authorizes `get`/`Deployment`, deliberately **not** `apply`: converting is a pure function
  of the caller's own input, so a VIEWER must be able to review what a Compose file would become.
  `ApplyCommand` has no `--force` flag, so the CLI route for an intentionally lossy apply is
  convert-then-apply-the-YAML.
- Compose sizes are not Kubernetes sizes: `128M` is not `128Mi`, and docker-java's
  `MemoryUnit.toBytes` cannot parse the Compose spelling. Passing one through produced a manifest
  that applied and validated cleanly and then threw inside `ServiceAdapter.create` on **every**
  reconciliation cycle, so the Deployment never appeared in Swarm and the only symptom was a
  repeating "Failed to create service". The converter now normalises the units it understands and
  drops the rest with a warning.
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
- Ingress beyond host-based HTTP routing. `spec.ingress` covers host, path and TLS through a
  Rigger-provisioned Traefik; middlewares, rate limiting, auth forwarding and multi-backend
  weighting are not in scope. (Host routing itself WAS out of scope until the user asked for it —
  this line is the remaining boundary, not a ban on the feature.)
- GraalVM native-image packaging for `riggerctl`.
- Multi-instance/HA `rigger-server` (single-instance, SQLite-backed operator is the model).
- A RBAC administration UI (only the enforcement mechanism is in scope for hardening).
