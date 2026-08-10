import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTransloco } from '@jsverse/transloco';
import { beforeEach, describe, expect, it } from 'vitest';
import { Topology } from '../../core/api.models';
import { TopologyPage } from './topology.page';

/** Serves translations from memory so the template renders real labels, not raw keys. */
class TestLoader {
  getTranslation() {
    return Promise.resolve({
      topology: { title: 'Topology', subtitle: '', graph: 'Graph', list: 'List', empty: 'Empty' },
      health: { healthy: 'Healthy', degraded: 'Degraded', down: 'Down', unknown: 'Unknown' },
      common: { refresh: 'Refresh', kind: 'Kind', name: 'Name' },
    });
  }
}

const SAMPLE: Topology = {
  namespace: 'default',
  nodes: [
    {
      id: 'Deployment/web', kind: 'Deployment', name: 'web', image: 'nginx:alpine',
      desiredReplicas: 2, runningReplicas: 2, health: 'healthy', hpaEnabled: false,
    },
    {
      id: 'Service/web-svc', kind: 'Service', name: 'web-svc', image: null,
      desiredReplicas: null, runningReplicas: null, health: 'n/a', hpaEnabled: false,
    },
    {
      id: 'ConfigMap/web-cm', kind: 'ConfigMap', name: 'web-cm', image: null,
      desiredReplicas: null, runningReplicas: null, health: 'n/a', hpaEnabled: false,
    },
  ],
  edges: [
    { from: 'Service/web-svc', to: 'Deployment/web', type: 'exposes' },
    { from: 'Deployment/web', to: 'ConfigMap/web-cm', type: 'mounts' },
  ],
};

describe('TopologyPage', () => {
  let fixture: ComponentFixture<TopologyPage>;
  let page: TopologyPage;
  let http: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [TopologyPage],
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
    fixture = TestBed.createComponent(TopologyPage);
    page = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  it('renders the graph and links every edge to placed nodes', async () => {
    http.expectOne('/api/v1/namespaces/default/topology').flush(SAMPLE);
    await fixture.whenStable();
    fixture.detectChanges();

    // One rect per node — proves the SVG template compiles and binds.
    const rects = fixture.nativeElement.querySelectorAll('svg rect');
    expect(rects.length).toBe(3);

    // Both edges resolved; an edge referencing a missing node would be silently dropped.
    expect(page.links().length).toBe(2);

    const lines = fixture.nativeElement.querySelectorAll('svg line');
    expect(lines.length).toBe(2);
  });

  it('lays kinds out in distinct columns so the graph reads left to right', async () => {
    http.expectOne('/api/v1/namespaces/default/topology').flush(SAMPLE);
    await fixture.whenStable();
    const byKind = Object.fromEntries(page.placed().map((p) => [p.kind, p.x]));
    expect(byKind['Service']).toBeLessThan(byKind['Deployment']);
    expect(byKind['Deployment']).toBeLessThan(byKind['ConfigMap']);
  });

  it('stacks same-kind nodes instead of overlapping them', async () => {
    http.expectOne('/api/v1/namespaces/default/topology').flush({
      namespace: 'default',
      nodes: [
        { ...SAMPLE.nodes[0], id: 'Deployment/a', name: 'a' },
        { ...SAMPLE.nodes[0], id: 'Deployment/b', name: 'b' },
      ],
      edges: [],
    });
    await fixture.whenStable();
    const ys = page.placed().map((p) => p.y);
    expect(new Set(ys).size).toBe(2);
  });

  it('switches to the list view and renders a row per node', async () => {
    http.expectOne('/api/v1/namespaces/default/topology').flush(SAMPLE);
    await fixture.whenStable();

    page.view.set('list');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('table.data tbody tr');
    expect(rows.length).toBe(3);
  });

  it('reports connections for a selected node in both directions', async () => {
    http.expectOne('/api/v1/namespaces/default/topology').flush(SAMPLE);
    await fixture.whenStable();

    page.selected.set(SAMPLE.nodes[0]); // the Deployment
    // Exposed by the Service and mounts the ConfigMap — both must appear.
    expect(page.connectionsOf().map((c) => c.other).sort()).toEqual([
      'ConfigMap/web-cm',
      'Service/web-svc',
    ]);
  });

  it('shows an explicit empty state rather than a blank canvas', async () => {
    http.expectOne('/api/v1/namespaces/default/topology').flush({
      namespace: 'default',
      nodes: [],
      edges: [],
    });
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Empty');
  });

  it('surfaces a forbidden response instead of an empty graph', async () => {
    http
      .expectOne('/api/v1/namespaces/default/topology')
      .flush(null, { status: 403, statusText: 'Forbidden' });
    await fixture.whenStable();
    expect(page.error()).toBe('errors.forbidden');
  });
});
