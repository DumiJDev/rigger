// ── Domain types matching Java records ────────────────────────────────────

export type NodeRole   = "MANAGER" | "WORKER";
export type NodeStatus = "PENDING" | "PROVISIONING" | "ACTIVE" | "DRAINING" | "OFFLINE";
export type AuditResult = "SUCCESS" | "DENIED" | "ERROR";

export interface NodeResponse {
  name: string;
  ip: string;
  role: NodeRole;
  status: NodeStatus;
  primary: boolean;
  swarmNodeId?: string;
  lastSeenAt?: string;
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

export interface DeploymentSpec {
  replicas: number;
  image: string;
  hpa?: HpaSpec;
  strategy?: RollingUpdateStrategy;
  env?: EnvVar[];
}

export interface HpaSpec {
  minReplicas: number;
  maxReplicas: number;
  targetCPUUtilizationPercentage: number;
  scaleDownCooldownSeconds?: number;
}

export interface RollingUpdateStrategy {
  maxUnavailable: number;
  delaySeconds: number;
  failureAction: string;
}

export interface EnvVar {
  name: string;
  value?: string;
}

export interface ClusterStatus {
  activeNodes: number;
  totalNodes: number;
  status: "healthy" | "degraded";
}

export interface AuditEntry {
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

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface ApplyResult {
  applied: number;
  resources: Array<{ kind: string; name: string; namespace: string; action: string }>;
}
