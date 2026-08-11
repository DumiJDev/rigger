import { computed, effect, inject, signal } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { ResourceResponse } from '../../core/api.models';
import { NamespaceService } from '../../core/namespace.service';
import { RefreshService } from '../../core/refresh.service';
import { RowAction } from '../../shared/row-menu';

export type SortDir = 'asc' | 'desc';

/**
 * Shared behaviour for the four resource list pages, which differ only in which endpoint they call
 * and which columns they render: loading, deleting, and now searching and sorting too.
 *
 * <p>Not a component — the pages keep their own templates so each can show fields that actually
 * matter for its kind (ports for Services, key names for ConfigMaps) instead of a lowest-common
 * table. Which is exactly why shared *behaviour* belongs here: search and sort were added once and
 * all four pages got them.
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

  readonly query = signal('');
  readonly sortKey = signal('name');
  readonly sortDir = signal<SortDir>('asc');

  /** The row whose detail drawer is open, or null. */
  readonly viewing = signal<ResourceResponse | null>(null);

  /**
   * Opens the detail drawer for a row.
   *
   * <p>Lives here rather than in each page for the same reason search and sort do: the drawer reads
   * only fields every kind has, so wiring it once gave all four pages details at the same time.
   * Bound both to the row itself and to a kebab entry — a row that shows a panel on click is the
   * expectation, and the explicit action is what makes that discoverable.
   */
  openDetails(item: ResourceResponse): void {
    this.viewing.set(item);
  }

  /**
   * Kebab entry every kind offers, first in the list. Shared so the four menus can't drift in label,
   * icon or position — the one thing a per-page copy always gets wrong eventually.
   */
  protected readonly detailsAction: RowAction = {
    id: 'details',
    labelKey: 'details.view',
    icon: 'file-code',
  };

  /**
   * What the table renders: the loaded rows, filtered and sorted.
   *
   * <p>Client-side, deliberately. The API returns a namespace's resources in one unpaged response
   * and these lists are tens of rows, not thousands — sorting on the server would mean a round trip
   * per column click for no benefit. If a namespace ever holds enough resources for this to matter,
   * the fix is paging the endpoint, not moving the comparison.
   */
  readonly visible = computed<ResourceResponse[]>(() => {
    const needle = this.query().trim().toLowerCase();
    const rows = needle
      ? this.items().filter((i) => this.searchText(i).toLowerCase().includes(needle))
      : [...this.items()];

    const key = this.sortKey();
    const factor = this.sortDir() === 'asc' ? 1 : -1;
    return rows.sort((a, b) => factor * compare(this.sortValue(a, key), this.sortValue(b, key)));
  });

  /** True when a filter is hiding rows, so the empty state can say so instead of "no resources". */
  readonly filteredOut = computed(() => this.items().length > 0 && this.visible().length === 0);

  /**
   * Clicking the active column flips direction; a different column sorts it ascending. Resetting to
   * ascending matters — carrying the previous direction over makes a new column look sorted wrongly.
   */
  sortBy(key: string): void {
    if (this.sortKey() === key) {
      this.sortDir.update((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      this.sortKey.set(key);
      this.sortDir.set('asc');
    }
  }

  /** For `aria-sort` on the header cell: screen readers announce the state, and the CSS tints it. */
  ariaSort(key: string): 'ascending' | 'descending' | 'none' {
    if (this.sortKey() !== key) return 'none';
    return this.sortDir() === 'asc' ? 'ascending' : 'descending';
  }

  /**
   * Text a search matches against. Name and who applied it cover every kind; a page adds its own
   * fields by overriding — Services their ports, ConfigMaps their key names.
   */
  protected searchText(item: ResourceResponse): string {
    return `${item.name} ${item.appliedBy ?? ''}`;
  }

  /**
   * Value to sort a column by. Pages override for their own columns and delegate here for the
   * shared ones, so a new column is one case in one method rather than a comparator per page.
   */
  protected sortValue(item: ResourceResponse, key: string): string | number | undefined {
    switch (key) {
      case 'name':      return item.name;
      case 'appliedBy': return item.appliedBy ?? '';
      default:          return undefined;
    }
  }

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
      // A drawer left open over a resource that no longer exists shows a spec nothing can act on.
      if (this.viewing()?.name === name) this.viewing.set(null);
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

/**
 * Undefined sorts last in both directions rather than being treated as empty, so rows missing a
 * value don't push real data down the list when sorting descending.
 */
function compare(a: string | number | undefined, b: string | number | undefined): number {
  if (a === undefined && b === undefined) return 0;
  if (a === undefined) return 1;
  if (b === undefined) return -1;
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  // Numeric collation so "web-2" comes before "web-10"; a plain string sort reverses them.
  return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: 'base' });
}
