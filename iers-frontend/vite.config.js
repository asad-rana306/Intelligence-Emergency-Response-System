import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  define: {
    global: 'window', // Fixes the sockjs-client crash
  },
  server: {
    port: 3000,
    proxy: {
      '/auth': 'http://localhost:8080',
      '/api': 'http://localhost:8080',
      '/internal': 'http://localhost:8080',
      '/ws': { target: 'http://localhost:8080', ws: true },
      '/actuator': 'http://localhost:8080'
    }
  }
});