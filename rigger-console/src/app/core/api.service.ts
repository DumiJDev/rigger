import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ApplyResult, AuditResponse, ClusterMetrics, ClusterStatus, DeploymentMetricName,
  DeploymentMetrics, EventResponse, GitOpsConfig, GitOpsState, MetricName, MetricSeries,
  NodeResponse, Page, PodResponse, ResourceResponse, Topology, UserResponse,
} from './api.models';

/**
 * Single place that knows the shape of the Rigger REST API.
 *
 * <p>Workload calls take the namespace explicitly rather than reading it from
 * {@link NamespaceService}: the caller usually already has it, and passing it keeps this free of
 * hidden global state (which matters for the topology and metrics polling, where the namespace can
 * change mid-flight).
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/v1';

  private ns(namespace: string): string {
    return `${this.base}/namespaces/${encodeURIComponent(namespace)}`;
  }

  // ── Identity ────────────────────────────────────────────────────────────
  me(): Observable<UserResponse> {
    return this.http.get<UserResponse>(`${this.base}/auth/me`);
  }

  // ── Workloads ───────────────────────────────────────────────────────────
  deployments(namespace: string): Observable<ResourceResponse[]> {
    return this.http.get<ResourceResponse[]>(`${this.ns(namespace)}/deployments`);
  }
  services(namespace: string): Observable<ResourceResponse[]> {
    return this.http.get<ResourceResponse[]>(`${this.ns(namespace)}/services`);
  }
  configMaps(namespace: string): Observable<ResourceResponse[]> {
    return this.http.get<ResourceResponse[]>(`${this.ns(namespace)}/configmaps`);
  }
  secrets(namespace: string): Observable<ResourceResponse[]> {
    return this.http.get<ResourceResponse[]>(`${this.ns(namespace)}/secrets`);
  }
  pods(namespace: string): Observable<PodResponse[]> {
    return this.http.get<PodResponse[]>(`${this.ns(namespace)}/pods`);
  }

  apply(namespace: string, manifest: string, dryRun = false): Observable<ApplyResult> {
    return this.http.post<ApplyResult>(`${this.ns(namespace)}/apply`, { manifest, dryRun });
  }
  scale(namespace: string, name: string, replicas: number): Observable<unknown> {
    return this.http.post(`${this.ns(namespace)}/deployments/${name}/scale`, { replicas });
  }
  /** kind is the plural path segment: deployments | services | configmaps | secrets. */
  deleteResource(namespace: string, kind: string, name: string): Observable<void> {
    return this.http.delete<void>(`${this.ns(namespace)}/${kind}/${name}`);
  }

  // ── Views ───────────────────────────────────────────────────────────────
  topology(namespace: string): Observable<Topology> {
    return this.http.get<Topology>(`${this.ns(namespace)}/topology`);
  }
  deploymentMetrics(namespace: string, name: string): Observable<DeploymentMetrics> {
    return this.http.get<DeploymentMetrics>(`${this.ns(namespace)}/deployments/${name}/metrics`);
  }

  /**
   * History for one metric, oldest point first — the server records it, so it survives a reload.
   *
   * <p>Cluster metrics take no namespace/name; per-Deployment ones require both. Sending them for a
   * cluster metric is harmless (the server ignores them and forces cluster scope) but omitting them
   * for a Deployment metric is a 400.
   */
  metricSeries(
    metric: MetricName,
    opts: { namespace?: string; name?: string; minutes?: number } = {},
  ): Observable<MetricSeries> {
    let params = new HttpParams().set('metric', metric);
    if (opts.namespace) params = params.set('namespace', opts.namespace);
    if (opts.name) params = params.set('name', opts.name);
    if (opts.minutes) params = params.set('minutes', opts.minutes);
    return this.http.get<MetricSeries>(`${this.base}/metrics/series`, { params });
  }

  /** Deployments with recorded samples for a metric, so a chart can plot them all without guessing. */
  metricSeriesNames(
    namespace: string,
    metric: DeploymentMetricName,
    minutes?: number,
  ): Observable<string[]> {
    let params = new HttpParams().set('metric', metric);
    if (minutes) params = params.set('minutes', minutes);
    return this.http.get<string[]>(`${this.ns(namespace)}/metrics/series-names`, { params });
  }

  // ── Cluster ─────────────────────────────────────────────────────────────
  clusterStatus(): Observable<ClusterStatus> {
    return this.http.get<ClusterStatus>(`${this.base}/cluster`);
  }
  clusterMetrics(): Observable<ClusterMetrics> {
    return this.http.get<ClusterMetrics>(`${this.base}/cluster/metrics`);
  }
  nodes(): Observable<NodeResponse[]> {
    return this.http.get<NodeResponse[]>(`${this.base}/cluster/nodes`);
  }
  clusterUp(manifest: string): Observable<unknown> {
    return this.http.post(`${this.base}/cluster/up`, { manifest });
  }
  clusterSync(manifest: string): Observable<unknown> {
    return this.http.post(`${this.base}/cluster/sync`, { manifest });
  }

  // ── Activity ────────────────────────────────────────────────────────────
  events(namespace?: string, page = 0, size = 25): Observable<Page<EventResponse>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (namespace) params = params.set('namespace', namespace);
    return this.http.get<Page<EventResponse>>(`${this.base}/events`, { params });
  }
  audit(namespace?: string, page = 0, size = 50): Observable<Page<AuditResponse>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (namespace) params = params.set('namespace', namespace);
    return this.http.get<Page<AuditResponse>>(`${this.base}/audit`, { params });
  }

  // ── GitOps ──────────────────────────────────────────────────────────────
  gitopsState(): Observable<GitOpsState> {
    return this.http.get<GitOpsState>(`${this.base}/gitops/state`);
  }
  gitopsConfig(): Observable<GitOpsConfig> {
    return this.http.get<GitOpsConfig>(`${this.base}/gitops/config`);
  }
  saveGitopsConfig(config: GitOpsConfig): Observable<GitOpsConfig> {
    return this.http.put<GitOpsConfig>(`${this.base}/gitops/config`, config);
  }

  // ── Users ───────────────────────────────────────────────────────────────
  users(): Observable<UserResponse[]> {
    return this.http.get<UserResponse[]>(`${this.base}/users`);
  }
  createUser(body: {
    username: string; password: string; role: string; namespace: string | null;
  }): Observable<UserResponse> {
    return this.http.post<UserResponse>(`${this.base}/users`, body);
  }
  deleteUser(username: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/users/${encodeURIComponent(username)}`);
  }
}
