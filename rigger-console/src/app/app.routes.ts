import { Routes } from '@angular/router';
import { authGuard, clusterAdminGuard } from './core/auth.guard';

/**
 * Everything except login sits behind the shell so the namespace picker, theme and locale
 * controls persist across navigation. Feature routes are lazy; the shell is not.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/login/login.page').then((m) => m.LoginPage),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./shell/shell').then((m) => m.Shell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
      {
        path: 'dashboard',
        loadComponent: () => import('./features/dashboard/dashboard.page').then((m) => m.DashboardPage),
      },
      {
        path: 'topology',
        loadComponent: () => import('./features/topology/topology.page').then((m) => m.TopologyPage),
      },
      {
        path: 'deployments',
        loadComponent: () =>
          import('./features/workloads/deployments.page').then((m) => m.DeploymentsPage),
      },
      {
        path: 'services',
        loadComponent: () => import('./features/workloads/services.page').then((m) => m.ServicesPage),
      },
      {
        path: 'configmaps',
        loadComponent: () =>
          import('./features/workloads/configmaps.page').then((m) => m.ConfigMapsPage),
      },
      {
        path: 'secrets',
        loadComponent: () => import('./features/workloads/secrets.page').then((m) => m.SecretsPage),
      },
      {
        path: 'pods',
        loadComponent: () => import('./features/workloads/pods.page').then((m) => m.PodsPage),
      },
      {
        path: 'apply',
        loadComponent: () => import('./features/apply/apply.page').then((m) => m.ApplyPage),
      },
      {
        path: 'nodes',
        loadComponent: () => import('./features/cluster/nodes.page').then((m) => m.NodesPage),
      },
      {
        path: 'cluster',
        canActivate: [clusterAdminGuard],
        loadComponent: () => import('./features/cluster/cluster.page').then((m) => m.ClusterPage),
      },
      {
        path: 'gitops',
        loadComponent: () => import('./features/gitops/gitops.page').then((m) => m.GitOpsPage),
      },
      {
        path: 'audit',
        canActivate: [clusterAdminGuard],
        loadComponent: () => import('./features/audit/audit.page').then((m) => m.AuditPage),
      },
      {
        path: 'users',
        canActivate: [clusterAdminGuard],
        loadComponent: () => import('./features/users/users.page').then((m) => m.UsersPage),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
