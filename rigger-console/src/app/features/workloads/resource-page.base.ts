import { effect, inject, signal } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { ResourceResponse } from '../../core/api.models';
import { NamespaceService } from '../../core/namespace.service';
import { RefreshService } from '../../core/refresh.service';

/**
 * Shared loading/deleting behaviour for the four resource list pages, which differ only in which
 * endpoint they call and which columns they render.
 *
 * <p>Not a component — the pages keep their own templates so each can show fields that actually
 * matter for its kind (ports for Services, key names for ConfigMaps) instead of a lowest-common
 * table.
 */
export abstract class ResourceListPage {
  protected readonly api = inject(ApiService);
  protected readonly ns = inject(NamespaceService);
  protected readonly refresh = inject(RefreshService);
  readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly items = signal<ResourceResponse[]>([]);
  readonly busyItem = signal<string | null>(null);

  /** Path segment used by the delete endpoint: deployments | services | configmaps | secrets. */
  protected abstract readonly pathKind: string;
  /** RBAC resource name: Deployment | Service | ConfigMap | Secret. */
  protected abstract readonly rbacKind: string;
  protected abstract fetch(namespace: string): Observable<ResourceResponse[]>;

  /**
   * Reloads when the namespace changes and on every auto-refresh tick. Both are read inside the
   * same effect, so a page opts into the masthead's refresh interval by doing nothing at all.
   */
  protected watchNamespace(): void {
    effect(() => {
      const namespace = this.ns.current();
      this.refresh.tick();
      void this.load(namespace);
    });
  }

  async load(namespace = this.ns.current()): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.items.set(await firstValueFrom(this.fetch(namespace)));
    } catch (e) {
      const err = e as { status?: number };
      this.error.set(err?.status === 403 ? 'errors.forbidden' : 'common.error');
    } finally {
      this.loading.set(false);
    }
  }

  canDelete(): boolean {
    return this.auth.can('delete', this.rbacKind);
  }

  async remove(name: string): Promise<void> {
    this.busyItem.set(name);
    try {
      await firstValueFrom(this.api.deleteResource(this.ns.current(), this.pathKind, name));
      // Drop it locally rather than refetching: reconciliation is asynchronous, so an immediate
      // refetch can still return the row and make the delete look like it failed.
      this.items.update((list) => list.filter((i) => i.name !== name));
    } catch (e) {
      const err = e as { status?: number };
      this.error.set(err?.status === 403 ? 'errors.forbidden' : 'common.error');
    } finally {
      this.busyItem.set(null);
    }
  }

  /** Reads a field out of the untyped spec blob the API returns. */
  protected specValue<T>(item: ResourceResponse, key: string): T | undefined {
    return item.spec?.[key] as T | undefined;
  }
}
