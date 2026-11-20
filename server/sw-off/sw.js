// 停用 Traccar 网页后台的 Service Worker（Caddy 用这个文件顶替 /sw.js）。
// 原因：traccar-web 的 Service Worker 管整个域名，导航兜底只排除 /api，会把 /m/（家长极简页）也换成后台首页，
// 打开过完整后台的浏览器里极简页一片空白（2026-11-16 主人电脑 Chrome）。
// 已装上的旧 Service Worker 在下次检查更新时换成这个：装上立即生效、清掉它的缓存、注销自己；刷新一次页面就恢复。
// 不主动刷新页面：后台每次打开都会重新注册 /sw.js，主动刷新会循环。
self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      for (const key of await caches.keys()) await caches.delete(key)
      await self.registration.unregister()
    })(),
  )
})
