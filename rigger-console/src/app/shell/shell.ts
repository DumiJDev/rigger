import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { AuthService } from '../core/auth.service';
import { NamespaceService } from '../core/namespace.service';
import { ThemeService } from '../core/theme.service';
import { AVAILABLE_LANGS, LANGUAGE_KEY, Lang } from '../core/transloco';

interface NavItem {
  path: string;
  labelKey: string;
  icon: string;
  adminOnly?: boolean;
}

/**
 * Application shell: sidebar, namespace picker, theme and language controls, user menu.
 *
 * <p>Eager (not lazy) because it holds the state every feature route reads — namespace especially,
 * which the API requires on every workload call.
 */
@Component({
  selector: 'r-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TranslocoDirective],
  templateUrl: './shell.html',
})
export class Shell {
  readonly auth = inject(AuthService);
  readonly ns = inject(NamespaceService);
  readonly theme = inject(ThemeService);
  private readonly transloco = inject(TranslocoService);

  readonly sidebarOpen = signal(true);
  readonly userMenuOpen = signal(false);
  readonly langs = AVAILABLE_LANGS;

  readonly activeLang = signal<Lang>(this.transloco.getActiveLang() as Lang);

  readonly workloadNav: NavItem[] = [
    { path: '/deployments', labelKey: 'nav.deployments', icon: 'box' },
    { path: '/services', labelKey: 'nav.services', icon: 'share' },
    { path: '/configmaps', labelKey: 'nav.configmaps', icon: 'sliders' },
    { path: '/secrets', labelKey: 'nav.secrets', icon: 'lock' },
    { path: '/pods', labelKey: 'nav.pods', icon: 'layers' },
  ];

  readonly clusterNav: NavItem[] = [
    { path: '/nodes', labelKey: 'nav.nodes', icon: 'server' },
    { path: '/cluster', labelKey: 'nav.cluster', icon: 'settings', adminOnly: true },
    { path: '/gitops', labelKey: 'nav.gitops', icon: 'git' },
  ];

  readonly adminNav: NavItem[] = [
    { path: '/audit', labelKey: 'nav.audit', icon: 'clipboard', adminOnly: true },
    { path: '/users', labelKey: 'nav.users', icon: 'users', adminOnly: true },
  ];

  readonly visibleClusterNav = computed(() =>
    this.clusterNav.filter((i) => !i.adminOnly || this.auth.isClusterAdmin()),
  );
  readonly visibleAdminNav = computed(() =>
    this.adminNav.filter((i) => !i.adminOnly || this.auth.isClusterAdmin()),
  );

  constructor() {
    void this.ns.load();
  }

  setLang(lang: Lang): void {
    this.transloco.setActiveLang(lang);
    this.activeLang.set(lang);
    localStorage.setItem(LANGUAGE_KEY, lang);
  }

  themeLabelKey(): string {
    return `theme.${this.theme.mode()}`;
  }

  onNamespaceChange(event: Event): void {
    this.ns.set((event.target as HTMLSelectElement).value);
  }
}
