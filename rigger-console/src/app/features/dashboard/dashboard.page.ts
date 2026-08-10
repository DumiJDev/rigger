import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { ClusterMetrics, EventResponse, Topology } from '../../core/api.models';
import { NamespaceService } from '../../core/namespace.service';
import { DataState } from '../../shared/data-state';
import { PageHeader } from '../../shared/page-header';
import { StatusBadge } from '../../shared/status-badge';

@Component({
  selector: 'r-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, DataState, StatusBadge, RouterLink, DatePipe],
  templateUrl: './dashboard.page.html',
})
export class DashboardPage {
  private readonly api = inject(ApiService);
  private readonly ns = inject(NamespaceService);
  readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly metrics = signal<ClusterMetrics | null>(null);
  readonly topology = signal<Topology | null>(null);
  readonly events = signal<EventResponse[]>([]);

  constructor() {
    // Reloads whenever the namespace changes — the workload half of this page is namespaced.
    effect(() => {
      const namespace = this.ns.current();
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
        firstValueFrom(this.api.events(undefined, 0, 8)),
      ]);
      this.topology.set(topology);
      this.events.set(events.content);

      if (this.auth.isClusterAdmin()) {
        try {
          this.metrics.set(await firstValueFrom(this.api.clusterMetrics()));
        } catch {
          this.metrics.set(null);
        }
      }
    } catch (e) {
      this.error.set(describe(e));
    } finally {
      this.loading.set(false);
    }
  }

  healthCounts(): { healthy: number; degraded: number; down: number; unknown: number } {
    const nodes = this.topology()?.nodes.filter((n) => n.kind === 'Deployment') ?? [];
    return {
      healthy: nodes.filter((n) => n.health === 'healthy').length,
      degraded: nodes.filter((n) => n.health === 'degraded').length,
      down: nodes.filter((n) => n.health === 'down').length,
      unknown: nodes.filter((n) => n.health === 'unknown').length,
    };
  }
}

function describe(e: unknown): string {
  const err = e as { status?: number; error?: { detail?: string } };
  if (err?.status === 403) return 'errors.forbidden';
  return err?.error?.detail ?? 'common.error';
}
