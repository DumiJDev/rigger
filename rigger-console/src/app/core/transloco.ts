import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Translation, TranslocoLoader, provideTransloco } from '@jsverse/transloco';

export const LANGUAGE_KEY = 'rigger.lang';
export const AVAILABLE_LANGS = ['pt', 'en'] as const;
export type Lang = (typeof AVAILABLE_LANGS)[number];

@Injectable({ providedIn: 'root' })
export class HttpTranslocoLoader implements TranslocoLoader {
  private readonly http = inject(HttpClient);
  getTranslation(lang: string) {
    // Served from public/, so it lands under the app's baseHref (/ui/) in the built jar.
    return this.http.get<Translation>(`i18n/${lang}.json`);
  }
}

export function storedLang(): Lang {
  const stored = localStorage.getItem(LANGUAGE_KEY);
  if (stored === 'pt' || stored === 'en') return stored;
  return navigator.language?.toLowerCase().startsWith('pt') ? 'pt' : 'en';
}

export const translocoProviders = provideTransloco({
  config: {
    availableLangs: [...AVAILABLE_LANGS],
    defaultLang: storedLang(),
    reRenderOnLangChange: true,
    // A missing key renders as the key itself, which is visible in the UI rather than silent.
    missingHandler: { logMissingKey: true, useFallbackTranslation: true },
    fallbackLang: 'en',
    prodMode: false,
  },
  loader: HttpTranslocoLoader,
});
