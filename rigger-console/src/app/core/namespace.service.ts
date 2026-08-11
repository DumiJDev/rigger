import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './auth.service';

const KEY = 'rigger.namespace';

/**
 * The namespace every workload request is scoped to.
 *
 * <p>The backend has no server-side notion of a "current" namespace — it reads one out of each
 * request's URL path — so this is purely a client-side selection that gets interpolated into
 * workload calls. Cluster-scoped endpoints ignore it entirely.
 */
@Injectable({ providedIn: 'root' })
export class NamespaceService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  private readonly _available = signal<string[]>([]);
  readonly available = this._available.asReadonly();
  readonly current = signal<string>(localStorage.getItem(KEY) ?? 'default');

  private readonly _loadError = signal<string | null>(null);
  /**
   * Non-null when the last {@link load} could not produce a usable namespace list.
   *
   * <p>Exists because a failed load and "this identity owns exactly one namespace" used to look
   * identical from the outside — both left the picker as a static chip — so a broken or empty list
   * degraded silently into a console whose every table renders empty for no stated reason. The
   * value is diagnostic detail for a tooltip; the visible wording stays a translation key in the
   * template.
   */
  readonly loadError = this._loadError.asReadonly();

  /** A namespace-scoped identity can't switch, so the picker should render as a static label. */
  readonly canSwitch = computed(() => this.auth.isClusterAdmin());

  async load(): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.get<string[]>('/api/v1/namespaces'));
      this._available.set(list);

      // An empty list is NOT an error, however tempting it is to treat it as one. The server derives
      // this list from the resources that exist, so a cluster where nothing has been applied yet
      // legitimately returns []. Reporting that as a failure put a permanent "something went wrong"
      // in the masthead of every fresh install — a false alarm is worse than the silence it replaced,
      // because it trains the operator to ignore the one place real errors appear. Keeping the current
      // selection is right too: it is where an apply will land.
      if (!list.length) {
        this._loadError.set(null);
        return;
      }

      // Keep the stored selection only if it still exists; otherwise fall back to something real
      // so the console never sits on a namespace that returns nothing but empty tables.
      if (!list.includes(this.current())) {
        this.set(list.includes('default') ? 'default' : list[0]);
      }
      this._loadError.set(null);
    } catch (err: unknown) {
      // Deliberately don't wipe `_available`: a transient failure shouldn't collapse a picker that
      // was working a moment ago into a chip. The error signal is what tells the shell to explain.
      this._loadError.set(describe(err));
    }
  }

  set(namespace: string): void {
    this.current.set(namespace);
    localStorage.setItem(KEY, namespace);
  }
}

/** Best available detail for a tooltip — HttpErrorResponse first, then anything with a message. */
function describe(err: unknown): string {
  if (err instanceof HttpErrorResponse) return `${err.status} ${err.statusText}`.trim();
  return err instanceof Error ? err.message : 'GET /api/v1/namespaces failed';
}
