# 前端镜像：构建阶段 vite build，运行阶段 nginx 托管 SPA + 反代 /api 到后端
FROM node:20-bookworm AS build
WORKDIR /build
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
