# Changes on core

Eagle uses two modules of Sunbird core.
  1. NGINX docker service (proxy_proxy)
  2. Keycloak

Both these require changes for them to work with Eagle.

## Changes on proxy_proxy
The following changes need to be made on proxy_proxy service
  1. <b>proxy_default.conf</b> file needs to be pointed to the UI which will be deployed as part of Eagle. We will have to update the app running on port 443 on path / to point to ui_lex-ui-static
  ```
   location / {
    set $target http://ui-static:3004;

  location /apis/ {
    proxy_set_header X-Real-IP  $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Server $host;

    proxy_cookie_path ~*^/.* /;

    set $target http://lex-ui-proxies:9001;
    rewrite ^/apis/(.*) /$1 break;
    proxy_pass $target;

    proxy_connect_timeout 10;
    proxy_send_timeout 30;
    proxy_read_timeout 30;
  }
  ```

  2. UI requires some static assets which it picks up on page load. We add a new server block to serve static configurations.
  ```
  server {
    listen                3007;
    server_name           35.170.8.171;

    proxy_set_header    Host              $host;
    proxy_set_header    X-Real-IP         $remote_addr;
    proxy_set_header    X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header    X-Forwarded-SSL   on;
    proxy_set_header    X-Forwarded-Proto $scheme;

    location /web-hosted/ {
      root   /content-mount/web-host;
      rewrite ^/web-hosted/(.*) /$1 break;
    }
  }
  ```
  3. Content service uses an API for fetching encryption keys, for this the an extra block in the server block running on 443, the below code is added
  ```
  location /content-api/ {
    set $target http://lex-content-service;
    rewrite ^/content-api/(.*) /public/$1 break;
    proxy_pass $target;

    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Scheme $scheme;
    proxy_connect_timeout 10;
    proxy_send_timeout 30;
    proxy_read_timeout 30;
    proxy_set_header    X-Forwarded-Proto $scheme;
    root   /usr/share/nginx/www;
  }
  ```
  4. <b>stack-proxy.yml</b> file needs to add some an extra port for working with static server set at step 2 and mount path which will be setup for the static content. (Assuming the content directory is at /mydata/content-directory on the swarm machine)
  ```
  version: '3.3'

  services:
    proxy:
      image: "{{hub_org}}/{{image_name}}:{{image_tag}}"
      ports:
        - "443:443"
        - "80:80"
      deploy:
        mode: global
        resources:
          reservations:
            memory: "{{ proxy_reservation_memory }}"
          limits:
            memory: "{{ proxy_limit_memory }}"
        update_config:
          parallelism: 1
          delay: 30s
      secrets:
        - site.key
        - site.crt
        - prom_admin_creds
      configs:
        - source: nginx.conf
          target: /etc/nginx/nginx.conf
        - source: proxy-default.conf
          target: /etc/nginx/conf.d/default.conf
      networks:
        application_default:
          aliases: # Added new aliases
            - static-host
            - private-static-host
      volumes: # Added this mount path
        - /mydata/content-directory:/content-mount
  secrets:
    site.key:
      external: true
    site.crt:
      external: true
    prom_admin_creds:
      external: true

  configs:
    nginx.conf:
      external: true
    proxy-default.conf:
      external: true

  networks:
    application_default:
      external: true
  ```

## Changes on Keycloak
  1. A redirect URL with the domain name which will be used by the application needs to be added to Sunbird realm
  2. Sunbird realm needs to be changed to Wingspan.

  ## Extra configurations
  client-assets directory needs to be moved to the place where the static files are served from NGINX.
  Assuming that the location is on `/mydata/content-directory/`, the directory needs to be placed at `/mydata/content-directory/web-host/`

  The directory here has configuration as per tenant. For your tenant to work, the configuration at [here](client-assets/assets/configurations/localhost_3000) should be renamed to your domain name.

  __Example__: If eagle is deployed on a domain called https://my-eagle-deployment.com, the folder should be re-named to my-eagle-deployment.

  The configuration folder will have all features which can be enabled and disabled for each tenant. For a different tenant, a new folder with the domain name of the tenant should be placed at the same location.