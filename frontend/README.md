# Custoking IMS Frontend

React + Vite frontend for Custoking IMS.

## Requirements
- Node.js 20+

## Run
```bash
npm install
npm run dev
```

The dev server starts at:
- `http://localhost:5173`

For Docker-based local run, use the root `docker-compose.yml`.


## API base URL

- In local dev, the Vite dev server proxies `/api/v1` to `http://localhost:8080`.
- For custom deployments, set `VITE_API_BASE_URL` to your backend base path, for example `http://localhost:8080/api/v1`.
