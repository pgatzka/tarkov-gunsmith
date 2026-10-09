# Frontend

React + Vite + TypeScript, React Router, ESLint + Prettier.

## Run

```sh
npm install
npm run dev      # dev server on http://localhost:5173
npm run build    # type-check + production build into dist/
npm run lint     # ESLint + Prettier check
npm run format   # apply Prettier
```

## Backend proxy

The dev server proxies `/api/*` to the backend unchanged (same as nginx does in Docker Compose, see `nginx.conf`).
Target defaults to `http://localhost:8080`; override with `BACKEND_URL`, e.g.
`BACKEND_URL=http://localhost:9090 npm run dev`.

The API client lives in `src/api/client.ts`. The placeholder page calls
`/api/actuator/health` and shows the backend status.
