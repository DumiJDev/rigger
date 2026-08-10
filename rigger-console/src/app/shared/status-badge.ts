import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

type Tone = 'ok' | 'warn' | 'error' | 'idle';

/**
 * Status pill with a fixed status → colour mapping, so the same colour always means the same thing
 * across the console. Unknown values fall back to neutral rather than guessing.
 */
@Component({
  selector: 'r-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium"
      [style.background-color]="'color-mix(in oklch, var(--color-' + tone() + ') 14%, transparent)'"
      [style.color]="'var(--color-' + tone() + ')'"
    >
      <span
        class="h-1.5 w-1.5 rounded-full"
        [style.background-color]="'var(--color-' + tone() + ')'"
        aria-hidden="true"
      ></span>
      {{ label() }}
    </span>
  `,
})
export class StatusBadge {
  readonly status = input.required<string>();
  readonly label = input.required<string>();

  readonly tone = computed<Tone>(() => {
    switch (this.status().toLowerCase()) {
      case 'healthy':
      case 'active':
      case 'running':
      case 'success':
      case 'ready':
      case 'complete':
        return 'ok';
      case 'degraded':
      case 'draining':
      case 'pending':
      case 'provisioning':
      case 'starting':
      case 'unknown':
        return 'warn';
      case 'down':
      case 'offline':
      case 'failed':
      case 'error':
      case 'denied':
      case 'rejected':
        return 'error';
      default:
        return 'idle';
    }
  });
}
