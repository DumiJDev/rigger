# Rigger examples

Working manifests you can apply as-is. Every file here is applied and verified against a real
Swarm — if one of them stops parsing, that is a bug in the example, not in your setup.

```
examples/
├── deployment-sample.yaml          # smallest possible Deployment (README quick start)
├── gitops/                         # a complete app — point the GitOps agent here
│   └── app.yaml                    # ConfigMap + Secret + Deployment + 2 Services, one file
└── cluster/
    └── rigger.cluster.yaml         # single-node cluster, with commented-out growth
```

`gitops/app.yaml` is one app — `whoami`, a web tier in namespace `demo` with its configuration, its
credentials, an internal Service and a published one — as a single multi-document manifest rather
than one resource per file, since `riggerctl apply -f` and the GitOps agent both already handle a
multi-document file the same way they'd handle a directory.

## Apply it by hand

```bash
riggerctl apply -f examples/gitops/app.yaml -n demo --dry-run --insecure   # validate only
riggerctl apply -f examples/gitops/app.yaml -n demo --insecure
riggerctl get deployments -n demo --insecure

# Cleanup — all four kinds have a DELETE endpoint; the CLI asks for confirmation on each
riggerctl delete service   whoami-public   -n demo --insecure
riggerctl delete service   whoami-internal -n demo --insecure
riggerctl delete configmap whoami-config   -n demo --insecure
riggerctl delete secret    whoami-secret   -n demo --insecure
riggerctl delete deployment whoami         -n demo --insecure
```

Deleting the resource also removes what it created in Swarm (the service, the Docker Config, the
Docker Secret) on the next reconciliation cycle — no manual `docker service rm` needed.

Or over HTTP, one file at a time:

```bash
TOKEN=$(curl -sk -X POST https://localhost:7433/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<from-log>"}' | jq -r .token)

curl -sk -X POST https://localhost:7433/api/v1/namespaces/demo/apply \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -n --rawfile m examples/gitops/30-deployment.yaml '{manifest:$m, dryRun:true}')"
```

## Point the GitOps agent at this folder

The agent clones a repository, then for each configured manifest path parses **every `*.yaml` /
`*.yml` file in that one directory** and applies them. Two consequences that shape the layout
above:

- **Discovery is not recursive.** Only files directly inside a configured path are read.
  Subdirectories are invisible unless you list them as paths of their own. That is why
  `gitops/` is flat, and why `cluster/` can sit next to it safely.
- **Files are applied in sorted filename order**, hence the `10-`/`20-`/`30-` prefixes:
  configuration and credentials before the workload that references them. Ordering is a
  courtesy, not a requirement — the reconciliation loop converges regardless — but it keeps the
  first cycle from creating a Deployment whose ConfigMap does not exist yet.

Configure it through the console (GitOps page), the API, or properties:

```yaml
rigger:
  gitops:
    enabled: true
    repository: git@github.com:myorg/rigger-manifests.git
    branch: main
    sshKeyPath: /etc/rigger/gitops-key
    pollIntervalSeconds: 60
    manifestPaths:
      - examples/gitops/
```

```bash
curl -sk -X PUT https://localhost:7433/api/v1/gitops/config \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"enabled":true,
       "repositoryUrl":"https://github.com/myorg/rigger-manifests.git",
       "branch":"main",
       "pollIntervalSeconds":60,
       "manifestPaths":["examples/gitops/"],
       "namespaceMapping":{}}'

curl -sk -H "Authorization: Bearer $TOKEN" https://localhost:7433/api/v1/gitops/state
```

The agent only applies when the branch HEAD changes, and it can only apply — it never deletes.
Removing a file from Git does not remove the resource from the cluster; delete it explicitly.

Things worth knowing before you rely on it:

- **`namespaceMapping` is effectively inert.** It maps a manifest path to a namespace, but only
  as a fallback for manifests without one — and `metadata.namespace` is mandatory on every
  Rigger resource, so the value in the file always wins. Set the namespace in the manifest.
- **The polled directory must contain workload manifests only.** `kind: Cluster` is not a
  workload kind; a single unparseable file aborts the entire sync cycle rather than being
  skipped. Keep `rigger.cluster.yaml` out of any polled path.
- **The GitOps path bypasses HTTP and RBAC by design** (trusted internal path) — which also
  means it does not run JSON-Schema validation or encrypt Secret values the way
  `POST /apply` does. Prefer `riggerctl apply --dry-run` against a file before committing it,
  and prefer `vaultRef` over `data` for real credentials.

## The two mistakes everybody makes

Both of these are documented Kubernetes spellings that Rigger does **not** accept. The manifest
parser rejects unknown properties, so each one fails the whole document with
`Failed to parse spec for Deployment`:

```yaml
# WRONG — nested, Kubernetes-style             # RIGHT — flat
resources:                                     resources:
  limits:                                        cpuLimit: "0.25"
    cpu: "0.25"                                  memoryLimit: "128Mi"
    memory: "128Mi"                              cpuReserved: "0.05"
  requests: {...}                                memoryReserved: "32Mi"

# WRONG — strategy has no type                 # RIGHT
strategy:                                      strategy:
  type: RollingUpdate                            maxUnavailable: 1
  maxUnavailable: 1                              delaySeconds: 5
```

(The JSON schemas in `rigger-schema` still describe the nested `limits`/`requests` shape and
allow `strategy.type`. Schema validation runs *before* domain parsing, so those documents pass
the schema and then fail the parser. The examples here use the form that satisfies both.)

Also worth internalising:

- `metadata.namespace` is **required and non-blank** on every resource. There is no implicit
  namespace.
- Names must match `^[a-z0-9][a-z0-9-]{0,61}[a-z0-9]$`.
- `type` on a Service must be exactly `ClusterIP` or `LoadBalancer` — the schema enum allows
  nothing else, even though the Java enum is more forgiving.
- A Service's `selector` must be a **subset** of the Deployment's `selector`, or the operator
  finds no target and quietly does nothing.
- A Secret declares **exactly one** of `data` (base64 values) or `vaultRef`.

## Known limitations these examples run into

Honest notes, so you do not debug your own manifest looking for a bug that is ours:

- **`env[].valueFrom` is not injected.** `configMapKeyRef` / `secretKeyRef` parse, validate and
  show up in the console topology, but the Swarm adapter only forwards env entries that carry a
  literal `value`. Until that gap closes, use literal env values, or `configMapRefs` (mounted as
  a file at `/configmap/<name>`).
- **`secretRefs` does not mount anything into the container.** The operator does reconcile the
  Secret into a real Docker Secret, but the Deployment side of the mount is not wired up.
- **ClusterIP Services reconcile to nothing** — intentionally. Swarm's overlay DNS already
  resolves `rigger-<namespace>-<name>`; the resource exists to declare and audit the intent.
- **A LoadBalancer `port` is cluster-wide.** Swarm publishes in ingress mode on every node, so
  the port must be free everywhere and unique across the whole swarm.
- **`riggerctl cluster up` / `cluster sync` have no server endpoint yet.** `cluster/` is
  parse-verified only — the provisioning path itself is not exercised by CI (it needs real SSH
  hosts).
