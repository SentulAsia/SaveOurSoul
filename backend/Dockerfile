FROM node:22-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY src ./src
ENV NODE_ENV=production PORT=8080
EXPOSE 8080
USER node
# Set APP_ENV, DATABASE_URL (postgres://…) and RESPONDER_API_KEY at run time.
CMD ["npm", "start"]
