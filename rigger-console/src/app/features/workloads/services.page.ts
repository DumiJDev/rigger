import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { Observable } from 'rxjs';
import { ResourceResponse } from '../../core/api.models';
import { DataState } from '../../shared/data-state';
import { ListToolbar } from '../../shared/list-toolbar';
import { PageHeader } from '../../shared/page-header';
import { RowAction, RowMenu } from '../../shared/row-menu';
import { ResourceListPage } from './resource-page.base';

interface ServicePort {
  port: number;
  targetPort: number;
  protocol: string;
}

@Component({
  selector: 'r-services',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, DataState, ListToolbar, RowMenu],
  template: `
    <ng-container *transloco="let t">
      <r-page-header [title]="t('services.title')" [subtitle]="t('services.subtitle')">
      </r-page-header>

      <r-list-toolbar
        [(query)]="query"
        [total]="items().length"
        [shown]="visible().length"
        [placeholder]="t('services.search')"
      />

      <r-data-state
        [loading]="loading()"
        [error]="error() ? t(error()!) : null"
        [empty]="!visible().length"
        [emptyMessage]="filteredOut() ? t('common.noMatches') : t('services.empty')"
        (retry)="load()"
      >
        <div class="surface table-wrap">
          <table class="data">
            <thead>
              <tr>
                <th class="sortable" [attr.aria-sort]="ariaSort('name')" (click)="sortBy('name')">
                  {{ t('common.name') }}
                </th>
                <th class="sortable" [attr.aria-sort]="ariaSort('type')" (click)="sortBy('type')">
                  {{ t('common.type') }}
                </th>
                <th class="sortable" [attr.aria-sort]="ariaSort('ports')" (click)="sortBy('ports')">
                  {{ t('services.ports') }}
                </th>
                <th class="sortable" [attr.aria-sort]="ariaSort('appliedBy')" (click)="sortBy('appliedBy')">
                  {{ t('common.appliedBy') }}
                </th>
                <th class="w-10"><span class="sr-only">{{ t('common.actions') }}</span></th>
              </tr>
            </thead>
            <tbody>
              @for (item of visible(); track item.name) {
                <tr>
                  <td class="font-medium">{{ item.name }}</td>
                  <td class="muted">{{ type(item) }}</td>
                  <td class="tabular-nums">{{ ports(item) }}</td>
                  <td class="muted">{{ item.appliedBy || '—' }}</td>
                  <td class="text-right">
                    <r-row-menu
                      [actions]="actionsFor()"
                      [disabled]="busyItem() === item.name"
                      (selected)="confirming.set(item.name)"
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
            <p class="text-sm">{{ t('services.deleteConfirm', { name }) }}</p>
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
export class ServicesPage extends ResourceListPage {
  protected readonly pathKind = 'services';
  protected readonly rbacKind = 'Service';
  readonly confirming = signal<string | null>(null);

  constructor() {
    super();
    this.watchNamespace();
  }

  protected fetch(namespace: string): Observable<ResourceResponse[]> {
    return this.api.services(namespace);
  }

  protected override searchText(item: ResourceResponse): string {
    return `${super.searchText(item)} ${this.type(item)} ${this.ports(item)}`;
  }

  protected override sortValue(item: ResourceResponse, key: string): string | number | undefined {
    switch (key) {
      case 'type':  return this.type(item);
      // First published port, so sorting groups by the port an operator would actually connect to.
      case 'ports': return this.firstPort(item);
      default:      return super.sortValue(item, key);
    }
  }

  /** Undefined rather than 0 for a portless Service, so those sort last instead of first. */
  private firstPort(item: ResourceResponse): number | undefined {
    return (this.specValue<ServicePort[]>(item, 'ports') ?? [])[0]?.port;
  }

  /** Delete is the only action these kinds support today; the menu grows when the API does. */
  actionsFor(): RowAction[] {
    return this.canDelete()
      ? [{ id: 'delete', labelKey: 'common.delete', icon: 'trash', danger: true }]
      : [];
  }

  type(item: ResourceResponse): string {
    return this.specValue<string>(item, 'type') ?? 'CLUSTER_IP';
  }

  ports(item: ResourceResponse): string {
    const ports = this.specValue<ServicePort[]>(item, 'ports') ?? [];
    if (!ports.length) return '—';
    return ports.map((p) => `${p.port}→${p.targetPort}/${p.protocol}`).join(', ');
  }
}
