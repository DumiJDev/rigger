import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export interface ChartSeries {
  label: string;
  /** `[epochMillis, value]` — see the x-scale note on {@link LineChart}. */
  points: [number, number][];
}

/**
 * Multi-series line chart, inline SVG, no dependencies — same reasoning as {@link Sparkline}.
 *
 * <p>Bigger than a sparkline in one way that matters: it has a shared vertical scale and labels it.
 * Several lines with no axis are unreadable, because you cannot tell a line at 80% from one at 8%.
 *
 * <p>All series share one vertical scale computed across every point, so the lines are comparable to
 * each other; scaling each to its own max would make an idle Deployment and a saturated one look
 * identical.
 *
 * <p>They also share one <strong>horizontal</strong> scale, taken from timestamps rather than array
 * position. This matters more here than in a sparkline: a Deployment created ten minutes ago has
 * fewer samples than one running all hour, and plotted by index its short series would stretch
 * across the full width and appear to have started at the same moment as the others.
 */
@Component({
  selector: 'r-line-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (hasData()) {
      <div class="flex gap-2">
        <!-- Axis labels sit outside the stretched SVG: inside it, non-uniform scaling would
             distort the text. -->
        <div
          class="muted flex shrink-0 flex-col justify-between text-right tabular-nums"
          style="font-size: var(--text-2xs); height: {{ height() }}px; min-width: 2.25rem"
        >
          <span>{{ format(scaleMax()) }}</span>
          <span>{{ format(scaleMax() / 2) }}</span>
          <span>0</span>
        </div>

        <svg
          [attr.viewBox]="'0 0 100 ' + height()"
          preserveAspectRatio="none"
          [style.height.px]="height()"
          class="min-w-0 flex-1"
          role="img"
          [attr.aria-label]="ariaLabel()"
        >
          <!-- Gridlines at 0, 50 and 100% of the scale: enough to read a value off, few enough
               not to compete with the data. -->
          @for (y of gridlines(); track y) {
            <line
              x1="0" [attr.y1]="y" x2="100" [attr.y2]="y"
              stroke="var(--border-subtle)" stroke-width="1" vector-effect="non-scaling-stroke"
            />
          }
          @for (s of plotted(); track s.label) {
            <polyline
              [attr.points]="s.d"
              fill="none"
              [attr.stroke]="s.color"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
              vector-effect="non-scaling-stroke"
            />
          }
        </svg>
      </div>

      <!-- Legend after the chart: without it the colours identify nothing. -->
      <div class="mt-2 flex flex-wrap gap-x-3 gap-y-1" style="font-size: var(--text-2xs)">
        @for (s of plotted(); track s.label) {
          <span class="flex items-center gap-1.5">
            <span class="inline-block h-0.5 w-3 rounded" [style.background-color]="s.color"></span>
            <span class="truncate" style="max-width: 10rem">{{ s.label }}</span>
            <span class="muted tabular-nums">{{ format(s.latest) }}</span>
          </span>
        }
      </div>
    } @else {
      <div class="flex items-center justify-center" [style.height.px]="height()">
        <span class="muted text-xs">{{ emptyLabel() }}</span>
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
  `,
})
export class LineChart {
  readonly series = input<ChartSeries[]>([]);
  readonly height = input(120);
  readonly emptyLabel = input('');
  /** Appended to each legend value and axis label, e.g. '%'. */
  readonly unit = input('');
  /**
   * Floor for the vertical scale, so a chart of near-idle CPU is not amplified into drama. Zero
   * means "scale purely to the data".
   */
  readonly minScale = input(0);

  /**
   * Hue-rotated from the brand violet rather than an arbitrary palette, and deliberately avoiding
   * the hues the status colours own (green 150, amber 80, red 25) — a line must not read as a
   * health signal it is not.
   */
  private static readonly HUES = [295, 265, 200, 320, 175, 240, 340, 220];

  private readonly withData = computed(() => this.series().filter((s) => s.points.length >= 2));

  readonly hasData = computed(() => this.withData().length > 0);

  readonly scaleMax = computed(() => {
    const max = Math.max(this.minScale(), ...this.withData().flatMap((s) => s.points.map((p) => p[1])));
    // Any positive span keeps a wholly-zero chart a flat line on the baseline rather than NaN.
    return max > 0 ? max : 1;
  });

  /** Time window spanned by all series together, so every line is drawn on the same axis. */
  private readonly timeSpan = computed(() => {
    const times = this.withData().flatMap((s) => s.points.map((p) => p[0]));
    const t0 = Math.min(...times);
    const t1 = Math.max(...times);
    return { t0, span: t1 - t0 };
  });

  readonly gridlines = computed(() => {
    const h = this.height();
    const pad = 2;
    return [pad, pad + (h - pad * 2) / 2, h - pad];
  });

  readonly plotted = computed(() => {
    const max = this.scaleMax();
    const h = this.height();
    const pad = 2;
    const vSpan = h - pad * 2;
    const { t0, span } = this.timeSpan();
    return this.withData().map((s, i) => ({
      label: s.label,
      color: `oklch(0.62 0.19 ${LineChart.HUES[i % LineChart.HUES.length]})`,
      latest: s.points[s.points.length - 1][1],
      d: s.points
        .map(([t, v], j) => {
          // Every series sampled at one instant would divide by zero; even spacing is the only
          // sensible fallback and it is visually identical for a single-timestamp chart.
          const x = span > 0 ? ((t - t0) / span) * 100 : (j / Math.max(1, s.points.length - 1)) * 100;
          const y = pad + (1 - v / max) * vSpan;
          return `${round(x)},${round(y)}`;
        })
        .join(' '),
    }));
  });

  readonly ariaLabel = computed(
    () => `${this.withData().length} series, maximum ${this.format(this.scaleMax())}`,
  );

  /** One decimal below 10 so small CPU percentages don't all render as "0". */
  format(n: number): string {
    const rounded = n >= 10 || Number.isInteger(n) ? Math.round(n) : Math.round(n * 10) / 10;
    return `${rounded}${this.unit()}`;
  }
}

function round(n: number): number {
  return Math.round(n * 100) / 100;
}
