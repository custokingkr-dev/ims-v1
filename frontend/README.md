# Custoking IMS Frontend

React + Vite frontend for Custoking IMS.

## Requirements
- Node.js 24 LTS (`>=24 <25`) with npm 11

## Run
```bash
npm install
npm run dev
```

The dev server starts at:
- `http://localhost:5173`

For Docker-based local run, use the root `docker-compose.yml`.


## API base URL

- In local dev, the Vite dev server proxies `/api/v1` to the local API gateway at `http://localhost`.
- For gateway deployments, keep `VITE_API_BASE_URL=/api/v1` so browser traffic stays on the same origin.
