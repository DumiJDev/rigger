import { Injectable, signal } from '@angular/core';

export type Density = 'compact' | 'comfortable';

const KEY = 'rigger.density';

/**
 * Row density, applied as a `data-density` attribute on &lt;html&gt;.
 *
 * <p>The attribute switches a handful of CSS tokens (`--row-h`, `--cell-py`, `--cell-px`), so every
 * table re-lays out without any component knowing density exists. Compact is the default: this is a
 * console read across many rows at once, and someone on a laptop and someone on a wall display want
 * different things.
 *
 * <p>Deliberately the same shape as {@link ThemeService} — same persistence, same signal pattern —
 * so there is one way to do user preferences here rather than two.
 */
@Injectable({ providedIn: 'root' })
export class DensityService {
  readonly mode = signal<Density>(readStored());

  constructor() {
    this.apply();
  }

  set(mode: Density): void {
    this.mode.set(mode);
    localStorage.setItem(KEY, mode);
    this.apply();
  }

  toggle(): void {
    this.set(this.mode() === 'compact' ? 'comfortable' : 'compact');
  }

  private apply(): void {
    document.documentElement.setAttribute('data-density', this.mode());
  }
}

function readStored(): Density {
  return localStorage.getItem(KEY) === 'comfortable' ? 'comfortable' : 'compact';
}
