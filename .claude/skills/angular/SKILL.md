---
name: angular
description: Conventions for Rigger's Angular 22 console (rigger-console) — standalone components and signals, Tailwind 4, Transloco i18n, dark mode, JWT auth, the namespace model, and how the build embeds into the Spring Boot jar. Use when adding or changing anything in the web UI.
---

# Rigger console (Angular 22)

The operator's web UI. Separate codebase, but its build output is embedded into the Spring Boot jar
— the deployment is a single monolith, same as the CLI's target.

## Build contract with the backend (don't break this)

- Build output goes to `rigger-console/dist/`; `rigger-server`'s pom builds it
  (frontend-maven-plugin at `generate-resources`) and declares `dist` as a resource root with
  `targetPath=static/ui`, so it lands in the jar. Never point the Angular build back into
  `src/main/resources` — that generates into the source tree.
- `baseHref` is `/ui/` and the router must match it. `UiResourceConfig` serves `/ui/**` by file
  existence — real files are served, anything else falls back to `index.html` so deep links work —
  and `/ui/**` is `permitAll` in Spring Security. Don't replace that with a pattern-based forward:
  a blanket one matches `index.html` itself and recurses, and excluding only dotted first segments
  still swallows nested assets like `i18n/en.json`, which arrives at Transloco as HTML and leaves
  every page blank with nothing in the console.
- API and UI are same-origin, so **no CORS config exists anywhere** — keep it that way. The dev
  server proxies `/api` and `/actuator` to `https://localhost:7433` with TLS verification off
  (self-signed dev cert).
- `mvn package` builds the UI. Use `-Dui.skip=true` for backend-only iteration — in particular with
  `spring-boot:run`, which otherwise re-runs `npm ci` on every restart. For UI work use `ng serve`.

## Framework conventions

Standalone components and signals throughout — no NgModules, no `@Input()`/`@Output()` decorators
(use `input()`/`output()`), no constructor DI where `inject()` reads better. Functional
interceptors and guards (`provideHttpClient(withInterceptors([...]))`), not class-based.

Prefer `@if` / `@for` / `@switch` control flow over the legacy structural directives. `@for`
requires `track`.

Lazy-load feature routes with `loadComponent`; the shell (nav, namespace picker, theme, locale)
stays eager.

## Auth model

`POST /api/v1/auth/login` returns `{token, username, role, namespace, expiresIn}`. Send it as
`Authorization: Bearer <token>` on every request via the auth interceptor.

- **There is no refresh token.** The backend doesn't have that endpoint. On 401, clear the session
  and route to `/login` — don't build retry logic around an endpoint that doesn't exist.
- Token lives in `localStorage`; a deliberate trade-off for an internal ops tool, documented in
  `CLAUDE.md`.
- `GET /api/v1/auth/me` gives the current identity for the user widget.
- `GET /api/v1/auth/permissions` returns `{resource: [actions]}` for the caller's role — drive
  button visibility from it rather than hard-coding a copy of the RBAC table. It's presentation
  only; the server still authorizes independently, so always handle a 403 response too.

## Namespace model

There is no server-side "current namespace" — the backend derives it from the URL path of each
request. So the console holds the selected namespace in a shared signal (persisted to
`localStorage`) and interpolates it into every workload call:
`/api/v1/namespaces/{ns}/deployments`. Cluster-scoped endpoints (`/cluster`, `/users`, `/audit`,
`/events`) ignore it.

`GET /api/v1/namespaces` populates the picker. Namespace-scoped users get back only their own
namespace, so the picker should render as fixed rather than a dropdown for them.

## Styling

Tailwind 4 with CSS-first configuration — theme tokens live in `@theme` inside the stylesheet,
there is no `tailwind.config.js`. Dark mode is class-based on `<html>`, persisted, defaulting to
`prefers-color-scheme`. Define colours as tokens and use them; the previous UI defined a brand
palette and then ignored it in favour of hard-coded hex values, which is what made it look
unfinished.

Aim for calm and legible over decorated: consistent spacing scale, one accent colour, real
loading/empty/error states for every data view (an empty table with no explanation is a bug).

## No webfonts

Don't add a Google Fonts (or any remote) `<link>`. Angular's production build inlines fonts by
*fetching them at build time*, so a remote font makes `mvn package` fail whenever the network is
unavailable — and it would have every operator's browser calling a third party on page load, which
is wrong for a console that may run in an air-gapped cluster. The `--font-sans` token leads with
Inter (used if the machine has it) and falls back to the platform UI font.

## i18n

Transloco, runtime-switchable, `pt` (default) and `en`. Every user-visible string goes through a
translation key — no literals in templates. Keep both locale files in sync when adding keys; a
missing key renders as the key itself, which is a visible defect.

## Streaming logs

Two endpoints, separated by path rather than by `Accept`:
- `GET .../pods/{pod}/logs/stream` → SSE, one event per line. This is the browser's.
- `GET .../pods/{pod}/logs` → raw chunked bytes, what `riggerctl` consumes. Don't change its framing.

They were originally one path with different `produces`, but content negotiation had to break the
tie for the wildcard Accept the CLI sends and picked event-stream, so `riggerctl logs` started
printing `data:` prefixes. Distinct paths keep each client's framing unambiguous.

`EventSource` can't set an `Authorization` header, so the console uses `fetch` with a
`ReadableStream` reader and parses `data:` lines itself.

## Verifying

```bash
cd rigger-console && npm ci && npm run build     # must emit into static/ui
npm start                                        # dev server with API proxy
```

CI runs `e2e/console.mjs` (Playwright) against a server started from the fat jar. It asserts real
things — i18n resolves, SSE logs arrive, a dry run creates nothing, the theme toggle flips, the
browser console is clean — and exits non-zero on any failure. Add a check there when you fix a UI bug.

Then click through against a real backend: login, switch namespace, apply a manifest, watch
topology, stream logs, toggle theme and locale. Check the browser console is clean — a 404 on a
lazy chunk or a missing translation key won't fail the build.
