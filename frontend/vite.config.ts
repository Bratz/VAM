import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    // PORT env override lets a second instance (e.g. the preview harness)
    // coexist with the primary dev server on 3000.
    port: Number(process.env.PORT) || 3000,
    proxy: {
      '/api': {
        // Dev-server proxy target. Override via VITE_API_PROXY_TARGET to
        // point your local frontend at a remote backend (e.g. a colleague's
        // machine, a staging deployment). Default keeps the in-tree dev
        // setup working with no extra env wiring.
        target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:8053',
        changeOrigin: true,
        secure: false,
        // Add error handling to see better error messages
        configure: (proxy, _options) => {
          proxy.on('error', (err, _req, _res) => {
            console.log('Proxy error:', err);
          });
          proxy.on('proxyReq', (proxyReq, req, _res) => {
            console.log('Proxying:', req.method, req.url, '→', 'http://localhost:8053' + req.url);
          });
        },
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['react', 'react-dom'],
          charts: ['recharts'],
        },
      },
    },
  },
});