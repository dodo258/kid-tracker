// Traccar 6.17 接口返回的字段里，本页用到的那部分。入口处由 parse.ts 校验后才变成这些类型。
/** 一个坐标；Traccar 存的、页面里传的都是 WGS-84，只有高德接口和高德、腾讯底图上是 GCJ-02（各处注明） */
export type LatLon = { latitude: number; longitude: number }

export type Device = {
  id: number
  name: string
}

/** App 上报的三种状态（attributes.state）：移动中、范围内活动、静止 */
export type ReportState = 'moving' | 'zone' | 'still'

export type Position = {
  id: number
  deviceId: number
  /** 定位时间（手机拿到这个点的时间），「多久前」按它算 */
  fixTime: Date
  /**
   * 手机发出这条记录的时间。App 心跳取不到新位置时不带坐标，Traccar 沿用上一个坐标和定位时间（标 outdated），
   * 这里是心跳时间，比 fixTime 晚；普通的点两者相同
   */
  deviceTime: Date
  /** 服务器收到这条记录的时间（服务器时钟，不受孩子手机时间不准影响）；「失联」「最后确认」按它，旧数据没有时退回 deviceTime */
  serverTime?: Date
  latitude: number
  longitude: number
  /** Traccar 存的是节，这里已换成 km/h */
  speedKmh: number
  /** 米，0 表示没有 */
  accuracy: number
  batteryLevel?: number
  charging?: boolean
  alarm?: string
  /** 服务器逆地理出来的地址（没配 Key、旧点时没有） */
  address?: string
  /** 服务器算出来这个点在哪些地理围栏里 */
  geofenceIds: number[]
  /** App 上报的状态；旧版 App 的点没有，照旧按速度判断 */
  state?: ReportState
  /** 行进方向，度（正北 0 顺时针） */
  course?: number
  /** App 标的定位来源（attributes.provider）：gps 卫星，wifi、network 是高德或系统的网络定位；旧版 App 没有 */
  provider?: string
  /** 运动摘要（attributes.steps、bumps）：离上一次上报走了几步、车上颠了几下；还没带运动摘要的 App 没有 */
  steps?: number
  bumps?: number
  /** 上报那一刻的联网方式（attributes.net）：wifi 固定 Wi-Fi、hotspot 热点或随身 Wi-Fi、cellular 移动网络；原样保留，不认识的值照写 */
  net?: string
  /** 旧字段（attributes.wifiType）：fixed 固定 Wi-Fi、hotspot 热点、none 没连 Wi-Fi；没有 net 时网络一行按它 */
  wifiType?: string
  /** 手机开着省电模式（attributes.powerSave） */
  powerSave?: boolean
  /** 这次上报没带坐标（只报了在线），Traccar 沿用上一个点的坐标和定位时间 */
  outdated?: boolean
  /**
   * 这台手机从没定到过位置：Traccar 没有上一个点可沿用时，只报时间的上报存成纬度 0 经度 0、定位时间 1980-01-06（GPS 纪元）。
   * 坐标不是真位置：不画圆点、不进轨迹、不算距离；联系时间、电量、状态、网络估算照常用（parse.ts 判断）
   */
  unlocated?: true
  /**
   * 网络定位的估算（attributes.netLat、netLon、netAcc，上报端 v2 起）：主坐标只放卫星点，Wi-Fi、基站定位的结果放这里，
   * 圆点不按它挪；只在「位置可能没跟上」的提示里用（domain/lagging）。旧版 App 没有
   */
  netLat?: number
  netLon?: number
  /** 估算的误差，米 */
  netAcc?: number
  /** 手机重启后孩子还没解锁过（attributes.locked，上报端 v2 起）：只用系统定位，状态句写「手机刚重启」 */
  locked?: boolean
}

/** 「最近 24 小时」里列的事件类型：告警、失联、到达/离开常去地点。REST 查询和 WebSocket 推来的都按它过滤（推送会带上线、离线等全部事件） */
export const LISTED_EVENTS: readonly string[] = ['alarm', 'deviceUnknown', 'geofenceEnter', 'geofenceExit']

export type TraccarEvent = {
  id: number
  type: string
  eventTime: Date
  deviceId: number
  positionId: number
  /** geofenceEnter、geofenceExit 事件对应的围栏，其他事件是 0 */
  geofenceId: number
  alarm?: string
}

/** 常去地点：Traccar 的地理围栏。area 是 Traccar 的 WKT 原文，本页只画 CIRCLE */
export type Geofence = {
  id: number
  name: string
  area: string
}

/** 高德搜索到的一个地方（输入提示或关键字搜索）。location 是 GCJ-02；只是类别的提示没有 */
export type AmapPlace = {
  name: string
  /** 区和详细地址，列表第二行 */
  detail: string
  location: LatLon | null
}

/** 高德逆地理：完整地址、所在城市（搜索时限定城市用） */
export type Regeo = { address: string | null; city: string | null }

export type SocketMessage = {
  devices: Device[]
  positions: Position[]
  events: TraccarEvent[]
}

/** 状态卡停在哪一档（记在浏览器里）：收起、展开 */
export type Snap = 'peek' | 'open'

/** 设置里选的底图：自动（高德打不开换腾讯，再换 OSM）或固定一家 */
export type BasePref = 'auto' | 'amap' | 'tencent' | 'osm'

/** 设置里选的深浅色：跟随系统，或固定浅色、深色 */
export type ThemePref = 'system' | 'light' | 'dark'

export type LoginOutcome = 'ok' | 'needCode' | 'wrong'

/** 用到的接口；正式环境是 traccar.ts，截图用 fixtures/mockApi.ts。只有常去地点会写服务器。 */
export interface Api {
  login(email: string, password: string, code?: string): Promise<LoginOutcome>
  /** POST /api/session/token，返回长期令牌 */
  createToken(expiration: Date): Promise<string>
  /** GET /api/session?token=，换一个会话 cookie 给 WebSocket 用；令牌失效抛 AuthError */
  openSession(token: string): Promise<void>
  /** DELETE /api/session：退出服务器上的会话（WebSocket 换来的会话 cookie） */
  endSession(token: string): Promise<void>
  /** POST /api/session/token/revoke：吊销这个长期令牌 */
  revokeToken(token: string): Promise<void>
  /**
   * 带 Bearer 令牌查一个要登录的接口（GET /api/devices），令牌吊销、过期，账号停用、删除都回 401，抛 AuthError。
   * 不用 GET /api/session：浏览器带着 WebSocket 的会话 cookie，吊销和停用后它还回 200（本机 Traccar 6.17 实测）
   */
  checkAuth(token: string): Promise<void>
  devices(token: string): Promise<Device[]>
  latestPositions(token: string): Promise<Position[]>
  positionsById(token: string, ids: number[]): Promise<Position[]>
  route(token: string, deviceId: number, from: Date, to: Date): Promise<Position[]>
  /** 告警、失联、到达/离开常去地点 */
  events(token: string, deviceId: number, from: Date, to: Date): Promise<TraccarEvent[]>
  /** GET /api/geofences?deviceId=，关联到这台设备的常去地点 */
  geofences(token: string, deviceId: number): Promise<Geofence[]>
  /** 建一个地理围栏，再关联到这台设备 */
  addGeofence(token: string, deviceId: number, name: string, area: string): Promise<Geofence>
  /** 改名、改范围：id 不变，关联的设备和到达/离开提醒都保留 */
  updateGeofence(token: string, id: number, name: string, area: string): Promise<Geofence>
  deleteGeofence(token: string, id: number): Promise<void>
  /** DELETE /api/permissions：只去掉这台设备和地点的关联，地点本身和别的设备的关联不动 */
  unlinkGeofence(token: string, deviceId: number, geofenceId: number): Promise<void>
  /** 高德输入提示（服务器 /amap/ 转发，只给已登录的家长）；city 为 null 时全国搜 */
  amapTips(token: string, keywords: string, city: string | null): Promise<AmapPlace[]>
  /** 高德关键字搜索：输入提示里没有坐标的那条再查一次 */
  amapSearch(token: string, keywords: string, city: string | null): Promise<AmapPlace[]>
  /** 高德逆地理，传 GCJ-02 */
  amapRegeo(token: string, gcj: LatLon): Promise<Regeo>
  /** 连 /api/socket；断开或 120 秒没有任何消息（假死）时调 onClose，只调一次。返回关闭函数 */
  subscribe(onMessage: (m: SocketMessage) => void, onClose: () => void): () => void
  now(): Date
}

/** 令牌失效或被吊销，要回登录页 */
export class AuthError extends Error {
  constructor() {
    super('登录已失效')
    this.name = 'AuthError'
  }
}

/** 服务器回了错误状态码。message 是给家长看的话，接口路径只打在 console 里 */
export class ServerError extends Error {
  readonly status: number
  constructor(status: number) {
    super(status >= 500 ? '暂时连不上服务器' : `服务器返回 ${status}`)
    this.name = 'ServerError'
    this.status = status
  }
}
