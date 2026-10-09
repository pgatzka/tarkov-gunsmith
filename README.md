# Tarkov Gunsmith

Random weapon build generator for Escape from Tarkov. See [docs/SPEC.md](docs/SPEC.md) and
[docs/ROADMAP.md](docs/ROADMAP.md).

- [`backend/`](backend/README.md) — Spring Boot API (served under `/api`)
- [`frontend/`](frontend/README.md) — React + Vite UI

## Run with Docker Compose

Requires Docker with the Compose plugin.

```sh
cp .env.example .env      # optional; defaults work as-is
docker compose up --build
```

Then open <http://localhost:8080>. The stack has three services:

| Service | What it does |
|---|---|
| `postgres` | Postgres 17, data kept in the `postgres-data` volume |
| `backend` | Spring Boot app, reachable only inside the compose network |
| `frontend` | nginx serving the built UI and proxying `/api/*` to the backend |

Only nginx is published on the host (`HTTP_PORT`, default `8080`). Check the backend through it:

```sh
curl http://localhost:8080/api/actuator/health   # {"status":"UP",...}
```

Configuration lives in `.env` (see [`.env.example`](.env.example)): `HTTP_PORT`, `DB_NAME`,
`DB_USER`, `DB_PASSWORD`.

Stop with `docker compose down`; add `-v` to also delete the database volume.
