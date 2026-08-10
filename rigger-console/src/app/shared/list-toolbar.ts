import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';
import { Icon } from './icon';

/**
 * The strip above a list: search on the left, row count, and whatever the page projects on the
 * right as its primary action. The OpenShift layout, and the one place a list page's controls
 * belong — before this, each page had a lone Refresh button and nothing else.
 *
 * <p>The search box is a two-way `model()` so a page binds its own `query` signal directly and owns
 * the filtering. This component deliberately knows nothing about the rows.
 */
@Component({
  selector: 'r-list-toolbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, Icon],
  template: `
    <div class="toolbar" *transloco="let t">
      <div class="relative">
        <span class="pointer-events-none absolute left-2 top-1/2 -translate-y-1/2 muted">
          <r-icon name="search" [size]="14" />
        </span>
        <input
          type="search"
          class="input w-72 pl-7 pr-7"
          [placeholder]="placeholder() || t('common.search')"
          [attr.aria-label]="placeholder() || t('common.search')"
          [value]="query()"
          (input)="query.set($any($event.target).value)"
        />
        @if (query()) {
          <button
            type="button"
            class="btn-icon absolute right-1 top-1/2 -translate-y-1/2"
            [attr.aria-label]="t('common.clear')"
            (click)="query.set('')"
          >
            <r-icon name="x" [size]="13" />
          </button>
        }
      </div>

      <!-- Shown only while filtering: "12" on its own is noise, "3 of 12" answers a question. -->
      @if (query()) {
        <span class="chip tabular-nums">{{ shown() }} / {{ total() }}</span>
      } @else if (total()) {
        <span class="chip tabular-nums">{{ total() }}</span>
      }

      <span class="flex-1"></span>
      <ng-content />
    </div>
  `,
})
export class ListToolbar {
  readonly query = model('');
  readonly total = input(0);
  readonly shown = input(0);
  readonly placeholder = input('');
}
