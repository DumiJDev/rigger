import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { Observable } from 'rxjs';
import { ResourceResponse } from '../../core/api.models';
import { DataState } from '../../shared/data-state';
import { PageHeader } from '../../shared/page-header';
import { ResourceListPage } from './resource-page.base';

@Component({
  selector: 'r-configmaps',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, DataState],
  template: `
    <ng-container *transloco="let t">
      <r-page-header [title]="t('configmaps.title')" [subtitle]="t('configmaps.subtitle')">
        <button type="button" class="btn btn-ghost" (click)="load()">{{ t('common.refresh') }}</button>
      </r-page-header>

      <r-data-state
        [loading]="loading()"
        [error]="error() ? t(error()!) : null"
        [empty]="!items().length"
        [emptyMessage]="t('configmaps.empty')"
        (retry)="load()"
      >
        <div class="surface table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th>{{ t('common.name') }}</th>
                <th>{{ t('configmaps.keys') }}</th>
                <th>{{ t('common.appliedBy') }}</th>
                <th class="text-right">{{ t('common.actions') }}</th>
              </tr>
            </thead>
            <tbody>
              @for (item of items(); track item.name) {
                <tr>
                  <td class="font-medium">{{ item.name }}</td>
                  <td>
                    <div class="flex flex-wrap gap-1">
                      @for (key of keys(item); track key) {
                        <span
                          class="rounded px-1.5 py-0.5 font-mono text-xs"
                          style="background-color: var(--surface-sunken)"
                          >{{ key }}</span
                        >
                      } @empty {
                        <span class="muted">—</span>
                      }
                    </div>
                  </td>
                  <td class="muted">{{ item.appliedBy || '—' }}</td>
                  <td>
                    <div class="flex justify-end">
                      @if (canDelete()) {
                        <button
                          type="button"
                          class="btn btn-danger py-1"
                          [disabled]="busyItem() === item.name"
                          (click)="confirming.set(item.name)"
                        >
                          {{ t('common.delete') }}
                        </button>
                      }
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </r-data-state>

      @if (confirming(); as name) {
        <div class="fixed inset-0 z-30 grid place-items-center bg-black/40 px-4" (click)="confirming.set(null)">
          <div class="surface w-full max-w-sm p-5" (click)="$event.stopPropagation()">
            <p class="text-sm">{{ t('configmaps.deleteConfirm', { name }) }}</p>
            <div class="mt-5 flex justify-end gap-2">
              <button type="button" class="btn btn-ghost" (click)="confirming.set(null)">
                {{ t('common.cancel') }}
              </button>
              <button type="button" class="btn btn-danger" (click)="confirming.set(null); remove(name)">
                {{ t('common.delete') }}
              </button>
            </div>
          </div>
        </div>
      }
    </ng-container>
  `,
})
export class ConfigMapsPage extends ResourceListPage {
  protected readonly pathKind = 'configmaps';
  protected readonly rbacKind = 'ConfigMap';
  readonly confirming = signal<string | null>(null);

  constructor() {
    super();
    this.watchNamespace();
  }

  protected fetch(namespace: string): Observable<ResourceResponse[]> {
    return this.api.configMaps(namespace);
  }

  /** Key names only — values are shown on purpose nowhere, to keep parity with Secrets. */
  keys(item: ResourceResponse): string[] {
    const data = this.specValue<Record<string, string>>(item, 'data') ?? {};
    return Object.keys(data);
  }
}
