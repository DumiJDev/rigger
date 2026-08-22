import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { ClusterMetrics } from '../../core/api.models';
import { Dialog } from '../../shared/dialog';
import { PageHeader } from '../../shared/page-header';

/**
 * Cluster provisioning (`up`) and reconciliation (`sync`), both of which take a cluster manifest
 * and act on real machines over SSH — so they're behind an explicit confirmation rather than a
 * single click.
 */
@Component({
  selector: 'r-cluster',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, Dialog, FormsModule],
  template: `
    <ng-container *transloco="let t">
      <r-page-header [title]="t('cluster.title')" [subtitle]="t('cluster.subtitle')" />

      @if (metrics(); as m) {
        <div class="mb-6 grid gap-4 sm:grid-cols-3">
          <div class="surface p-4">
            <div class="muted text-xs uppercase tracking-wide">{{ t('dashboard.nodes') }}</div>
            <div class="mt-2 text-2xl font-semibold">{{ m.activeNodes }} / {{ m.totalNodes }}</div>
          </div>
          <div class="surface p-4">
            <div class="muted text-xs uppercase tracking-wide">{{ t('dashboard.runningTasks') }}</div>
            <div class="mt-2 text-2xl font-semibold">{{ m.runningTasks }} / {{ m.desiredTasks }}</div>
          </div>
          <div class="surface p-4">
            <div class="muted text-xs uppercase tracking-wide">{{ t('dashboard.deployments') }}</div>
            <div class="mt-2 text-2xl font-semibold">{{ m.deployments }}</div>
          </div>
        </div>
      }

      <div class="surface p-4">
        <label class="mb-1.5 block text-sm font-medium" for="manifest">
          {{ t('cluster.manifest') }}
        </label>
        <textarea
          id="manifest"
          class="input min-h-56 font-mono text-xs leading-relaxed"
          spellcheck="false"
          [(ngModel)]="manifest"
          [disabled]="busy()"
        ></textarea>

        <div class="mt-4 grid gap-3 sm:grid-cols-2">
          <div class="rounded-lg p-3" style="background-color: var(--surface-sunken)">
            <div class="text-sm font-medium">{{ t('cluster.up') }}</div>
            <p class="muted mt-1 text-xs">{{ t('cluster.upHelp') }}</p>
            <button
              type="button"
              class="btn btn-primary mt-3"
              [disabled]="busy() || !manifest.trim()"
              (click)="pending.set('up')"
            >
              {{ t('cluster.up') }}
            </button>
          </div>
          <div class="rounded-lg p-3" style="background-color: var(--surface-sunken)">
            <div class="text-sm font-medium">{{ t('cluster.sync') }}</div>
            <p class="muted mt-1 text-xs">{{ t('cluster.syncHelp') }}</p>
            <button
              type="button"
              class="btn btn-ghost mt-3"
              [disabled]="busy() || !manifest.trim()"
              (click)="pending.set('sync')"
            >
              {{ t('cluster.sync') }}
            </button>
          </div>
        </div>

        @if (busy()) {
          <p class="muted mt-4 text-sm">{{ t('cluster.running') }}</p>
        }
        @if (message(); as msg) {
          <p
            class="mt-4 rounded-lg px-3 py-2 text-sm"
            [style.background-color]="
              failed()
                ? 'color-mix(in oklch, var(--color-error) 12%, transparent)'
                : 'color-mix(in oklch, var(--color-ok) 12%, transparent)'
            "
            [style.color]="failed() ? 'var(--color-error)' : 'var(--color-ok)'"
          >
            {{ msg }}
          </p>
        }
      </div>

      @if (pending(); as op) {
        <r-dialog
          size="md"
          ariaLabel="{{ op === 'up' ? t('cluster.upHelp') : t('cluster.syncHelp') }}"
          (closed)="pending.set(null)"
        >
          <p class="text-sm">
            {{ op === 'up' ? t('cluster.upHelp') : t('cluster.syncHelp') }}
          </p>
          <div class="mt-5 flex justify-end gap-2">
            <button type="button" class="btn btn-ghost" (click)="pending.set(null)">
              {{ t('common.cancel') }}
            </button>
            <button type="button" class="btn btn-primary" (click)="run(op)">
              {{ t('common.confirm') }}
            </button>
          </div>
        </r-dialog>
      }
    </ng-container>
  `,
})
export class ClusterPage {
  private readonly api = inject(ApiService);
  private readonly transloco = inject(TranslocoService);

  manifest = '';
  readonly busy = signal(false);
  readonly pending = signal<'up' | 'sync' | null>(null);
  readonly message = signal<string | null>(null);
  readonly failed = signal(false);
  readonly metrics = signal<ClusterMetrics | null>(null);

  constructor() {
    void this.loadMetrics();
  }

  private async loadMetrics(): Promise<void> {
    try {
      this.metrics.set(await firstValueFrom(this.api.clusterMetrics()));
    } catch {
      this.metrics.set(null);
    }
  }

  async run(op: 'up' | 'sync'): Promise<void> {
    this.pending.set(null);
    this.busy.set(true);
    this.message.set(null);
    try {
      await firstValueFrom(
        op === 'up' ? this.api.clusterUp(this.manifest) : this.api.clusterSync(this.manifest),
      );
      this.failed.set(false);
      this.message.set(this.transloco.translate('cluster.succeeded'));
      await this.loadMetrics();
    } catch (e) {
      this.failed.set(true);
      // Provisioning failures name the node and the SSH problem — surface them verbatim.
      const err = e as { error?: { detail?: string } };
      this.message.set(err?.error?.detail ?? this.transloco.translate('cluster.failed'));
    } finally {
      this.busy.set(false);
    }
  }
}
