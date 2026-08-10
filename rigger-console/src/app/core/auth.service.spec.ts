import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { AuthService } from './auth.service';

/**
 * Lets pending promise callbacks run. loadPermissions is only issued once the login/me response
 * has been awaited, so without this the follow-up request hasn't been made yet when we assert.
 */
const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  it('stores the token and identity on login', async () => {
    const done = auth.login('admin', 'pw');

    http.expectOne('/api/v1/auth/login').flush({
      token: 'jwt-123',
      username: 'admin',
      role: 'CLUSTER_ADMIN',
      namespace: null,
      expiresIn: 900,
    });
    await tick();
    http.expectOne('/api/v1/auth/permissions').flush({
      role: 'CLUSTER_ADMIN',
      namespace: '',
      permissions: { Deployment: ['get', 'scale'] },
    });

    await done;
    expect(auth.token).toBe('jwt-123');
    expect(auth.user()?.username).toBe('admin');
    expect(auth.isClusterAdmin()).toBe(true);
  });

  it('reports permissions per resource, not globally', async () => {
    const done = auth.login('viewer', 'pw');
    http.expectOne('/api/v1/auth/login').flush({
      token: 't',
      username: 'viewer',
      role: 'VIEWER',
      namespace: 'production',
      expiresIn: 900,
    });
    await tick();
    http.expectOne('/api/v1/auth/permissions').flush({
      role: 'VIEWER',
      namespace: 'production',
      permissions: { Deployment: ['get'], Pod: ['get', 'logs'] },
    });
    await done;

    expect(auth.can('get', 'Deployment')).toBe(true);
    expect(auth.can('logs', 'Pod')).toBe(true);
    // A permission granted on one resource must not leak to another.
    expect(auth.can('logs', 'Deployment')).toBe(false);
    expect(auth.can('delete', 'Deployment')).toBe(false);
    expect(auth.can('get', 'Secret')).toBe(false);
  });

  it('treats a rejected /auth/me as a dead session rather than trusting localStorage', async () => {
    localStorage.setItem('rigger.token', 'stale');
    localStorage.setItem(
      'rigger.user',
      JSON.stringify({ username: 'ghost', role: 'VIEWER', namespace: 'x', active: true }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const service = TestBed.inject(AuthService);
    const client = TestBed.inject(HttpTestingController);

    const restored = service.restore();
    client.expectOne('/api/v1/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(await restored).toBe(false);
    expect(service.token).toBeNull();
    expect(service.user()).toBeNull();
  });

  it('only calls the server once when restoring, however many guards ask', async () => {
    localStorage.setItem('rigger.token', 'good');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const service = TestBed.inject(AuthService);
    const client = TestBed.inject(HttpTestingController);

    const first = service.restore();
    const second = service.restore();

    client.expectOne('/api/v1/auth/me').flush({
      username: 'admin',
      role: 'CLUSTER_ADMIN',
      namespace: null,
      active: true,
    });
    await tick();
    client.expectOne('/api/v1/auth/permissions').flush({
      role: 'CLUSTER_ADMIN',
      namespace: '',
      permissions: {},
    });

    expect(await first).toBe(true);
    expect(await second).toBe(true);
    // A third call after settling must not produce another request.
    expect(await service.restore()).toBe(true);
    client.verify();
  });
});
