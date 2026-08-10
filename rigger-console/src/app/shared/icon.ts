import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Inline SVG icons.
 *
 * <p>Path data is taken from Lucide (https://lucide.dev, ISC licence, © Lucide Contributors) and
 * inlined rather than pulled from a package. Two reasons, both concrete: `lucide-angular@1.0.0`
 * declares a peer of `@angular/core 13.x – 21.x`, so it would break `npm ci` on Angular 22 and take
 * CI down with it; and this console must work in an air-gapped cluster, so nothing may be fetched at
 * runtime or at build time.
 *
 * <p>All paths are drawn on Lucide's 24×24 grid with a 2px stroke, `round` caps and joins, so they
 * stay optically consistent with each other. Adding one means keeping to that grid.
 */
export type IconName =
  // navigation
  | 'gauge' | 'network' | 'box' | 'share' | 'sliders' | 'lock' | 'layers'
  | 'server' | 'settings' | 'git' | 'clipboard' | 'users' | 'file-code'
  // controls
  | 'search' | 'filter' | 'more' | 'chevron-down' | 'chevron-right' | 'chevron-left'
  | 'refresh' | 'rows' | 'sun' | 'moon' | 'monitor' | 'globe' | 'log-out'
  | 'x' | 'check' | 'alert' | 'play' | 'pause' | 'trash' | 'scale' | 'plus' | 'copy';

/**
 * Icons drawn filled rather than stroked. An outlined triangle at 16px is nearly invisible, so the
 * transport controls are solid.
 */
const FILLED = new Set<IconName>(['play', 'pause', 'more']);

const PATHS: Record<IconName, string> = {
  // ── navigation ────────────────────────────────────────────────────────────
  gauge: 'M22 12h-4l-3 9L9 3l-3 9H2',
  network:
    'M9 2h6a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1zM2 17h6a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1H2a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1zM16 17h6a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-6a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1zM12 7v4M5 17v-2a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v2',
  box: 'M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16zM3.3 7l8.7 5 8.7-5M12 22V12',
  share:
    'M18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM8.6 13.5l6.8 3.9M15.4 6.6L8.6 10.5',
  sliders: 'M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6',
  lock: 'M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2zM7 11V7a5 5 0 0 1 10 0v4',
  layers:
    'M12 2l9 5-9 5-9-5 9-5zM3 12l9 5 9-5M3 17l9 5 9-5',
  server:
    'M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zM4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2zM6 6h.01M6 18h.01',
  settings:
    'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-2.8 1.17V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 7.3 19.4l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 3 15H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 8.6l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 10 4.6V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 2.7 1.51l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z',
  git: 'M6 3v12M18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 9a9 9 0 0 1-9 9',
  clipboard:
    'M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2M9 2h6a1 1 0 0 1 1 1v1a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z',
  users:
    'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75',
  'file-code':
    'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M10 12l-2 2 2 2M14 12l2 2-2 2',

  // ── controls ──────────────────────────────────────────────────────────────
  search: 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.35-4.35',
  filter: 'M22 3H2l8 9.46V19l4 2v-8.54z',
  more: 'M12 13.6a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2zM12 6.6a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2zM12 20.6a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z',
  'chevron-down': 'M6 9l6 6 6-6',
  'chevron-right': 'M9 18l6-6-6-6',
  'chevron-left': 'M15 18l-6-6 6-6',
  refresh: 'M3 12a9 9 0 0 1 15-6.7L21 8M21 3v5h-5M21 12a9 9 0 0 1-15 6.7L3 16M3 21v-5h5',
  rows: 'M3 5h18M3 12h18M3 19h18',
  sun: 'M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4',
  moon: 'M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z',
  monitor: 'M3 3h18a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM8 21h8M12 17v4',
  globe: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3a15 15 0 0 1 0 18 15 15 0 0 1 0-18z',
  'log-out': 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9',
  x: 'M18 6L6 18M6 6l12 12',
  check: 'M20 6L9 17l-5-5',
  alert: 'M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z',
  play: 'M6 3l14 9-14 9z',
  pause: 'M6 4h4v16H6zM14 4h4v16h-4z',
  trash: 'M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6',
  scale: 'M7 15l5 5 5-5M7 9l5-5 5 5',
  plus: 'M12 5v14M5 12h14',
  copy: 'M9 9h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V11a2 2 0 0 1 2-2zM5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1',
};

@Component({
  selector: 'r-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.width]="size()"
      [attr.height]="size()"
      viewBox="0 0 24 24"
      [attr.fill]="filled() ? 'currentColor' : 'none'"
      [attr.stroke]="filled() ? 'none' : 'currentColor'"
      [attr.stroke-width]="strokeWidth()"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path [attr.d]="path()" />
    </svg>
  `,
  styles: `
    :host {
      display: inline-flex;
      flex: none;
      line-height: 0;
    }
  `,
})
export class Icon {
  readonly name = input.required<IconName>();
  readonly size = input(16);

  readonly path = computed(() => PATHS[this.name()]);
  readonly filled = computed(() => FILLED.has(this.name()));

  /**
   * Thinner stroke as the icon grows, so a 16px icon reads as solidly as a 24px one. A fixed 2px
   * looks heavy small and spindly large.
   */
  readonly strokeWidth = computed(() => (this.size() <= 16 ? 1.9 : this.size() <= 24 ? 1.7 : 1.5));
}

export const ICON_NAMES = Object.keys(PATHS) as IconName[];
