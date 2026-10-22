FROM node:20.18.1-alpine3.20 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27.3-alpine3.20 AS runtime
COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=build /app/dist /usr/share/nginx/html
RUN mkdir -p /tmp/client_body /tmp/proxy /tmp/fastcgi /tmp/uwsgi /tmp/scgi && chown -R nginx:nginx /tmp/client_body /tmp/proxy /tmp/fastcgi /tmp/uwsgi /tmp/scgi /usr/share/nginx/html
USER nginx
EXPOSE 8080
ENTRYPOINT ["nginx", "-g", "daemon off;"]
