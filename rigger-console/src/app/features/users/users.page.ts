import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { RefreshService } from '../../core/refresh.service';
import { AuthService } from '../../core/auth.service';
import { UserResponse } from '../../core/api.models';
import { DataState } from '../../shared/data-state';
import { Dialog } from '../../shared/dialog';
import { PageHeader } from '../../shared/page-header';

const ROLES = ['CLUSTER_ADMIN', 'DEPLOYER', 'VIEWER', 'GITOPS_AGENT'] as const;

@Component({
  selector: 'r-users',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, DataState, Dialog, FormsModule],
  templateUrl: './users.page.html',
})
export class UsersPage {
  private readonly api = inject(ApiService);
  private readonly refresh = inject(RefreshService);
  private readonly transloco = inject(TranslocoService);
  readonly auth = inject(AuthService);

  readonly roles = ROLES;
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly users = signal<UserResponse[]>([]);
  readonly creating = signal(false);
  readonly busy = signal(false);
  readonly confirming = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  readonly failed = signal(false);

  form = { username: '', password: '', role: 'VIEWER' as string, namespace: '' };

  constructor() {
    // Tracks the masthead's refresh tick, so this page follows the chosen interval without
    // needing a Refresh button of its own.
    effect(() => {
      this.refresh.tick();
      void this.load();
    });
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      this.users.set(await firstValueFrom(this.api.users()));
    } catch (e) {
      const err = e as { status?: number };
      this.error.set(err?.status === 403 ? 'errors.forbidden' : 'common.error');
    } finally {
      this.loading.set(false);
    }
  }

  async create(): Promise<void> {
    if (!this.form.username || !this.form.password) return;
    this.busy.set(true);
    this.message.set(null);
    try {
      await firstValueFrom(
        this.api.createUser({
          username: this.form.username,
          password: this.form.password,
          role: this.form.role,
          // A blank namespace means cluster-wide; the API expects null rather than "".
          namespace: this.form.namespace.trim() ? this.form.namespace.trim() : null,
        }),
      );
      this.failed.set(false);
      this.message.set(this.transloco.translate('users.created'));
      this.form = { username: '', password: '', role: 'VIEWER', namespace: '' };
      this.creating.set(false);
      await this.load();
    } catch (e) {
      this.failed.set(true);
      const err = e as { error?: { detail?: string } };
      this.message.set(err?.error?.detail ?? this.transloco.translate('common.error'));
    } finally {
      this.busy.set(false);
    }
  }

  async remove(username: string): Promise<void> {
    this.confirming.set(null);
    this.busy.set(true);
    try {
      await firstValueFrom(this.api.deleteUser(username));
      this.users.update((list) => list.filter((u) => u.username !== username));
    } catch (e) {
      this.failed.set(true);
      const err = e as { error?: { detail?: string } };
      this.message.set(err?.error?.detail ?? this.transloco.translate('common.error'));
    } finally {
      this.busy.set(false);
    }
  }

  isSelf(username: string): boolean {
    return this.auth.user()?.username === username;
  }
}
