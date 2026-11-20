// 正式环境：同域名下的 Traccar 6.17 接口（页面在 /m/，接口在 /api/）。
// 只有常去地点（地理围栏的新建、关联到设备、改名改范围、删除）会写服务器，其他都是查询。
// 搜地址、查地址走同域名的 /amap/（服务器转发到高德 Web 服务，Key 在服务器上，只给带令牌的请求用）。
// 普通请求带 Authorization: Bearer <令牌>，不靠会话 cookie，所以 04:30 备份重启 Traccar 后不用重新登录；
// WebSocket 不能带请求头，连之前先用 GET /api/session?token= 换一个会话。
import { amapLocation, parseInputTips, parsePlaceText, parseRegeo } from './amap'
import { parseDevices, parseEvents, parseGeofence, parseGeofences, parsePositions, parseSocketMessage } from './parse'
import { AuthError, LISTED_EVENTS, ServerError, type Api, type LoginOutcome, type SocketMessage } from './types'

const TIMEOUT_MS = 15_000
/** Traccar 每 55 秒推一次 {} 保活；这么久什么都没收到，连接就是假死了（手机切网、NAT 超时，浏览器不一定报断） */
export const SOCKET_IDLE_MS = 120_000

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const res = await fetch(path, { ...init, signal: AbortSignal.timeout(TIMEOUT_MS) })
  if (res.status === 401) throw new AuthError()
  if (!res.ok) throw serverError(res.status, path)
  return res
}

/** 接口路径只打在 console 里，页面上只显示「暂时连不上服务器」这类话 */
function serverError(status: number, path: string): ServerError {
  console.warn(`服务器返回 ${status}`, path.split('?')[0])
  return new ServerError(status)
}

async function getJson(token: string, path: string): Promise<unknown> {
  const res = await request(path, { headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' } })
  return res.json()
}

// 账号没有写权限时 Traccar 回 400，正文是 PermissionsService 抛的英文原话（ResourceErrorHandler 把异常都转成 400）
const DENIED = ['Write access denied', 'Operation restricted']

/** 写接口：JSON 请求体，带 Bearer 令牌；没有写权限时给一句家长看得懂的话 */
async function send(token: string, method: string, path: string, body?: unknown): Promise<Response> {
  const res = await fetch(path, {
    method,
    headers: { Authorization: `Bearer ${token}`, Accept: 'application/json', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  })
  if (res.status === 401) throw new AuthError()
  if (res.ok) return res
  const text = await res.text().catch(() => '')
  if (DENIED.some((d) => text.includes(d))) throw new Error('这个账号只能看、不能改，请用管理员账号登录或去完整后台设置')
  throw serverError(res.status, path)
}

function range(deviceId: number, from: Date, to: Date): URLSearchParams {
  return new URLSearchParams({ deviceId: String(deviceId), from: from.toISOString(), to: to.toISOString() })
}

function amapQuery(keywords: string, city: string | null): URLSearchParams {
  const q = new URLSearchParams({ keywords })
  if (city) q.set('city', city)
  return q
}

export const traccarApi: Api = {
  async login(email, password, code) {
    const body = new URLSearchParams({ email, password })
    if (code) body.set('code', code)
    const res = await fetch('/api/session', { method: 'POST', body, signal: AbortSignal.timeout(TIMEOUT_MS) })
    if (res.ok) return 'ok'
    if (res.status === 401) {
      // 开了两步验证的账号先回 401 + WWW-Authenticate: TOTP（SessionResource.add）
      const outcome: LoginOutcome = res.headers.get('WWW-Authenticate') === 'TOTP' && !code ? 'needCode' : 'wrong'
      return outcome
    }
    throw serverError(res.status, '/api/session')
  },

  async createToken(expiration) {
    // 用刚登录的会话 cookie 生成令牌；expiration 按 ISO 时间传（DateParameterConverterProvider）
    const res = await request('/api/session/token', {
      method: 'POST',
      body: new URLSearchParams({ expiration: expiration.toISOString() }),
    })
    const token = (await res.text()).trim()
    if (!token) throw new Error('服务器没有返回令牌')
    return token
  },

  async openSession(token) {
    const res = await fetch(`/api/session?token=${encodeURIComponent(token)}`, {
      headers: { Accept: 'application/json' },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
    // 过期、吊销回 401；令牌本身坏了（解不开）回 400（本机 Traccar 6.17 实测），都要重新登录
    if (res.status === 401 || res.status === 400) throw new AuthError()
    if (!res.ok) throw serverError(res.status, '/api/session')
  },

  async checkAuth(token) {
    // 只看状态码：401 由 request 抛 AuthError
    await request('/api/devices', { headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' } })
  },

  async endSession(token) {
    await request('/api/session', { method: 'DELETE', headers: { Authorization: `Bearer ${token}` } })
  },

  async revokeToken(token) {
    await request('/api/session/token/revoke', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: new URLSearchParams({ token }),
    })
  },

  async devices(token) {
    return parseDevices(await getJson(token, '/api/devices'))
  },

  async latestPositions(token) {
    return parsePositions(await getJson(token, '/api/positions'))
  },

  async positionsById(token, ids) {
    if (ids.length === 0) return []
    const q = new URLSearchParams()
    for (const id of ids) q.append('id', String(id))
    try {
      return parsePositions(await getJson(token, `/api/positions?${q}`))
    } catch (err) {
      // 一个编号查不到（告警指向的点已从库里删掉），Traccar 整批回 400（PositionResource 取到 null 抛异常）。
      // 这时逐个取，查不到的跳过，别的告警位置照常显示
      if (!(err instanceof ServerError && err.status === 400)) throw err
      const one = async (id: number) => {
        try {
          return parsePositions(await getJson(token, `/api/positions?id=${id}`))
        } catch (e) {
          if (e instanceof ServerError && e.status === 400) return []
          throw e
        }
      }
      return (await Promise.all(ids.map(one))).flat()
    }
  },

  async route(token, deviceId, from, to) {
    return parsePositions(await getJson(token, `/api/reports/route?${range(deviceId, from, to)}`))
  },

  async events(token, deviceId, from, to) {
    const q = range(deviceId, from, to)
    for (const type of LISTED_EVENTS) q.append('type', type)
    return parseEvents(await getJson(token, `/api/reports/events?${q}`))
  },

  async geofences(token, deviceId) {
    return parseGeofences(await getJson(token, `/api/geofences?deviceId=${deviceId}`))
  },

  async addGeofence(token, deviceId, name, area) {
    // 新建时 Traccar 自动把围栏关联给当前用户（BaseObjectResource.add），设备要再单独关联（PermissionsResource.add）
    const res = await send(token, 'POST', '/api/geofences', { name, area })
    const geofence = parseGeofence(await res.json())
    if (!geofence) throw new Error('服务器返回的地点格式不对')
    try {
      await send(token, 'POST', '/api/permissions', { deviceId, geofenceId: geofence.id })
    } catch (err) {
      // 关联不上就删掉刚建的，不在服务器上留一个哪台设备都不用的地点
      await send(token, 'DELETE', `/api/geofences/${geofence.id}`).catch(() => {})
      throw err
    }
    return geofence
  },

  async updateGeofence(token, id, name, area) {
    // PUT 会整行覆盖（BaseObjectResource.update 写除 id 外的所有列），不带的描述、日历、属性会被清空，
    // 所以先取完整的一份再只改名字和范围；id 写在请求体里（Traccar 按请求体的 id 改，不看路径）
    const current: unknown = await getJson(token, `/api/geofences/${id}`)
    if (typeof current !== 'object' || current === null || Array.isArray(current)) throw new Error('服务器返回的地点格式不对')
    const res = await send(token, 'PUT', `/api/geofences/${id}`, { ...current, id, name, area })
    const geofence = parseGeofence(await res.json())
    if (!geofence) throw new Error('服务器返回的地点格式不对')
    return geofence
  },

  async deleteGeofence(token, id) {
    await send(token, 'DELETE', `/api/geofences/${id}`)
  },

  async unlinkGeofence(token, deviceId, geofenceId) {
    // PermissionsResource.remove：请求体和关联时一样
    await send(token, 'DELETE', '/api/permissions', { deviceId, geofenceId })
  },

  async amapTips(token, keywords, city) {
    return parseInputTips(await getJson(token, `/amap/v3/assistant/inputtips?${amapQuery(keywords, city)}`))
  },

  async amapSearch(token, keywords, city) {
    return parsePlaceText(await getJson(token, `/amap/v3/place/text?${amapQuery(keywords, city)}`))
  },

  async amapRegeo(token, gcj) {
    return parseRegeo(await getJson(token, `/amap/v3/geocode/regeo?${new URLSearchParams({ location: amapLocation(gcj) })}`))
  },

  subscribe(onMessage: (m: SocketMessage) => void, onClose: () => void) {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    const socket = new WebSocket(`${scheme}://${location.host}/api/socket`)
    let done = false
    let idle: ReturnType<typeof setTimeout> | undefined
    const finish = () => {
      done = true
      clearTimeout(idle)
      socket.close()
    }
    // 收到任何消息（含保活的 {}）都重新计时；超时就自己关掉，按断线处理（上层会轮询兜底并重连）
    const watch = () => {
      clearTimeout(idle)
      idle = setTimeout(() => {
        if (done) return
        console.warn(`WebSocket ${SOCKET_IDLE_MS / 1000} 秒没有消息，当作断线`)
        finish()
        onClose()
      }, SOCKET_IDLE_MS)
    }
    watch()
    socket.onmessage = (e) => {
      if (done) return
      watch()
      if (typeof e.data !== 'string') return
      try {
        onMessage(parseSocketMessage(JSON.parse(e.data)))
      } catch (err) {
        console.warn('WebSocket 消息解析失败', err)
      }
    }
    socket.onclose = () => {
      if (done) return
      done = true
      clearTimeout(idle)
      onClose()
    }
    return finish
  },

  now: () => new Date(),
}
