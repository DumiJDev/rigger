import type {
  NodeResponse, ResourceResponse, ClusterStatus,
  AuditEntry, Page, ApplyResult
} from "./types";

const BASE = "/api/v1";

async function request<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await fetch(BASE + path, {
    headers: { "Content-Type": "application/json", ...opts.headers },
    ...opts,
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ detail: res.statusText }));
    throw new Error((err as { detail?: string }).detail ?? `HTTP ${res.status}`);
  }
  if (res.status === 204) return null as unknown as T;
  return res.json() as Promise<T>;
}

export const api = {
  // ── Cluster ─────────────────────────────────────────────────────────────
  clusterStatus: () =>
    request<ClusterStatus>("/cluster"),

  nodes: () =>
    request<NodeResponse[]>("/cluster/nodes"),

  // ── Workloads ────────────────────────────────────────────────────────────
  deployments: (ns: string) =>
    request<ResourceResponse[]>(`/namespaces/${ns}/deployments`),

  deployment: (ns: string, name: string) =>
    request<ResourceResponse>(`/namespaces/${ns}/deployments/${name}`),

  services: (ns: string) =>
    request<ResourceResponse[]>(`/namespaces/${ns}/services`),

  configmaps: (ns: string) =>
    request<ResourceResponse[]>(`/namespaces/${ns}/configmaps`),

  secrets: (ns: string) =>
    request<ResourceResponse[]>(`/namespaces/${ns}/secrets`),

  apply: (ns: string, manifest: string, dryRun = false) =>
    request<ApplyResult>(`/namespaces/${ns}/apply`, {
      method: "POST",
      body: JSON.stringify({ manifest, dryRun }),
    }),

  scale: (ns: string, name: string, replicas: number) =>
    request<{ name: string; replicas: number }>(`/namespaces/${ns}/deployments/${name}/scale`, {
      method: "POST",
      body: JSON.stringify({ replicas }),
    }),

  deleteResource: (ns: string, kind: string, name: string) =>
    request<void>(`/namespaces/${ns}/${kind}/${name}`, { method: "DELETE" }),

  // ── Audit ────────────────────────────────────────────────────────────────
  audit: (ns: string, page = 0) =>
    request<Page<AuditEntry>>(`/audit?namespace=${ns}&page=${page}&size=50`),
};
