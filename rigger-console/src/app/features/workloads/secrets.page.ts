import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { Observable, firstValueFrom } from 'rxjs';
import { ResourceResponse } from '../../core/api.models';
import { DataState } from '../../shared/data-state';
import { KvEditor, KvPair, kvPairsToMap } from '../../shared/kv-editor';
import { ListToolbar } from '../../shared/list-toolbar';
import { PageHeader } from '../../shared/page-header';
import { DetailDrawer } from '../../shared/detail-drawer';
import { RowAction, RowMenu } from '../../shared/row-menu';
import { toYaml } from '../../shared/yaml';
import { ResourceListPage } from './resource-page.base';

@Component({
  selector: 'r-secrets',
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
  ],
  template: `
    <ng-container *transloco="let t">
      <r-page-header [title]="t('secrets.title')" [subtitle]="t('secrets.subtitle')">
      </r-page-header>

      <r-list-toolbar
        [(query)]="query"
        [total]="items().length"
        [shown]="visible().length"
        [placeholder]="t('secrets.search')"
      >
        @if (canCreate()) {
          <button type="button" class="btn btn-primary" (click)="openCreate()">
            {{ t('secrets.create') }}
          </button>
        }
      </r-list-toolbar>

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

      @if (creating()) {
        <div class="fixed inset-0 z-30 grid place-items-center bg-black/40 px-4" (click)="closeCreate()">
          <div class="surface w-full max-w-lg p-5" (click)="$event.stopPropagation()">
            <h2 class="text-base font-semibold">{{ t('secrets.createTitle') }}</h2>

            <label class="mt-4 mb-1.5 block text-sm font-medium" for="secret-name">
              {{ t('common.name') }}
            </label>
            <input id="secret-name" class="input" [(ngModel)]="newName" [disabled]="creatingBusy()" />

            <label class="mt-4 mb-1.5 block text-sm font-medium">{{ t('secrets.data') }}</label>
            <r-kv-editor
              [(pairs)]="dataPairs"
              [keyPlaceholder]="t('common.key')"
              [valuePlaceholder]="t('common.value')"
              valueType="password"
              [addLabel]="t('secrets.addKey')"
              [removeLabel]="t('common.remove')"
              [disabled]="creatingBusy()"
            />
            <p class="muted mt-1 text-xs">{{ t('secrets.dataHelp') }}</p>

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
                [disabled]="creatingBusy() || !newName.trim()"
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
export class SecretsPage extends ResourceListPage {
  protected readonly pathKind = 'secrets';
  protected readonly rbacKind = 'Secret';
  readonly confirming = signal<string | null>(null);

  private readonly transloco = inject(TranslocoService);

  readonly creating = signal(false);
  readonly creatingBusy = signal(false);
  readonly createError = signal<string | null>(null);
  newName = '';
  dataPairs: KvPair[] = [];

  constructor() {
    super();
    this.watchNamespace();
  }

  canCreate(): boolean {
    return this.auth.can('apply', this.rbacKind);
  }

  openCreate(): void {
    this.newName = '';
    this.dataPairs = [];
    this.createError.set(null);
    this.creating.set(true);
  }

  closeCreate(): void {
    if (this.creatingBusy()) return;
    this.creating.set(false);
  }

  async submitCreate(): Promise<void> {
    this.creatingBusy.set(true);
    this.createError.set(null);
    try {
      // Secret values are base64 in the YAML by contract (ManifestParser/SecretSpec expect it) —
      // the form takes plain text and encodes it here so the operator never has to `base64` by
      // hand. AES-256-GCM encryption at rest happens server-side, unaffected by this.
      const encoded: Record<string, string> = {};
      for (const [key, value] of Object.entries(kvPairsToMap(this.dataPairs))) {
        encoded[key] = base64EncodeUtf8(value);
      }
      const manifest = {
        apiVersion: 'rigger.io/v1',
        kind: 'Secret',
        metadata: { name: this.newName.trim(), namespace: this.ns.current() },
        spec: { data: encoded },
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

/** UTF-8-safe base64 encoding — plain `btoa` throws on any character outside Latin1. */
function base64EncodeUtf8(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary);
}
