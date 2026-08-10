import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { NodeResponse } from '../../core/api.models';
import { DataState } from '../../shared/data-state';
import { PageHeader } from '../../shared/page-header';
import { StatusBadge } from '../../shared/status-badge';

@Component({
  selector: 'r-nodes',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, DataState, StatusBadge, DatePipe],
  template: `
    <ng-container *transloco="let t">
      <r-page-header [title]="t('nodes.title')" [subtitle]="t('nodes.subtitle')">
        <button type="button" class="btn btn-ghost" (click)="load()">{{ t('common.refresh') }}</button>
      </r-page-header>

      <r-data-state
        [loading]="loading()"
        [error]="error() ? t(error()!) : null"
        [empty]="!nodes().length"
        [emptyMessage]="t('nodes.empty')"
        (retry)="load()"
      >
        <div class="surface table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th>{{ t('common.name') }}</th>
                <th>{{ t('nodes.ip') }}</th>
                <th>{{ t('common.role') }}</th>
                <th>{{ t('common.status') }}</th>
                <th>{{ t('nodes.primary') }}</th>
                <th>{{ t('nodes.lastSeen') }}</th>
              </tr>
            </thead>
            <tbody>
              @for (node of nodes(); track node.name) {
                <tr>
                  <td class="font-medium">{{ node.name }}</td>
                  <td class="muted tabular-nums">{{ node.ip }}</td>
                  <td>{{ node.role }}</td>
                  <td><r-status-badge [status]="node.status" [label]="node.status" /></td>
                  <td>{{ node.primary ? t('common.yes') : t('common.no') }}</td>
                  <td class="muted">
                    {{ node.lastSeenAt ? (node.lastSeenAt | date: 'short') : t('common.never') }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </r-data-state>
    </ng-container>
  `,
})
export class NodesPage {
  private readonly api = inject(ApiService);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly nodes = signal<NodeResponse[]>([]);

  constructor() {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.nodes.set(await firstValueFrom(this.api.nodes()));
    } catch (e) {
      const err = e as { status?: number };
      this.error.set(err?.status === 403 ? 'errors.forbidden' : 'common.error');
    } finally {
      this.loading.set(false);
    }
  }
}
