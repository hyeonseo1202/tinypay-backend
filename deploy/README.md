# Blue-Green deployment

The deployment workflow builds an immutable Docker image tagged with the Git
commit SHA. The EC2 script starts the inactive color, waits for
`/actuator/health` to report `UP`, switches Nginx, and only then drains and
removes the previous color.

## One-time EC2 prerequisites

- Docker, Nginx, and curl are installed.
- `~/tinypay/.env` contains the production application environment variables.
- The deployment user can run `sudo nginx -t`, copy the Nginx configuration,
  and reload Nginx without an interactive password prompt.
- Security groups expose the public service port while ports 8081 and 8082
  remain bound to `127.0.0.1` only.

The first deployment may briefly interrupt traffic while the legacy container
that owns port 8080 is replaced by Nginx. Later deployments switch between
ports 8081 and 8082 without stopping the active container first.

## Scheduler safety

Blue and Green overlap during health checks. Reconciliation, alert retry, and
outbox workers therefore rely on their database claim locks and unique event
keys to prevent duplicate processing. Keep this overlap short and do not run a
database migration that is incompatible with the previous application version.
Use expand-and-contract migrations when a schema change spans a deployment.

## Manual rollback

Normally a failed health check or failed Nginx validation leaves traffic on the
old color automatically. For a rollback after a successful switch, redeploy the
previous Git SHA from GitHub Actions using `workflow_dispatch`, or run:

```bash
~/tinypay/release/blue-green-deploy.sh <dockerhub-user>/tinypay-backend:<previous-sha>
```
