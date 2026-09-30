import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Dev proxy: API runs locally on :8080 (Go service from ../api, docker-compose provides
// Postgres + MinIO). No client ever talks to S3/MinIO directly except via the API's
// short-lived presigned PUT/GET URLs (server-chosen immutable object keys).
export default defineConfig({
  plugins: [vue()],
  // es2022: libsodium's ESM wrapper contains a Node-only top-level-await branch;
  // modern browsers (the only supported ones here) are fine with it.
  build: {
    target: 'es2022',
  },
  resolve: {
    alias: [
      // libsodium-wrappers' ESM build has a broken relative import
      // (`./libsodium.mjs`, which lives in the sibling `libsodium` package) —
      // it breaks esbuild pre-bundling in dev AND rollup in build. Force the CJS
      // entry instead: it does `require("libsodium")`, which resolves correctly.
      {
        find: /^libsodium-wrappers-sumo$/,
        replacement: fileURLToPath(
          new URL(
            './node_modules/libsodium-wrappers-sumo/dist/modules-sumo/libsodium-wrappers.js',
            import.meta.url,
          ),
        ),
      },
    ],
  },
  server: {
    // Pinned + strict: if 5173 is taken we FAIL LOUDLY instead of silently drifting
    // to another port — S3 CORS matches origins exactly (ports included), so a drifted
    // port silently breaks every upload/download. See bucket CORS origins.
    port: 5173,
    strictPort: true,
    proxy: {
      '/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
