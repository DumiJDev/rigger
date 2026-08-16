import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { Observable, firstValueFrom } from 'rxjs';
import { ResourceResponse } from '../../core/api.models';
import { DataState } from '../../shared/data-state';
import { Icon } from '../../shared/icon';
import { KvEditor, KvPair, kvPairsToMap } from '../../shared/kv-editor';
import { ListToolbar } from '../../shared/list-toolbar';
import { PageHeader } from '../../shared/page-header';
import { DetailDrawer } from '../../shared/detail-drawer';
import { RowAction, RowMenu } from '../../shared/row-menu';
import { toYaml } from '../../shared/yaml';
import { ResourceListPage } from './resource-page.base';

interface ServicePort {
  port: number;
  targetPort: number;
  protocol: string;
}

@Component({
  selector: 'r-services',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoDirective,
    PageHeader,
    DataState,
    ListToolbar,
    RowMenu,
    DetailDrawer,
    KvEditor,
    FormsModule,
    Icon,
  ],
  template: `
    <ng-container *transloco="let t">
      <r-page-header [title]="t('services.title')" [subtitle]="t('services.subtitle')">
      </r-page-header>

      <r-list-toolbar
        [(query)]="query"
        [total]="items().length"
        [shown]="visible().length"
        [placeholder]="t('services.search')"
      >
        @if (canCreate()) {
          <button type="button" class="btn btn-primary" (click)="openCreate()">
            {{ t('services.create') }}
          </button>
        }
      </r-list-toolbar>

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
                <tr class="clickable" (click)="openDetails(item)">
                  <td class="font-medium">{{ item.name }}</td>
                  <td class="muted">{{ type(item) }}</td>
                  <td class="tabular-nums">{{ ports(item) }}</td>
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
      @if (viewing(); as item) {
        <r-detail-drawer [resource]="item" (closed)="viewing.set(null)" />
      }

      @if (creating()) {
        <div class="fixed inset-0 z-30 grid place-items-center bg-black/40 px-4" (click)="closeCreate()">
          <div class="surface w-full max-w-lg p-5" (click)="$event.stopPropagation()">
            <h2 class="text-base font-semibold">{{ t('services.createTitle') }}</h2>

            <label class="mt-4 mb-1.5 block text-sm font-medium" for="svc-name">
              {{ t('common.name') }}
            </label>
            <input id="svc-name" class="input" [(ngModel)]="newName" [disabled]="creatingBusy()" />

            <label class="mt-4 mb-1.5 block text-sm font-medium">{{ t('services.selector') }}</label>
            <r-kv-editor
              [(pairs)]="selectorPairs"
              [keyPlaceholder]="t('common.key')"
              [valuePlaceholder]="t('common.value')"
              [addLabel]="t('services.addSelector')"
              [removeLabel]="t('common.remove')"
              [disabled]="creatingBusy()"
            />

            <label class="mt-4 mb-1.5 block text-sm font-medium">{{ t('services.ports') }}</label>
            <div class="space-y-2">
              @for (row of newPorts; track $index) {
                <div class="flex items-center gap-2">
                  <input
                    type="number"
                    min="1"
                    class="input w-24"
                    [placeholder]="t('services.port')"
                    [(ngModel)]="row.port"
                    [disabled]="creatingBusy()"
                  />
                  <input
                    type="number"
                    min="1"
                    class="input w-24"
                    [placeholder]="t('services.targetPort')"
                    [(ngModel)]="row.targetPort"
                    [disabled]="creatingBusy()"
                  />
                  <select class="input w-28" [(ngModel)]="row.protocol" [disabled]="creatingBusy()">
                    <option value="TCP">TCP</option>
                    <option value="UDP">UDP</option>
                  </select>
                  <button
                    type="button"
                    class="btn-icon"
                    [attr.aria-label]="t('common.remove')"
                    [disabled]="creatingBusy()"
                    (click)="removePort($index)"
                  >
                    <r-icon name="x" [size]="14" />
                  </button>
                </div>
              }
              <button type="button" class="btn btn-ghost text-xs" [disabled]="creatingBusy()" (click)="addPort()">
                <r-icon name="plus" [size]="14" />
                {{ t('services.addPort') }}
              </button>
            </div>

            <label class="mt-4 mb-1.5 block text-sm font-medium" for="svc-type">
              {{ t('common.type') }}
            </label>
            <select id="svc-type" class="input" [(ngModel)]="newType" [disabled]="creatingBusy()">
              <option value="ClusterIP">ClusterIP</option>
              <option value="LoadBalancer">LoadBalancer</option>
            </select>

            @if (newType === 'LoadBalancer') {
              <div class="mt-4 space-y-3 rounded-lg p-3" style="background-color: var(--surface-sunken)">
                <div>
                  <label class="mb-1.5 block text-sm font-medium" for="svc-host">
                    {{ t('services.ingressHost') }}
                  </label>
                  <input
                    id="svc-host"
                    class="input font-mono text-xs"
                    placeholder="shop.example.com"
                    [(ngModel)]="ingressHost"
                    [disabled]="creatingBusy()"
                  />
                </div>
                <div>
                  <label class="mb-1.5 block text-sm font-medium" for="svc-path">
                    {{ t('services.ingressPath') }}
                  </label>
                  <input
                    id="svc-path"
                    class="input font-mono text-xs"
                    placeholder="/api"
                    [(ngModel)]="ingressPath"
                    [disabled]="creatingBusy()"
                  />
                </div>
                <label class="flex cursor-pointer items-center gap-2 text-sm">
                  <input type="checkbox" [(ngModel)]="ingressTls" [disabled]="creatingBusy()" />
                  {{ t('services.ingressTls') }}
                </label>
              </div>
            }

            @if (createError(); as msg) {
              <p
                class="mt-4 rounded-lg px-3 py-2 text-sm"
                style="background-color: color-mix(in oklch, var(--color-error) 12%, transparent); color: var(--color-error)"
              >
                {{ msg }}
              </p>
            }

            <div class="mt-5 flex justify-end gap-2">
              <button type="button" class="btn btn-ghost" [disabled]="creatingBusy()" (click)="closeCreate()">
                {{ t('common.cancel') }}
              </button>
              <button
                type="button"
                class="btn btn-primary"
                [disabled]="creatingBusy() || !canSubmitCreate()"
                (click)="submitCreate()"
              >
                {{ creatingBusy() ? t('common.loading') : t('common.create') }}
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

  private readonly transloco = inject(TranslocoService);

  readonly creating = signal(false);
  readonly creatingBusy = signal(false);
  readonly createError = signal<string | null>(null);
  newName = '';
  selectorPairs: KvPair[] = [];
  newPorts: ServicePort[] = [];
  newType: 'ClusterIP' | 'LoadBalancer' = 'ClusterIP';
  ingressHost = '';
  ingressPath = '';
  ingressTls = false;

  constructor() {
    super();
    this.watchNamespace();
  }

  canCreate(): boolean {
    return this.auth.can('apply', this.rbacKind);
  }

  openCreate(): void {
    this.newName = '';
    this.selectorPairs = [];
    this.newPorts = [{ port: 80, targetPort: 80, protocol: 'TCP' }];
    this.newType = 'ClusterIP';
    this.ingressHost = '';
    this.ingressPath = '';
    this.ingressTls = false;
    this.createError.set(null);
    this.creating.set(true);
  }

  closeCreate(): void {
    if (this.creatingBusy()) return;
    this.creating.set(false);
  }

  addPort(): void {
    this.newPorts = [...this.newPorts, { port: 80, targetPort: 80, protocol: 'TCP' }];
  }

  removePort(index: number): void {
    this.newPorts = this.newPorts.filter((_, i) => i !== index);
  }

  canSubmitCreate(): boolean {
    return (
      this.newName.trim().length > 0 &&
      Object.keys(kvPairsToMap(this.selectorPairs)).length > 0 &&
      this.newPorts.length > 0 &&
      (this.newType !== 'LoadBalancer' || this.ingressHost.trim().length > 0)
    );
  }

  async submitCreate(): Promise<void> {
    this.creatingBusy.set(true);
    this.createError.set(null);
    try {
      const spec: Record<string, unknown> = {
        selector: kvPairsToMap(this.selectorPairs),
        ports: this.newPorts,
        type: this.newType,
      };
      if (this.newType === 'LoadBalancer' && this.ingressHost.trim()) {
        spec['ingress'] = {
          host: this.ingressHost.trim(),
          path: this.ingressPath.trim() || null,
          tls: this.ingressTls,
        };
      }
      const manifest = {
        apiVersion: 'rigger.io/v1',
        kind: 'Service',
        metadata: { name: this.newName.trim(), namespace: this.ns.current() },
        spec,
      };
      await firstValueFrom(this.api.apply(this.ns.current(), toYaml(manifest), false));
      this.creating.set(false);
      await this.load();
    } catch (e) {
      const err = e as { status?: number; error?: { detail?: string } };
      this.createError.set(
        err?.status === 403
          ? this.transloco.translate('errors.forbidden')
          : (err?.error?.detail ?? this.transloco.translate('common.error')),
      );
    } finally {
      this.creatingBusy.set(false);
    }
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

  type(item: ResourceResponse): string {
    return this.specValue<string>(item, 'type') ?? 'CLUSTER_IP';
  }

  ports(item: ResourceResponse): string {
    const ports = this.specValue<ServicePort[]>(item, 'ports') ?? [];
    if (!ports.length) return '—';
    return ports.map((p) => `${p.port}→${p.targetPort}/${p.protocol}`).join(', ');
  }
}
