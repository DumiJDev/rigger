import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { PodResponse } from '../../core/api.models';
import { LogStreamService } from '../../core/log-stream.service';
import { NamespaceService } from '../../core/namespace.service';
import { RefreshService } from '../../core/refresh.service';
import { DataState } from '../../shared/data-state';
import { PageHeader } from '../../shared/page-header';
import { StatusBadge } from '../../shared/status-badge';

const MAX_LINES = 2000;

@Component({
  selector: 'r-pods',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, DataState, StatusBadge, FormsModule],
  templateUrl: './pods.page.html',
})
export class PodsPage {
  private readonly api = inject(ApiService);
  private readonly ns = inject(NamespaceService);
  private readonly refresh = inject(RefreshService);
  private readonly logs = inject(LogStreamService);
  readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly pods = signal<PodResponse[]>([]);

  readonly viewing = signal<PodResponse | null>(null);
  readonly lines = signal<string[]>([]);
  readonly streaming = signal(false);
  readonly logError = signal<string | null>(null);
  readonly ended = signal(false);
  filter = '';
  readonly filterSignal = signal('');

  private stop: (() => void) | null = null;

  readonly visibleLines = computed(() => {
    const needle = this.filterSignal().toLowerCase();
    const all = this.lines();
    return needle ? all.filter((l) => l.toLowerCase().includes(needle)) : all;
  });

  constructor() {
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
      this.pods.set(await firstValueFrom(this.api.pods(namespace)));
    } catch (e) {
      const err = e as { status?: number };
      this.error.set(err?.status === 403 ? 'errors.forbidden' : 'common.error');
    } finally {
      this.loading.set(false);
    }
  }

  openLogs(pod: PodResponse, follow = true): void {
    this.closeLogs();
    this.viewing.set(pod);
    this.lines.set([]);
    this.logError.set(null);
    this.ended.set(false);
    this.streaming.set(true);

    this.stop = this.logs.stream(
      this.ns.current(),
      pod.name,
      follow,
      (line) =>
        // Cap retained output: a followed stream is unbounded and would grow the DOM forever.
        this.lines.update((current) =>
          current.length >= MAX_LINES ? [...current.slice(1), line] : [...current, line],
        ),
      (message) => {
        this.logError.set(message);
        this.streaming.set(false);
      },
      () => {
        this.streaming.set(false);
        this.ended.set(true);
      },
    );
  }

  pauseLogs(): void {
    this.stop?.();
    this.stop = null;
    this.streaming.set(false);
  }

  closeLogs(): void {
    this.pauseLogs();
    this.viewing.set(null);
    this.lines.set([]);
    this.filter = '';
    this.filterSignal.set('');
  }

  onFilterChange(value: string): void {
    this.filterSignal.set(value);
  }
}
