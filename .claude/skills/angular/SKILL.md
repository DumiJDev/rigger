---
name: angular
description: Conventions for Rigger's Angular 22 console (rigger-console) — standalone components and signals, Tailwind 4, Transloco i18n, dark mode, JWT auth, the namespace model, and how the build embeds into the Spring Boot jar. Use when adding or changing anything in the web UI.
---

# Rigger console (Angular 22)

The operator's web UI. Separate codebase, but its build output is embedded into the Spring Boot jar
— the deployment is a single monolith, same as the CLI's target.

## Build contract with the backend (don't break this)

- Build output goes to `rigger-server/src/main/resources/static/ui` — there is no copy step.
- `baseHref` is `/ui/` and the router must match it: `UiController` forwards `/ui/**` to
  `index.html` for deep links, and `/ui/**` is `permitAll` in Spring Security.
- API and UI are same-origin, so **no CORS config exists anywhere** — keep it that way. The dev
  server proxies `/api` and `/actuator` to `https://localhost:7433` with TLS verification off
  (self-signed dev cert).
- `mvn` does not build the UI. It's a separate `npm run build`.

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

## i18n

Transloco, runtime-switchable, `pt` (default) and `en`. Every user-visible string goes through a
translation key — no literals in templates. Keep both locale files in sync when adding keys; a
missing key renders as the key itself, which is a visible defect.

## Streaming logs

`GET /api/v1/namespaces/{ns}/pods/{pod}/logs` serves two framings, chosen by `Accept`:
- `text/event-stream` → SSE, one event per line. Use this in the browser.
- `text/plain` → raw chunked bytes, what `riggerctl` consumes. Don't change its framing.

`EventSource` can't set an `Authorization` header, so use `fetch` with a `ReadableStream` reader
and parse `data:` lines, or pass the token another way — verify whichever you pick against a
running server.

## Verifying

```bash
cd rigger-console && npm ci && npm run build     # must emit into static/ui
npm start                                        # dev server with API proxy
```

Then click through against a real backend: login, switch namespace, apply a manifest, watch
topology, stream logs, toggle theme and locale. Check the browser console is clean — a 404 on a
lazy chunk or a missing translation key won't fail the build.
