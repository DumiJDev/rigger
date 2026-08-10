import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { RefreshService } from '../../core/refresh.service';
import { AuthService } from '../../core/auth.service';
import { GitOpsConfig, GitOpsState } from '../../core/api.models';
import { PageHeader } from '../../shared/page-header';
import { StatusBadge } from '../../shared/status-badge';

@Component({
  selector: 'r-gitops',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, StatusBadge, FormsModule, DatePipe],
  templateUrl: './gitops.page.html',
})
export class GitOpsPage {
  private readonly api = inject(ApiService);
  private readonly refresh = inject(RefreshService);
  private readonly transloco = inject(TranslocoService);
  readonly auth = inject(AuthService);

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly state = signal<GitOpsState | null>(null);
  readonly config = signal<GitOpsConfig | null>(null);
  readonly message = signal<string | null>(null);
  readonly failed = signal(false);

  /** Editable copy — kept separate so a failed save doesn't leave the form showing server values. */
  form: GitOpsConfig = emptyConfig();
  manifestPathsText = '';

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
    try {
      const config = await firstValueFrom(this.api.gitopsConfig());
      this.config.set(config);
      this.form = { ...config };
      this.manifestPathsText = (config.manifestPaths ?? []).join(', ');
    } catch {
      this.config.set(null);
    }

    await this.loadState();
    this.loading.set(false);
  }

  /**
   * Only asks for sync state when the config says GitOps is on. The endpoint 404s when disabled,
   * which is a normal state rather than an error — but calling it regardless meant every visit to
   * this page logged a failed request in the browser console for no reason.
   */
  private async loadState(): Promise<void> {
    if (!this.config()?.enabled) {
      this.state.set(null);
      return;
    }
    try {
      this.state.set(await firstValueFrom(this.api.gitopsState()));
    } catch {
      this.state.set(null);
    }
  }

  canEdit(): boolean {
    return this.auth.can('configure', 'GitOps');
  }

  async save(): Promise<void> {
    this.saving.set(true);
    this.message.set(null);
    try {
      const payload: GitOpsConfig = {
        ...this.form,
        manifestPaths: this.manifestPathsText
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean),
        namespaceMapping: this.form.namespaceMapping ?? {},
      };
      const saved = await firstValueFrom(this.api.saveGitopsConfig(payload));
      this.config.set(saved);
      this.form = { ...saved };
      this.failed.set(false);
      this.message.set(this.transloco.translate('gitops.saved'));
      // The agent picks this up on its next poll, so refresh state to reflect the new repository.
      await this.loadState();
    } catch (e) {
      this.failed.set(true);
      const err = e as { status?: number; error?: { detail?: string } };
      this.message.set(
        err?.status === 403
          ? this.transloco.translate('errors.forbidden')
          : (err?.error?.detail ?? this.transloco.translate('common.error')),
      );
    } finally {
      this.saving.set(false);
    }
  }
}

function emptyConfig(): GitOpsConfig {
  return {
    enabled: false,
    repositoryUrl: '',
    branch: 'main',
    sshKeyPath: '',
    pollIntervalSeconds: 60,
    manifestPaths: ['manifests/'],
    namespaceMapping: {},
  };
}
