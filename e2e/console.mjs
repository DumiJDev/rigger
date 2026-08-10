/**
 * Browser walkthrough of the Rigger console against a running server.
 *
 * Exists because every serious defect in this project's UI and API surface was invisible to
 * compilation and to the jsdom unit tests, and only showed up in a real browser: a pattern-based
 * SPA fallback that served index.html for `i18n/en.json` (blank pages, empty console), SSE framing
 * that sent headers and then nothing, a dry run that actually applied, and a theme toggle that
 * appeared to do nothing. Each check below corresponds to one of those.
 *
 * Fails loudly: collects every failure and exits non-zero with a summary, rather than printing and
 * returning 0 like a smoke script.
 *
 * Usage: BASE=https://localhost:7433 ADMIN_PASSWORD=... node console.mjs
 */
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';

const BASE = (process.env.BASE ?? 'https://localhost:7433').replace(/\/$/, '');
const UI = `${BASE}/ui/`;
const USER = process.env.ADMIN_USER ?? 'admin';
const PASS = process.env.ADMIN_PASSWORD;
const SHOTS = process.env.SHOTS_DIR ?? 'shots';
const HEADLESS_CHANNEL = process.env.PW_CHANNEL; // e.g. "chromium" to use the full build

if (!PASS) {
  console.error('ADMIN_PASSWORD is required');
  process.exit(2);
}

mkdirSync(SHOTS, { recursive: true });

const failures = [];
const consoleErrors = [];
const badResponses = [];

function check(name, condition, detail = '') {
  if (condition) {
    console.log(`  ok   ${name}${detail ? ` — ${detail}` : ''}`);
  } else {
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`);
    failures.push(`${name}${detail ? `: ${detail}` : ''}`);
  }
}

const browser = await chromium.launch(HEADLESS_CHANNEL ? { channel: HEADLESS_CHANNEL } : {});
const context = await browser.newContext({
  ignoreHTTPSErrors: true, // dev keystore is self-signed
  viewport: { width: 1440, height: 900 },
});
const page = await context.newPage();

page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text());
});
page.on('response', (r) => {
  // Aborts are expected: the walkthrough navigates faster than requests settle.
  if (r.status() >= 400 && !r.url().includes('favicon')) {
    badResponses.push(`${r.status()} ${r.url()}`);
  }
});

try {
  // ── Login ────────────────────────────────────────────────────────────────
  await page.goto(UI, { waitUntil: 'networkidle' });
  await page.waitForSelector('input#username', { timeout: 20000 });

  // A raw translation key here means the i18n bundle didn't load — the exact symptom of the
  // SPA fallback swallowing nested assets.
  const label = (await page.textContent('label[for="username"]'))?.trim() ?? '';
  check('i18n bundle resolved', label.length > 0 && !label.includes('login.'), `label="${label}"`);

  await page.fill('input#username', USER);
  await page.fill('input#password', PASS);
  await page.click('button[type="submit"]');
  await page.waitForURL('**/dashboard', { timeout: 20000 });
  check('login lands on dashboard', page.url().includes('/dashboard'));
  await page.screenshot({ path: `${SHOTS}/01-dashboard.png` });

  // ── Topology ─────────────────────────────────────────────────────────────
  await page.click('a[href="/ui/topology"]');
  await page.waitForURL('**/topology', { timeout: 15000 });
  await page.waitForTimeout(2000);

  const nodeCount = await page.$$eval('svg rect', (r) => r.length);
  check('topology graph renders nodes', nodeCount > 0, `${nodeCount} node(s)`);
  await page.screenshot({ path: `${SHOTS}/02-topology.png` });

  if (nodeCount > 0) {
    await page.click('svg g');
    await page.waitForTimeout(500);
    check('topology detail panel opens', (await page.$('aside')) !== null);
  }

  await page.getByRole('button', { name: /^(Lista|List)$/ }).click();
  await page.waitForTimeout(700);
  const listRows = await page.$$eval('table.data tbody tr', (r) => r.length);
  check('topology list view renders rows', listRows > 0, `${listRows} row(s)`);

  // ── Theme ────────────────────────────────────────────────────────────────
  // Targeted by aria-label, not position. Positional selectors passed until the masthead gained
  // refresh and density controls, then silently started clicking the wrong thing — which is the
  // failure mode that makes an e2e suite worse than none.
  //
  // From "system" the first click must flip away from what's on screen; cycling blindly to
  // "light" while already light made the toggle look broken.
  await page.locator('header button[aria-label="Theme"], header button[aria-label="Tema"]').click();
  await page.waitForTimeout(400);
  const isDark = await page.evaluate(() => document.documentElement.classList.contains('dark'));
  check('theme toggle flips away from the visible theme', isDark === true, `dark=${isDark}`);
  await page.screenshot({ path: `${SHOTS}/03-dark.png` });

  // ── Density ──────────────────────────────────────────────────────────────
  // Compact by default; the toggle must actually change the attribute the row-height tokens hang
  // off, since nothing else in the DOM would reveal that it didn't.
  const densityBefore = await page.getAttribute('html', 'data-density');
  await page.locator('header button[aria-label="Density"], header button[aria-label="Densidade"]').click();
  await page.waitForTimeout(300);
  const densityAfter = await page.getAttribute('html', 'data-density');
  check(
    'density toggle switches the row-height tokens',
    densityBefore === 'compact' && densityAfter === 'comfortable',
    `${densityBefore} -> ${densityAfter}`,
  );

  // ── Language ─────────────────────────────────────────────────────────────
  await page.click('a[href="/ui/deployments"]');
  await page.waitForURL('**/deployments', { timeout: 15000 });
  await page.waitForTimeout(1200);
  await page.locator('header select[aria-label="Language"], header select[aria-label="Idioma"]').selectOption('en');
  await page.waitForTimeout(1000);
  const heading = (await page.textContent('h1'))?.trim() ?? '';
  check('runtime language switch applies', heading === 'Deployments', `h1="${heading}"`);

  const deploymentRows = await page.$$eval('table.data tbody tr', (r) => r.length);
  check('deployments list renders', deploymentRows > 0, `${deploymentRows} row(s)`);

  // ── Auto-refresh ─────────────────────────────────────────────────────────
  // One masthead interval replaced the Refresh button that used to sit on eleven pages, so two
  // things have to hold: the old buttons are gone, and the replacement actually re-fetches.
  const strayRefreshButtons = await page.locator('main button', { hasText: /^(Refresh|Atualizar)$/ }).count();
  check('no per-page Refresh buttons remain', strayRefreshButtons === 0, `${strayRefreshButtons} found`);

  let deploymentFetches = 0;
  const countFetches = (req) => {
    if (/\/api\/v1\/namespaces\/[^/]+\/deployments(\?|$)/.test(req.url())) deploymentFetches += 1;
  };
  page.on('request', countFetches);
  await page
    .locator('header select[aria-label="Refresh interval"], header select[aria-label="Intervalo de atualização"]')
    .selectOption('10');
  // Just over two intervals, so a single boundary miss doesn't fail the check.
  await page.waitForTimeout(23000);
  page.off('request', countFetches);
  check(
    'auto-refresh re-fetches on its interval',
    deploymentFetches >= 2,
    `${deploymentFetches} fetch(es) in 23s at 10s`,
  );
  // Back to off before continuing: a live refresh re-runs each page's load, and the log stream
  // further down would be torn down mid-read by one.
  await page
    .locator('header select[aria-label="Refresh interval"], header select[aria-label="Intervalo de atualização"]')
    .selectOption('0');

  // ── Pod logs over SSE ────────────────────────────────────────────────────
  await page.click('a[href="/ui/pods"]');
  await page.waitForURL('**/pods', { timeout: 15000 });
  await page.waitForTimeout(2000);

  const runningRow = page.locator('table.data tbody tr', { hasText: /RUNNING/i }).first();
  const hasRunning = (await runningRow.count()) > 0;
  check('at least one running pod to stream from', hasRunning);

  if (hasRunning) {
    await runningRow.locator('button').click();
    await page.waitForTimeout(6000);
    const logLines = await page.$$eval('.font-mono.text-xs div', (d) => d.length);
    // Zero lines was the signature of SSE sending headers and then nothing at all.
    check('pod logs stream over SSE', logLines > 0, `${logLines} line(s)`);
    await page.screenshot({ path: `${SHOTS}/04-logs.png` });
    const close = page.getByRole('button', { name: /^(Fechar|Close)$/ }).last();
    if (await close.count()) await close.click();
  }

  // ── Apply, dry run ───────────────────────────────────────────────────────
  await page.click('a[href="/ui/apply"]');
  await page.waitForURL('**/apply', { timeout: 15000 });
  const probeName = `e2e-dryrun-probe`;
  await page.fill(
    'textarea',
    `apiVersion: rigger.io/v1
kind: ConfigMap
metadata:
  name: ${probeName}
  namespace: default
spec:
  data:
    SOURCE: "e2e"`,
  );
  await page.check('input[type="checkbox"]');
  await page.getByRole('button', { name: /^(Aplicar|Apply)$/ }).click();
  await page.waitForTimeout(2500);
  const applyMsg = (await page.textContent('p.rounded-lg'))?.trim() ?? '';
  check('dry run reports validation', /valid/i.test(applyMsg), `"${applyMsg}"`);

  // A dry run must not create anything. This caught dryRun persisting for real.
  await page.click('a[href="/ui/configmaps"]');
  await page.waitForURL('**/configmaps', { timeout: 15000 });
  await page.waitForTimeout(1500);
  const bodyText = (await page.textContent('body')) ?? '';
  check('dry run created nothing', !bodyText.includes(probeName));

  // ── Admin pages ──────────────────────────────────────────────────────────
  for (const [path, name] of [
    ['/ui/gitops', 'gitops'],
    ['/ui/nodes', 'nodes'],
    ['/ui/audit', 'audit'],
    ['/ui/users', 'users'],
  ]) {
    await page.click(`a[href="${path}"]`);
    await page.waitForURL(`**${path.replace('/ui', '')}`, { timeout: 15000 });
    await page.waitForTimeout(1200);
    const rendered = ((await page.textContent('h1')) ?? '').trim().length > 0;
    check(`${name} page renders`, rendered);
  }

  // ── Deep link survives a reload ──────────────────────────────────────────
  await page.goto(`${UI}topology`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  check('deep link survives a full reload', page.url().includes('/topology'));

  // ── Global hygiene ───────────────────────────────────────────────────────
  check('browser console is clean', consoleErrors.length === 0, consoleErrors.join(' | '));
  check('no 4xx/5xx responses', badResponses.length === 0, badResponses.join(' | '));
} catch (e) {
  failures.push(`walkthrough threw: ${e?.message ?? e}`);
  await page.screenshot({ path: `${SHOTS}/99-failure.png` }).catch(() => {});
} finally {
  await browser.close();
}

console.log('');
if (failures.length) {
  console.error(`${failures.length} check(s) failed:`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log('All console checks passed.');
