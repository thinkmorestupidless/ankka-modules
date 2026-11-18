# The reference player site (spec 027): the player SDK's site server and the built pages, nothing
# of the platform's; the platform runs its proxy beside it and gives it PORT and
# ANKKA_SERVICES_URL. Built from the repository root, so the npm workspace is in context:
#   docker build -f services/player-site/Dockerfile .
# Dockerfile.dockerignore beside this file admits only what the build needs.
FROM node:24-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
COPY sdk/player/package.json sdk/player/
COPY services/player-site/package.json services/player-site/
RUN npm ci --workspace player-site --include-workspace-root
COPY sdk/player sdk/player
COPY services/player-site services/player-site
RUN npm run build --workspace player-site && npm prune --omit=dev --workspace player-site --include-workspace-root

FROM node:24-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/sdk/player/package.json sdk/player/
COPY --from=build /app/sdk/player/src sdk/player/src
COPY --from=build /app/services/player-site/package.json services/player-site/
COPY --from=build /app/services/player-site/dist services/player-site/dist
COPY --from=build /app/services/player-site/src/server services/player-site/src/server
USER 1000
CMD ["node", "services/player-site/src/server/main.ts"]
