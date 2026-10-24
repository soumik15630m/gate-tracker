FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=3001 HOST=0.0.0.0 DATA_DIR=/data
COPY --from=build /app/dist ./dist
COPY server ./server
COPY shared ./shared
RUN mkdir -p /data && chown node:node /data
USER node
EXPOSE 3001
VOLUME ["/data"]
CMD ["node", "server/index.mjs"]
