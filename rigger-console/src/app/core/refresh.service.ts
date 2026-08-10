import { DestroyRef, Injectable, inject, signal } from '@angular/core';

/** Seconds between refreshes; 0 means off. */
export type RefreshInterval = 0 | 10 | 30 | 60 | 300;

export const REFRESH_INTERVALS: RefreshInterval[] = [0, 10, 30, 60, 300];

const KEY = 'rigger.refreshInterval';

/**
 * Cluster-wide auto-refresh, the way a monitoring console does it.
 *
 * <p>Replaces the per-page "Refresh" button that was repeated across thirteen pages. Pages read
 * {@link tick} inside the same `effect` they already use to react to the current namespace, so
 * subscribing costs them one extra line and no lifecycle management.
 *
 * <p>Off by default on purpose: every tick re-fetches, and some of those calls reach Docker (pod
 * lists, per-Deployment CPU). Turning it on should be the operator's decision, not a default that
 * quietly polls a cluster forever.
 */
@Injectable({ providedIn: 'root' })
export class RefreshService {
  private readonly _tick = signal(0);
  private timer: ReturnType<typeof setInterval> | null = null;

  /** Increments on every refresh, whether automatic or requested by hand. */
  readonly tick = this._tick.asReadonly();
  readonly interval = signal<RefreshInterval>(readStored());

  constructor() {
    this.schedule();
    // A stray interval would keep hitting the API after teardown — relevant in tests, which create
    // and discard the injector repeatedly.
    inject(DestroyRef).onDestroy(() => this.clear());
  }

  /** Forces a refresh now, without disturbing the schedule. */
  refreshNow(): void {
    this._tick.update((n) => n + 1);
  }

  setInterval(seconds: RefreshInterval): void {
    this.interval.set(seconds);
    localStorage.setItem(KEY, String(seconds));
    this.schedule();
  }

  private schedule(): void {
    this.clear();
    const seconds = this.interval();
    if (seconds > 0) {
      this.timer = setInterval(() => this.refreshNow(), seconds * 1000);
    }
  }

  private clear(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}

function readStored(): RefreshInterval {
  const parsed = Number(localStorage.getItem(KEY));
  return (REFRESH_INTERVALS as number[]).includes(parsed) ? (parsed as RefreshInterval) : 0;
}
