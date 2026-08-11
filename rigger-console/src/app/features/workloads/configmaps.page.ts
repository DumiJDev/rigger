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
  selector: 'r-configmaps',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, DataState, ListToolbar, RowMenu, DetailDrawer],
  template: `
    <ng-container *transloco="let t">
      <r-page-header [title]="t('configmaps.title')" [subtitle]="t('configmaps.subtitle')">
      </r-page-header>

      <r-list-toolbar
        [(query)]="query"
        [total]="items().length"
        [shown]="visible().length"
        [placeholder]="t('configmaps.search')"
      />

      <r-data-state
        [loading]="loading()"
        [error]="error() ? t(error()!) : null"
        [empty]="!visible().length"
        [emptyMessage]="filteredOut() ? t('common.noMatches') : t('configmaps.empty')"
        (retry)="load()"
      >
        <div class="surface table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th class="sortable" [attr.aria-sort]="ariaSort('name')" (click)="sortBy('name')">
                  {{ t('common.name') }}
                </th>
                <th class="sortable" [attr.aria-sort]="ariaSort('keys')" (click)="sortBy('keys')">
                  {{ t('configmaps.keys') }}
                </th>
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
      @if (viewing(); as item) {
        <r-detail-drawer [resource]="item" (closed)="viewing.set(null)" />
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

  protected override searchText(item: ResourceResponse): string {
    return `${super.searchText(item)} ${this.keys(item).join(' ')}`;
  }

  protected override sortValue(item: ResourceResponse, key: string): string | number | undefined {
    // By key count, not by the joined key names: "how much is in here" is the useful ordering.
    return key === 'keys' ? this.keys(item).length : super.sortValue(item, key);
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

  /** Key names only — values are shown on purpose nowhere, to keep parity with Secrets. */
  keys(item: ResourceResponse): string[] {
    const data = this.specValue<Record<string, string>>(item, 'data') ?? {};
    return Object.keys(data);
  }
}
