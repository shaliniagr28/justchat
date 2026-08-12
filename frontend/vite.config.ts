import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Dev-only convenience: proxy REST and WS to the backend so the browser sees one
      // origin and cookies flow without CORS gymnastics. In docker-compose this job is
      // done by nginx instead - see frontend/nginx.conf.
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'ws://localhost:8080', ws: true }
    }
  }
});
