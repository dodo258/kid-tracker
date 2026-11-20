// 高德逆地理：传 GCJ-02 坐标，停 delayMs 毫秒没再变才查（图钉地址：地图停下来 600 毫秒后）。
// 传 null 或查失败都返回 null，调用方不显示那一行。只记最近一次的结果，坐标变了旧结果马上作废。
import { useEffect, useState } from 'react'
import { amapLocation } from '../api/amap'
import type { Api, LatLon, Regeo } from '../api/types'

export function useRegeo(api: Api, token: string, gcj: LatLon | null, delayMs = 0): Regeo | null {
  const key = gcj ? amapLocation(gcj) : null
  const [result, setResult] = useState<{ key: string; regeo: Regeo } | null>(null)
  const lat = gcj?.latitude
  const lon = gcj?.longitude

  useEffect(() => {
    if (key === null || lat === undefined || lon === undefined) return
    let cancelled = false
    const timer = setTimeout(() => {
      api
        .amapRegeo(token, { latitude: lat, longitude: lon })
        .then((regeo) => {
          if (!cancelled) setResult({ key, regeo })
        })
        .catch((err: unknown) => console.warn('查地址失败', err))
    }, delayMs)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [api, token, key, lat, lon, delayMs])

  return result && result.key === key ? result.regeo : null
}
