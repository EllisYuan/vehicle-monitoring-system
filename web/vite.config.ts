import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/ws': {
        target: process.env.VMS_API_TARGET ?? 'http://127.0.0.1:8080',
        ws: true,
        changeOrigin: true,
        rewriteWsOrigin: true,
      },
      '/api': {
        target: process.env.VMS_API_TARGET ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
