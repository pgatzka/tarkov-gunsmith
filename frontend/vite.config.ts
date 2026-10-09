import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backend = env.BACKEND_URL ?? 'http://localhost:8080';

  return {
    plugins: [react()],
    server: {
      proxy: {
        // Mirrors the nginx setup: the backend serves its API under /api.
        '/api': { target: backend, changeOrigin: true },
      },
    },
  };
});
