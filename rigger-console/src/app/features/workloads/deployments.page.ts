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
  selector: 'r-deployments',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoDirective,
    PageHeader,
    DataState,
    FormsModule,
    ListToolbar,
    RowMenu,
    DetailDrawer,
    KvEditor,
  ],
  templateUrl: './deployments.page.html',
})
export class DeploymentsPage extends ResourceListPage {
  protected readonly pathKind = 'deployments';
  protected readonly rbacKind = 'Deployment';

  private readonly transloco = inject(TranslocoService);

  readonly scaling = signal<ResourceResponse | null>(null);
  readonly confirming = signal<string | null>(null);
  scaleValue = 1;

  readonly creating = signal(false);
  readonly creatingBusy = signal(false);
  readonly createError = signal<string | null>(null);
  readonly availableConfigMaps = signal<string[]>([]);
  readonly availableSecrets = signal<string[]>([]);
  newName = '';
  newImage = '';
  newReplicas = 1;
  selectorPairs: KvPair[] = [];
  envPairs: KvPair[] = [];
  selectedConfigMaps: string[] = [];
  selectedSecrets: string[] = [];

  constructor() {
    super();
    this.watchNamespace();
  }

  canCreate(): boolean {
    return this.auth.can('apply', this.rbacKind);
  }

  async openCreate(): Promise<void> {
    this.newName = '';
    this.newImage = '';
    this.newReplicas = 1;
    this.selectorPairs = [];
    this.envPairs = [];
    this.selectedConfigMaps = [];
    this.selectedSecrets = [];
    this.createError.set(null);
    this.creating.set(true);
    try {
      const [configMaps, secrets] = await Promise.all([
        firstValueFrom(this.api.configMaps(this.ns.current())),
        firstValueFrom(this.api.secrets(this.ns.current())),
      ]);
      this.availableConfigMaps.set(configMaps.map((c) => c.name));
      this.availableSecrets.set(secrets.map((s) => s.name));
    } catch {
      // Not fatal — the form still works without refs, just with an empty pick list.
      this.availableConfigMaps.set([]);
      this.availableSecrets.set([]);
    }
  }

  closeCreate(): void {
    if (this.creatingBusy()) return;
    this.creating.set(false);
  }

  toggleConfigMapRef(name: string, checked: boolean): void {
    this.selectedConfigMaps = checked
      ? [...this.selectedConfigMaps, name]
      : this.selectedConfigMaps.filter((n) => n !== name);
  }

  toggleSecretRef(name: string, checked: boolean): void {
    this.selectedSecrets = checked
      ? [...this.selectedSecrets, name]
      : this.selectedSecrets.filter((n) => n !== name);
  }

  async submitCreate(): Promise<void> {
    this.creatingBusy.set(true);
    this.createError.set(null);
    try {
      const env = this.envPairs
        .filter((p) => p.key.trim())
        .map((p) => ({ name: p.key.trim(), value: p.value }));
      const manifest = {
        apiVersion: 'rigger.io/v1',
        kind: 'Deployment',
        metadata: { name: this.newName.trim(), namespace: this.ns.current() },
        spec: {
          replicas: Math.max(0, Math.floor(this.newReplicas)),
          image: this.newImage.trim(),
          selector: kvPairsToMap(this.selectorPairs),
          env,
          configMapRefs: this.selectedConfigMaps,
          secretRefs: this.selectedSecrets,
        },
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
    return this.api.deployments(namespace);
  }

  /** Image is worth searching: "which of these runs nginx" is a question people actually ask. */
  protected override searchText(item: ResourceResponse): string {
    return `${super.searchText(item)} ${this.image(item)}`;
  }

  protected override sortValue(item: ResourceResponse, key: string): string | number | undefined {
    switch (key) {
      case 'image':    return this.image(item);
      case 'replicas': return this.replicas(item);
      // Boolean as a number, so sorting groups autoscaled Deployments together rather than
      // ordering them by the rendered word, which changes with the locale.
      case 'hpa':      return this.hasHpa(item) ? 1 : 0;
      default:         return super.sortValue(item, key);
    }
  }

  /** Built per row because the actions depend on the caller's permissions, which the base knows. */
  actionsFor(item: ResourceResponse): RowAction[] {
    const actions: RowAction[] = [this.detailsAction];
    if (this.auth.can('scale', 'Deployment')) {
      actions.push({ id: 'scale', labelKey: 'deployments.scale', icon: 'scale' });
    }
    if (this.canDelete()) {
      actions.push({ id: 'delete', labelKey: 'common.delete', icon: 'trash', danger: true });
    }
    return actions;
  }

  onAction(id: string, item: ResourceResponse): void {
    if (id === 'details') this.openDetails(item);
    if (id === 'scale') this.openScale(item);
    if (id === 'delete') this.confirming.set(item.name);
  }

  replicas(item: ResourceResponse): number {
    return this.specValue<number>(item, 'replicas') ?? 0;
  }
  image(item: ResourceResponse): string {
    return this.specValue<string>(item, 'image') ?? '—';
  }
  hasHpa(item: ResourceResponse): boolean {
    return this.specValue<unknown>(item, 'hpa') != null;
  }

  openScale(item: ResourceResponse): void {
    this.scaleValue = this.replicas(item);
    this.scaling.set(item);
  }

  async applyScale(): Promise<void> {
    const item = this.scaling();
    if (!item) return;
    const replicas = Math.max(0, Math.floor(this.scaleValue));
    this.busyItem.set(item.name);
    try {
      await firstValueFrom(this.api.scale(this.ns.current(), item.name, replicas));
      // Reflect it immediately; the operator reconciles onto Swarm within a cycle.
      this.items.update((list) =>
        list.map((i) => (i.name === item.name ? { ...i, spec: { ...i.spec, replicas } } : i)),
      );
      this.scaling.set(null);
    } catch (e) {
      const err = e as { status?: number };
      this.error.set(err?.status === 403 ? 'errors.forbidden' : 'common.error');
    } finally {
      this.busyItem.set(null);
    }
  }

  async confirmDelete(name: string): Promise<void> {
    this.confirming.set(null);
    await this.remove(name);
  }
}
