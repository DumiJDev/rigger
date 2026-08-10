import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { ApplyResult } from '../../core/api.models';
import { NamespaceService } from '../../core/namespace.service';
import { PageHeader } from '../../shared/page-header';

/**
 * Applies raw rigger.io/v1 YAML. The endpoint has existed since the beginning but the previous UI
 * never exposed it, so manifests could only be applied from the CLI.
 */
@Component({
  selector: 'r-apply',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, PageHeader, FormsModule],
  template: `
    <ng-container *transloco="let t">
      <r-page-header [title]="t('apply.title')" [subtitle]="t('apply.subtitle')" />

      <div class="surface p-4">
        <textarea
          class="input min-h-80 font-mono text-xs leading-relaxed"
          spellcheck="false"
          [placeholder]="t('apply.placeholder')"
          [(ngModel)]="manifest"
          [disabled]="busy()"
        ></textarea>

        <div class="mt-4 flex flex-wrap items-center gap-3">
          <label class="flex cursor-pointer items-center gap-2 text-sm">
            <input type="checkbox" [(ngModel)]="dryRun" [disabled]="busy()" />
            {{ t('apply.dryRun') }}
          </label>

          <span class="muted text-sm">
            {{ t('common.namespace') }}: <strong>{{ ns.current() }}</strong>
          </span>

          <div class="flex-1"></div>

          <button type="button" class="btn btn-primary" [disabled]="busy()" (click)="submit()">
            {{ busy() ? t('common.loading') : t('apply.submit') }}
          </button>
        </div>

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

        @if (result(); as r) {
          @if (r.resources.length) {
            <div class="table-wrap mt-4">
              <table class="data">
                <thead>
                  <tr>
                    <th>{{ t('common.kind') }}</th>
                    <th>{{ t('common.name') }}</th>
                    <th>{{ t('common.status') }}</th>
                  </tr>
                </thead>
                <tbody>
                  @for (item of r.resources; track item.kind + item.name) {
                    <tr>
                      <td class="muted">{{ item.kind }}</td>
                      <td class="font-medium">{{ item.name }}</td>
                      <td>{{ item.action }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        }
      </div>
    </ng-container>
  `,
})
export class ApplyPage {
  private readonly api = inject(ApiService);
  readonly ns = inject(NamespaceService);

  manifest = '';
  dryRun = false;
  readonly busy = signal(false);
  readonly message = signal<string | null>(null);
  readonly failed = signal(false);
  readonly result = signal<ApplyResult | null>(null);

  private readonly transloco = inject(TranslocoService);

  async submit(): Promise<void> {
    if (this.busy()) return;
    this.message.set(null);
    this.result.set(null);

    if (!this.manifest.trim()) {
      this.failed.set(true);
      this.message.set(this.text('apply.empty'));
      return;
    }

    this.busy.set(true);
    try {
      const res = await firstValueFrom(
        this.api.apply(this.ns.current(), this.manifest, this.dryRun),
      );
      this.result.set(res);
      this.failed.set(false);
      this.message.set(
        this.text(this.dryRun ? 'apply.validated' : 'apply.applied', { count: res.applied }),
      );
    } catch (e) {
      this.failed.set(true);
      // The server's 422 detail names the offending field or document, which is far more useful
      // than a generic failure message when a manifest is rejected.
      const err = e as { error?: { detail?: string } };
      this.message.set(err?.error?.detail ?? this.text('apply.failed'));
    } finally {
      this.busy.set(false);
    }
  }

  private text(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate(key, params);
  }
}
