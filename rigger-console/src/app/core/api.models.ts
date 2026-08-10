/**
 * Types mirroring the Java DTOs in rigger-api. Field names must match the @JsonProperty values
 * exactly — the backend has no OpenAPI spec, so nothing catches a drift here at build time.
 */

export type RiggerRole = 'CLUSTER_ADMIN' | 'DEPLOYER' | 'VIEWER' | 'GITOPS_AGENT';
export type NodeRole = 'MANAGER' | 'WORKER';
export type NodeStatus = 'PENDING' | 'PROVISIONING' | 'ACTIVE' | 'DRAINING' | 'OFFLINE';
export type AuditResult = 'SUCCESS' | 'DENIED' | 'ERROR';
export type Health = 'healthy' | 'degraded' | 'down' | 'unknown' | 'n/a';

export interface LoginResponse {
  token: string;
  username: string;
  role: RiggerRole;
  namespace: string | null;
  expiresIn: number;
}

export interface UserResponse {
  username: string;
  role: RiggerRole;
  namespace: string | null;
  active: boolean;
}

export interface PermissionsResponse {
  role: RiggerRole;
  namespace: string;
  permissions: Record<string, string[]>;
}

export interface ResourceResponse {
  kind: string;
  name: string;
  namespace: string;
  spec: Record<string, unknown>;
  labels: Record<string, string>;
  appliedBy?: string;
  createdAt: string;
  updatedAt: string;
}

export interface NodeResponse {
  name: string;
  ip: string;
  role: NodeRole;
  status: NodeStatus;
  primary: boolean;
  swarmNodeId?: string;
  lastSeenAt?: string;
}

export interface PodResponse {
  name: string;
  namespace: string;
  deployment?: string;
  image?: string;
  node?: string;
  state?: string;
  desiredState?: string;
  message?: string;
  createdAt?: string;
}

export interface ClusterStatus {
  activeNodes: number;
  totalNodes: number;
  status: 'healthy' | 'degraded';
}

export interface ClusterMetrics {
  activeNodes: number;
  totalNodes: number;
  managedServices: number;
  runningTasks: number;
  desiredTasks: number;
  deployments: number;
  services: number;
  configMaps: number;
  secrets: number;
}

export interface DeploymentMetrics {
  namespace: string;
  name: string;
  cpuPercent: number;
  desiredReplicas: number;
  runningReplicas: number;
  hpaEnabled: boolean;
  hpaMinReplicas: number | null;
  hpaMaxReplicas: number | null;
  hpaTargetCpu: number | null;
}

/**
 * Names match the server's enumerated {@code MetricNames}. Kept as a union rather than plain
 * strings so a typo is a compile error here — the server answers an unknown name with a 400, but
 * finding that out at runtime is worse than not shipping it.
 */
export type ClusterMetricName =
  | 'nodes.active' | 'nodes.total' | 'services.managed'
  | 'replicas.running' | 'replicas.desired'
  | 'resources.deployments' | 'resources.services'
  | 'resources.configmaps' | 'resources.secrets';

export type DeploymentMetricName =
  | 'deployment.cpu' | 'deployment.replicas.running' | 'deployment.replicas.desired';

export type MetricName = ClusterMetricName | DeploymentMetricName;

/** One point of a series. Short field names because a 24h window is thousands of these. */
export interface MetricPoint {
  t: string;
  v: number;
}

export interface MetricSeries {
  metric: MetricName;
  namespace: string;
  name: string;
  points: MetricPoint[];
}

export interface TopologyNode {
  id: string;
  kind: string;
  name: string;
  image: string | null;
  desiredReplicas: number | null;
  runningReplicas: number | null;
  health: Health;
  hpaEnabled: boolean;
}

export interface TopologyEdge {
  from: string;
  to: string;
  type: 'exposes' | 'mounts';
}

export interface Topology {
  namespace: string;
  nodes: TopologyNode[];
  edges: TopologyEdge[];
}

export interface EventResponse {
  id: string;
  type: string;
  resourceKind: string | null;
  resourceName: string | null;
  namespace: string | null;
  actor: string | null;
  message: string | null;
  occurredAt: string;
}

export interface AuditResponse {
  id: string;
  identityName: string;
  identityRole: string;
  action: string;
  resourceKind?: string;
  resourceName?: string;
  namespace?: string;
  sourceIp: string;
  timestamp: string;
  result: AuditResult;
  errorMessage?: string;
}

export interface GitOpsState {
  enabled: boolean;
  repositoryUrl: string;
  branch: string;
  lastAppliedCommit: string | null;
  lastAppliedAt: string | null;
  result: string;
  errorMessage: string | null;
}

export interface GitOpsConfig {
  enabled: boolean;
  repositoryUrl: string;
  branch: string;
  sshKeyPath: string;
  pollIntervalSeconds: number;
  manifestPaths: string[];
  namespaceMapping: Record<string, string>;
  source?: 'database' | 'properties';
  updatedAt?: string | null;
  updatedBy?: string | null;
}

export interface ApplyResult {
  applied: number;
  resources: Array<{ kind: string; name: string; namespace: string; action: string }>;
}

/** Spring Data's page envelope. */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** Shape of GlobalExceptionHandler's error body. */
export interface ApiError {
  status: number;
  title: string;
  detail: string;
  instance?: string;
  correlationId?: string | null;
}
