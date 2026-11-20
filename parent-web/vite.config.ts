import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { execFileSync } from 'node:child_process'
import { defineConfig } from 'vitest/config'

// 部署在 https://gps.example.com/m/，接口在同域名的 /api/（见 README.md）。
// 本机调试真接口：TRACCAR_URL=http://127.0.0.1:8082 npm run dev（只指向本机测试用的 Traccar）
const traccar = process.env.TRACCAR_URL

/** 设置页的版本号：「2026-11-18 · 50ee9dd」；不在 git 仓库里只写日期 */
function appVersion(): string {
  const day = new Date().toISOString().slice(0, 10)
  try {
    const hash = execFileSync('git', ['rev-parse', '--short', 'HEAD'], { encoding: 'utf8', timeout: 5000 }).trim()
    return `${day} · ${hash}`
  } catch {
    return day
  }
}

export default defineConfig({
  base: '/m/',
  plugins: [react(), tailwindcss()],
  define: { __APP_VERSION__: JSON.stringify(appVersion()) },
  server: {
    host: '127.0.0.1',
    port: 5199,
    strictPort: true,
    proxy: traccar ? { '/api': { target: traccar, ws: true, changeOrigin: true } } : undefined,
  },
  // css.include：vitest 默认把 CSS 换成空串，themeTokens.test.ts 要读 index.css 原文
  test: { include: ['src/**/*.test.ts', 'src/**/*.test.tsx', 'fixtures/**/*.test.ts'], css: { include: [/index\.css/] } },
})
