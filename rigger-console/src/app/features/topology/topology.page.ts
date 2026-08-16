import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { Topology, TopologyEdge, TopologyNode } from '../../core/api.models';
import { LiveUpdateService } from '../../core/live-update.service';
import { NamespaceService } from '../../core/namespace.service';
import { RefreshService } from '../../core/refresh.service';
import { DataState } from '../../shared/data-state';
import { PageHeader } from '../../shared/page-header';
import { StatusBadge } from '../../shared/status-badge';

interface Placed extends TopologyNode {
  x: number;
  y: number;
}

interface Link {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  type: TopologyEdge['type'];
}

const COL_X = { Deployment: 300, Service: 60, ConfigMap: 560, Secret: 560 } as const;
const ROW_H = 96;
const TOP = 48;

/**
 * Graph and list views of the namespace's workloads.
 *
 * <p>The layout is a deliberate three-column arrangement (Services → Deployments →
 * ConfigMaps/Secrets) rather than a force-directed simulation: the graph is small, the data has a
 * natural direction, and a stable layout is far easier to read across refreshes than one that
 * reshuffles every time. That also avoids pulling in a graph library for a handful of nodes.
 */
@Component({
  selector: 'r-topology',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, DataState, StatusBadge],
  templateUrl: './topology.page.html',
})
export class TopologyPage {
  private readonly api = inject(ApiService);
  private readonly ns = inject(NamespaceService);
  private readonly refresh = inject(RefreshService);
  private readonly live = inject(LiveUpdateService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly data = signal<Topology | null>(null);
  readonly view = signal<'graph' | 'list'>('graph');
  readonly selected = signal<TopologyNode | null>(null);

  readonly placed = computed<Placed[]>(() => {
    const nodes = this.data()?.nodes ?? [];
    const counters: Record<string, number> = {};
    return nodes.map((n) => {
      const column = (COL_X as Record<string, number>)[n.kind] ?? COL_X.Deployment;
      const key = n.kind === 'Secret' ? 'ConfigMap' : n.kind; // share the right-hand column
      counters[key] = (counters[key] ?? 0) + 1;
      return { ...n, x: column, y: TOP + (counters[key] - 1) * ROW_H };
    });
  });

  readonly links = computed<Link[]>(() => {
    const byId = new Map(this.placed().map((p) => [p.id, p]));
    return (this.data()?.edges ?? [])
      .map((e) => {
        const from = byId.get(e.from);
        const to = byId.get(e.to);
        if (!from || !to) return null;
        return { x1: from.x + 110, y1: from.y + 26, x2: to.x, y2: to.y + 26, type: e.type };
      })
      .filter((l): l is Link => l !== null);
  });

  readonly canvasHeight = computed(() => {
    const rows = Math.max(1, ...Object.values(countByColumn(this.placed())));
    return TOP + rows * ROW_H;
  });

  readonly connectionsOf = computed(() => {
    const node = this.selected();
    if (!node) return [];
    return (this.data()?.edges ?? [])
      .filter((e) => e.from === node.id || e.to === node.id)
      .map((e) => ({ other: e.from === node.id ? e.to : e.from, type: e.type }));
  });

  constructor() {
    effect((onCleanup) => {
      const namespace = this.ns.current();
      // Kept as a fallback: if the SSE connection below drops silently, the page still catches up
      // within one polling interval instead of going stale forever.
      this.refresh.tick();
      void this.load(namespace);

      const stop = this.live.watch(
        `/api/v1/namespaces/${encodeURIComponent(namespace)}/topology/stream`,
        () => void this.load(namespace),
      );
      onCleanup(stop);
    });
  }

  async load(namespace = this.ns.current()): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    this.selected.set(null);
    try {
      this.data.set(await firstValueFrom(this.api.topology(namespace)));
    } catch (e) {
      const err = e as { status?: number; error?: { detail?: string } };
      this.error.set(err?.status === 403 ? 'errors.forbidden' : 'common.error');
    } finally {
      this.loading.set(false);
    }
  }

  nodeColour(node: TopologyNode): string {
    switch (node.health) {
      case 'healthy':
        return 'var(--color-ok)';
      case 'degraded':
      case 'unknown':
        return 'var(--color-warn)';
      case 'down':
        return 'var(--color-error)';
      default:
        return 'var(--color-idle)';
    }
  }
}

function countByColumn(placed: Placed[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const p of placed) {
    const key = p.kind === 'Secret' ? 'ConfigMap' : p.kind;
    counts[key] = (counts[key] ?? 0) + 1;
  }
  return counts;
}
