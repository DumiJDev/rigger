import { HttpClient } from '@angular/common/http';
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

  /** A namespace-scoped identity can't switch, so the picker should render as a static label. */
  readonly canSwitch = computed(() => this.auth.isClusterAdmin());

  async load(): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.get<string[]>('/api/v1/namespaces'));
      this._available.set(list);

      // Keep the stored selection only if it still exists; otherwise fall back to something real
      // so the console never sits on a namespace that returns nothing but empty tables.
      if (list.length && !list.includes(this.current())) {
        this.set(list.includes('default') ? 'default' : list[0]);
      }
    } catch {
      this._available.set([]);
    }
  }

  set(namespace: string): void {
    this.current.set(namespace);
    localStorage.setItem(KEY, namespace);
  }
}
