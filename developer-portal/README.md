# developer-portal

The PaymentFlow merchant developer portal. One Next.js (App Router) application, added in
**M23.1** as the foundation the rest of M23 builds on.

> **State.** This is a foundation, not a product. There is no login, no dashboard and no
> merchant data yet — M23.2 adds authentication, M23.3 the data layer, and M23.4–M23.8 the
> screens. What exists today is the shell, the design system, the API and session scaffolding,
> and the build.

---

## Running it

```bash
npm install
npm run dev
```

`http://localhost:3000` — the landing page, and `/foundation`, which renders every primitive in
the design system. Toggle the theme from the header; both are designed, and neither is an
inversion of the other.

The portal expects a gateway at `PF_GATEWAY_URL` (default `http://localhost:8080`). Nothing in
M23.1 calls it, so the dev server runs happily without one.

| Script                 | What it does                                             |
| ---------------------- | -------------------------------------------------------- |
| `npm run dev`          | Development server                                       |
| `npm run build`        | Production build (`output: 'standalone'`)                |
| `npm run typecheck`    | `tsc --noEmit`, strict plus four extra flags             |
| `npm run lint`         | ESLint                                                   |
| `npm run format:check` | Prettier                                                 |
| `npm run verify`       | All four, in cheapest-first order. This is what CI runs. |

## Configuration

Validated once, at import, by `src/lib/env.ts` — a portal that starts without its session
secret and invents one signs every user out on each deploy, so it refuses to start instead.

| Variable                | Required          | Default                 | Purpose                              |
| ----------------------- | ----------------- | ----------------------- | ------------------------------------ |
| `PF_GATEWAY_URL`        | no                | `http://localhost:8080` | Where this server calls the platform |
| `PORTAL_PUBLIC_ORIGIN`  | no                | `http://localhost:3000` | This portal's own origin             |
| `PORTAL_SESSION_SECRET` | **in production** | dev-only value          | Encrypts the session cookie (M23.2)  |

## How it is put together

### The browser holds no token (D187)

Every call to the platform is made by this server. The browser talks to the portal and to
nothing else, which is why `connect-src 'self'` is affordable in the CSP and why CORS plays no
part in the portal's data path. `src/lib/api` and `src/lib/session` are both marked
`server-only`, so a client component that imports either is a **build error** rather than a
leak.

### It reads the same contract the SDKs do (D182, D188, D189)

`src/generated/` is emitted by `:sdks:shared` from `docs/openapi.yaml` — the same generator, in
the same pass, as the Node SDK's copy. It is committed, and `./gradlew build` fails if it goes
stale or is hand-edited (`verifySdkSources`).

Regenerate with:

```bash
./gradlew :sdks:shared:generateSdkSources
```

The portal does **not** depend on the `sdks/node` package. That client's `apiKey` option is
required and the portal has a session; its retry policy is right for a program calling across
the internet and wrong for a server next to the gateway acting for a human who clicked once.
What it reuses is the generated operation descriptors — paths, query-parameter names, and which
operations require an `Idempotency-Key` all come from the contract rather than from a list kept
here.

### Layout

```
src/
├── app/                    Routing only. No business logic, no fetching.
│   ├── (marketing)/        Public. The landing page.
│   ├── (app)/              The authenticated shell: sidebar, header, mode banner.
│   ├── api/health/         Liveness, for the container healthcheck.
│   ├── layout.tsx          Fonts, providers, skip link.
│   ├── global-error.tsx    The root layout itself failing.
│   └── not-found.tsx
├── components/
│   ├── ui/                 Vendored primitives. Ours to change.
│   ├── layout/             Sidebar, header, mode banner, theme toggle.
│   └── patterns/           Compositions: PageHeader, EmptyState, ErrorState.
├── lib/
│   ├── api/                server-only. Transport + the typed error hierarchy.
│   ├── session/            server-only. The route guards.
│   ├── env.ts              Configuration, validated once.
│   ├── format.ts           Money, dates, ids. The only place a number becomes a string.
│   └── utils.ts            cn()
├── providers/              Client roots, mounted once.
├── generated/              From docs/openapi.yaml. Never hand-edited.
├── styles/tokens.css       The design system's single source of colour, type and motion.
└── types/                  Types that are not derived from the contract.
```

Route groups rather than path segments: `(app)` supplies the chrome, so `/payments` stays
`/payments` instead of becoming `/dashboard/payments` merely because it shares a sidebar.

### shadcn/ui, vendored

The primitives in `components/ui` are **copied into this repository**, not consumed from a
component package — which is how shadcn is designed to be used and what makes their markup,
their classes and their accessibility ours to change. Radix sits underneath the three that need
real behaviour (dialog, dropdown, tooltip), because focus traps, roving tabindex and correct
ARIA are exactly the things that look identical in a screenshot and are unusable without a
mouse.

Rule: `components/ui/*` stays close to upstream so a regeneration is reviewable; our own
compositions live in `components/patterns/*`.

## Design system

The direction comes from `Design/design-system.md`: dark-first, purple and blue accents, subtle
gradients, grid backgrounds, glass only where useful — Stripe, Vercel, Linear and Railway for
the dashboard, and explicitly not Material, not Bootstrap, not neon, not overly rounded.

The references were read for their language rather than their pixels. `src/styles/tokens.css`
carries the result and the reasoning; the short version:

- **Neutrals are blue-violet tinted, not grey.** One hue across every surface, border and muted
  label, so the accent never looks bolted on.
- **Semantic tokens only in components.** `bg-surface`, `text-fg-muted`, `border-border`. A
  component naming a raw ramp is a review failure.
- **Both themes are designed.** Every semantic token is defined twice, and neither is an
  inversion of the other.
- **14px base, six-step scale, one radius family.** Dashboards are dense; the references are
  unanimous.
- **Money, ids and timestamps are tabular.** Proportional digits in a numeric column is the
  clearest "nobody designed this" tell in a financial product.
- **Test mode has its own colour**, deliberately outside the status palette, so amber never has
  to mean two things.

`/foundation` renders all of it.

## Docker

Its own `Dockerfile`, built from **this directory** rather than the repository root:

```bash
docker build -t paymentflow/developer-portal:0.0.1-snapshot developer-portal
```

The root `.dockerignore` excludes `developer-portal/` so the nine service images do not carry
it — which is also why the portal cannot be built from the root context.
`DockerBuildContextConsistencyTest` asserts that exclusion, and discovers the set of Node
toolchain trees by walking the repository rather than from a list, so a second one would fail
the build until it was excluded too.

## What M23.1 deliberately did not build

- **Login, signup, verification, password reset** — M23.2. `src/lib/session/require.ts` has the
  guards and they currently redirect unconditionally, because there is no session cookie yet.
  That is the correct behaviour for a portal with no login: closed, not open.
- **`requireMerchant()` in the `(app)` layout** — the same reason. Calling it today would make
  the shell unreachable and this milestone unreviewable. M23.2 adds the one line.
- **Any data fetching** — `src/lib/api/transport.ts` is complete and has no callers. M23.3
  brings TanStack Query and the first screen that uses it.
- **Breadcrumbs, command palette, mode switching** — M23.3, once there is a route tree deep
  enough to need them and a session to switch mode on.
- **A strict CSP** — the one in `next.config.ts` is `Report-Only` and still admits
  `'unsafe-inline'` for scripts, because the nonce is issued by middleware that arrives in
  M23.2. It says so at the site rather than claiming to be a finished control.
