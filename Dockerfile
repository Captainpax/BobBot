# syntax=docker/dockerfile:1.7

# Stage 1: Build Node.js API
FROM node:22-alpine AS node-build
WORKDIR /app/osrs-api
COPY osrs-api/package*.json ./
RUN npm install
COPY osrs-api/ .
RUN npm run build

# Stage 2: Build Java Bot
FROM gradle:8.14.3-jdk21 AS java-build
WORKDIR /app
COPY . .
RUN gradle --no-daemon clean installDist

# Stage 3: Final Image
FROM eclipse-temurin:21-jre
WORKDIR /app

# Install Node.js 22
RUN apt-get update && apt-get install -y curl && \
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Copy Java Bot
COPY --from=java-build /app/build/install/bobbot /app

# Copy Node.js API
COPY --from=node-build /app/osrs-api/dist /app/osrs-api/dist
COPY --from=node-build /app/osrs-api/node_modules /app/osrs-api/node_modules
COPY --from=node-build /app/osrs-api/package.json /app/osrs-api/package.json

COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh /app/bin/bobbot

ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENV OSRS_API_URL="http://localhost:3000"

# OSRS API port
EXPOSE 3000
# Health port (default 8080)
EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]
