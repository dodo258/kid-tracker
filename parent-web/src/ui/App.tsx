import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { locateMe } from '../api/geolocate'
import { loadBasePref, loadSnap, loadThemePref, saveBasePref, saveSnap, saveThemePref, saveToken } from '../api/storage'
import type { Api, BasePref, LatLon, Position, Snap, ThemePref } from '../api/types'
import { spotFromWgs, type Spot } from '../domain/pick'
import { circleArea, DEFAULT_RADIUS, parseCircle, placeOf } from '../domain/places'
import { laggingOf, laggingText, recentEstimate } from '../domain/lagging'
import { holdsDot, movingSince, offlinePresence, parentOfflineSince, presenceOf, type Presence } from '../domain/presence'
import { situationOf } from '../domain/status'
import { EMPTY_HISTORY, steadyOf, trailOf } from '../domain/track'
import { MapView, type KidOnMap } from '../map/MapView'
import { acceptedFix, smoothTrack } from '../motion/smooth'
import { usePlaces } from '../state/usePlaces'
import { useRegeo } from '../state/useRegeo'
import { NOT_SIGNED_OUT, signedOut, signOut, type SignOutSteps } from '../state/signOut'
import { useTracker } from '../state/useTracker'
import { AlertsPage } from './AlertsPage'
import { DetailsPage } from './DetailsPage'
import { LoginPage } from './LoginPage'
import { PickSheet } from './PickSheet'
import { PlaceCard } from './PlaceCard'
import { PlaceSearch } from './PlaceSearch'
import { PlaceSheet } from './PlaceSheet'
import { PlacesPage } from './PlacesPage'
import { isDevicePage, PAGE_TITLE, routeTarget, usePage, visiblePage, type Page } from './route'
import { SettingsPage } from './SettingsPage'
import { SignOutPage } from './SignOutPage'
import { centeredScrollLeft } from './scroll'
import { StatusSheet } from './StatusSheet'
import { SubPage } from './SubPage'
import { applyTheme } from './theme'

/**
 * 截图用：假数据场景可以指定状态卡停在哪一档（不传按上次记住的）、打开哪个二级页（地址里没有 #/… 时）、轨迹打开、所有设备框进屏幕、
 * 「设为常去地点」打开（选的范围、在不在选点、搜索框里的词）、打开哪个地点的卡片（正在改范围时选的半径）
 */
export type InitialUi = {
  snap?: Snap
  page?: Page
  track?: boolean
  fitAll?: boolean
  addPlace?: boolean
  placeRadius?: number
  picking?: boolean
  search?: string
  card?: number
  cardRadius?: number
}

/** 图钉地址：地图停下来这么久才查（方案 2026-11-16-places-v2） */
const PIN_ADDRESS_DELAY_MS = 600
const LOCATE_FAILED = '拿不到你的位置，请拖地图选'

/** 打开的地点卡片；preview 是改范围时地图上预览的半径 */
type Card = { id: number; preview: number | null }

/**
 * 正在填的「设为常去地点」。center 为 null 时圆心是孩子现在的位置（孩子在动就跟着动），
 * 在地图上选过（用这里、长按、右键）就是选的那一点（WGS-84）。名字放在这里，进出选点不丢。
 * 选点时 pickFrom 是进来时的圆心：孩子在动也不把地图拉走，取消就回到原来的圆心
 */
type Draft = { center: LatLon | null; radius: number; name: string; picking: boolean; pickFrom: LatLon | null }
const newDraft = (center: LatLon | null, radius = DEFAULT_RADIUS, picking = false): Draft => ({ center, radius, name: '', picking, pickFrom: null })

/** 正式环境的动画时钟：浏览器时间（毫秒） */
const wallClock = () => Date.now()

/** 正在退出：令牌（本机已清，留着去服务器退出用）、服务器上各步做成没有、是不是正在请求 */
type Leaving = { token: string; steps: SignOutSteps; busy: boolean }

/** clock：圆点动画用的时钟（毫秒），截图和回放时换成假时钟 */
type AppProps = { api: Api; initialToken: string | null; initialUi?: InitialUi; clock?: () => number }

export function App({ api, initialToken, initialUi, clock = wallClock }: AppProps) {
  const [token, setToken] = useState(initialToken)
  const [expired, setExpired] = useState(false)

  const onLoggedIn = useCallback((t: string) => {
    saveToken(t)
    setExpired(false)
    setToken(t)
  }, [])

  const onAuthLost = useCallback(() => {
    saveToken(null)
    setExpired(true)
    setToken(null)
  }, [])

  // 退出：本机先锁上（清令牌、不再显示位置），再去服务器退出；没退成显示出来，可以重试
  const [leaving, setLeaving] = useState<Leaving | null>(null)
  const leave = useCallback(
    (t: string, steps: SignOutSteps) => {
      setLeaving({ token: t, steps, busy: true })
      void signOut(api, t, steps).then((done) => setLeaving(signedOut(done) ? null : { token: t, steps: done, busy: false }))
    },
    [api],
  )
  const onLogout = useCallback(() => {
    saveToken(null)
    setExpired(false)
    setToken(null)
    if (token) leave(token, NOT_SIGNED_OUT)
  }, [token, leave])

  if (leaving) {
    return <SignOutPage busy={leaving.busy} onRetry={() => leave(leaving.token, leaving.steps)} onDone={() => setLeaving(null)} />
  }
  if (!token) return <LoginPage api={api} expired={expired} onLoggedIn={onLoggedIn} />
  return <Home key={token} api={api} token={token} initialUi={initialUi} clock={clock} onAuthLost={onAuthLost} onLogout={onLogout} />
}

type HomeProps = { api: Api; token: string; initialUi?: InitialUi; clock: () => number; onAuthLost: () => void; onLogout: () => void }

function Home({ api, token, initialUi, clock, onAuthLost, onLogout }: HomeProps) {
  const t = useTracker(api, token, onAuthLost, initialUi?.track ?? false)
  const deviceIds = useMemo(() => t.devices.map((d) => d.id), [t.devices])
  const places = usePlaces(api, token, t.selectedId, deviceIds, onAuthLost)
  /** 还没拉到时地图上先不画圈、不算「在学校」 */
  const placeList = useMemo(() => places.list ?? [], [places.list])
  // 状态卡两档：默认收起（地图优先），记住上次停的档
  const [snap, setSnapState] = useState<Snap>(() => initialUi?.snap ?? loadSnap())
  const setSnap = useCallback((s: Snap) => {
    setSnapState(s)
    saveSnap(s)
  }, [])
  // 二级页（告警、常去地点、详情、设置），记在地址的 #/… 里（某个孩子的页带着设备号）
  const route = usePage(initialUi?.page)
  // 设置：底图、深浅色，记在这台手机的浏览器里
  const [basePref, setBasePrefState] = useState<BasePref>(loadBasePref)
  const [themePref, setThemePrefState] = useState<ThemePref>(loadThemePref)
  const setBasePref = (b: BasePref) => {
    setBasePrefState(b)
    saveBasePref(b)
  }
  const setThemePref = (theme: ThemePref) => {
    setThemePrefState(theme)
    saveThemePref(theme)
    applyTheme(theme)
  }
  const [draft, setDraft] = useState<Draft | null>(
    initialUi?.addPlace ? newDraft(null, initialUi.placeRadius, initialUi.picking) : null,
  )
  const [card, setCard] = useState<Card | null>(initialUi?.card ? { id: initialUi.card, preview: initialUi.cardRadius ?? null } : null)
  /** 选点模式下图钉对准的地方，地图每动一下更新，不用重画页面 */
  const picked = useRef<Spot | null>(null)
  /** 选点模式下地图停下来时图钉的位置（查地址用），动起来是 null */
  const [pinSpot, setPinSpot] = useState<Spot | null>(null)
  const [locating, setLocating] = useState(false)
  const [locateNotice, setLocateNotice] = useState<string | null>(null)
  const [refit, setRefit] = useState(0)
  const [recenter, setRecenter] = useState(0)
  // 截图用：打开时把所有设备框进屏幕
  const fitAll = initialUi?.fitAll ? 1 : 0
  const [sheetHeight, setSheetHeight] = useState(260)
  const sheetRef = useRef<HTMLDivElement>(null)
  const switcherRef = useRef<HTMLElement>(null)

  useEffect(() => {
    const el = sheetRef.current
    if (!el) return
    const ro = new ResizeObserver(() => setSheetHeight(el.offsetHeight))
    ro.observe(el)
    return () => ro.disconnect()
  }, [t.loaded])

  // 顶部切换器：打开页面、换孩子、选点回来时把选中的那个滚到中间（孩子多、名字长时它可能在外面）。
  // 只在设备名单变了时重算，推来新位置不打断家长手动滑
  const switcherKey = t.devices.map((d) => `${d.id}:${d.name}`).join('|')
  const picking = draft?.picking ?? false
  useEffect(() => {
    const nav = switcherRef.current
    const item = nav?.querySelector<HTMLElement>('[aria-current="true"]')
    if (!nav || !item) return
    const n = nav.getBoundingClientRect()
    const b = item.getBoundingClientRect()
    nav.scrollTo({ left: centeredScrollLeft(nav.scrollLeft, n.left, n.width, b.left, b.width) })
  }, [t.selectedId, switcherKey, picking])

  const device = t.devices.find((d) => d.id === t.selectedId)
  // 浏览器前进、刷新恢复的页属于别的孩子：先换到那个孩子再显示；那台设备看不到了回地图
  const target = route.route ? routeTarget(route.route, { loaded: t.loaded, deviceIds, selectedId: t.selectedId }) : null
  const selectTo = target?.kind === 'select' ? target.deviceId : null
  const mustLeave = target?.kind === 'leave'
  const { select: selectKid } = t
  const { leave: leavePage, open: openRoute } = route
  useEffect(() => {
    if (selectTo !== null) selectKid(selectTo)
    else if (mustLeave) leavePage()
  }, [selectTo, mustLeave, selectKid, leavePage])
  const openPage = (p: Page) => openRoute(p, t.selectedId)
  const page = target?.kind === 'show' && route.route ? visiblePage(route.route.page, { device: device !== undefined, position: t.position !== null }) : null
  // 这页打开时告警补查一次：推送断着时不能只信上次查的
  const { reloadEvents } = t
  useEffect(() => {
    if (page === 'alerts') reloadEvents()
  }, [page, reloadEvents])
  // 家长这边连不上服务器超过 1 分钟：手里的数据停在最后一次同步那一刻，颜色档按那一刻判，不因为本地时钟往前走就显示孩子失联
  const offlineSince = parentOfflineSince(t, t.now)
  // 每台设备：最新一条上报定颜色档和在不在路上，挪过圆点的点剔除跳点、平滑以后给地图播放（motion/smooth）
  const kids = useMemo(() => {
    const out: (KidOnMap & { presence: Presence; kept: readonly Position[]; shown: Position | null })[] = []
    for (const d of t.devices) {
      const p = t.positions.get(d.id)
      if (!p) continue
      const h = t.history.get(d.id) ?? EMPTY_HISTORY
      const since = movingSince(h.reports)
      const presence = offlineSince ? offlinePresence(p, offlineSince, since) : presenceOf(p, t.now, since)
      const smoothed = smoothTrack(steadyOf(h))
      const { dot, kept } = smoothed
      // 卡片的地址、围栏、时间用地图实际采信的那个点；被剔除的漂移点只更新电量和最后联系。
      // 手机从没定到过位置（0,0 占位）时没有这个点，地图上不画圆点
      const fallback = p.unlocated ? null : p
      const shown = acceptedFix(smoothed) ?? fallback
      out.push({ id: d.id, name: d.name, ...presence, presence, hold: holdsDot(p), fixes: dot.length > 0 ? dot : fallback ? [fallback] : [], kept, shown })
    }
    return out
  }, [t.devices, t.positions, t.history, t.now, offlineSince])
  const selected = kids.find((k) => k.id === t.selectedId)
  const presence: Presence = selected?.presence ?? { freshness: 'lost', live: null }
  /** 地图实际采信的那个定位点（状态卡的地址、常去地点、定位方式按它）；手机从没定到过位置时是 null */
  const shown = selected?.shown ?? null
  /** 最新一条上报，但只在它的坐标是真位置时（设为常去地点的圆心、搜索限定的城市） */
  const located = t.position && !t.position.unlocated ? t.position : null
  // 在路上（含位置旧了的）画最近 30 分钟的轨迹线（跳点不画）；看今天的轨迹时不画
  const trail = useMemo(() => {
    const k = kids.find((kid) => kid.id === t.selectedId)
    return k && k.live !== null && !t.trackOn ? trailOf(k.kept, t.now) : null
  }, [kids, t.selectedId, t.trackOn, t.now])

  const place = shown ? placeOf(shown, placeList) : undefined
  // 位置可能没跟上：在附近活动或静止、最近 15 分钟的网络估算离圆点很远（domain/lagging）。卡片第三行换成提示，地图画淡色估算圈，圆点不动。
  // 手机从没定到过位置（没有圆点）时，有估算就只画估算圈，卡片照写「还没有位置」
  const netEstimate = t.position ? recentEstimate(t.selectedHistory.reports, t.now) : null
  const situation =
    t.position && shown
      ? situationOf({ position: t.position, presence, history: t.selectedHistory, offlineSince, updating: t.updating, slow: t.slow, now: t.now })
      : null
  const lagging = situation && netEstimate && shown ? laggingOf(situation, netEstimate, shown) : null
  const estimateCircle = lagging
    ? { latitude: lagging.latitude, longitude: lagging.longitude, radius: lagging.accuracy }
    : !shown && netEstimate?.netLat !== undefined && netEstimate.netLon !== undefined
      ? { latitude: netEstimate.netLat, longitude: netEstimate.netLon, radius: netEstimate.netAcc ?? 0 }
      : null
  const draftCenter = draft ? (draft.pickFrom ?? draft.center ?? located) : null
  const draftCircle = draft && draftCenter ? { latitude: draftCenter.latitude, longitude: draftCenter.longitude, radius: draft.radius } : null
  const updateDraft = (patch: Partial<Draft>) => setDraft((d) => (d ? { ...d, ...patch } : d))

  // 地点卡片：圈画成实线（改范围时就是预览的半径），这个地点的虚线圈先不画
  const cardPlace = card && !draft ? placeList.find((p) => p.id === card.id) : undefined
  const cardCircle = cardPlace ? parseCircle(cardPlace.area) : undefined
  const mapCircle = draftCircle ?? (cardCircle ? { ...cardCircle, radius: card?.preview ?? cardCircle.radius } : null)

  // 高德查地址：图钉位置（停下 600 毫秒后）、地点卡片的圆心、搜索限定的城市（孩子所在城市，约 1 公里内不重查）
  const pinRegeo = useRegeo(api, token, picking && pinSpot ? pinSpot.gcj : null, PIN_ADDRESS_DELAY_MS)
  const cardRegeo = useRegeo(api, token, cardCircle ? spotFromWgs(cardCircle).gcj : null)
  // 估算位置的地址：取到约 100 米（估算本身误差就有几十到上千米），估算一点点变时不重查
  const laggingRegeo = useRegeo(
    api,
    token,
    lagging ? spotFromWgs({ latitude: Math.round(lagging.latitude * 1000) / 1000, longitude: Math.round(lagging.longitude * 1000) / 1000 }).gcj : null,
  )
  const cityAt = picking ? (located ?? draftCenter) : null
  const cityRegeo = useRegeo(
    api,
    token,
    cityAt ? spotFromWgs({ latitude: Math.round(cityAt.latitude * 100) / 100, longitude: Math.round(cityAt.longitude * 100) / 100 }).gcj : null,
  )

  const closeCard = useCallback(() => {
    setCard(null)
    setRecenter((n) => n + 1)
  }, [])
  const previewRadius = useCallback((r: number | null) => setCard((c) => (c ? { ...c, preview: r } : c)), [])
  const openCard = (id: number) => {
    setDraft(null)
    setCard({ id, preview: null })
  }

  /** 选点时把图钉移到这一点（搜索结果、我的位置），地图飞过去；定位、搜索回来时已经退出选点就不动 */
  const pinTo = (spot: Spot) => {
    setLocateNotice(null)
    setDraft((d) => (d?.picking ? { ...d, pickFrom: spot.wgs } : d))
    setRefit((n) => n + 1)
  }
  const pinToMe = async () => {
    setLocating(true)
    setLocateNotice(null)
    const me = await locateMe()
    setLocating(false)
    if (me) pinTo(spotFromWgs(me))
    else setLocateNotice(LOCATE_FAILED)
  }
  const startPicking = (from: LatLon) => {
    setLocateNotice(null)
    setPinSpot(null)
    updateDraft({ picking: true, pickFrom: from })
  }
  // 关掉（保存、取消）回到孩子身上
  const closeDraft = useCallback(() => {
    setDraft(null)
    setRecenter((n) => n + 1)
  }, [])
  /** 长按、右键地图上一点：打开「设为常去地点」，圆心是这一点；已经开着就只换圆心 */
  const placeAt = (center: LatLon) => {
    setCard(null)
    setDraft((d) => (d ? { ...d, center } : newDraft(center)))
  }

  const select = (id: number) => {
    t.select(id)
    setDraft(null)
    setCard(null)
    setRecenter((n) => n + 1)
  }
  const toggleTrack = () => {
    const on = !t.trackOn
    t.setTrackOn(on)
    if (!on) setRecenter((n) => n + 1)
  }

  return (
    <main className="relative h-dvh overflow-hidden bg-map" data-picking={picking ? '' : undefined}>
      {/* 二级页盖着时，地图、顶栏、抽屉都不能被键盘聚焦和点到 */}
      <div className="contents" inert={page !== null}>
        <MapView
          kids={kids}
          selectedId={t.selectedId}
          onSelect={select}
          trail={trail}
          track={t.track}
          places={placeList}
          hiddenPlaceId={cardPlace?.id}
          onPlaceClick={device && !draft ? openCard : undefined}
          draft={mapCircle}
          estimate={estimateCircle}
          picking={picking}
          onPickMove={(p) => {
            picked.current = p
          }}
          onPickSettle={setPinSpot}
          refit={refit}
          onLongPress={device && !picking ? placeAt : undefined}
          recenter={recenter}
          fitAll={fitAll}
          bottomInset={sheetHeight}
          onMapTouch={() => {
            // 碰地图：展开的卡片收起，让出地图
            if (snap === 'open') setSnap('peek')
          }}
          basePref={basePref}
          clock={clock}
        />

        {picking ? (
          <header className="pointer-events-none absolute inset-x-0 top-0 z-[1000] mx-auto max-w-[480px] px-3 pt-[max(12px,env(safe-area-inset-top))]">
            <PlaceSearch api={api} token={token} city={cityRegeo?.city ?? null} onChoose={pinTo} initialQuery={initialUi?.search} />
          </header>
        ) : (
          t.devices.length > 1 && (
            // 顶部只留切换孩子（头像 + 名字）；只有一个孩子时什么都不放，地图整个露出来
            <header className="pointer-events-none absolute inset-x-0 top-0 z-[1000] flex px-3 pt-[max(12px,env(safe-area-inset-top))]">
              <nav ref={switcherRef} className="pointer-events-auto flex min-w-0 gap-1 overflow-x-auto rounded-full bg-float p-1 shadow-card" aria-label="切换孩子">
                {t.devices.map((d) => {
                  const on = d.id === t.selectedId
                  return (
                    <button
                      key={d.id}
                      type="button"
                      onClick={() => select(d.id)}
                      aria-current={on}
                      className={`flex shrink-0 items-center gap-1.5 rounded-full py-1 pr-3 pl-1 text-body ${on ? 'bg-paper font-semibold text-ink' : 'text-mute'}`}
                    >
                      <span
                        aria-hidden
                        className={`flex size-7 items-center justify-center rounded-full text-note font-semibold ${on ? 'bg-ink text-paper' : 'bg-press text-mute'}`}
                      >
                        {[...d.name][0]}
                      </span>
                      {d.name}
                    </button>
                  )
                })}
              </nav>
            </header>
          )
        )}

        <div ref={sheetRef} className="pointer-events-none absolute inset-x-0 bottom-0 z-[1000]">
          {!t.loaded ? (
            <div className="pointer-events-auto mx-auto mb-[max(24px,env(safe-area-inset-bottom))] flex w-fit max-w-[calc(100%-24px)] items-center gap-2 rounded-full bg-paper py-1.5 pr-1.5 pl-4 text-body text-mute shadow-card">
              <span className="min-w-0">{t.error ? `${t.error}，30 秒后自动重试` : '正在连接服务器…'}</span>
              <SettingsButton onClick={() => openPage('settings')} />
            </div>
          ) : !device ? (
            <div className="pointer-events-auto mx-auto w-full max-w-[480px] rounded-t-card bg-paper px-5 pt-5 pb-[max(20px,env(safe-area-inset-bottom))] shadow-card">
              <p className="text-name font-semibold text-ink">这个账号下还没有孩子的手机</p>
              <p className="mt-1 text-body text-mute">去完整后台添加孩子手机 App 首页显示的设备标识。完整后台和退出登录都在设置里。</p>
              <div className="mt-3">
                <SettingsButton onClick={() => openPage('settings')} />
              </div>
            </div>
          ) : draft && draftCircle && draft.picking ? (
            <PickSheet
              address={pinRegeo?.address ?? null}
              notice={locateNotice}
              radius={draft.radius}
              // 换范围时圆心留在图钉现在对准的地方，地图只缩放
              onRadius={(radius) => updateDraft({ radius, pickFrom: picked.current?.wgs ?? draft.pickFrom })}
              onMyLocation={() => void pinToMe()}
              locating={locating}
              onUse={() =>
                updateDraft({
                  center: picked.current?.wgs ?? { latitude: draftCircle.latitude, longitude: draftCircle.longitude },
                  picking: false,
                  pickFrom: null,
                })
              }
              onCancel={() => updateDraft({ picking: false, pickFrom: null })}
            />
          ) : draft && draftCircle ? (
            <PlaceSheet
              kidName={device.name}
              pickedOnMap={draft.center !== null}
              name={draft.name}
              onName={(name) => updateDraft({ name })}
              radius={draft.radius}
              onRadius={(radius) => updateDraft({ radius })}
              onPick={() => startPicking({ latitude: draftCircle.latitude, longitude: draftCircle.longitude })}
              onSave={async (name) => {
                await places.add(name, draftCircle)
                closeDraft()
              }}
              onCancel={closeDraft}
            />
          ) : cardPlace ? (
            <PlaceCard
              key={cardPlace.id}
              place={cardPlace}
              circle={cardCircle}
              address={cardRegeo?.address ?? null}
              onRename={(name) => places.update(cardPlace.id, name, cardPlace.area)}
              onResize={(c) => places.update(cardPlace.id, cardPlace.name, circleArea(c))}
              onPreview={previewRadius}
              onDelete={() => places.remove(cardPlace.id)}
              kidName={device.name}
              sharedWith={async () => {
                const ids = await places.sharedWith(cardPlace.id)
                return t.devices.filter((d) => ids.includes(d.id)).map((d) => d.name)
              }}
              onUnlink={() => places.unlink(cardPlace.id)}
              onClose={closeCard}
              initialMode={initialUi?.cardRadius ? 'radius' : undefined}
              initialRadius={initialUi?.cardRadius}
            />
          ) : (
            <StatusSheet
              name={device.name}
              position={t.position}
              fix={shown}
              lagging={lagging && laggingText(lagging, laggingRegeo?.address)}
              place={place}
              places={places.list}
              presence={presence}
              history={t.selectedHistory}
              offlineSince={offlineSince}
              updating={t.updating}
              slow={t.slow}
              now={t.now}
              events={t.events}
              eventsAsOf={t.eventsAsOf}
              eventPositions={t.eventPositions}
              error={t.error}
              trackOn={t.trackOn}
              snap={snap}
              onSnap={setSnap}
              onToggleTrack={toggleTrack}
              onAddPlace={() => setDraft(newDraft(null))}
              onOpen={openPage}
              onReconnect={t.refresh}
            />
          )}
        </div>
      </div>

      {page && (
        <SubPage title={device && isDevicePage(page) ? `${device.name} · ${PAGE_TITLE[page]}` : PAGE_TITLE[page]} onBack={route.close}>
          {page === 'alerts' && (
            <AlertsPage
              events={t.events}
              asOf={t.eventsAsOf}
              error={t.eventsError}
              eventPositions={t.eventPositions}
              places={placeList}
              now={t.now}
              onRetry={t.reloadEvents}
            />
          )}
          {page === 'places' && (
            <PlacesPage
              places={places.list}
              onOpen={(id) => {
                route.close()
                openCard(id)
              }}
            />
          )}
          {page === 'details' && t.position && <DetailsPage position={t.position} place={placeOf(t.position, placeList)} now={t.now} />}
          {page === 'settings' && (
            <SettingsPage
              base={basePref}
              onBase={setBasePref}
              theme={themePref}
              onTheme={setThemePref}
              onLogout={() => {
                // 地址里的 #/settings 也去掉：下次登录回到地图，不是直接进设置页
                route.close()
                onLogout()
              }}
            />
          )}
        </SubPage>
      )}
    </main>
  )
}

/** 加载中、连不上服务器、账号下没有设备时进设置（退出登录、完整后台都在那里） */
function SettingsButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="h-9 shrink-0 rounded-full border border-line px-4 text-body text-ink active:bg-press">
      设置
    </button>
  )
}
