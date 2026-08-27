# syntax=docker/dockerfile:1

# Build the Spring Boot fat jar inside Docker so a clean VM checkout can run
# `docker compose --profile tools build` without a pre-generated app.jar.
FROM maven:3.9.11-eclipse-temurin-17 AS java-build
WORKDIR /build

COPY pom.xml ./
COPY lib ./lib
COPY src ./src
RUN --mount=type=cache,id=smart-query-maven,target=/root/.m2 \
    mvn -B package \
 && cp "$(find target -maxdepth 1 -type f -name 'smart-query-*.jar' ! -name '*.original' | head -n 1)" /build/app.jar

# Node is required by the ECharts SSR renderer; Java runs the application.
FROM node:20-bookworm-slim AS runtime
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=java-build /opt/java/openjdk /opt/java/openjdk

# The backend starts short-lived Python containers through the host daemon.
COPY --from=docker:25-cli /usr/local/bin/docker /usr/local/bin/docker

WORKDIR /app
COPY --from=java-build /build/app.jar /app/app.jar
COPY tools/echarts-ssr ./tools/echarts-ssr
RUN cd tools/echarts-ssr && npm ci --omit=dev

EXPOSE 9000
STOPSIGNAL SIGTERM
ENTRYPOINT ["java","-jar","/app/app.jar"]
