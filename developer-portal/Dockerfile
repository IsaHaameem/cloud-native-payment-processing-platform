# The developer portal (M23.1).
#
# Its own Dockerfile rather than the repository's shared one: that file is parameterised over
# Gradle modules and builds a Spring Boot layered jar. This builds a Node application, and the
# two have nothing in common but the word "stage".
#
# Build from the portal directory, not the repository root:
#   docker build -t paymentflow/developer-portal:0.0.1-snapshot developer-portal
#
# That matters. The root context excludes `developer-portal/` (see .dockerignore) precisely so
# the nine service images do not carry it, which means the portal cannot be built from there.

# ── Stage 1: dependencies ──────────────────────────────────────────────────────────────────
# Separate from the build so a source change does not re-resolve the dependency tree. `npm ci`
# installs exactly the lockfile, which is what makes an image reproducible rather than merely
# repeatable.
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# ── Stage 2: build ─────────────────────────────────────────────────────────────────────────
FROM node:22-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# Telemetry is off by design: this is a payments platform, and a build that phones home is a
# build that has to be explained.
ENV NEXT_TELEMETRY_DISABLED=1

# `env.ts` validates configuration at import time, and `next build` imports the modules it
# renders. Placeholders here satisfy the shape without pretending to be real values — the
# runtime stage takes them from the environment, and production refuses to start without them.
ENV PF_GATEWAY_URL=http://gateway-service:8080 \
    PORTAL_PUBLIC_ORIGIN=http://localhost:3000

RUN npm run build

# ── Stage 3: runtime ───────────────────────────────────────────────────────────────────────
# `output: 'standalone'` in next.config.ts produces a server bundle with only the files it
# actually traced, so the runtime image carries neither the full node_modules tree nor the
# toolchain that produced it.
FROM node:22-alpine AS runtime
WORKDIR /app

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# Non-root, matching every other image in this repository. `node` already exists in the base
# image, so this is a change of owner rather than a new account.
RUN addgroup -S paymentflow && adduser -S paymentflow -G paymentflow

COPY --from=builder --chown=paymentflow:paymentflow /app/public ./public
COPY --from=builder --chown=paymentflow:paymentflow /app/.next/standalone ./
COPY --from=builder --chown=paymentflow:paymentflow /app/.next/static ./.next/static

USER paymentflow
EXPOSE 3000

# Hits the app's own health route rather than `/`, so the check does not depend on a page that
# renders. `wget` is in the base image; adding curl for a healthcheck would be a dependency
# whose only purpose is to be checked.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:3000/api/health || exit 1

CMD ["node", "server.js"]
