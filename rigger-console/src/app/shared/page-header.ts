import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'r-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="mb-3 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="text-base font-semibold tracking-tight">{{ title() }}</h1>
        @if (subtitle()) {
          <p class="muted mt-0.5 text-xs">{{ subtitle() }}</p>
        }
      </div>
      <div class="flex items-center gap-2">
        <ng-content />
      </div>
    </header>
  `,
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly subtitle = input<string>();
}
