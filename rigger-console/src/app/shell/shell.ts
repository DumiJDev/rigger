import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { AuthService } from '../core/auth.service';
import { DensityService } from '../core/density.service';
import { NamespaceService } from '../core/namespace.service';
import { REFRESH_INTERVALS, RefreshInterval, RefreshService } from '../core/refresh.service';
import { ThemeService } from '../core/theme.service';
import { AVAILABLE_LANGS, LANGUAGE_KEY, Lang } from '../core/transloco';
import { Icon, IconName } from '../shared/icon';

interface NavItem {
  path: string;
  labelKey: string;
  icon: IconName;
  adminOnly?: boolean;
}

interface NavGroup {
  /** Absent for the top group, which needs no heading. */
  labelKey?: string;
  items: NavItem[];
}

/**
 * Application shell: dark masthead, icon sidebar, and the controls every page depends on.
 *
 * <p>Eager rather than lazy because it holds the state feature routes read — the namespace especially,
 * which the API requires on every workload call.
 */
@Component({
  selector: 'r-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TranslocoDirective, Icon],
  templateUrl: './shell.html',
})
export class Shell {
  readonly auth = inject(AuthService);
  readonly ns = inject(NamespaceService);
  readonly theme = inject(ThemeService);
  readonly density = inject(DensityService);
  readonly refresh = inject(RefreshService);
  private readonly transloco = inject(TranslocoService);

  readonly sidebarOpen = signal(true);
  readonly userMenuOpen = signal(false);
  readonly langs = AVAILABLE_LANGS;
  readonly intervals = REFRESH_INTERVALS;
  readonly activeLang = signal<Lang>(this.transloco.getActiveLang() as Lang);

  /**
   * Every destination lives in this structure, including the three that used to be hardcoded blocks
   * in the template. One shape means one rendering path, so the active state and collapsed
   * behaviour can't drift between entries.
   */
  private readonly groups: NavGroup[] = [
    {
      items: [
        { path: '/dashboard', labelKey: 'nav.dashboard', icon: 'gauge' },
        { path: '/topology', labelKey: 'nav.topology', icon: 'network' },
      ],
    },
    {
      labelKey: 'nav.workloads',
      items: [
        { path: '/deployments', labelKey: 'nav.deployments', icon: 'box' },
        { path: '/services', labelKey: 'nav.services', icon: 'share' },
        { path: '/configmaps', labelKey: 'nav.configmaps', icon: 'sliders' },
        { path: '/secrets', labelKey: 'nav.secrets', icon: 'lock' },
        { path: '/pods', labelKey: 'nav.pods', icon: 'layers' },
        { path: '/apply', labelKey: 'nav.apply', icon: 'file-code' },
      ],
    },
    {
      labelKey: 'nav.cluster',
      items: [
        { path: '/nodes', labelKey: 'nav.nodes', icon: 'server' },
        { path: '/cluster', labelKey: 'nav.provisioning', icon: 'settings', adminOnly: true },
        { path: '/gitops', labelKey: 'nav.gitops', icon: 'git' },
      ],
    },
    {
      labelKey: 'nav.administration',
      items: [
        { path: '/audit', labelKey: 'nav.audit', icon: 'clipboard', adminOnly: true },
        { path: '/users', labelKey: 'nav.users', icon: 'users', adminOnly: true },
      ],
    },
  ];

  /** Groups with their admin-only entries removed, and any group left empty dropped entirely. */
  readonly visibleGroups = computed<NavGroup[]>(() => {
    const admin = this.auth.isClusterAdmin();
    return this.groups
      .map((g) => ({ ...g, items: g.items.filter((i) => !i.adminOnly || admin) }))
      .filter((g) => g.items.length > 0);
  });

  readonly themeIcon = computed<IconName>(() =>
    this.theme.mode() === 'light' ? 'sun' : this.theme.mode() === 'dark' ? 'moon' : 'monitor',
  );

  constructor() {
    void this.ns.load();
  }

  setLang(lang: Lang): void {
    this.transloco.setActiveLang(lang);
    this.activeLang.set(lang);
    localStorage.setItem(LANGUAGE_KEY, lang);
  }

  onNamespaceChange(event: Event): void {
    this.ns.set((event.target as HTMLSelectElement).value);
  }

  onIntervalChange(event: Event): void {
    this.refresh.setInterval(Number((event.target as HTMLSelectElement).value) as RefreshInterval);
  }

  /** "off" for 0, otherwise a compact form: 10s, 30s, 1m, 5m. */
  intervalLabel(seconds: RefreshInterval): string {
    if (seconds === 0) return this.transloco.translate('refresh.off');
    return seconds < 60 ? `${seconds}s` : `${seconds / 60}m`;
  }
}
