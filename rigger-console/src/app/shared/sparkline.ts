import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * A single-series sparkline, drawn as inline SVG.
 *
 * <p>No charting library: none was ever a dependency here, the console must work air-gapped, and
 * the topology graph already establishes that hand-rolled SVG is how this codebase draws. A
 * sparkline is a polyline and an optional area — a library would be more code, not less.
 *
 * <p>Sized by CSS, not by attributes: the SVG uses a fixed 100×{@link height} viewBox with
 * {@code preserveAspectRatio="none"} and stretches to its container. That means the horizontal
 * scale is "the whole panel" regardless of how many points there are, which is what a sparkline is
 * for — the shape, not the axis.
 */
@Component({
  selector: 'r-sparkline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (points().length >= 2) {
      <svg
        [attr.viewBox]="'0 0 100 ' + height()"
        preserveAspectRatio="none"
        [style.height.px]="height()"
        class="w-full"
        aria-hidden="true"
        focusable="false"
      >
        @if (fill()) {
          <path [attr.d]="areaPath()" [attr.fill]="color()" opacity="0.14" />
        }
        <polyline
          [attr.points]="linePoints()"
          fill="none"
          [attr.stroke]="color()"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
          vector-effect="non-scaling-stroke"
        />
        <!-- The latest value is the one being read, so it gets a dot. -->
        <circle [attr.cx]="last().x" [attr.cy]="last().y" r="1.8" [attr.fill]="color()"
                vector-effect="non-scaling-stroke" />
      </svg>
    } @else {
      <!-- One point is not a trend. Reserving the height stops the panel jumping once data lands. -->
      <div class="flex items-center" [style.height.px]="height()">
        <span class="muted" style="font-size: var(--text-2xs)">{{ emptyLabel() }}</span>
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
export class Sparkline {
  /**
   * Points as `[epochMillis, value]`. Timestamps rather than a bare value array because sampling is
   * not evenly spaced — a round costs a round-trip to the Docker Engine, so the interval drifts, and
   * a server restart leaves a real gap. Plotted by index those irregularities vanish and the chart
   * quietly claims a regularity the data does not have.
   */
  readonly points = input<[number, number][]>([]);
  readonly height = input(28);
  readonly color = input('var(--color-brand-500)');
  /**
   * Off by default. These series are mostly flat operational counts, and an area under a flat line
   * is a filled rectangle — it draws far more attention than the number it sits beneath, which is
   * the wrong way round on a KPI panel.
   */
  readonly fill = input(false);
  readonly emptyLabel = input('');

  /**
   * Baseline pinned at zero for non-negative data, because a sparkline scaled to its own min makes
   * a flat series look like noise — 2 replicas for an hour would read as a jagged line. A series
   * that does go negative gets a min-based scale, since zero is no longer the floor.
   */
  private readonly bounds = computed(() => {
    const values = this.points().map((p) => p[1]);
    const max = Math.max(...values);
    const min = Math.min(...values);
    const lo = min < 0 ? min : 0;
    // A constant series has zero range; any positive span keeps it a flat line rather than NaN.
    const top = max > lo ? max : lo + 1;
    // Headroom above the maximum, so a steady value sits inside the box instead of being welded to
    // its top edge — pinned there it read as "at the limit", and its area fill became a solid
    // rectangle filling the whole panel rather than a shape.
    return { lo, hi: lo + (top - lo) / 0.85 };
  });

  private readonly coords = computed(() => {
    const pts = this.points();
    const { lo, hi } = this.bounds();
    const h = this.height();
    // Inset by the stroke radius so the line is not clipped at the extremes.
    const pad = 2;
    const span = h - pad * 2;
    const t0 = pts[0][0];
    const t1 = pts[pts.length - 1][0];
    // Every point sharing one timestamp would divide by zero; fall back to even spacing there.
    const dt = t1 - t0;
    return pts.map(([t, v], i) => ({
      x: dt > 0 ? ((t - t0) / dt) * 100 : (i / Math.max(1, pts.length - 1)) * 100,
      y: pad + (1 - (v - lo) / (hi - lo)) * span,
    }));
  });

  readonly linePoints = computed(() =>
    this.coords().map((p) => `${round(p.x)},${round(p.y)}`).join(' '),
  );

  readonly areaPath = computed(() => {
    const c = this.coords();
    const h = this.height();
    const line = c.map((p, i) => `${i === 0 ? 'M' : 'L'}${round(p.x)},${round(p.y)}`).join(' ');
    return `${line} L100,${h} L0,${h} Z`;
  });

  readonly last = computed(() => {
    const c = this.coords();
    const p = c[c.length - 1] ?? { x: 0, y: 0 };
    return { x: round(p.x), y: round(p.y) };
  });
}

/** Two decimals: enough for a 100-unit viewBox, and keeps the DOM attribute short. */
function round(n: number): number {
  return Math.round(n * 100) / 100;
}
