import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { RefreshService } from '../../core/refresh.service';
import { AuditResponse } from '../../core/api.models';
import { DataState } from '../../shared/data-state';
import { PageHeader } from '../../shared/page-header';
import { StatusBadge } from '../../shared/status-badge';

@Component({
  selector: 'r-audit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, DataState, StatusBadge, DatePipe],
  template: `
    <ng-container *transloco="let t">
      <r-page-header [title]="t('audit.title')" [subtitle]="t('audit.subtitle')">
      </r-page-header>

      <r-data-state
        [loading]="loading()"
        [error]="error() ? t(error()!) : null"
        [empty]="!entries().length"
        [emptyMessage]="t('audit.empty')"
        (retry)="load()"
      >
        <div class="surface table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th>{{ t('common.time') }}</th>
                <th>{{ t('audit.identity') }}</th>
                <th>{{ t('audit.action') }}</th>
                <th>{{ t('audit.resource') }}</th>
                <th>{{ t('common.namespace') }}</th>
                <th>{{ t('audit.sourceIp') }}</th>
                <th>{{ t('audit.result') }}</th>
              </tr>
            </thead>
            <tbody>
              @for (e of entries(); track e.id) {
                <tr>
                  <td class="muted whitespace-nowrap tabular-nums">
                    {{ e.timestamp | date: 'short' }}
                  </td>
                  <td>
                    <div class="font-medium">{{ e.identityName }}</div>
                    <div class="muted text-xs">{{ e.identityRole }}</div>
                  </td>
                  <td>{{ e.action }}</td>
                  <td class="muted">
                    {{ e.resourceKind ? e.resourceKind + '/' + (e.resourceName || '') : '—' }}
                  </td>
                  <td class="muted">{{ e.namespace || '—' }}</td>
                  <td class="muted tabular-nums">{{ e.sourceIp }}</td>
                  <td><r-status-badge [status]="e.result" [label]="e.result" /></td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <div class="mt-4 flex items-center justify-between">
          <span class="muted text-sm">{{ page() + 1 }} / {{ totalPages() || 1 }}</span>
          <div class="flex gap-2">
            <button
              type="button"
              class="btn btn-ghost"
              [disabled]="page() === 0"
              (click)="go(page() - 1)"
            >
              ‹
            </button>
            <button
              type="button"
              class="btn btn-ghost"
              [disabled]="page() + 1 >= totalPages()"
              (click)="go(page() + 1)"
            >
              ›
            </button>
          </div>
        </div>
      </r-data-state>
    </ng-container>
  `,
})
export class AuditPage {
  private readonly api = inject(ApiService);
  private readonly refresh = inject(RefreshService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly entries = signal<AuditResponse[]>([]);
  readonly page = signal(0);
  readonly totalPages = signal(0);

  constructor() {
    // Tracks the masthead's refresh tick, so this page follows the chosen interval without
    // needing a Refresh button of its own.
    effect(() => {
      this.refresh.tick();
      void this.load();
    });
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const res = await firstValueFrom(this.api.audit(undefined, this.page(), 50));
      this.entries.set(res.content);
      this.totalPages.set(res.totalPages);
    } catch (e) {
      const err = e as { status?: number };
      this.error.set(err?.status === 403 ? 'errors.forbidden' : 'common.error');
    } finally {
      this.loading.set(false);
    }
  }

  go(page: number): void {
    this.page.set(Math.max(0, page));
    void this.load();
  }
}
