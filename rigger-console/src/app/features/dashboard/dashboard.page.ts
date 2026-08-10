import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { Observable, catchError, firstValueFrom, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import {
  ClusterMetricName, ClusterMetrics, EventResponse, MetricSeries, Topology,
} from '../../core/api.models';
import { NamespaceService } from '../../core/namespace.service';
import { RefreshService } from '../../core/refresh.service';
import { DataState } from '../../shared/data-state';
import { Icon } from '../../shared/icon';
import { ChartSeries, LineChart } from '../../shared/line-chart';
import { PageHeader } from '../../shared/page-header';
import { Sparkline } from '../../shared/sparkline';
import { StatusBadge } from '../../shared/status-badge';

/** Window charted on this page. An hour is short enough to still show a shape at 30s sampling. */
const WINDOW_MINUTES = 60;

/** Deployments charted at once. Beyond this the legend is longer than the chart. */
const MAX_CHARTED_DEPLOYMENTS = 6;

/** The cluster series each KPI panel plots beneath its number. */
const KPI_SERIES: ClusterMetricName[] = [
  'nodes.active',
  'replicas.running',
  'resources.deployments',
];

@Component({
  selector: 'r-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoDirective, PageHeader, DataState, StatusBadge, RouterLink, DatePipe,
    Sparkline, LineChart, Icon,
  ],
  templateUrl: './dashboard.page.html',
})
export class DashboardPage {
  private readonly api = inject(ApiService);
  private readonly ns = inject(NamespaceService);
  private readonly refresh = inject(RefreshService);
  readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly metrics = signal<ClusterMetrics | null>(null);
  readonly topology = signal<Topology | null>(null);
  readonly events = signal<EventResponse[]>([]);

  /** Keyed by metric name; absent or short means the sparkline renders its empty state. */
  readonly history = signal<Record<string, number[]>>({});
  readonly cpuSeries = signal<ChartSeries[]>([]);

  readonly health = computed(() => {
    const nodes = this.topology()?.nodes.filter((n) => n.kind === 'Deployment') ?? [];
    const of_ = (h: string) => nodes.filter((n) => n.health === h).length;
    return {
      total: nodes.length,
      healthy: of_('healthy'),
      degraded: of_('degraded'),
      down: of_('down'),
      unknown: of_('unknown'),
    };
  });

  /** Rows for the health panel: only states that actually occur, so an empty row is never drawn. */
  readonly healthRows = computed(() => {
    const h = this.health();
    return (
      [
        { key: 'healthy', count: h.healthy },
        { key: 'degraded', count: h.degraded },
        { key: 'down', count: h.down },
        { key: 'unknown', count: h.unknown },
      ] as const
    )
      .filter((r) => r.count > 0)
      .map((r) => ({ ...r, percent: h.total ? (r.count / h.total) * 100 : 0 }));
  });

  constructor() {
    // Reloads whenever the namespace changes — the workload half of this page is namespaced.
    effect(() => {
      const namespace = this.ns.current();
      this.refresh.tick();
      void this.load(namespace);
    });
  }

  async load(namespace = this.ns.current()): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      // Cluster metrics are admin-only; a scoped user still gets a useful page without them
      // rather than an error, so that call is allowed to fail on its own.
      const [topology, events] = await Promise.all([
        firstValueFrom(this.api.topology(namespace)),
        firstValueFrom(this.api.events(undefined, 0, 12)),
      ]);
      this.topology.set(topology);
      this.events.set(events.content);

      if (this.auth.isClusterAdmin()) {
        this.metrics.set(await this.optional(this.api.clusterMetrics()));
        await this.loadClusterHistory();
      }
      await this.loadCpuHistory(namespace);
    } catch (e) {
      this.error.set(describe(e));
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Charts are supplementary: a page that renders its numbers but 403s or errors on a series must
   * still render. So every history call goes through {@link optional} and a missing series simply
   * leaves the sparkline in its empty state.
   */
  private async loadClusterHistory(): Promise<void> {
    const results = await Promise.all(
      KPI_SERIES.map((metric) =>
        this.optional(this.api.metricSeries(metric, { minutes: WINDOW_MINUTES })),
      ),
    );
    const next: Record<string, number[]> = {};
    for (const series of results) {
      if (series) next[series.metric] = series.points.map((p) => p.v);
    }
    this.history.set(next);
  }

  private async loadCpuHistory(namespace: string): Promise<void> {
    // Ask which Deployments actually have samples rather than deriving names from the resource
    // list — a Deployment applied a minute ago has no history yet and would chart as an empty line.
    const names = await this.optional(
      this.api.metricSeriesNames(namespace, 'deployment.cpu', WINDOW_MINUTES),
    );
    if (!names?.length) {
      this.cpuSeries.set([]);
      return;
    }
    const charted = names.slice(0, MAX_CHARTED_DEPLOYMENTS);
    const series = await Promise.all(
      charted.map((name) =>
        this.optional(
          this.api.metricSeries('deployment.cpu', { namespace, name, minutes: WINDOW_MINUTES }),
        ),
      ),
    );
    this.cpuSeries.set(
      series
        .filter((s): s is MetricSeries => s !== null)
        .map((s) => ({ label: s.name, points: s.points.map((p) => p.v) })),
    );
  }

  /** Resolves to null instead of throwing, so one supplementary call cannot fail the whole page. */
  private optional<T>(source: Observable<T>): Promise<T | null> {
    return firstValueFrom(source.pipe(catchError(() => of(null))));
  }

  sparkline(metric: ClusterMetricName): number[] {
    return this.history()[metric] ?? [];
  }
}

function describe(e: unknown): string {
  const err = e as { status?: number; error?: { detail?: string } };
  if (err?.status === 403) return 'errors.forbidden';
  return err?.error?.detail ?? 'common.error';
}
