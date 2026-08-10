import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { LoginResponse, PermissionsResponse, RiggerRole, UserResponse } from './api.models';

const TOKEN_KEY = 'rigger.token';
const USER_KEY = 'rigger.user';

/**
 * Session state and the permission matrix that drives which actions the UI offers.
 *
 * <p>Token lives in localStorage — a deliberate trade-off for an internal ops tool. There is no
 * refresh endpoint on the backend, so an expired token simply means logging in again; nothing here
 * attempts to renew one.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly _user = signal<UserResponse | null>(readStoredUser());
  private readonly _permissions = signal<Record<string, string[]>>({});
  private restored = false;
  private restoreInFlight: Promise<boolean> | null = null;

  readonly user = this._user.asReadonly();
  readonly isAuthenticated = computed(() => this._user() !== null && this.token !== null);
  readonly role = computed<RiggerRole | null>(() => this._user()?.role ?? null);
  readonly isClusterAdmin = computed(() => this.role() === 'CLUSTER_ADMIN');

  get token(): string | null {
    return localStorage.getItem(TOKEN_KEY);
  }

  async login(username: string, password: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<LoginResponse>('/api/v1/auth/login', { username, password }),
    );
    localStorage.setItem(TOKEN_KEY, res.token);
    const user: UserResponse = {
      username: res.username,
      role: res.role,
      namespace: res.namespace,
      active: true,
    };
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    this._user.set(user);
    this.restored = true;
    await this.loadPermissions();
  }

  /**
   * Validates the stored session against the backend once per app load, then resolves from state.
   *
   * <p>A token restored from localStorage is not trusted blindly: one that expired while the tab
   * was closed must land the user on the login page rather than in a shell whose every request
   * 401s. Memoised so route guards don't add a round-trip per navigation.
   */
  restore(): Promise<boolean> {
    if (!this.token) {
      this.clear();
      return Promise.resolve(false);
    }
    if (this.restored) return Promise.resolve(this._user() !== null);

    this.restoreInFlight ??= (async () => {
      try {
        const me = await firstValueFrom(this.http.get<UserResponse>('/api/v1/auth/me'));
        this._user.set(me);
        localStorage.setItem(USER_KEY, JSON.stringify(me));
        await this.loadPermissions();
        this.restored = true;
        return true;
      } catch {
        this.clear();
        this.restored = true;
        return false;
      } finally {
        this.restoreInFlight = null;
      }
    })();

    return this.restoreInFlight;
  }

  async loadPermissions(): Promise<void> {
    try {
      const res = await firstValueFrom(
        this.http.get<PermissionsResponse>('/api/v1/auth/permissions'),
      );
      this._permissions.set(res.permissions ?? {});
    } catch {
      // Without the matrix the console just shows fewer affordances; the server still enforces.
      this._permissions.set({});
    }
  }

  /**
   * Whether the current role may perform an action, for hiding or disabling controls.
   * Presentation only — every request is authorized server-side regardless.
   */
  can(action: string, resource: string): boolean {
    return this._permissions()[resource]?.includes(action) ?? false;
  }

  clear(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this._user.set(null);
    this._permissions.set({});
    this.restored = false;
  }

  logout(): void {
    this.clear();
    void this.router.navigate(['/login']);
  }
}

function readStoredUser(): UserResponse | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as UserResponse;
  } catch {
    return null;
  }
}
