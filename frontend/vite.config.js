import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:9000',
        changeOrigin: true,
        configure: (proxy, _options) => {
          proxy.on('proxyReq', (proxyReq, req, res) => {
            // 为 SSE 请求设置必要的头
            if (req.accepts && req.accepts('text/event-stream')) {
              proxyReq.setHeader('Accept', 'text/event-stream');
            }
          });
          proxy.on('proxyRes', (proxyRes, req, res) => {
            // 确保 SSE 响应不被缓冲
            if (proxyRes.headers['content-type'] && proxyRes.headers['content-type'].includes('text/event-stream')) {
              proxyRes.headers['Cache-Control'] = 'no-cache';
              proxyRes.headers['Connection'] = 'keep-alive';
              // 禁用压缩以确保实时传输
              delete proxyRes.headers['content-encoding'];
            }
          });
          proxy.on('error', (err, req, res) => {
            console.log('[VITE-PROXY] SSE 代理错误:', err.message);
            if (!res.headersSent) {
              res.writeHead(500, { 'Content-Type': 'text/plain' });
              res.end('代理服务器错误');
            }
          });
        }
      },
      '/artifacts': {
        target: 'http://localhost:9000',
        changeOrigin: true
      }
    }
  }
})
