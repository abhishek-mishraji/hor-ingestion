import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Served under /admin/ (auth-console links to http://localhost:5182/admin/; production apps.handsoffretail.com/admin/).
// Both APIs are proxied so every call is same-origin: the services enable no CORS and the cookies are host-only.
// Targets use 127.0.0.1, not localhost: the services bind 127.0.0.1 only, and Node may resolve localhost to ::1.
export default defineConfig({
  plugins: [react()],
  base: '/admin/',
  server: {
    port: 5182,
    strictPort: true,
    proxy: {
      '/api/auth': { target: 'http://127.0.0.1:8091', changeOrigin: false },
      '/api/ingestion/gilbarco': { target: 'http://127.0.0.1:8093', changeOrigin: false },
      '/api/ingestion': { target: 'http://127.0.0.1:8092', changeOrigin: false },
    },
  },
});
