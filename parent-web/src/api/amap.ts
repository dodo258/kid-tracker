// 高德 Web 服务（经服务器 /amap/ 转发，Key 在服务器上）返回的 JSON 校验：输入提示 inputtips、关键字搜索 place/text、逆地理 regeo。
// 高德的习惯：status 是字符串 "1" 成功、"0" 失败（info 写原因）；字段没有值时给空数组 []，不是空字符串。
// location 是 GCJ-02 的「经度,纬度」。
import type { LatLon } from './types'
import type { AmapPlace, Regeo } from './types'

type Obj = Record<string, unknown>

const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)

/** 有内容的字符串；[]、空串、非字符串都算没有 */
function text(v: unknown): string | null {
  return typeof v === 'string' && v.trim() !== '' ? v.trim() : null
}

/** 高德接口的 location 参数：「经度,纬度」，小数不超过 6 位（高德 Web 服务的要求） */
export function amapLocation(gcj: LatLon): string {
  return `${gcj.longitude.toFixed(6)},${gcj.latitude.toFixed(6)}`
}

/** 「经度,纬度」→ GCJ-02 坐标；空的、格式不对、超出范围返回 null */
export function parseAmapLocation(v: unknown): LatLon | null {
  const s = text(v)
  if (!s) return null
  const parts = s.split(',')
  if (parts.length !== 2) return null
  const [longitude, latitude] = parts.map((x) => (x.trim() === '' ? Number.NaN : Number(x)))
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null
  if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return null
  return { latitude, longitude }
}

/** status 不是 "1" 就抛错，带上高德的原因 */
function ok(v: unknown): Obj {
  if (!isObj(v)) throw new Error('高德返回的格式不对')
  if (v.status !== '1') throw new Error(`高德返回：${text(v.info) ?? '未知错误'}`)
  return v
}

function place(v: unknown, detailKeys: string[]): AmapPlace | undefined {
  if (!isObj(v)) return undefined
  const name = text(v.name)
  if (!name) return undefined
  const detail = detailKeys.map((k) => text(v[k])).filter((x) => x !== null)
  return { name, detail: detail.join(' '), location: parseAmapLocation(v.location) }
}

function places(v: unknown, detailKeys: string[]): AmapPlace[] {
  if (!Array.isArray(v)) return []
  const out: AmapPlace[] = []
  for (const x of v) {
    const p = place(x, detailKeys)
    if (p) out.push(p)
  }
  return out
}

/** /v3/assistant/inputtips：tips 里只是一个类别（「小学」）的没有 location，选它时再用 place/text 查 */
export function parseInputTips(v: unknown): AmapPlace[] {
  return places(ok(v).tips, ['district', 'address'])
}

/** /v3/place/text：pois */
export function parsePlaceText(v: unknown): AmapPlace[] {
  return places(ok(v).pois, ['adname', 'address'])
}

/** /v3/geocode/regeo：地址取 formatted_address；城市取 addressComponent.city，直辖市等 city 是空的用 province */
export function parseRegeo(v: unknown): Regeo {
  const r = ok(v).regeocode
  if (!isObj(r)) return { address: null, city: null }
  const c = isObj(r.addressComponent) ? r.addressComponent : {}
  return { address: text(r.formatted_address), city: text(c.city) ?? text(c.province) }
}
