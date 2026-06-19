# syntax=docker/dockerfile:1
# 后端运行镜像（运行时态）。
# jar 在 docker build 之前由本地/CI 打好：
#   cd backend && ../tools/apache-maven-3.9.16/bin/mvn -DskipTests package \
#     && cp target/smart-query-*.jar app.jar
# 运行时 = Node20（ECharts SSR 需 node 调 tools/echarts-ssr/render.mjs）+ JRE17
# 用 scripts/build.sh 或 scripts/airpack.sh 会自动完成 mvn + cp + build。

FROM node:20-bookworm
RUN apt-get update \
 && apt-get install -y --no-install-recommends openjdk-17-jre-headless \
 && rm -rf /var/lib/apt/lists/*

# docker CLI：后端经宿主 docker socket 以 `docker run` 调 smart-query-python 镜像（DooD）
# 仅装客户端二进制，daemon 用宿主的（compose 已挂 /var/run/docker.sock）
COPY --from=docker:25-cli /usr/local/bin/docker /usr/local/bin/docker

WORKDIR /app
COPY app.jar /app/app.jar
# ECharts SSR 脚本 + node 依赖（后端用 ProcessBuilder("node", render.mjs) 调用）
COPY tools/echarts-ssr ./tools/echarts-ssr
RUN cd tools/echarts-ssr && (npm ci --omit=dev || npm install --omit=dev)

EXPOSE 9000
ENTRYPOINT ["java","-jar","/app/app.jar"]
