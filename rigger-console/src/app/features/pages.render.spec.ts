import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTransloco } from '@jsverse/transloco';
import { beforeEach, describe, expect, it } from 'vitest';

import { ApplyPage } from './apply/apply.page';
import { AuditPage } from './audit/audit.page';
import { ClusterPage } from './cluster/cluster.page';
import { NodesPage } from './cluster/nodes.page';
import { DashboardPage } from './dashboard/dashboard.page';
import { GitOpsPage } from './gitops/gitops.page';
import { LoginPage } from './login/login.page';
import { UsersPage } from './users/users.page';
import { ConfigMapsPage } from './workloads/configmaps.page';
import { DeploymentsPage } from './workloads/deployments.page';
import { PodsPage } from './workloads/pods.page';
import { SecretsPage } from './workloads/secrets.page';
import { ServicesPage } from './workloads/services.page';
import { Shell } from '../shell/shell';

/**
 * Renders every page once.
 *
 * <p>Angular compiles templates ahead of time, but a template that references a missing member,
 * misuses a pipe or binds a property that doesn't exist only fails when the component is actually
 * instantiated — which, without this, would first happen in front of a user. This is deliberately
 * shallow: it asserts each page mounts and produces markup, not what it looks like.
 */
class TestLoader {
  getTranslation() {
    return Promise.resolve({});
  }
}

const PAGES: Array<{ name: string; type: unknown }> = [
  { name: 'LoginPage', type: LoginPage },
  { name: 'Shell', type: Shell },
  { name: 'DashboardPage', type: DashboardPage },
  { name: 'DeploymentsPage', type: DeploymentsPage },
  { name: 'ServicesPage', type: ServicesPage },
  { name: 'ConfigMapsPage', type: ConfigMapsPage },
  { name: 'SecretsPage', type: SecretsPage },
  { name: 'PodsPage', type: PodsPage },
  { name: 'ApplyPage', type: ApplyPage },
  { name: 'NodesPage', type: NodesPage },
  { name: 'ClusterPage', type: ClusterPage },
  { name: 'GitOpsPage', type: GitOpsPage },
  { name: 'AuditPage', type: AuditPage },
  { name: 'UsersPage', type: UsersPage },
];

describe('page rendering', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTransloco({
          config: { availableLangs: ['en'], defaultLang: 'en', prodMode: false },
          loader: TestLoader,
        }),
      ],
    });
  });

  for (const { name, type } of PAGES) {
    it(`${name} mounts and renders`, async () => {
      const fixture = TestBed.createComponent(type as never);
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();

      expect(fixture.nativeElement.innerHTML.length).toBeGreaterThan(0);

      // Outstanding requests are fine (pages load on init); unexpected ones are not.
      TestBed.inject(HttpTestingController).match(() => true);
    });
  }
});
