// 家长手机的位置（选点时「我的位置」）：浏览器定位，WGS-84。拒绝授权、拿不到、超时都返回 null。
import type { LatLon } from './types'

const TIMEOUT_MS = 10_000
/** 一分钟内的旧位置也行：家长人就站在那儿 */
const MAX_AGE_MS = 60_000

export function locateMe(): Promise<LatLon | null> {
  return new Promise((resolve) => {
    if (!('geolocation' in navigator)) {
      resolve(null)
      return
    }
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude }),
      (err) => {
        console.warn('浏览器定位失败', err.code, err.message)
        resolve(null)
      },
      { enableHighAccuracy: true, timeout: TIMEOUT_MS, maximumAge: MAX_AGE_MS },
    )
  })
}
