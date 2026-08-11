import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { Observable } from 'rxjs';
import { ResourceResponse } from '../../core/api.models';
import { DataState } from '../../shared/data-state';
import { ListToolbar } from '../../shared/list-toolbar';
import { PageHeader } from '../../shared/page-header';
import { DetailDrawer } from '../../shared/detail-drawer';
import { RowAction, RowMenu } from '../../shared/row-menu';
import { ResourceListPage } from './resource-page.base';

@Component({
  selector: 'r-secrets',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, DataState, ListToolbar, RowMenu, DetailDrawer],
  template: `
    <ng-container *transloco="let t">
      <r-page-header [title]="t('secrets.title')" [subtitle]="t('secrets.subtitle')">
      </r-page-header>

      <r-list-toolbar
        [(query)]="query"
        [total]="items().length"
        [shown]="visible().length"
        [placeholder]="t('secrets.search')"
      />

      <r-data-state
        [loading]="loading()"
        [error]="error() ? t(error()!) : null"
        [empty]="!visible().length"
        [emptyMessage]="filteredOut() ? t('common.noMatches') : t('secrets.empty')"
        (retry)="load()"
      >
        <div class="surface table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th class="sortable" [attr.aria-sort]="ariaSort('name')" (click)="sortBy('name')">
                  {{ t('common.name') }}
                </th>
                <th>{{ t('common.status') }}</th>
                <th class="sortable" [attr.aria-sort]="ariaSort('appliedBy')" (click)="sortBy('appliedBy')">
                  {{ t('common.appliedBy') }}
                </th>
                <th class="w-10"><span class="sr-only">{{ t('common.actions') }}</span></th>
              </tr>
            </thead>
            <tbody>
              @for (item of visible(); track item.name) {
                <tr class="clickable" (click)="openDetails(item)">
                  <td class="font-medium">{{ item.name }}</td>
                  <td>
                    <!-- The API never returns secret values; it substitutes a redaction marker.
                         Showing that plainly is more honest than rendering an empty cell. -->
                    <span
                      class="rounded px-1.5 py-0.5 font-mono text-xs"
                      style="background-color: var(--surface-sunken)"
                      >{{ t('secrets.redacted') }}</span
                    >
                  </td>
                  <td class="muted">{{ item.appliedBy || '—' }}</td>
                  <td class="text-right">
                    <r-row-menu
                      [actions]="actionsFor()"
                      [disabled]="busyItem() === item.name"
                      (selected)="onAction($event, item)"
                    />
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
            <p class="text-sm">{{ t('secrets.deleteConfirm', { name }) }}</p>
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
      @if (viewing(); as item) {
        <r-detail-drawer [resource]="item" (closed)="viewing.set(null)" />
      }
    </ng-container>
  `,
})
export class SecretsPage extends ResourceListPage {
  protected readonly pathKind = 'secrets';
  protected readonly rbacKind = 'Secret';
  readonly confirming = signal<string | null>(null);

  constructor() {
    super();
    this.watchNamespace();
  }

  protected fetch(namespace: string): Observable<ResourceResponse[]> {
    return this.api.secrets(namespace);
  }

  /** Details, plus delete when allowed — the only mutation these kinds support today. */
  actionsFor(): RowAction[] {
    const actions: RowAction[] = [this.detailsAction];
    if (this.canDelete()) {
      actions.push({ id: 'delete', labelKey: 'common.delete', icon: 'trash', danger: true });
    }
    return actions;
  }

  onAction(id: string, item: ResourceResponse): void {
    if (id === 'details') this.openDetails(item);
    if (id === 'delete') this.confirming.set(item.name);
  }
}
