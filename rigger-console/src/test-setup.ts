/**
 * jsdom doesn't implement matchMedia, which ThemeService uses to follow the OS colour scheme.
 * Every real browser has had it for over a decade, so this is a gap in the test environment
 * rather than something the app should defend against.
 */
if (!window.matchMedia) {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}
