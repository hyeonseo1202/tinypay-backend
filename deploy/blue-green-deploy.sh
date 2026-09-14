#!/usr/bin/env bash
set -Eeuo pipefail

IMAGE="${1:?Usage: blue-green-deploy.sh <image:tag>}"
APP_DIR="${APP_DIR:-$HOME/tinypay}"
ENV_FILE="${ENV_FILE:-$APP_DIR/.env}"
RELEASE_DIR="${RELEASE_DIR:-$APP_DIR/release}"
STATE_FILE="$APP_DIR/.active-backend-color"
NGINX_TEMPLATE="$RELEASE_DIR/nginx/tinypay-backend.conf"
NGINX_CONFIG="${NGINX_CONFIG:-/etc/nginx/conf.d/tinypay-backend.conf}"
HEALTH_ATTEMPTS="${HEALTH_ATTEMPTS:-30}"
HEALTH_INTERVAL_SECONDS="${HEALTH_INTERVAL_SECONDS:-5}"
DOCKER_NETWORK="${DOCKER_NETWORK:-tinypay_default}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing environment file: $ENV_FILE" >&2
  exit 1
fi

if [[ ! -f "$NGINX_TEMPLATE" ]]; then
  echo "Missing Nginx template: $NGINX_TEMPLATE" >&2
  exit 1
fi

if ! command -v nginx >/dev/null 2>&1; then
  echo "Nginx must be installed before the first Blue-Green deployment." >&2
  exit 1
fi

ACTIVE_COLOR="blue"
if [[ -f "$STATE_FILE" ]]; then
  ACTIVE_COLOR="$(tr -d '[:space:]' < "$STATE_FILE")"
fi

case "$ACTIVE_COLOR" in
  blue)
    TARGET_COLOR="green"
    TARGET_PORT="8082"
    ;;
  green)
    TARGET_COLOR="blue"
    TARGET_PORT="8081"
    ;;
  *)
    echo "Invalid active color in $STATE_FILE: $ACTIVE_COLOR" >&2
    exit 1
    ;;
esac

TARGET_CONTAINER="tinypay-backend-$TARGET_COLOR"
ACTIVE_CONTAINER="tinypay-backend-$ACTIVE_COLOR"
TEMP_NGINX_CONFIG="$(mktemp)"
BACKUP_NGINX_CONFIG="$(mktemp)"
HAD_NGINX_CONFIG=false
LEGACY_CONTAINER_IDS=""

cleanup() {
  rm -f "$TEMP_NGINX_CONFIG" "$BACKUP_NGINX_CONFIG"
}
trap cleanup EXIT

echo "Deploying $IMAGE as $TARGET_COLOR on port $TARGET_PORT"
docker pull "$IMAGE"
docker rm -f "$TARGET_CONTAINER" >/dev/null 2>&1 || true
DOCKER_RUN_ARGS=(
  -d
  --name "$TARGET_CONTAINER"
  --restart unless-stopped
  --env-file "$ENV_FILE"
  -p "127.0.0.1:${TARGET_PORT}:8080"
  --label "tinypay.deployment.color=$TARGET_COLOR"
  --label "tinypay.deployment.image=$IMAGE"
)
if docker network inspect "$DOCKER_NETWORK" >/dev/null 2>&1; then
  DOCKER_RUN_ARGS+=(--network "$DOCKER_NETWORK")
fi
docker run "${DOCKER_RUN_ARGS[@]}" "$IMAGE" >/dev/null

HEALTHY=false
for ((attempt=1; attempt<=HEALTH_ATTEMPTS; attempt++)); do
  if curl --fail --silent --show-error \
    "http://127.0.0.1:${TARGET_PORT}/actuator/health" \
    | grep -q '"status":"UP"'; then
    HEALTHY=true
    break
  fi
  echo "Health check $attempt/$HEALTH_ATTEMPTS failed; retrying..."
  sleep "$HEALTH_INTERVAL_SECONDS"
done

if [[ "$HEALTHY" != true ]]; then
  echo "New container did not become healthy. Keeping current traffic unchanged." >&2
  docker logs --tail 200 "$TARGET_CONTAINER" || true
  docker rm -f "$TARGET_CONTAINER" >/dev/null 2>&1 || true
  exit 1
fi

sed "s/__BACKEND_PORT__/${TARGET_PORT}/g" "$NGINX_TEMPLATE" > "$TEMP_NGINX_CONFIG"
if sudo test -f "$NGINX_CONFIG"; then
  sudo cp "$NGINX_CONFIG" "$BACKUP_NGINX_CONFIG"
  HAD_NGINX_CONFIG=true
fi
sudo cp "$TEMP_NGINX_CONFIG" "$NGINX_CONFIG"

if ! sudo nginx -t; then
  echo "Nginx validation failed. Restoring the previous configuration." >&2
  if [[ "$HAD_NGINX_CONFIG" == true ]]; then
    sudo cp "$BACKUP_NGINX_CONFIG" "$NGINX_CONFIG"
  else
    sudo rm -f "$NGINX_CONFIG"
  fi
  docker rm -f "$TARGET_CONTAINER" >/dev/null 2>&1 || true
  exit 1
fi

# During the first migration, a legacy container may still own port 8080.
# It is stopped only after the new color has passed its health check.
if [[ "$HAD_NGINX_CONFIG" == false ]]; then
  LEGACY_CONTAINER_IDS="$(docker ps --filter publish=8080 --format '{{.ID}}')"
  if [[ -n "$LEGACY_CONTAINER_IDS" ]]; then
    docker stop --time 30 $LEGACY_CONTAINER_IDS >/dev/null
  fi
fi

if sudo systemctl is-active --quiet nginx; then
  NGINX_SWITCH_COMMAND=(sudo systemctl reload nginx)
else
  NGINX_SWITCH_COMMAND=(sudo systemctl start nginx)
fi

if ! "${NGINX_SWITCH_COMMAND[@]}"; then
  echo "Traffic switch failed. Restoring the previous Nginx configuration." >&2
  if [[ "$HAD_NGINX_CONFIG" == true ]]; then
    sudo cp "$BACKUP_NGINX_CONFIG" "$NGINX_CONFIG"
    sudo nginx -t && sudo systemctl reload nginx || true
  else
    sudo rm -f "$NGINX_CONFIG"
  fi
  if [[ -n "$LEGACY_CONTAINER_IDS" ]]; then
    docker start $LEGACY_CONTAINER_IDS >/dev/null || true
  fi
  docker rm -f "$TARGET_CONTAINER" >/dev/null 2>&1 || true
  exit 1
fi

printf '%s\n' "$TARGET_COLOR" > "$STATE_FILE"
docker stop --time 30 "$ACTIVE_CONTAINER" >/dev/null 2>&1 || true
docker rm "$ACTIVE_CONTAINER" >/dev/null 2>&1 || true
docker image prune -f >/dev/null

echo "Deployment complete: $TARGET_COLOR is serving $IMAGE"
