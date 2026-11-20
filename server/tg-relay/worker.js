// Telegram Bot API 中转：国内云服务器连不上 api.telegram.org，Traccar 把通知发到这里再转给 Telegram。
// 只放行 POST /<RELAY_PREFIX>/bot<token>/(sendMessage|sendLocation)，RELAY_PREFIX 是 wrangler secret。
const ALLOWED = /^\/bot[0-9]+:[A-Za-z0-9_-]+\/(sendMessage|sendLocation)$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const prefix = `/${env.RELAY_PREFIX}`;
    if (!env.RELAY_PREFIX || !url.pathname.startsWith(prefix + "/")) {
      return new Response("not found", { status: 404 });
    }
    const rest = url.pathname.slice(prefix.length);
    if (request.method !== "POST" || !ALLOWED.test(rest)) {
      return new Response("forbidden", { status: 403 });
    }
    return fetch("https://api.telegram.org" + rest, {
      method: "POST",
      headers: { "content-type": request.headers.get("content-type") || "application/json" },
      body: request.body,
    });
  },
};
