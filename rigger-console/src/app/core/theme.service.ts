import { Injectable, signal } from '@angular/core';

export type ThemeMode = 'light' | 'dark' | 'system';

const KEY = 'rigger.theme';

/**
 * Light/dark theme, applied as a class on &lt;html&gt;.
 *
 * <p>Three states rather than a boolean toggle: "system" is the default and keeps following the OS
 * as it changes, while an explicit choice pins the theme and survives reloads.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly media = window.matchMedia('(prefers-color-scheme: dark)');
  readonly mode = signal<ThemeMode>(readStored());

  constructor() {
    this.apply();
    // Only relevant while following the system; a pinned choice ignores OS changes.
    this.media.addEventListener('change', () => {
      if (this.mode() === 'system') this.apply();
    });
  }

  set(mode: ThemeMode): void {
    this.mode.set(mode);
    localStorage.setItem(KEY, mode);
    this.apply();
  }

  /** Cycles light → dark → system, for a single toolbar button. */
  cycle(): void {
    const next: ThemeMode =
      this.mode() === 'light' ? 'dark' : this.mode() === 'dark' ? 'system' : 'light';
    this.set(next);
  }

  isDark(): boolean {
    return this.mode() === 'dark' || (this.mode() === 'system' && this.media.matches);
  }

  private apply(): void {
    document.documentElement.classList.toggle('dark', this.isDark());
  }
}

function readStored(): ThemeMode {
  const raw = localStorage.getItem(KEY);
  return raw === 'light' || raw === 'dark' || raw === 'system' ? raw : 'system';
}
