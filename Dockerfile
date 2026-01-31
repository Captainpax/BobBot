# syntax=docker/dockerfile:1.7
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /app
COPY . .
RUN ./gradlew --no-daemon clean installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/bobbot /app
ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENTRYPOINT ["/app/bin/bobbot"]
