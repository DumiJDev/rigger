---
name: spring-boot
description: Conventions and known traps for Rigger's Spring Boot 4 backend — adding controllers/endpoints, RBAC, JPA entities and Flyway migrations, auto-configuration across modules, and the Jackson/preview-API pitfalls specific to this repo. Use when touching any Java module (rigger-api, rigger-store, rigger-security, rigger-operator, rigger-gitops, …).
---

# Rigger backend (Spring Boot 4)

Java 21+ / Spring Boot 4.1 multi-module Maven reactor. Read `CLAUDE.md` for the module map first;
this skill covers *how to work in it* without re-hitting problems this project already solved.

## Non-negotiables

**Every controller method that touches a resource starts with an explicit RBAC call:**

```java
var ctx = ctx(req, namespace);              // or read the "riggerContext" request attribute
rbac.authorize(ctx, "get", "Deployment");   // throws AccessDeniedException -> 403
```

There is no annotation and no compiler check — an AOP alternative was tried and deleted (it
couldn't express `apply()`'s mixed batch of kinds). Forgetting this call silently ships an
unauthorized endpoint. When adding a new `(action, resource)` pair, update `RbacPolicyEngine`:
non-admin roles need a `POLICY` row, and admin-only operations need an `ADMIN_ONLY` entry so
`permissionsFor()` still describes an admin's capabilities to the console.

**Namespace comes from the URL path, not a header or session.** `RiggerAuthenticationFilter`
scans path segments for `namespaces` and takes the next one, defaulting to `"cluster"`. Consequences:
- Workload endpoints must live under `/api/v1/namespaces/{namespace}/…`.
- Cluster-scoped paths resolve to `"cluster"`, which every namespace-scoped identity fails
  `isScopedTo()` against — so calling `authorize` there makes the endpoint admin-only whether you
  meant it or not. If a scoped user legitimately needs the endpoint, derive the answer from their
  own identity instead (see `NamespaceController` for the pattern and the reasoning).

**Secrets never round-trip in the clear.** `SecretSpec.data` is encrypted with `SecretEncryptor`
before it reaches the store; reads always return `{"keys":"redacted"}`. The single legitimate
decryption point is the operator's `SecretController` pushing into a Docker Secret. Audit entries
for Secret applies record `"<redacted-secret-data>"`.

## Spring Boot 4 traps (each cost real debugging time here)

- **Jackson stays on 2.x.** `spring-boot-starter-jackson` ships Jackson 2 (`com.fasterxml.jackson`)
  and that's what Spring MVC serialises with. Jackson 3 (`tools.jackson`) is on the classpath too,
  pulled in by Flyway for its own use. Do **not** "modernise" domain records to `tools.jackson`
  imports — they'd silently detach from the converter Spring actually uses and field names would
  stop matching.
- **Flyway needs `spring-boot-starter-flyway`.** Auto-configuration moved out of
  `spring-boot-autoconfigure`. With bare `flyway-core` the migrations never run and startup dies on
  Hibernate schema validation (`missing table [audit_log]`) — a failure that points at JPA rather
  than the real cause.
- **`@EntityScan` moved** to `org.springframework.boot.persistence.autoconfigure`.
- **No preview APIs.** `--enable-preview` was removed deliberately; preview class files bind to one
  JDK major version and broke the build when the machine's default JDK changed. Use virtual threads
  via `Executors.newVirtualThreadPerTaskExecutor()`, not `StructuredTaskScope`.
- **`<parameters>true</parameters>` is required** for implicit `@PathVariable`/`@RequestParam`
  names. Without it endpoints fail at request time, not compile time.

## Adding an endpoint

1. Put it in the controller matching its scope (`WorkloadController` for namespaced resources,
   `ClusterController` for cluster ops, …), or a new `@RestController` under `io.rigger.api.controller`.
2. Start with `rbac.authorize(...)`; add the pair to `RbacPolicyEngine` if new.
3. Return a record DTO from `io.rigger.api.dto` with explicit `@JsonProperty` names — don't leak
   entities or `Map<String,Object>` blobs.
4. Audit anything that mutates: `audit.recordSuccess(ctx, action, kind, name, before, after)`,
   redacting secret payloads.
5. Verify by calling it against a running server. Compilation proves nothing about serialisation,
   RBAC, or path resolution — several bugs here compiled fine and failed only at runtime.

## Persistence

SQLite via Spring Data JPA + Flyway (`rigger-store/src/main/resources/db/migration/`, `V<n>__name.sql`).

- Entities mapping to `TEXT`/`INTEGER` SQLite columns **must** set `columnDefinition` explicitly
  (`Instant` → `TEXT`, `boolean` → `INTEGER`); Hibernate's schema validation is strict even though
  SQLite is dynamically typed.
- Derived delete queries need an explicit `@Transactional` on the repository method — without it
  they throw `No EntityManager with actual transaction available`.
- Migrations are immutable once applied; add a new version rather than editing an old one.

## Cross-module wiring

Each module owns an `@AutoConfiguration` class registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Framework-free
modules (`rigger-core`, `rigger-schema`) stay that way — their beans get registered by a dependent
module. Before adding a module dependency, check the direction: `rigger-api` may depend on
`rigger-operator`, never the reverse.

## Verifying

```bash
mvn clean verify                      # whole reactor; any JDK 21+, no flags
cd rigger-server && RIGGER_ATTACH_EXISTING_SWARM=true RIGGER_ADMIN_PASSWORD=<pw> mvn spring-boot:run
```

Then exercise the change end-to-end against the live Swarm — login, apply, observe reconciliation,
delete. Reconciliation changes especially need a convergence check: watch that the Swarm object's
version stops incrementing once in sync, since a hash mismatch produces an endless update loop that
looks fine in logs.
