import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

/**
 * Wraps a data view so loading, error and empty each get an explicit, translated state.
 *
 * <p>Exists because the previous UI rendered an empty table for all three, which reads as "nothing
 * is deployed" whether the request failed, is still running, or genuinely returned nothing.
 */
@Component({
  selector: 'r-data-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective],
  template: `
    <ng-container *transloco="let t">
      @if (loading()) {
        <div class="surface flex items-center gap-3 px-4 py-8">
          <span
            class="inline-block h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent opacity-60"
            aria-hidden="true"
          ></span>
          <span class="muted text-sm">{{ t('common.loading') }}</span>
        </div>
      } @else if (error()) {
        <div class="surface px-4 py-8 text-center">
          <p class="text-sm" style="color: var(--color-error)">{{ error() }}</p>
          <button type="button" class="btn btn-ghost mt-4" (click)="retry.emit()">
            {{ t('common.retry') }}
          </button>
        </div>
      } @else if (empty()) {
        <div class="surface px-4 py-10 text-center">
          <p class="muted text-sm">{{ emptyMessage() || t('common.empty') }}</p>
        </div>
      } @else {
        <ng-content />
      }
    </ng-container>
  `,
})
export class DataState {
  readonly loading = input(false);
  readonly error = input<string | null>(null);
  readonly empty = input(false);
  readonly emptyMessage = input<string>();
  readonly retry = output<void>();
}
