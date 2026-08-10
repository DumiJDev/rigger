import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Blocks routes until the stored session has been validated against the backend.
 *
 * <p>{@link AuthService.restore} only calls the server the first time — afterwards it resolves
 * from the signal, so this doesn't add a round-trip to every navigation.
 */
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (await auth.restore()) return true;
  return router.createUrlTree(['/login'], { queryParams: { redirect: state.url } });
};

/** Routes only a cluster-admin can use (users, audit, cluster operations). */
export const clusterAdminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.isClusterAdmin() ? true : router.createUrlTree(['/']);
};
