// 选中设备的常去地点（Traccar 地理围栏）：换设备时拉一次，添加、改名改范围、删除、只对这台取消后就地更新列表。
// 添加、删除失败把错误抛给调用方（弹窗里显示）；令牌失效回登录页。
import { useCallback, useEffect, useRef, useState } from 'react'
import { AuthError, type Api, type Geofence } from '../api/types'
import { circleArea, type Circle } from '../domain/places'

export type Places = {
  /** 还没拉到（刚打开、刚切换孩子）是 null；拉不到当作没有 */
  list: Geofence[] | null
  add: (name: string, circle: Circle) => Promise<void>
  /** 改名、改范围：area 是 Traccar 原文（改名时原样传回，完整后台画的多边形也能改名） */
  update: (id: number, name: string, area: string) => Promise<void>
  remove: (id: number) => Promise<void>
  /** 这个地点还关联了账号下哪些别的设备（删除前提醒用） */
  sharedWith: (id: number) => Promise<number[]>
  /** 只对选中的这台取消：去掉关联，地点本身和别的设备的关联不动 */
  unlink: (id: number) => Promise<void>
}

export function usePlaces(api: Api, token: string, deviceId: number | null, deviceIds: number[], onAuthLost: () => void): Places {
  // 带上设备号：换设备后、新列表回来之前不显示上一台的地点
  const [loaded, setLoaded] = useState<{ deviceId: number; list: Geofence[] } | null>(null)
  const authLostRef = useRef(onAuthLost)
  useEffect(() => {
    authLostRef.current = onAuthLost
  })

  const guard = useCallback(async <T,>(work: Promise<T>): Promise<T> => {
    try {
      return await work
    } catch (err) {
      if (err instanceof AuthError) authLostRef.current()
      throw err
    }
  }, [])

  useEffect(() => {
    if (deviceId === null) return
    let cancelled = false
    guard(api.geofences(token, deviceId))
      .then((list) => {
        if (!cancelled) setLoaded({ deviceId, list })
      })
      // 拉不到地点不影响看位置，只是少了「在学校」和地图上的圈
      .catch((err: unknown) => {
        console.warn('常去地点加载失败', err)
        if (!cancelled) setLoaded({ deviceId, list: [] })
      })
    return () => {
      cancelled = true
    }
  }, [api, token, deviceId, guard])

  const add = useCallback(
    async (name: string, circle: Circle) => {
      if (deviceId === null) return
      const place = await guard(api.addGeofence(token, deviceId, name, circleArea(circle)))
      setLoaded((prev) => ({ deviceId, list: [...(prev?.deviceId === deviceId ? prev.list : []), place] }))
    },
    [api, token, deviceId, guard],
  )

  const update = useCallback(
    async (id: number, name: string, area: string) => {
      const place = await guard(api.updateGeofence(token, id, name, area))
      setLoaded((prev) => (prev ? { ...prev, list: prev.list.map((p) => (p.id === id ? place : p)) } : prev))
    },
    [api, token, guard],
  )

  const remove = useCallback(
    async (id: number) => {
      await guard(api.deleteGeofence(token, id))
      setLoaded((prev) => (prev ? { ...prev, list: prev.list.filter((p) => p.id !== id) } : prev))
    },
    [api, token, guard],
  )

  const sharedWith = useCallback(
    async (id: number) => {
      const others = deviceIds.filter((d) => d !== deviceId)
      const lists = await guard(Promise.all(others.map((d) => api.geofences(token, d))))
      return others.filter((_, i) => lists[i].some((g) => g.id === id))
    },
    [api, token, deviceId, deviceIds, guard],
  )

  const unlink = useCallback(
    async (id: number) => {
      if (deviceId === null) return
      await guard(api.unlinkGeofence(token, deviceId, id))
      setLoaded((prev) => (prev ? { ...prev, list: prev.list.filter((p) => p.id !== id) } : prev))
    },
    [api, token, deviceId, guard],
  )

  return { list: loaded?.deviceId === deviceId ? loaded.list : null, add, update, remove, sharedWith, unlink }
}
