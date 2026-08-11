import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { ApplyResult, ComposeIssue, ConvertResult } from '../../core/api.models';
import { NamespaceService } from '../../core/namespace.service';
import { PageHeader } from '../../shared/page-header';

/**
 * Applies raw rigger.io/v1 YAML. The endpoint has existed since the beginning but the previous UI
 * never exposed it, so manifests could only be applied from the CLI.
 *
 * <p>docker-compose input is also accepted — the server detects and converts it — but that used to
 * happen invisibly: the user pasted a Compose file, saw "2 resources applied", and never learned that
 * their `volumes:` and `command:` had been dropped. So a Compose paste is now converted first via
 * POST /convert (which persists nothing), and the generated YAML plus the loss report are shown
 * before anything is applied. What is then applied is the YAML on screen, not the Compose text — what
 * you see is what you get.
 *
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
          (ngModelChange)="onManifestChange($event)"
          [disabled]="busy()"
        ></textarea>

        @if (looksLikeCompose()) {
          <p
            class="mt-3 rounded-lg px-3 py-2 text-sm"
            style="
              background-color: color-mix(in oklch, var(--color-info) 12%, transparent);
              color: var(--color-info);
            "
          >
            {{ t('apply.compose.detected') }}
          </p>
        }

        <div class="mt-4 flex flex-wrap items-center gap-3">
          <label class="flex cursor-pointer items-center gap-2 text-sm">
            <input type="checkbox" [(ngModel)]="dryRun" [disabled]="busy()" />
            {{ t('apply.dryRun') }}
          </label>

          <span class="muted text-sm">
            {{ t('common.namespace') }}: <strong>{{ ns.current() }}</strong>
          </span>

          <div class="flex-1"></div>

          @if (looksLikeCompose()) {
            <button type="button" class="btn" [disabled]="busy()" (click)="preview()">
              {{ t('apply.compose.convert') }}
            </button>
          }

          <button
            type="button"
            class="btn btn-primary"
            [disabled]="busy() || conversion()?.blocked"
            (click)="submit()"
          >
            {{
              busy()
                ? t('common.loading')
                : conversion()
                  ? t('apply.compose.applyConverted')
                  : t('apply.submit')
            }}
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

        @if (conversion(); as conv) {
          @if (conv.blocked) {
            <p
              class="mt-4 rounded-lg px-3 py-2 text-sm"
              style="
                background-color: color-mix(in oklch, var(--color-error) 12%, transparent);
                color: var(--color-error);
              "
            >
              {{ t('apply.compose.blocked') }}
            </p>
          }

          <h3 class="mt-5 text-sm font-semibold">{{ t('apply.compose.previewTitle') }}</h3>
          <pre
            class="table-wrap mt-2 max-h-80 overflow-auto p-3 font-mono text-xs leading-relaxed"
            >{{ conv.yaml }}</pre
          >

          <h3 class="mt-5 text-sm font-semibold">{{ t('apply.compose.issuesTitle') }}</h3>
          @if (!conv.issues.length) {
            <p class="muted mt-2 text-sm">{{ t('apply.compose.noIssues') }}</p>
          } @else {
            <div class="table-wrap mt-2">
              <table class="data">
                <thead>
                  <tr>
                    <th>{{ t('apply.compose.severity') }}</th>
                    <th>{{ t('apply.compose.path') }}</th>
                    <th>{{ t('common.message') }}</th>
                  </tr>
                </thead>
                <tbody>
                  @for (issue of sortedIssues(); track issue.path + issue.message) {
                    <tr>
                      <td class="font-medium" [style.color]="severityColor(issue)">
                        {{ issue.severity }}
                      </td>
                      <td class="font-mono text-xs">{{ issue.path }}</td>
                      <td class="muted">{{ issue.message }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
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
  readonly conversion = signal<ConvertResult | null>(null);
  private readonly content = signal('');

  /**
   * Same rule the server uses (a top-level `services:` map and no apiVersion/kind), evaluated here
   * only to decide whether to offer the conversion — the server's detection remains authoritative.
   */
  readonly looksLikeCompose = computed(() => {
    const text = this.content();
    return /^services:/m.test(text) && !/^apiVersion:/m.test(text) && !/^kind:/m.test(text);
  });

  private readonly transloco = inject(TranslocoService);

  onManifestChange(value: string): void {
    this.content.set(value);
    // A stale preview next to edited input is worse than none — it would be applied instead of
    // what's on screen above it.
    this.conversion.set(null);
  }

  /**
   * ERRORs first, then WARNINGs, then INFO — the server reports in Compose-file order, which put a
   * blocking top-level `volumes:` error underneath a dozen warnings where it read as a footnote.
   * Order within a severity is left alone, so paths still follow the file.
   */
  readonly sortedIssues = computed(() => {
    const rank = { ERROR: 0, WARNING: 1, INFO: 2 } as const;
    return [...(this.conversion()?.issues ?? [])].sort(
      (a, b) => rank[a.severity] - rank[b.severity],
    );
  });

  severityColor(issue: ComposeIssue): string {
    if (issue.severity === 'ERROR') return 'var(--color-error)';
    if (issue.severity === 'WARNING') return 'var(--color-warn)';
    return 'var(--color-info)';
  }

  async preview(): Promise<void> {
    if (this.busy()) return;
    this.message.set(null);
    this.result.set(null);
    if (!this.guardNotEmpty()) return;

    this.busy.set(true);
    try {
      this.conversion.set(await firstValueFrom(this.api.convert(this.ns.current(), this.manifest)));
      this.failed.set(false);
      this.message.set(this.transloco.translate('apply.compose.reviewFirst'));
    } catch (e) {
      this.failed.set(true);
      this.message.set(this.detail(e) ?? this.translate('apply.failed'));
    } finally {
      this.busy.set(false);
    }
  }

  async submit(): Promise<void> {
    if (this.busy()) return;
    this.message.set(null);
    this.result.set(null);
    if (!this.guardNotEmpty()) return;

    // Compose input is never applied unreviewed: the first click converts and shows the report.
    if (this.looksLikeCompose() && !this.conversion()) {
      await this.preview();
      return;
    }

    const payload = this.conversion()?.yaml ?? this.manifest;
    this.busy.set(true);
    try {
      const res = await firstValueFrom(this.api.apply(this.ns.current(), payload, this.dryRun));
      this.result.set(res);
      this.failed.set(false);
      this.message.set(
        this.translate(this.dryRun ? 'apply.validated' : 'apply.applied', { count: res.applied }),
      );
    } catch (e) {
      this.failed.set(true);
      // The server's 422 detail names the offending field or document, which is far more useful
      // than a generic failure message when a manifest is rejected.
      this.message.set(this.detail(e) ?? this.translate('apply.failed'));
    } finally {
      this.busy.set(false);
    }
  }

  private guardNotEmpty(): boolean {
    if (this.manifest.trim()) return true;
    this.failed.set(true);
    this.message.set(this.translate('apply.empty'));
    return false;
  }

  private detail(e: unknown): string | null {
    return (e as { error?: { detail?: string } })?.error?.detail ?? null;
  }

  private translate(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate(key, params);
  }
}
