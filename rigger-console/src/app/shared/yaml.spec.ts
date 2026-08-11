import { describe, expect, it } from 'vitest';
import { toYaml } from './yaml';

/**
 * The drawer's Spec tab is read to decide what to change, so the only property worth testing is
 * faithfulness: what comes out must parse back to what went in. There is no YAML parser in this
 * project's dependencies to prove that mechanically, so each case asserts the exact text — which
 * also pins the formatting an operator copies out of the panel.
 */
describe('toYaml', () => {
  it('renders a flat map', () => {
    expect(toYaml({ image: 'nginx:1.27', replicas: 3, hpa: null, enabled: true })).toBe(
      ['image: nginx:1.27', 'replicas: 3', 'hpa: null', 'enabled: true'].join('\n'),
    );
  });

  it('indents nested maps', () => {
    expect(toYaml({ hpa: { minReplicas: 1, maxReplicas: 5, metric: { type: 'cpu' } } })).toBe(
      ['hpa:', '  minReplicas: 1', '  maxReplicas: 5', '  metric:', '    type: cpu'].join('\n'),
    );
  });

  it('renders an array of scalars with dashes at the parent indent', () => {
    expect(toYaml({ command: ['sh', '-c', 'echo hi'] })).toBe(
      ['command:', "- sh", "- '-c'", '- echo hi'].join('\n'),
    );
  });

  it('puts the first key of an object item on the dash line', () => {
    expect(toYaml({ ports: [{ port: 80, targetPort: 8080 }, { port: 443, targetPort: 8443 }] })).toBe(
      ['ports:', '- port: 80', '  targetPort: 8080', '- port: 443', '  targetPort: 8443'].join('\n'),
    );
  });

  it('nests a map inside an object inside an array', () => {
    expect(toYaml({ items: [{ env: { KEY: 'v' }, tags: ['a'] }] })).toBe(
      ['items:', '- env:', '    KEY: v', '  tags:', '  - a'].join('\n'),
    );
  });

  it('renders empty containers inline', () => {
    expect(toYaml({ labels: {}, ports: [] })).toBe(['labels: {}', 'ports: []'].join('\n'));
    expect(toYaml({})).toBe('{}');
    expect(toYaml([])).toBe('[]');
  });

  /** Each of these would re-parse as something other than the string it is, unquoted. */
  it('quotes strings that would otherwise change type', () => {
    expect(toYaml({ a: '', b: 'true', c: '123', d: '1.5', e: 'null', f: 'no', g: '~' })).toBe(
      ["a: ''", "b: 'true'", "c: '123'", "d: '1.5'", "e: 'null'", "f: 'no'", "g: '~'"].join('\n'),
    );
  });

  it('quotes strings containing YAML syntax', () => {
    expect(toYaml({ a: 'k: v', b: 'trailing ', c: '#hash', d: 'mid # hash', e: 'ends:' })).toBe(
      ["a: 'k: v'", "b: 'trailing '", "c: '#hash'", "d: 'mid # hash'", "e: 'ends:'"].join('\n'),
    );
  });

  it('leaves ordinary strings unquoted, including ones with colons but no space after', () => {
    expect(toYaml({ image: 'registry.local:5000/app:1.0', path: '/var/lib/app' })).toBe(
      ['image: registry.local:5000/app:1.0', 'path: /var/lib/app'].join('\n'),
    );
  });

  it('escapes a single quote by doubling it', () => {
    expect(toYaml({ msg: "it's: fine" })).toBe("msg: 'it''s: fine'");
  });

  it('double-quotes only when a single-quoted scalar could not carry the value', () => {
    // A backslash is literal in a plain scalar, so a Windows path needs no quoting at all; it only
    // has to be escaped once something else forces quotes.
    expect(toYaml({ a: 'say "hi": ok', b: 'C:\\tmp', c: 'C:\\tmp #1' })).toBe(
      ['a: "say \\"hi\\": ok"', 'b: C:\\tmp', 'c: "C:\\\\tmp #1"'].join('\n'),
    );
  });

  /** Chomping: `|-` strips, `|` keeps exactly one, `|+` keeps all. */
  it('renders multi-line strings as block scalars with the right chomping', () => {
    expect(toYaml({ conf: 'a\nb' })).toBe(['conf: |-', '  a', '  b'].join('\n'));
    expect(toYaml({ conf: 'a\nb\n' })).toBe(['conf: |', '  a', '  b'].join('\n'));
    expect(toYaml({ conf: 'a\nb\n\n' })).toBe(['conf: |+', '  a', '  b', ''].join('\n'));
  });

  it('keeps blank lines inside a block scalar genuinely blank', () => {
    expect(toYaml({ conf: 'a\n\nb' })).toBe(['conf: |-', '  a', '', '  b'].join('\n'));
  });

  it('indents a block scalar under its nesting level', () => {
    expect(toYaml({ data: { 'nginx.conf': 'server {\n  listen 80;\n}' } })).toBe(
      ['data:', '  nginx.conf: |-', '    server {', '      listen 80;', '    }'].join('\n'),
    );
  });

  /** A block scalar cannot express these, so they fall back to a double-quoted one-liner. */
  it('falls back to a quoted scalar when a block scalar would lose whitespace', () => {
    expect(toYaml({ a: 'x \ny' })).toBe('a: "x \\ny"');
    expect(toYaml({ a: ' x\ny' })).toBe('a: " x\\ny"');
    expect(toYaml({ a: 'x\r\ny' })).toBe('a: "x\\r\\ny"');
  });

  it('renders a Secret spec exactly as the API redacts it', () => {
    expect(toYaml({ keys: 'redacted' })).toBe('keys: redacted');
  });

  it('drops undefined entries rather than inventing null', () => {
    expect(toYaml({ a: 1, b: undefined, c: 2 })).toBe(['a: 1', 'c: 2'].join('\n'));
  });

  it('renders a whole Deployment spec', () => {
    const spec = {
      image: 'nginx:1.27-alpine',
      replicas: 2,
      ports: [{ containerPort: 80, protocol: 'TCP' }],
      env: { LOG_LEVEL: 'debug', PORT: '8080' },
      configMapRefs: ['app-config'],
      hpa: { minReplicas: 1, maxReplicas: 4, targetCpuPercent: 70 },
    };
    expect(toYaml(spec)).toBe(
      [
        'image: nginx:1.27-alpine',
        'replicas: 2',
        'ports:',
        '- containerPort: 80',
        '  protocol: TCP',
        'env:',
        '  LOG_LEVEL: debug',
        "  PORT: '8080'",
        'configMapRefs:',
        '- app-config',
        'hpa:',
        '  minReplicas: 1',
        '  maxReplicas: 4',
        '  targetCpuPercent: 70',
      ].join('\n'),
    );
  });
});
