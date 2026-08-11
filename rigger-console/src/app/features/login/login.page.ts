import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'r-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, TranslocoDirective],
  template: `
    <ng-container *transloco="let t">
      <div class="grid min-h-screen place-items-center px-4">
        <div class="w-full max-w-sm">
          <!-- Two images rather than one: an <img> cannot pick up the theme, and the lockup needs a
               darker violet on white than it does on graphite. Inlining the SVG would let
               currentColor do it, but then the wordmark paths would be duplicated in the bundle. -->
          <div class="mb-8">
            <img src="brand/lockup-on-light.svg" alt="Rigger" class="h-9 w-auto dark:hidden" />
            <img src="brand/lockup-on-dark.svg" alt="Rigger" class="hidden h-9 w-auto dark:block" />
            <p class="muted mt-2 text-xs leading-tight">{{ t('app.tagline') }}</p>
          </div>

          <div class="surface p-6">
            <h1 class="text-base font-semibold">{{ t('login.title') }}</h1>
            <p class="muted mt-1 text-sm">{{ t('login.subtitle') }}</p>

            @if (expired()) {
              <p
                class="mt-4 rounded-lg px-3 py-2 text-sm"
                style="background-color: color-mix(in oklch, var(--color-warn) 14%, transparent); color: var(--color-warn)"
              >
                {{ t('login.expired') }}
              </p>
            }

            <form class="mt-5 space-y-4" (ngSubmit)="submit()">
              <div>
                <label class="mb-1.5 block text-sm font-medium" for="username">
                  {{ t('login.username') }}
                </label>
                <input
                  id="username"
                  name="username"
                  class="input"
                  autocomplete="username"
                  required
                  [(ngModel)]="username"
                  [disabled]="busy()"
                />
              </div>
              <div>
                <label class="mb-1.5 block text-sm font-medium" for="password">
                  {{ t('login.password') }}
                </label>
                <input
                  id="password"
                  name="password"
                  type="password"
                  class="input"
                  autocomplete="current-password"
                  required
                  [(ngModel)]="password"
                  [disabled]="busy()"
                />
              </div>

              @if (error()) {
                <p class="text-sm" style="color: var(--color-error)">{{ t('login.failed') }}</p>
              }

              <button type="submit" class="btn btn-primary w-full justify-center" [disabled]="busy()">
                {{ busy() ? t('login.submitting') : t('login.submit') }}
              </button>
            </form>
          </div>
        </div>
      </div>
    </ng-container>
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  username = '';
  password = '';
  readonly busy = signal(false);
  readonly error = signal(false);
  readonly expired = signal(this.route.snapshot.queryParamMap.get('expired') === '1');

  async submit(): Promise<void> {
    if (this.busy() || !this.username || !this.password) return;
    this.busy.set(true);
    this.error.set(false);
    this.expired.set(false);
    try {
      await this.auth.login(this.username, this.password);
      const redirect = this.route.snapshot.queryParamMap.get('redirect');
      await this.router.navigateByUrl(redirect ?? '/dashboard');
    } catch {
      this.error.set(true);
    } finally {
      this.busy.set(false);
    }
  }
}
