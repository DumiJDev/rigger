import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective } from '@jsverse/transloco';
import { Observable, firstValueFrom } from 'rxjs';
import { ResourceResponse } from '../../core/api.models';
import { DataState } from '../../shared/data-state';
import { PageHeader } from '../../shared/page-header';
import { ResourceListPage } from './resource-page.base';

@Component({
  selector: 'r-deployments',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, DataState, FormsModule],
  templateUrl: './deployments.page.html',
})
export class DeploymentsPage extends ResourceListPage {
  protected readonly pathKind = 'deployments';
  protected readonly rbacKind = 'Deployment';

  readonly scaling = signal<ResourceResponse | null>(null);
  readonly confirming = signal<string | null>(null);
  scaleValue = 1;

  constructor() {
    super();
    this.watchNamespace();
  }

  protected fetch(namespace: string): Observable<ResourceResponse[]> {
    return this.api.deployments(namespace);
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
