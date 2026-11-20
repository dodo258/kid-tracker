"""上报端 v2 判断层（app-v2/sdk/core/src/commonMain 的 sense/、motion/、policy/）的 Python 版，模拟手机用。

模拟器不依赖 App 和 SDK，所以照着 Kotlin 移植一份。怎么保证两边一样：
- 结构一一对应：每个 Kotlin 类一个同名类，方法同名（onSnapshot → on_snapshot），常量放在同名类上、名字和 Kotlin 的
  companion 一字不差（MotionClassifier.ACTIVE_ACC、ReportPolicy.MOVING_EVERY……）。test_simulator.py 的 ReporterConstantsTest
  读 Kotlin 源码逐个比对，改了一边的数没改另一边就过不了。
- 公开的入口全移植，模拟器用不到的也移植（心跳 on_tick、改钟 clock_shifted、重启接着静止 restored、策略自己发现往回改钟）：
  以前只移植模拟场景走得到的分支，Kotlin 那边加的分支这里漏了也没人知道。
- 行为两道检查：`python3 sim.py golden` 把几个场景的输入和这里的判断写成金样本，SDK 的 SimulatorGoldenTest 用 Kotlin 重放、逐行比对；
  金样本走不到的分支由 test_simulator.py 的 ReporterContractTest 用和 Kotlin 单测同样的输入、同样的期望钉住。
改了这里或 Kotlin 那边任何一处，两边一起改，补对应的用例，再重新生成金样本。

没移植的：Kotlin Judge 的 wifiChanged、mainChanged（通知引擎存 Wi-Fi 记忆和上一个主坐标，模拟器重启时直接拿 wifi.memory、fallback.main）、
current（App 里「发送位置」）。
时间都是毫秒。
"""

import math
import re
from dataclasses import dataclass, field, replace

MIN_LONG = -(2**63)

# ---- sense/：快照、天空 ----


@dataclass(frozen=True)
class Fix:
    """Snapshot.Fix / Position 里判断层用到的几样"""

    provider: str  # gps / wifi / cell / network；NetFallback 顶的卫星锚点是 anchor
    time: int
    lat: float | None = None
    lon: float | None = None
    acc: float | None = None
    speed: float | None = None  # m/s
    elapsed: int | None = None  # 定位时的开机时长（毫秒），旧录制、模拟器没有


@dataclass(frozen=True)
class Snap:
    """sense/Snapshot.kt 里判断层用到的字段"""

    time: int
    airplane: bool = False
    acc_mean: float | None = None
    acc_std: float | None = None
    acc_n: int = 0
    acc_peaks: int | None = None
    gyro_mean: float | None = None
    steps_total: int | None = None
    net: str = "none"  # wifi / cell / other / none
    wifi_bssid: str = ""
    wifi_ssid: str = ""
    wifi_rssi: int | None = None
    wifi_metered: bool | None = None
    wifi_gateway: str = ""
    fix: Fix | None = None
    cell_tac: int | None = None
    cell_id: int | None = None
    net_link: int | None = None  # 这条网络连接是第几条：同一个 Wi-Fi 断开重连也变（审计 11.0.11 第 3 条）
    elapsed: int | None = None  # 这一窗结束时的开机时长（毫秒，改系统时间不影响，审计 11.0.11 第 6 条）

    BATCH_LATE_MILLIS = 10_000  # 不是字段（没有类型标注）：快照一批 5 份约 10 秒，晚一批以内的卫星点也算新鲜

    def fresh_gps(self, max_age: int) -> Fix | None:
        f = self.fix
        if f and f.provider == "gps" and -Snap.BATCH_LATE_MILLIS <= self.age_of(f) <= max_age:
            return f
        return None

    def age_of(self, f: Fix) -> int:
        """[f] 比这一窗早多少：两边都有开机时长就用它，不然用时间"""
        if self.elapsed is not None and f.elapsed is not None:
            return self.elapsed - f.elapsed
        return self.time - f.time

    @property
    def clock(self) -> int:
        """判断快照间隔用的时刻：有开机时长用它，没有用时间"""
        return self.elapsed if self.elapsed is not None else self.time

    def cell_key(self) -> str | None:
        if self.cell_id is None:
            return None
        return f"{'' if self.cell_tac is None else self.cell_tac}-{self.cell_id}"


class Sky:
    """sense/Sky.kt：卫星点按精度分三档，没精度的当开阔"""

    OPEN, PARTIAL, BLOCKED = "open", "partial", "blocked"
    OPEN_ACCURACY = 50.0
    BLOCKED_ACCURACY = 300.0

    @staticmethod
    def of(acc: float | None) -> str:
        if acc is None or acc <= Sky.OPEN_ACCURACY:
            return Sky.OPEN
        return Sky.PARTIAL if acc <= Sky.BLOCKED_ACCURACY else Sky.BLOCKED

    @staticmethod
    def main_coordinate(acc: float | None) -> bool:
        """能不能当主坐标：头顶被挡的不能（普通上报、告警、求救、手动发送共用）"""
        return Sky.of(acc) != Sky.BLOCKED


# ---- motion/：运动判断 ----

STILL, FIDGET, WALK, VEHICLE, OFF = "still", "fidget", "walk", "vehicle", "off"
MOVING_MOTIONS = (WALK, VEHICLE)


def _lasted(start: int | None, t: int, ms: int) -> bool:
    return start is not None and t - start >= ms


def _within(start: int | None, t: int, ms: int) -> bool:
    return start is not None and t - start < ms


class MotionClassifier:
    """motion/MotionClassifier.kt。[raw] 是没被 Wi-Fi 改判的判断，update 返回改判后的"""

    WINDOW_MILLIS = 10_000
    MAX_GAP_MILLIS = 10_000
    ACTIVE_ACC = 0.15
    QUIET_ACC = 0.05
    QUIET_GYRO = 0.01
    STILL_MILLIS = 120_000
    SWING_ACC = 2.0
    SWING_GYRO = 0.8
    SWING_COUNT = 3
    RHYTHM_WINDOW_MILLIS = 20_000
    RHYTHM_PEAKS = 24
    RHYTHM_ACC = 0.5
    GAIT_HOLD_MILLIS = 60_000
    VEHICLE_AFTER_MILLIS = 60_000
    BOUT_GAP_MILLIS = 30_000
    VEHICLE_IDLE_MILLIS = 180_000
    VEHICLE_SPEED = 3.0
    SLOW_SPEED = 1.0
    GPS_FRESH_MILLIS = 10_000
    GPS_STALE_MILLIS = 30_000
    FAST_HOLD_MILLIS = 60_000
    DENY_MILLIS = 180_000
    RECHECK_MILLIS = 300_000

    def __init__(self) -> None:
        self.raw = FIDGET
        self.last_time: int | None = None
        self.seeded = False
        self._reset()

    def seed_still(self) -> None:
        """重启前是静止：接着静止，等「有动静」再出来"""
        self.raw = STILL
        self.seeded = True

    def _reset(self) -> None:
        self.raw = FIDGET
        self.window: list[Snap] = []
        self.quiet_since: int | None = None
        self.bout_start: int | None = None
        self.last_active: int | None = None
        self.last_gait: int | None = None
        self.last_fast: int | None = None
        self.last_gps: int | None = None
        self.slow_since: int | None = None
        self.vehicle_blocked_until: int | None = None
        self.last_fixed_wifi: str | None = None

    def update(self, s: Snap, fixed_wifi: str | None = None) -> str:
        c = MotionClassifier
        t = s.clock  # 快照间隔按开机时长算：系统时间被改快一天也接着判
        previous = self.last_time
        self.last_time = t
        if s.airplane:
            self._reset()
            self.raw = OFF
            return OFF
        if previous is None or not (1 <= t - previous <= c.MAX_GAP_MILLIS) or self.raw == OFF:
            stay_still = (previous is not None or self.seeded) and self.raw == STILL
            self._reset()
            if stay_still:
                self.raw = STILL
        self.seeded = False

        self.window.append(s)
        while self.window[0].clock <= t - c.RHYTHM_WINDOW_MILLIS:
            self.window.pop(0)
        recent = [w for w in self.window if w.clock > t - c.WINDOW_MILLIS]
        spread = _pooled_std(recent)
        active = spread >= c.ACTIVE_ACC
        # 只拿开阔天空下的卫星点测速
        g = s.fresh_gps(c.GPS_FRESH_MILLIS)
        speed = g.speed if g and Sky.of(g.acc) == Sky.OPEN else None
        fast = speed is not None and speed > c.VEHICLE_SPEED
        # 卫星正说在快速移动时不算安静
        quiet = s.acc_std is not None and s.acc_std < c.QUIET_ACC and (s.gyro_mean or 0.0) < c.QUIET_GYRO and not fast
        self.quiet_since = (self.quiet_since if self.quiet_since is not None else t) if quiet else None
        if active:
            last = self.last_active
            if self.bout_start is None or last is None or t - last >= c.BOUT_GAP_MILLIS:
                self.bout_start = t
            self.last_active = t

        if speed is not None:
            # 卫星断过一阵再来：以前那段「没在走」不接着算
            if not _within(self.last_gps, t, c.GPS_STALE_MILLIS):
                self.slow_since = None
            self.last_gps = t
            if fast:
                self.last_fast = t
            self.slow_since = None if speed > c.SLOW_SPEED else (self.slow_since if self.slow_since is not None else t)
        gps_denies = _lasted(self.slow_since, t, c.DENY_MILLIS) and _within(self.last_gps, t, c.GPS_STALE_MILLIS)
        swinging = (
            sum(1 for w in recent if (w.acc_std or 0.0) >= c.SWING_ACC and (w.gyro_mean or 0.0) >= c.SWING_GYRO)
            >= c.SWING_COUNT
        )
        rhythmic = spread >= c.RHYTHM_ACC and sum(w.acc_peaks or 0 for w in self.window) >= c.RHYTHM_PEAKS
        gait = not _within(self.last_fast, t, c.FAST_HOLD_MILLIS) and (swinging or rhythmic)
        if gait:
            self.last_gait = t
        gait_recent = _within(self.last_gait, t, c.GAIT_HOLD_MILLIS)
        blocked = self.vehicle_blocked_until is not None and t < self.vehicle_blocked_until

        same_fixed = fixed_wifi is not None and fixed_wifi == self.last_fixed_wifi
        self.last_fixed_wifi = fixed_wifi

        m = self.raw
        # 开阔天空下的高速卫星点排在「此前静止、没动静」前面（审计 11.0.11 第 7 条）
        if _lasted(self.quiet_since, t, c.STILL_MILLIS):
            nxt = STILL
        elif fast:
            nxt = VEHICLE
        elif m == STILL and not active:
            nxt = STILL
        elif gait:
            nxt = WALK
        elif m == WALK and gait_recent:
            nxt = WALK
        elif m == VEHICLE and not gps_denies and _within(self.last_active, t, c.VEHICLE_IDLE_MILLIS):
            nxt = VEHICLE
        elif (
            active
            and _lasted(self.bout_start, t, c.VEHICLE_AFTER_MILLIS)
            and not gait_recent
            and not gps_denies
            and not blocked
        ):
            nxt = VEHICLE
        else:
            nxt = FIDGET
        if m == VEHICLE and nxt == FIDGET and gps_denies:
            self.vehicle_blocked_until = t + c.RECHECK_MILLIS
        self.raw = nxt
        return FIDGET if nxt in MOVING_MOTIONS and same_fixed and not fast else nxt


def _pooled_std(snaps: list[Snap]) -> float:
    n = 0
    total = 0.0
    squares = 0.0
    for w in snaps:
        if w.acc_mean is None or w.acc_std is None or w.acc_n < 2:
            continue
        n += w.acc_n
        total += w.acc_n * w.acc_mean
        squares += w.acc_n * (w.acc_std * w.acc_std + w.acc_mean * w.acc_mean)
    if n == 0:
        return 0.0
    m = total / n
    return math.sqrt(max(squares / n - m * m, 0.0))


# ---- policy/ReportPolicy.kt：开不开卫星、什么时候报哪个点 ----

MOVING, ZONE, STILL_STATE = "moving", "zone", "still"
STATE_OF = {WALK: MOVING, VEHICLE: MOVING, FIDGET: ZONE, STILL: STILL_STATE, OFF: None}


@dataclass
class Outgoing:
    time: int
    fix: Fix | None
    reason: str


@dataclass
class Decision:
    report: Outgoing | None = None
    fetch: bool = False


@dataclass
class _Seen:
    fix: Fix
    at: int


class ReportPolicy:
    MOVING_EVERY = 10_000
    GPS_WAIT = 30_000
    NO_FIX_EVERY = 60_000
    GPS_GIVE_UP = 180_000
    GPS_RETRY = 120_000
    ZONE_EVERY = 300_000
    BREATH_EVERY = 300_000
    STILL_FETCH_MAX = 4
    HOUR = 3_600_000
    FRESH = 10_000

    def __init__(self) -> None:
        self.motion: str | None = None
        self.latest_gps: _Seen | None = None
        self.latest_net: _Seen | None = None
        # 报过的点的定位时间按来源分开记（网络定位跟手机时间走、卫星点用卫星时间）
        self.reported_gps_time = MIN_LONG
        self.reported_net_time = MIN_LONG
        self.last_report_at = MIN_LONG // 2
        self.search_since: int | None = None
        self.gps_paused_at: int | None = None
        self.next_zone_at = 0
        self.next_breath_at = 0
        self.fetch_for: str | None = None
        self.breathing = False  # 正在取的网络定位是静止呼吸顺带取的：回来时报这次呼吸
        self.still_fetches: list[int] = []  # 静止时取网络定位的时刻，只留最近一小时（STILL_FETCH_MAX）
        self.last_now: int | None = None

    @property
    def state(self) -> str | None:
        return STATE_OF[self.motion] if self.motion else None

    @property
    def gps(self) -> bool:
        return self.state == MOVING and self.gps_paused_at is None

    def seed(self, restored: str) -> None:
        """重启前的运动状态（只用静止）：不报状态变化，第一次 on_tick 就呼吸"""
        self.motion = restored
        self.next_breath_at = 0

    def _clock(self, now: int) -> None:
        """时间被往回改了：记的时间全部跟着往回挪"""
        before = self.last_now
        if before is not None and now < before:
            self.shift(now - before)
        self.last_now = now

    def shift(self, delta: int) -> None:
        if delta == 0:
            return
        self.last_now = None if self.last_now is None else self.last_now + delta
        self.last_report_at += delta
        self.search_since = None if self.search_since is None else self.search_since + delta
        self.gps_paused_at = None if self.gps_paused_at is None else self.gps_paused_at + delta
        self.next_zone_at += delta
        self.next_breath_at += delta
        self.latest_gps = self.latest_gps and _Seen(self.latest_gps.fix, self.latest_gps.at + delta)
        self.latest_net = self.latest_net and _Seen(self.latest_net.fix, self.latest_net.at + delta)
        self.still_fetches = [t + delta for t in self.still_fetches]
        # 有的定位点时间跟系统时间走：不再拿它比新旧
        self.reported_gps_time = MIN_LONG
        self.reported_net_time = MIN_LONG

    def on_motion(self, now: int, nxt: str) -> Decision:
        self._clock(now)
        before = self.motion
        self.motion = nxt
        state = STATE_OF[nxt]
        if state is None:
            self.search_since = None
            self.gps_paused_at = None
            self.fetch_for = None
            self.breathing = False
            return Decision()
        frm = STATE_OF[before] if before is not None else None
        if before is not None and frm == state:
            return Decision()
        return self._enter(now, state, moved=frm is None or frm == MOVING)

    def on_fix(self, now: int, fix: Fix) -> Decision:
        self._clock(now)
        seen = _Seen(fix, now)
        if fix.provider == "gps":
            self.latest_gps = seen
        else:
            self.latest_net = seen
        return self._moving(now) if self.state == MOVING else Decision()

    def probed(self, now: int, fix: Fix) -> Decision:
        """探测量出已离开锚、运动判断没说在动：这个卫星点当主坐标马上报，状态不变"""
        self._clock(now)
        self.latest_gps = _Seen(fix, now)
        return Decision(self._report(now, fix, "probe"))

    def located(self, now: int, fix: Fix) -> Outgoing:
        """确认过的网络估算要当主坐标（NetFallback），这一刻策略又没别的要报：单独报一条"""
        self._clock(now)
        self.latest_net = _Seen(fix, now)
        return self._report(now, fix, "estimate")

    def confirm(self, now: int) -> Decision:
        """网络估算要再取一次确认：屋里、静止时马上取，结果照这个状态的规矩报；在路上自己会取、已经在取也不另取"""
        self._clock(now)
        state = self.state
        if self.fetch_for is not None or state is None or state == MOVING:
            return Decision()
        if state == STILL_STATE and not self._still_fetch_allowed(now):
            return Decision()
        if state == ZONE:
            self.next_zone_at = now + self.ZONE_EVERY
        return self._fetch(now)

    def on_fetched(self, now: int, fix: Fix | None) -> Decision:
        self._clock(now)
        purpose = self.fetch_for
        breath = self.breathing
        self.fetch_for = None
        self.breathing = False
        if fix is not None and fix.provider != "gps":
            self.latest_net = _Seen(fix, now)
        if purpose is None or purpose != self.state:
            return Decision()
        if purpose == STILL_STATE:
            if breath:
                return Decision(self._report(now, fix, "breath"))
            return Decision(self._report(now, fix, "last")) if fix else Decision()
        if purpose == ZONE:
            return Decision(self._report(now, fix, "zone"))
        return Decision(self._report(now, fix, "nogps"))

    def on_tick(self, now: int, locate: bool = False) -> Decision:
        """locate：主坐标要顶（NetFallback.wants），静止呼吸时顺带取一次网络定位"""
        self._clock(now)
        state = self.state
        if state == MOVING:
            return self._moving(now)
        if state == ZONE:
            if now >= self.next_zone_at and self.fetch_for is None:
                self.next_zone_at = now + self.ZONE_EVERY
                return self._fetch(now)
            return Decision()
        if state == STILL_STATE and now >= self.next_breath_at:
            self.next_breath_at = now + self.BREATH_EVERY
            if locate and self.fetch_for is None and self._still_fetch_allowed(now):
                self.breathing = True
                return self._fetch(now)
            return Decision(self._report(now, None, "breath"))
        return Decision()

    def _enter(self, now: int, state: str, moved: bool) -> Decision:
        self.search_since = now if state == MOVING else None
        self.gps_paused_at = None
        self.fetch_for = None
        self.breathing = False
        candidates = [
            x for x in (self.latest_gps, self.latest_net) if x and now - x.at <= self.FRESH and self._unreported(x.fix)
        ]
        # 按收到的先后挑：两种来源的定位时间不是一个钟（max 遇到相同取先出现的卫星点，和 Kotlin 的 maxByOrNull 一样）
        fix = max(candidates, key=lambda x: x.at).fix if candidates else None
        out = self._report(now, fix, "state")
        if state == MOVING:
            return Decision(out)
        if state == ZONE:
            self.next_zone_at = now + self.ZONE_EVERY
        else:
            self.next_breath_at = now + self.BREATH_EVERY
        return self._fetch(now, out) if fix is None and moved else Decision(out)

    def _moving(self, now: int) -> Decision:
        since = self.search_since if self.search_since is not None else now
        gps_seen = self.latest_gps.at if self.latest_gps and self.latest_gps.at >= since else None
        last_gps_at = gps_seen if gps_seen is not None else since
        net_allowed = now - gps_seen > self.FRESH if gps_seen is not None else now - since >= self.GPS_WAIT
        paused = self.gps_paused_at
        if paused is not None and now - paused >= self.GPS_RETRY:
            self.gps_paused_at = None
            self.search_since = now
        elif paused is None and now - last_gps_at >= self.GPS_GIVE_UP:
            self.gps_paused_at = now
        if now - self.last_report_at < self.MOVING_EVERY:
            return Decision()
        f = self._fresh(self.latest_gps, now)
        if f:
            return Decision(self._report(now, f, "gps"))
        if net_allowed:
            f = self._fresh(self.latest_net, now)
            if f:
                return Decision(self._report(now, f, "net"))
        if now - self.last_report_at < self.NO_FIX_EVERY:
            return Decision()
        if self.gps_paused_at is None:
            return Decision(self._report(now, None, "nofix"))
        return self._fetch(now) if self.fetch_for is None else Decision()

    def _fresh(self, seen: _Seen | None, now: int) -> Fix | None:
        if seen and now - seen.at <= self.FRESH and self._unreported(seen.fix):
            return seen.fix
        return None

    def _unreported(self, fix: Fix) -> bool:
        return fix.time > (self.reported_gps_time if fix.provider == "gps" else self.reported_net_time)

    def _fetch(self, now: int, out: Outgoing | None = None) -> Decision:
        self.fetch_for = self.state
        if self.state == STILL_STATE:
            self.still_fetches.append(now)
        return Decision(out, fetch=True)

    def _still_fetch_allowed(self, now: int) -> bool:
        """静止时最近一小时取网络定位还没到 STILL_FETCH_MAX 次"""
        while self.still_fetches and now - self.still_fetches[0] >= self.HOUR:
            self.still_fetches.pop(0)
        return len(self.still_fetches) < self.STILL_FETCH_MAX

    def _report(self, now: int, fix: Fix | None, reason: str) -> Outgoing:
        self.last_report_at = now
        if fix is not None:
            if fix.provider == "gps":
                self.reported_gps_time = fix.time
            else:
                self.reported_net_time = fix.time
        return Outgoing(now, fix, reason)


MOVING_EVERY = ReportPolicy.MOVING_EVERY  # phone.py 用

# ---- policy/WifiBook.kt：锚 Wi-Fi、跟着走过的 Wi-Fi、锚这里见过的基站 ----

ANCHOR, HOTSPOT, OTHER, NO_WIFI = "anchor", "hotspot", "other", "none"
EARTH_RADIUS_METERS = 6_371_000.0


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    # 和 Kotlin 的 distanceMeters 逐步一样（先乘 π 再除 180），金样本里比门槛时不差最后一位
    p1, p2 = lat1 * math.pi / 180, lat2 * math.pi / 180
    dp, dl = (lat2 - lat1) * math.pi / 180, (lon2 - lon1) * math.pi / 180
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_METERS * math.asin(math.sqrt(h))


@dataclass
class WifiMemory:
    """存在状态库里的 Wi-Fi 记忆（Kotlin 的 WifiMemory）"""

    anchor: str | None = None
    anchor_at: Fix | None = None  # 锚被记下时的卫星点（只用纬度、经度、精度）
    moving: list[str] = field(default_factory=list)
    cells: list[str] = field(default_factory=list)
    temp: bool = False  # 锚点是确认过的网络估算顶上的临时锚点，第一个好的卫星点替换


class WifiBook:
    """锚 = 好的卫星点时连着的 Wi-Fi + 那个点的位置；一眼认得出的热点不当锚；跟着人走过的只当提示"""

    MOVE_METERS = 200.0
    HERE_METERS = 100.0
    GOOD_ACCURACY = 50.0
    SETTLE_METERS = 50.0
    MAX_MOVING = 64
    MAX_CELLS = 8
    IPHONE_HOTSPOT_PREFIX = "172.20.10."
    HOTSPOT_NAMES = re.compile(r"iphone|ipad|的手机|手机热点|androidap|galaxy|mifi|随身|ufi", re.IGNORECASE)

    def __init__(self, memory: WifiMemory | None = None) -> None:
        m = memory or WifiMemory()
        self.anchor = m.anchor
        at = m.anchor_at
        # 存的精度丢了当好的卫星点（和 Kotlin 一样）
        self.anchor_at = replace(at, acc=self.GOOD_ACCURACY) if at is not None and at.acc is None else at
        self.moving = list(m.moving)
        self.cells = list(m.cells)
        # 临时锚点（temp_anchor）：第一个好的卫星点就替换；量出离开了也不把这个 Wi-Fi 记成跟着人走
        self.temporary = m.temp and self.anchor_at is not None

    @property
    def memory(self) -> WifiMemory:
        return WifiMemory(self.anchor, self.anchor_at, list(self.moving), list(self.cells), self.temporary)

    @staticmethod
    def looks_like_hotspot(s: Snap) -> bool:
        return s.net == "wifi" and (
            s.wifi_metered is True
            or s.wifi_gateway.startswith(WifiBook.IPHONE_HOTSPOT_PREFIX)
            or WifiBook.HOTSPOT_NAMES.search(s.wifi_ssid) is not None
        )

    @staticmethod
    def locally_administered(bssid: str) -> bool:
        try:
            return int(bssid[:2], 16) & 0x02 != 0
        except ValueError:
            return False

    def kind(self, s: Snap) -> str:
        if s.net != "wifi":
            return NO_WIFI
        if self.looks_like_hotspot(s):
            return HOTSPOT
        if s.wifi_bssid and s.wifi_bssid == self.anchor and self.anchor_at is not None:
            return ANCHOR
        return OTHER

    def anchor_bssid(self, s: Snap) -> str | None:
        return s.wifi_bssid if self.kind(s) == ANCHOR else None

    def hinted(self, bssid: str) -> bool:
        return bssid in self.moving or self.locally_administered(bssid)

    def followed(self, s: Snap) -> bool:
        """连着的 Wi-Fi 跟着人走过：高德按它的 MAC 查出来的是它原来所在的地方"""
        return s.net == "wifi" and bool(s.wifi_bssid) and s.wifi_bssid in self.moving

    def unfamiliar_cell(self, s: Snap) -> bool:
        cell = s.cell_key()
        return cell is not None and self.kind(s) == ANCHOR and bool(self.cells) and cell not in self.cells

    def _from_anchor(self, fix: Fix, frm: Fix | None) -> float | None:
        """离锚点 frm（不给就是现在的锚点）多远，减掉两个点的精度"""
        at = frm if frm is not None else self.anchor_at
        if at is None:
            return None
        return distance_m(at.lat, at.lon, fix.lat, fix.lon) - at.acc - fix.acc

    def beyond(self, fix: Fix, frm: Fix | None = None) -> bool:
        d = self._from_anchor(fix, frm)
        return d is not None and d > self.MOVE_METERS

    def near(self, fix: Fix, frm: Fix | None = None) -> bool:
        d = self._from_anchor(fix, frm)
        return d is not None and d <= self.HERE_METERS

    def observe(self, s: Snap, motion: str, gps: Fix | None, on_the_road: bool = False) -> bool:
        """返回 Wi-Fi 记忆变了没有"""
        bssid = s.wifi_bssid if s.net == "wifi" and s.wifi_bssid and not self.looks_like_hotspot(s) else None
        if gps is None or gps.lat is None or gps.lon is None or gps.acc is None or gps.acc > self.GOOD_ACCURACY:
            return False
        moving = motion in MOVING_MOTIONS
        if bssid is None:
            return self.anchor is not None and self.release(followed=False)
        if bssid == self.anchor and moving and self.beyond(gps):
            return self.release(followed=True)
        if bssid == self.anchor and self.anchor_at is not None and moving and not on_the_road:
            return False
        return self._set_anchor(bssid, gps, s)

    def release(self, followed: bool) -> bool:
        bssid = self.anchor
        if bssid is None:
            return False
        # 临时锚点本身就可能差几百米：量出「离开了」不说明 Wi-Fi 跟着人走
        if followed and not self.temporary and bssid not in self.moving:
            self.moving.append(bssid)
            if len(self.moving) > self.MAX_MOVING:
                self.moving.pop(0)
        self.anchor = None
        self.anchor_at = None
        self.temporary = False
        self.cells = []
        return True

    def temp_anchor(self, s: Snap, at: Fix) -> bool:
        """连着的 Wi-Fi 还没有锚（不是热点、没跟着人走过）、屋里卫星点一直没有：确认过的网络估算当临时锚点"""
        if self.kind(s) != OTHER or not s.wifi_bssid or self.followed(s):
            return False
        self.anchor = s.wifi_bssid
        self.anchor_at = at
        self.temporary = True
        cell = s.cell_key()
        self.cells = [cell] if cell is not None else []
        return True

    def learn_cell(self, s: Snap) -> bool:
        cell = s.cell_key()
        if cell is None or self.kind(s) != ANCHOR or cell in self.cells:
            return False
        self.cells.append(cell)
        if len(self.cells) > self.MAX_CELLS:
            self.cells.pop(0)
        return True

    def _set_anchor(self, bssid: str, fix: Fix, s: Snap) -> bool:
        at = self.anchor_at
        cell_changed = self.learn_cell(s) if bssid == self.anchor else False
        if (
            bssid == self.anchor
            and at is not None
            and not self.temporary
            and distance_m(at.lat, at.lon, fix.lat, fix.lon) <= self.SETTLE_METERS
        ):
            return cell_changed
        if bssid != self.anchor:
            cell = s.cell_key()
            self.cells = [cell] if cell is not None else []
        self.anchor = bssid
        self.anchor_at = fix
        self.temporary = False
        return True


def usable_fix(provider: str | None, kind: str, followed: bool = False) -> bool:
    return provider != "wifi" or (kind != HOTSPOT and not followed)


def net_label(net: str | None, kind: str) -> str:
    if net is None or net == "none":
        return "none"
    if net == "wifi":
        return "hotspot" if kind == HOTSPOT else "wifi"
    return "cellular"


# ---- policy/AnchorProbe.kt：连着锚 Wi-Fi 时什么时候开卫星量离锚点多远 ----


class AnchorProbe:
    SNAPSHOT_MILLIS = 2_000
    WINDOW = 180_000
    NEED = 60_000
    NEED_HINTED = 20_000
    LIMIT = 90_000
    WALK_LIMIT = 180_000
    RETRY = 300_000
    COOLDOWN = 600_000
    MAX_COOLDOWN_STEPS = 3
    NET_FAR_METERS = 1_000.0

    def __init__(self) -> None:
        self.since: int | None = None
        self.target: str | None = None
        self.blocked_until = MIN_LONG
        self.retry_at = MIN_LONG  # 离开信号（start 的 force）最早什么时候能再量
        self.blocked_for: str | None = None  # 冷却、重试挡的是哪个锚（换了锚照样能量）
        # 一边走一边量时这次探测的第一个点、收到它的时刻、最近一个点明显在锚这里没有
        self.first: Fix | None = None
        self.first_at = 0
        self.last_near = False
        self.streak = 0
        self.streak_wifi: str | None = None
        self.streak_link: int | None = None
        self.origin: Fix | None = None  # 这次探测拿来比的锚点：开始量时的那个，量的过程中不跟着挪
        self.moving: list[int] = []

    @property
    def active(self) -> bool:
        return self.since is not None

    def on_snapshot(
        self, now: int, wifi: str | None, link: int | None, anchor: str | None, at: Fix | None, raw: str, hinted: bool
    ) -> bool:
        # 换了 Wi-Fi，或者同一个 Wi-Fi 断开又连上（代次变了）：以前的冷却不算了
        if wifi != self.streak_wifi or link != self.streak_link:
            self.streak_wifi = wifi
            self.streak_link = link
            self.forget()
        if anchor is None:
            self.moving = []
            return False
        if raw in MOVING_MOTIONS:
            self.moving.append(now)
        while self.moving and self.moving[0] <= now - self.WINDOW:
            self.moving.pop(0)
        need = self.NEED_HINTED if hinted else self.NEED
        return len(self.moving) * self.SNAPSHOT_MILLIS >= need and self.start(now, anchor, at)

    def start(self, now: int, anchor: str, at: Fix | None, force: bool = False) -> bool:
        """拿 at（锚现在的锚点）当这次比的地方；force（网络估算太远、陌生基站）不看冷却，只看离这个锚上次量完过没过 RETRY"""
        if self.since is not None:
            return False
        if anchor == self.blocked_for and now < (self.retry_at if force else self.blocked_until):
            return False
        self.since = now
        self.target = anchor
        self.origin = at
        self.moving = []
        return True

    def expire(self, now: int) -> bool:
        """到时限：一边走一边量、最后一个点明显在锚这里算还在这里（冷却）；别的不下结论，过 RETRY 再量（时间往回改过也算到时限）"""
        if self.since is None:
            return False
        limit = self.LIMIT if self.first is None else min(self.WALK_LIMIT, self.first_at - self.since + self.LIMIT)
        if 0 <= now - self.since < limit:
            return False
        if self.first is not None and self.last_near:
            self.finish(now, here=True)
            return True
        self.blocked_for = self.target
        self.stop()
        self.blocked_until = now + self.RETRY
        self.retry_at = self.blocked_until
        return True

    def walked(self, now: int, point: Fix, near: bool) -> bool:
        """一边走一边量到一个点：记下这次的第一个点，离它超过 HERE_METERS 加两点精度就是在离开"""
        self.last_near = near
        f = self.first
        if f is None:
            self.first = point
            self.first_at = now
            return False
        return distance_m(f.lat, f.lon, point.lat, point.lon) - f.acc - point.acc > WifiBook.HERE_METERS

    def finish(self, now: int, here: bool) -> None:
        measured = self.target
        self.stop()
        if not here:
            self.forget()
            return
        self.blocked_for = measured
        self.streak = min(self.streak + 1, self.MAX_COOLDOWN_STEPS)
        self.blocked_until = now + self.COOLDOWN * self.streak
        self.retry_at = now + self.RETRY

    def stop(self) -> None:
        self.since = None
        self.target = None
        self.origin = None
        self.first = None
        self.last_near = False

    def forget(self) -> None:
        """冷却、重试不算了：断开或换了 Wi-Fi、锚被清、锚点挪了地方"""
        self.blocked_for = None
        self.blocked_until = MIN_LONG
        self.retry_at = MIN_LONG
        self.streak = 0

    def shift(self, delta: int) -> None:
        if self.since is not None:
            self.since += delta
        self.first_at += delta
        if self.blocked_until != MIN_LONG:
            self.blocked_until += delta
        if self.retry_at != MIN_LONG:
            self.retry_at += delta
        self.moving = [t + delta for t in self.moving]


# ---- policy/NetFallback.kt：屋里定不到卫星时拿网络估算当主坐标 ----


@dataclass(frozen=True)
class MainFix:
    """上一个报给服务器的主坐标（Kotlin 的 MainFix）：gps 是卫星点，at 是报出的时刻"""

    lat: float
    lon: float
    acc: float
    gps: bool
    at: int


@dataclass
class _Target:
    """要顶的主坐标 main，from_ 起值得为它催取；anchor 是顶锚点时的那个锚点（Kotlin NetFallback.Target）"""

    main: Fix
    from_: int
    anchor: Fix | None = None


class NetFallback:
    """屋里定不到卫星时拿别的位置顶上主坐标。可信：有坐标、误差 ≤ 200 米。顶什么：连着有卫星锚点的 Wi-Fi 时，估算和锚点对得上
    （离锚点不超过 WifiBook.HERE_METERS 加两边精度）就顶锚点本身（来源 anchor），一个估算就够；对不上就挡掉、不催取。别的时候连续两个
    估算一致（彼此在误差内、隔 ≥ 60 秒）顶估算。什么时候顶：卫星点 10 分钟没报过（或从没有过主坐标），要顶的位置离上一个主坐标超过
    300 米 + 它的精度（或没有主坐标）。要不要估算（wants）：卫星点 10 分钟没报过，估算还没定下来或定下来已经过了 15 分钟，
    静止时呼吸顺带取一次网络定位。最近 10 分钟内可信的估算每个上报点都带上"""

    NO_GPS = 600_000
    TRUSTED_ACCURACY = 200.0
    FAR_METERS = 300.0
    CONFIRM_GAP = 60_000
    RECENT = 600_000
    SETTLED_EVERY = 900_000
    MAX_ASKS = 3
    SAVE_EVERY = 300_000
    SAVE_METERS = 50.0

    NONE, CONFIRMED, PROMOTED = "none", "confirmed", "promoted"

    def __init__(self, main: MainFix | None = None) -> None:
        self.main = main
        self.changed = False
        self.saved = main
        self.gps_at = main.at if main is not None and main.gps else None
        self.candidate: _Seen | None = None  # 等第二次确认的估算（没连着卫星锚点时）
        self.latest: _Seen | None = None
        self.target: _Target | None = None  # 现在要顶的位置；被卫星锚点挡掉的估算没有
        self.asked_for: _Target | None = None
        self.asks = 0
        self.pending: Fix | None = None
        self.settled_at: int | None = None  # 估算上一次定下来（确认了能顶、或和主坐标对得上）的时刻

    def trusted(self, fix: Fix) -> bool:
        return (
            fix.provider != "gps"
            and fix.lat is not None
            and fix.lon is not None
            and (fix.acc if fix.acc is not None else math.inf) <= self.TRUSTED_ACCURACY
        )

    def estimate(self, now: int, fix: Fix, anchor: Fix | None) -> str:
        """anchor：连着有卫星锚点的 Wi-Fi 时是那个锚点（临时锚点不算），别的时候 None"""
        self.latest = _Seen(fix, now)
        t = self._by_anchor(now, fix, anchor) if anchor is not None else self._by_estimates(now, fix)
        if t is None:
            return self.NONE
        self.target = t
        if not self._stale(now):
            return self.NONE
        self.settled_at = now
        if not self._far(t.main):
            return self.CONFIRMED
        self.pending = t.main
        return self.PROMOTED

    def _by_anchor(self, now: int, fix: Fix, anchor: Fix) -> _Target | None:
        self.candidate = None
        self.asks = 0
        if not self._near_anchor(fix, anchor):
            self.target = None
            self.settled_at = None
            return None
        if self.target is not None and self.target.anchor is anchor:
            return self.target
        return _Target(Fix("anchor", anchor.time, anchor.lat, anchor.lon, anchor.acc), now, anchor)

    def _by_estimates(self, now: int, fix: Fix) -> _Target | None:
        c = self.candidate
        if c is None or not self._agree(c.fix, fix):
            self.candidate = _Seen(fix, now)
            self.settled_at = None
            self.asks = self.asks + 1 if self._far(fix) else 0
            self.target = _Target(fix, now + self.CONFIRM_GAP)
            return None
        if now - c.at < self.CONFIRM_GAP:
            return None
        self.candidate = _Seen(fix, now)
        self.asks = 0
        return _Target(fix, now + self.CONFIRM_GAP)

    def due(self, now: int) -> bool:
        t = self.target
        if t is None or t is self.asked_for or self.asks > self.MAX_ASKS or not self._far(t.main):
            return False
        return now >= t.from_ and self._stale(now)

    def wants(self, now: int) -> bool:
        """静止时要不要顺带取一次网络定位：主坐标过时，估算还没定下来或定下来已经过了 SETTLED_EVERY"""
        if not self._stale(now):
            return False
        return self.settled_at is None or now - self.settled_at >= self.SETTLED_EVERY

    def asked(self) -> None:
        self.asked_for = self.target

    def take_pending(self) -> Fix | None:
        p, self.pending = self.pending, None
        return p

    def recent(self, now: int) -> Fix | None:
        e = self.latest
        return e.fix if e is not None and 0 <= now - e.at <= self.RECENT else None

    def reported(self, now: int, main: Fix | None, gps: bool) -> None:
        """报出去了一条：main 是这条的主坐标（没有是 None），gps 是不是卫星点"""
        if main is None or main.lat is None or main.lon is None:
            return
        if gps:
            self.gps_at = now
            self.pending = None
            self.asks = 0
            self.settled_at = None
        nxt = MainFix(main.lat, main.lon, main.acc if main.acc is not None else 0.0, gps, now)
        self.main = nxt
        sv = self.saved
        if (
            sv is None
            or sv.gps != gps
            or now - sv.at >= self.SAVE_EVERY
            or distance_m(sv.lat, sv.lon, main.lat, main.lon) > self.SAVE_METERS
        ):
            self.saved = nxt
            self.changed = True

    def shift(self, delta: int) -> None:
        if self.gps_at is not None:
            self.gps_at += delta
        for seen in (self.candidate, self.latest):
            if seen is not None:
                seen.at += delta
        if self.target is not None:
            self.target.from_ += delta
        if self.settled_at is not None:
            self.settled_at += delta
        self.main = self.main and replace(self.main, at=self.main.at + delta)
        self.saved = self.saved and replace(self.saved, at=self.saved.at + delta)

    def _stale(self, now: int) -> bool:
        return self.gps_at is None or now - self.gps_at > self.NO_GPS

    def _far(self, fix: Fix) -> bool:
        m = self.main
        if m is None:
            return True
        if fix.lat is None or fix.lon is None:
            return False
        return distance_m(m.lat, m.lon, fix.lat, fix.lon) > self.FAR_METERS + (fix.acc or 0.0)

    @staticmethod
    def _near_anchor(fix: Fix, anchor: Fix) -> bool:
        """估算和卫星锚点对得上：距离减掉两边的精度不超过 WifiBook.HERE_METERS"""
        if fix.lat is None or fix.lon is None:
            return False
        d = distance_m(anchor.lat, anchor.lon, fix.lat, fix.lon)
        return d - (anchor.acc or 0.0) - (fix.acc or 0.0) <= WifiBook.HERE_METERS

    @staticmethod
    def _agree(a: Fix, b: Fix) -> bool:
        if a.lat is None or a.lon is None or b.lat is None or b.lon is None:
            return False
        return distance_m(a.lat, a.lon, b.lat, b.lon) <= max(a.acc or 0.0, b.acc or 0.0)


# ---- policy/Judge.kt：合起来 ----


def _newer(a: Fix, b: Fix | None) -> bool:
    """a 比 b 新：两边都有开机时长就比它，不然比定位时间"""
    if b is None:
        return True
    if a.elapsed is not None and b.elapsed is not None:
        return a.elapsed > b.elapsed
    return a.time > b.time


@dataclass
class Report:
    """要报的一条，字段和 Kotlin 的 Position 上判断层那几样一样：[fix] 是主坐标（卫星点，或 [estimate] 时确认过的网络估算，
    Kotlin 那边来源写 network），网络定位放 [net_fix]（netLat 等，最近 10 分钟内可信的估算每条都带）；[fix_time] 是策略给的那个点的定位时间"""

    time: int
    fix: Fix | None
    net_fix: Fix | None
    reason: str
    state: str
    motion: str
    steps: int | None
    bumps: int
    net: str
    wifi_same: bool | None
    estimate: bool = False
    fix_time: int | None = None


class Judge:
    SNAPSHOT_SECONDS = 2
    SENSORS_STALE_MILLIS = 180_000

    def __init__(
        self, memory: WifiMemory | None = None, restored: str | None = None, main: MainFix | None = None
    ) -> None:
        self.classifier = MotionClassifier()
        self.policy = ReportPolicy()
        self.wifi = WifiBook(memory)
        self.probe = AnchorProbe()
        self.fallback = NetFallback(main)  # 屋里一直没卫星时拿网络估算当主坐标；main 是存下的上一个主坐标
        # 重启前是静止：接着静止（只接这一种），第一次呼吸就报
        if restored == STILL:
            self.classifier.seed_still()
            self.policy.seed(STILL)
        self.last: Snap | None = None
        self.last_snapshot_at: int | None = None
        self.own_gps: Fix | None = None
        self.steps_base: int | None = None
        self.bump_seconds = 0
        self.measured_up_to = MIN_LONG  # 探测量过的最后一个卫星点的定位时间：快照、定位回调各来一次只量一次

    @property
    def motion(self) -> str | None:
        return self.policy.motion

    @property
    def gps(self) -> bool:
        return self.policy.gps or self.probe.active

    @property
    def sleeping(self) -> bool:
        """静止、飞行模式时传感器睡；正在量时不睡（探测的时限靠快照来查）"""
        return self.policy.motion in (STILL, OFF) and not self.probe.active

    def kind(self) -> str:
        return self.wifi.kind(self.last) if self.last else NO_WIFI

    def followed(self) -> bool:
        return self.wifi.followed(self.last) if self.last else False

    def on_snapshot(self, now: int, raw: Snap) -> Decision:
        own = self.own_gps
        s = replace(raw, fix=own) if own and _newer(own, raw.fix) else raw
        self.last = s
        self.last_snapshot_at = now
        self._expire(now, s)
        # 探测中快照先带来了卫星点：先和探测开始时的锚点比完，再让 Wi-Fi 记忆按它挪锚（审计 11.0.11 第 2 条）
        g = s.fresh_gps(MotionClassifier.GPS_FRESH_MILLIS)
        early = (
            self._measured(now, g)
            if g and self.probe.active and self.policy.state != MOVING and Sky.main_coordinate(g.acc)
            else None
        )
        anchor = None if self.policy.state == MOVING else self.wifi.anchor_bssid(s)
        motion = self.classifier.update(s, anchor)
        if s.steps_total is not None and (self.steps_base is None or s.steps_total < self.steps_base):
            self.steps_base = s.steps_total
        if (s.acc_std or 0.0) >= MotionClassifier.ACTIVE_ACC:
            self.bump_seconds += self.SNAPSHOT_SECONDS
        gps = s.fresh_gps(MotionClassifier.GPS_FRESH_MILLIS)
        anchor_at = self.wifi.anchor_at
        self.wifi.observe(s, self.classifier.raw, gps, on_the_road=self.policy.state == MOVING)
        # 锚被清、锚点挪了地方：以前的冷却和重试不再挡
        if self.wifi.anchor_at is not anchor_at:
            self.probe.forget()
        changed = self.policy.on_motion(now, motion)
        self._watch_anchor(now, s)
        nxt = changed if changed.report or changed.fetch else self.policy.on_tick(now, self.fallback.wants(now))
        # 探测量出来要报的点优先
        out = nxt if early is None or early.report is None else Decision(early.report, nxt.fetch)
        return self._confirming(now, out)

    def _confirming(self, now: int, d: Decision) -> Decision:
        """等确认的网络估算到点了、这一步又没在取：马上取一次网络定位"""
        if d.fetch or not self.fallback.due(now):
            return d
        if not self.policy.confirm(now).fetch:
            return d
        self.fallback.asked()
        return Decision(d.report, True)

    def _estimated(self, now: int, fix: Fix | None, d: Decision) -> Decision:
        """能用的网络估算交给 NetFallback，连着有卫星锚点的 Wi-Fi 时连同锚点（顶估算还是锚点它来判断）：确认了就给还没有锚的 Wi-Fi
        当临时锚点；要顶主坐标而策略没别的要报就单独报一条"""
        if fix is None or not self.fallback.trusted(fix):
            return d
        anchor = self.wifi.anchor_at if self.kind() == ANCHOR and not self.wifi.temporary else None
        verdict = self.fallback.estimate(now, fix, anchor)
        if verdict == NetFallback.NONE:
            return d
        s = self.last
        acc = fix.acc if fix.acc is not None else NetFallback.TRUSTED_ACCURACY
        if s is not None and self.wifi.temp_anchor(s, replace(fix, acc=acc)):
            self.probe.forget()
        if verdict != NetFallback.PROMOTED or d.report is not None:
            return d
        return Decision(self.policy.located(now, fix), d.fetch)

    def _watch_anchor(self, now: int, s: Snap) -> None:
        state = self.policy.state
        anchor = None if state == MOVING or state is None else self.wifi.anchor_bssid(s)
        if self.probe.active and self.probe.target != anchor:
            self.probe.stop()
        wifi = s.wifi_bssid if s.net == "wifi" else None
        at = self.wifi.anchor_at
        hinted = anchor is not None and self.wifi.hinted(anchor)
        self.probe.on_snapshot(now, wifi, s.net_link, anchor, at, self.classifier.raw, hinted)
        if anchor is not None and self.wifi.unfamiliar_cell(s):
            self.probe.start(now, anchor, at, force=True)

    def _expire(self, now: int, s: Snap | None) -> None:
        if self.probe.expire(now) and s is not None:
            self.wifi.learn_cell(s)

    def _admits(self, fix: Fix) -> bool:
        """卫星点要能当主坐标，Wi-Fi 定位要不是热点、跟着人走过的 Wi-Fi 下的"""
        if fix.provider == "gps":
            return Sky.main_coordinate(fix.acc)
        return usable_fix(fix.provider, self.kind(), self.followed())

    def on_fix(self, now: int, fix: Fix, elapsed: int | None = None) -> Decision | None:
        """连续定位来的点；返回 None 表示丢掉（热点下的 Wi-Fi 定位、头顶被挡的卫星点）。elapsed 是收到它时的开机时长"""
        stopped = self._sensors_stopped(now)
        if stopped is not None:
            return stopped
        if not self._admits(fix):
            return None
        self._expire(now, self.last)
        if fix.provider != "gps":
            return self._estimated(now, fix, self.policy.on_fix(now, fix))
        self.own_gps = replace(fix, elapsed=elapsed)
        if not self.probe.active or self.policy.state == MOVING:
            return self.policy.on_fix(now, fix)
        return self._measured(now, fix)

    def _measured(self, now: int, fix: Fix) -> Decision:
        if fix.lat is None or fix.lon is None or fix.acc is None:
            return Decision()
        if fix.time <= self.measured_up_to:
            return Decision()
        self.measured_up_to = fix.time
        # 和探测开始时的锚点比，不和量的过程中挪过的比
        origin = self.probe.origin if self.probe.origin is not None else self.wifi.anchor_at
        # 单点速度只认开阔天空下的
        fast = Sky.of(fix.acc) == Sky.OPEN and (fix.speed or 0.0) > MotionClassifier.VEHICLE_SPEED
        near = fix.acc <= WifiBook.GOOD_ACCURACY and self.wifi.near(fix, origin)
        walking = self.classifier.raw in MOVING_MOTIONS
        leaving = walking and self.probe.walked(now, fix, near)
        if fast or leaving or self.wifi.beyond(fix, origin):
            self.probe.finish(now, here=False)
            self.wifi.release(followed=True)
            motion = VEHICLE if fast else self.classifier.raw
            if motion not in MOVING_MOTIONS:
                return self.policy.probed(now, fix)
            self.policy.on_fix(now, fix)
            return self.policy.on_motion(now, motion)
        if near and not walking:
            self.probe.finish(now, here=True)
            if self.last is not None:
                self.wifi.learn_cell(self.last)
        return Decision()

    def on_fetched(self, now: int, fix: Fix | None) -> Decision:
        self._expire(now, self.last)
        usable = fix if fix and usable_fix(fix.provider, self.kind(), self.followed()) else None
        anchor = self.wifi.anchor_bssid(self.last) if self.last else None
        at = self.wifi.anchor_at
        if (
            usable is not None
            and anchor is not None
            and at is not None
            and self.policy.state != MOVING
            and usable.lat is not None
            and usable.lon is not None
            and distance_m(at.lat, at.lon, usable.lat, usable.lon) > AnchorProbe.NET_FAR_METERS + (usable.acc or 0.0)
        ):
            self.probe.start(now, anchor, at, force=True)
        return self._estimated(now, usable, self.policy.on_fetched(now, usable))

    def clock_shifted(self, delta: int) -> None:
        """系统时间被改了 delta 毫秒：记的时间跟着挪"""
        if self.last_snapshot_at is not None:
            self.last_snapshot_at += delta
        self.measured_up_to = MIN_LONG  # 有的定位源的卫星点时间跟着系统时间走
        self.policy.shift(delta)
        self.probe.shift(delta)
        self.fallback.shift(delta)

    def on_tick(self, now: int) -> Decision:
        """心跳闹钟；一份快照都没有时按「在附近活动」接着报"""
        self._expire(now, self.last)
        if self.last is None and self.policy.motion is None:
            return self.policy.on_motion(now, FIDGET)
        stopped = self._sensors_stopped(now)
        if stopped is not None:
            return stopped
        return self._confirming(now, self.policy.on_tick(now, self.fallback.wants(now)))

    def _sensors_stopped(self, now: int) -> Decision | None:
        """走路、坐车途中 SENSORS_STALE_MILLIS 没快照：降成在附近活动"""
        at = self.last_snapshot_at
        if at is None or self.policy.motion not in MOVING_MOTIONS or now - at <= self.SENSORS_STALE_MILLIS:
            return None
        return self.policy.on_motion(now, FIDGET)

    def describe(self, out: Outgoing) -> Report:
        s = self.last
        steps = None
        if s and s.steps_total is not None and self.steps_base is not None:
            steps = s.steps_total - self.steps_base
        if s and s.steps_total is not None:
            self.steps_base = s.steps_total
        bumps = self.bump_seconds
        self.bump_seconds = 0
        gps = out.fix if out.fix and out.fix.provider == "gps" else None
        net_fix = out.fix if out.fix and gps is None else None
        # 屋里一直没卫星时 NetFallback 要顶的位置（确认过的估算或卫星锚点）放进这条的主坐标（没有卫星点时）
        estimate = self.fallback.take_pending() if gps is None or gps.lat is None else None
        main = estimate or gps
        if net_fix is None:
            net_fix = self.fallback.recent(out.time)
        kind = self.kind()
        self.fallback.reported(out.time, main, estimate is None and gps is not None)
        return Report(
            time=out.time,
            fix=main,
            net_fix=net_fix,
            reason=out.reason,
            state=self.policy.state or "",
            motion=self.policy.motion or "",
            steps=steps,
            bumps=bumps,
            net=net_label(s.net if s else None, kind),
            wifi_same=kind == ANCHOR if kind in (ANCHOR, OTHER) else None,
            estimate=estimate is not None,
            fix_time=out.fix.time if out.fix else None,
        )
