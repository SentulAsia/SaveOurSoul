FROM node:22-alpine
WORKDIR /app
COPY package.json package-lock.json ./
COPY src ./src
ENV NODE_ENV=production DATABASE_PATH=/data/sos.db PORT=8080
RUN mkdir -p /data && chown node:node /data
VOLUME /data
EXPOSE 8080
USER node
CMD ["node", "--disable-warning=ExperimentalWarning", "src/server.ts"]
