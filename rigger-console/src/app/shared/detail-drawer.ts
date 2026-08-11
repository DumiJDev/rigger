import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { ResourceResponse } from '../core/api.models';
import { Icon } from './icon';
import { toYaml } from './yaml';

type Tab = 'details' | 'spec' | 'labels';

/**
 * Read-only detail panel for one resource, opened from a row or its kebab.
 *
 * <p>It needs no endpoint of its own: the list responses already carry the full `spec`, `labels` and
 * provenance for every row, so the object the table is holding is the whole story. Fetching the
 * resource again on open would only add a round trip and a way for the panel to disagree with the
 * row above it.
 *
 * <p>Same slide-over shape as the pod log viewer, deliberately — two overlays that behave
 * differently in the same console read as two different products.
 */
@Component({
  selector: 'r-detail-drawer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, DatePipe, Icon],
  template: `
    <ng-container *transloco="let t">
      <div
        class="fixed inset-0 z-30 flex items-stretch justify-end bg-black/40"
        (click)="closed.emit()"
      >
        <div
          class="flex h-full w-full max-w-4xl flex-col border-l"
          style="border-color: var(--border-subtle); background-color: var(--surface-raised)"
          (click)="$event.stopPropagation()"
        >
          <header
            class="flex shrink-0 items-center gap-2 border-b px-4 py-3"
            style="border-color: var(--border-subtle)"
          >
            <div class="mr-auto min-w-0">
              <h2 class="truncate text-sm font-semibold">{{ resource().name }}</h2>
              <p class="muted truncate text-xs">
                {{ resource().kind }} · {{ resource().namespace }}
              </p>
            </div>
            <button type="button" class="btn btn-ghost py-1.5" (click)="closed.emit()">
              {{ t('common.close') }}
            </button>
          </header>

          <nav
            class="tabs shrink-0 border-b px-4"
            style="border-color: var(--border-subtle)"
            role="tablist"
          >
            @for (tab of TABS; track tab) {
              <button
                type="button"
                class="tab"
                role="tab"
                [class.tab-active]="active() === tab"
                [attr.aria-selected]="active() === tab"
                (click)="active.set(tab)"
              >
                {{ t('details.tab.' + tab) }}
              </button>
            }
          </nav>

          <div class="flex-1 overflow-auto p-4">
            @switch (active()) {
              @case ('details') {
                <dl class="kv text-xs">
                  <dt>{{ t('common.kind') }}</dt>
                  <dd>{{ resource().kind }}</dd>
                  <dt>{{ t('common.name') }}</dt>
                  <dd class="font-mono">{{ resource().name }}</dd>
                  <dt>{{ t('common.namespace') }}</dt>
                  <dd>{{ resource().namespace }}</dd>
                  <dt>{{ t('common.appliedBy') }}</dt>
                  <dd>{{ resource().appliedBy || '—' }}</dd>
                  <dt>{{ t('common.created') }}</dt>
                  <dd>{{ (resource().createdAt | date: 'medium') || '—' }}</dd>
                  <dt>{{ t('common.updated') }}</dt>
                  <dd>{{ (resource().updatedAt | date: 'medium') || '—' }}</dd>
                </dl>
              }

              @case ('spec') {
                <!-- The redaction is the server's, not a choice made here, and saying so matters:
                     otherwise an operator reads an almost-empty spec as a broken panel and goes
                     looking for a way to reveal the values. -->
                @if (redacted()) {
                  <p class="muted mb-3 text-xs">{{ t('details.secretRedacted') }}</p>
                }
                <div class="mb-2 flex justify-end">
                  <button type="button" class="btn btn-ghost py-1" (click)="copy()">
                    <r-icon name="copy" [size]="13" />
                    {{ copied() ? t('details.copied') : t('details.copy') }}
                  </button>
                </div>
                <pre class="code-block" data-test="spec-yaml">{{ yaml() }}</pre>
              }

              @case ('labels') {
                @if (labels().length) {
                  <dl class="kv text-xs">
                    @for (entry of labels(); track entry[0]) {
                      <dt class="font-mono">{{ entry[0] }}</dt>
                      <dd class="font-mono break-all">{{ entry[1] }}</dd>
                    }
                  </dl>
                } @else {
                  <!-- Worded as "none returned", not "none set": WorkloadController.toResponse
                       hard-codes an empty label map even though labels_json is persisted, so an
                       absent label here says nothing about what was applied.
                       (No backticks in this comment: the template is a backtick literal.) -->
                  <p class="muted text-xs">
                    {{ redacted() ? t('details.labelsRedacted') : t('details.noLabels') }}
                  </p>
                }
              }
            }
          </div>
        </div>
      </div>
    </ng-container>
  `,
})
export class DetailDrawer {
  readonly resource = input.required<ResourceResponse>();
  readonly closed = output<void>();

  protected readonly TABS: Tab[] = ['details', 'spec', 'labels'];
  readonly active = signal<Tab>('details');
  readonly copied = signal(false);

  readonly yaml = computed(() => toYaml(this.resource().spec ?? {}));
  readonly labels = computed(() => Object.entries(this.resource().labels ?? {}));

  /**
   * Secret specs come back from the API as `{keys: "redacted"}` with no labels at all — the values
   * never leave the server. Detected by that marker rather than by kind, so the note tracks what the
   * response actually contains instead of what this component assumes about a kind.
   */
  readonly redacted = computed(() => this.resource().spec?.['keys'] === 'redacted');

  /** Escape closes, matching every other overlay in the console. */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }

  async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.yaml());
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 1500);
    } catch {
      // Clipboard access can be denied outright (permission, or a non-secure context). Nothing to
      // recover — the text is on screen and selectable, which is the fallback.
    }
  }
}
