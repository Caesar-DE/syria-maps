# Deployment

## 1. Server

```bash
git clone <repo> syria-maps && cd syria-maps
cp .env.example .env
# edit .env: strong POSTGRES_PASSWORD, SYRIA_MAPS_PUBLIC_BASE_URL=https://maps.your-domain,
#            LOAD_SYNTHETIC_FIXTURES=false for production
docker compose up -d --build
docker compose ps            # db and api "healthy", migrate "exited (0)"
curl -fsS http://127.0.0.1:8000/health
```

Bind the API to localhost only in production: set `API_PORT=127.0.0.1:8000`.

## 2. HTTPS reverse proxy

OsmAnd release packages must use HTTPS. Examples:

- Caddy (automatic Let's Encrypt): [`examples/reverse-proxy/Caddyfile`](../examples/reverse-proxy/Caddyfile)
- nginx: [`examples/reverse-proxy/nginx.conf`](../examples/reverse-proxy/nginx.conf)

The API runs uvicorn with `--proxy-headers`. Keep `SYRIA_MAPS_PUBLIC_BASE_URL` equal to the public URL —
it is written into tile templates and the `.osf` package.

## 3. Data

```bash
docker compose run --rm -v "$PWD/data.geojson:/data.geojson:ro" migrate python -m app.cli import /data.geojson
```

## 4. Publish the package

Either let users download `https://maps.your-domain/osmand/syria-maps.osf`, or push a tag `vX.Y.Z` —
the release workflow builds, validates and attaches `syria-maps.osf` (set the repository variable
`SYRIA_MAPS_PUBLIC_BASE_URL`).

## 5. Backups

```bash
docker compose exec db pg_dump -U syria_maps -Fc syria_maps > backup-$(date +%F).dump
```
