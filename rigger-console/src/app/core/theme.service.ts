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

  /**
   * Advances the single toolbar button: explicit choices cycle light → dark → system, and from
   * "system" the next click flips to the opposite of what's currently on screen.
   *
   * <p>Cycling blindly to "light" from "system" is what makes these toggles feel broken — a user
   * already looking at a light page clicks it and nothing appears to happen.
   */
  cycle(): void {
    const next: ThemeMode =
      this.mode() === 'light' ? 'dark'
      : this.mode() === 'dark' ? 'system'
      : this.media.matches ? 'light' : 'dark';
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
