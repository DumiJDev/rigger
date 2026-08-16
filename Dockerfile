# Rigger server container image.
#
# Multi-stage on purpose, even though a single-stage image over a prebuilt jar would be shorter:
# the console is built into the jar by rigger-server's pom (frontend-maven-plugin -> npm ci ->
# ng build -> static/ui), and that step used to be manual. A Dockerfile that copied
# target/*.jar would happily produce an image from whatever jar happened to be lying around —
# including one built with -Dui.skip=true, which serves no UI at all and looks fine until someone
# opens /ui/. Building from the checkout means `docker build .` on a fresh clone cannot produce
# that image.
#
#   docker build -t rigger:local .
#   docker run -d --name rigger \
#     -p 7433:7433 \
#     -e RIGGER_ADMIN_PASSWORD=... -e RIGGER_JWT_KEY=<>=32 chars> \
#     -e RIGGER_ATTACH_EXISTING_SWARM=true \
#     -v rigger-state:/var/lib/rigger \
#     -v /var/run/docker.sock:/var/run/docker.sock \
#     --group-add "$(stat -c %g /var/run/docker.sock)" \
#     --memory 1g \
#     rigger:local
#
# Same image, PostgreSQL instead of the default SQLite (see StoreAutoConfiguration — the
# datasource bean is picked by RIGGER_STORE_TYPE, no image or profile difference):
#   docker run -d --name rigger \
#     -p 7433:7433 \
#     -e RIGGER_ADMIN_PASSWORD=... -e RIGGER_JWT_KEY=<>=32 chars> \
#     -e RIGGER_ATTACH_EXISTING_SWARM=true \
#     -e RIGGER_STORE_TYPE=postgresql \
#     -e RIGGER_DB_HOST=postgres -e RIGGER_DB_PORT=5432 \
#     -e RIGGER_DB_NAME=rigger -e RIGGER_DB_USER=rigger -e RIGGER_DB_PASSWORD=... \
#     -v /var/run/docker.sock:/var/run/docker.sock \
#     --group-add "$(stat -c %g /var/run/docker.sock)" \
#     --memory 1g \
#     rigger:local
# (drop -v rigger-state:/var/lib/rigger — nothing is written there in postgresql mode. See
#  docker-compose.postgres.yml for a runnable local example with both containers.)

# ---------------------------------------------------------------------------
# Build stage
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Poms first, then `dependency:go-offline`, so the dependency layer is cached and only invalidated
# by a pom change rather than by every source edit.
COPY pom.xml ./
COPY rigger-core/pom.xml           rigger-core/
COPY rigger-events/pom.xml         rigger-events/
COPY rigger-manifest/pom.xml       rigger-manifest/
COPY rigger-schema/pom.xml         rigger-schema/
COPY rigger-swarm-adapter/pom.xml  rigger-swarm-adapter/
COPY rigger-provisioner/pom.xml    rigger-provisioner/
COPY rigger-security/pom.xml       rigger-security/
COPY rigger-store/pom.xml          rigger-store/
COPY rigger-operator/pom.xml       rigger-operator/
COPY rigger-gitops/pom.xml         rigger-gitops/
COPY rigger-api/pom.xml            rigger-api/
COPY rigger-cli/pom.xml            rigger-cli/
COPY rigger-server/pom.xml         rigger-server/
RUN mvn -B -q dependency:go-offline -DskipTests || true

# The console's npm dependencies are their own cache layer for the same reason.
COPY rigger-console/package.json rigger-console/package-lock.json rigger-console/
COPY . .
# Node's toolchain is installed into rigger-console/node by the pom; a host build leaves a
# platform-specific copy there and COPY . . would drag it in. Same for any host target/.
RUN rm -rf rigger-console/node rigger-console/node_modules

# -Dmaven.test.skip, not -DskipTests: the latter still *compiles* the test sources, so the image
# build fails on a checkout whose tests don't compile even though nothing in the image comes from
# them. Tests are CI's job (`mvn clean verify`), not the image's, and skipping the compile also
# saves a stage.
RUN mvn -B -Dmaven.test.skip=true package

# Assert the console actually made it in. Without this the image can silently ship a UI-less
# server, which is the exact failure this multi-stage build exists to prevent.
# `jar tf`, not `unzip -l`: the maven image ships a JDK but no unzip.
RUN jar tf rigger-server/target/rigger-server-*.jar | grep -q 'BOOT-INF/classes/static/ui/index.html' \
    && echo "console present in jar"

# ---------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------
# JRE, not JDK: nothing at runtime compiles. Jammy rather than Alpine because the SQLite JDBC
# driver loads a bundled native library and glibc is the variant this project has actually run on.
FROM eclipse-temurin:21-jre-jammy

# curl is here for HEALTHCHECK below, which has to speak HTTPS to a self-signed cert.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root. Fixed uid/gid so a bind-mounted state directory has predictable ownership.
RUN groupadd -g 10001 rigger && useradd -u 10001 -g 10001 -m -s /usr/sbin/nologin rigger

# The SQLite database must outlive the container: it holds every resource, identity, audit entry
# and metric sample. Its own directory (not /app) so the volume never shadows the jar, and the
# directory rather than the file so SQLite can create its WAL and shm siblings next to it.
RUN mkdir -p /var/lib/rigger && chown 10001:10001 /var/lib/rigger
VOLUME /var/lib/rigger

WORKDIR /app
COPY --from=build --chown=10001:10001 /src/rigger-server/target/rigger-server-*.jar /app/rigger-server.jar

USER 10001:10001

ENV RIGGER_DB_PATH=/var/lib/rigger/rigger-state.db

# MaxRAMPercentage rather than a fixed -Xmx: the JVM is container-aware, so this tracks whatever
# `docker run --memory` says instead of sizing the heap off the host's total RAM. With no limit set
# the default heap is 1/4 of host RAM, which on a 32 GB host is an 8 GB heap for a process measured
# at ~180 MB committed — and under a limit that is *smaller* than a quarter of the host, a fixed
# -Xmx is how you get an OOM-kill instead of a GC. 70% leaves room for the metaspace, code cache,
# thread stacks and the netty/docker-java direct buffers that live outside the heap.
# MaxRAMPercentage only applies when no explicit -Xmx is given, so RIGGER_JAVA_OPTS can override it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=25 -XX:+ExitOnOutOfMemoryError"

EXPOSE 7433

# --insecure because the default keystore is self-signed; a deployment that supplies its own
# TLS_KEYSTORE_PATH is unaffected either way, since this only checks liveness from inside.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsk https://localhost:7433/actuator/health | grep -q '"status":"UP"'

ENTRYPOINT ["sh", "-c", "exec java $RIGGER_JAVA_OPTS -jar /app/rigger-server.jar"]
