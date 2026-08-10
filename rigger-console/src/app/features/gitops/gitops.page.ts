import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
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
    void this.load();
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

    // 404 here means GitOps is disabled, which is a normal state rather than an error.
    try {
      this.state.set(await firstValueFrom(this.api.gitopsState()));
    } catch {
      this.state.set(null);
    }
    this.loading.set(false);
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
      try {
        this.state.set(await firstValueFrom(this.api.gitopsState()));
      } catch {
        this.state.set(null);
      }
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
