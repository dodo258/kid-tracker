"""手机：按真实轨迹和干扰算出「手机会报的点」。

上报端行为就是上报端 v2 的判断层（reporter.py，app-v2/sdk 的 motion/、policy/ 的 Python 版，金样本对齐）：传感器时间线
（sensors.py）每 2 秒一份喂进去，它判断走路 / 坐车 / 屋里走动 / 静止 / 飞行模式，说开卫星就每秒把这一秒拿到的点给它，说取网络定位
就给一个网络定位点；它说报什么就报什么。每个点带 state、motion、provider、精度、运动摘要（steps 步数、bumps 晃动秒数）、
wifiType（连的是固定 Wi-Fi 还是热点；不能叫 wifi：那是 OsmAnd 协议的保留参数，格式 mac,rssi，Traccar 解析不了会丢掉整条）
和 net（上报那一刻的联网方式：wifi、hotspot、cellular、none；方案 2026-11-18-parent-web-info-sheet 第 3 节，wifiType 先留着兼容）。
不在这里丢点、去坐标：信不信这个点是服务器和网页的事（只有判断层自己的规矩：连着热点时不用高德的 Wi-Fi 定位）。
"""

import dataclasses
import math
import random
from dataclasses import dataclass, field

import geo
import reporter
import sensors
from scenario import Scenario
from sensors import in_window

MOVING_EVERY = reporter.MOVING_EVERY // 1000  # 在动时上报间隔（秒），回放检查用
COLD_FIX_EVERY = 15  # 冷启动时基站定位多久一个
LATENCY = 0.3  # 上报到服务器的网络延迟（秒）
BURST_GAP = 0.15  # 补传时一条接一条的间隔（秒）
FIX_FRESH = 5  # 状态变化那一刻，多旧以内的定位算当前的

ACC_RANGE = {"gps": (4.0, 10.0), "wifi": (25.0, 60.0), "cell": (300.0, 1200.0)}
ERR_TAU = {"gps": 20.0, "net": 120.0}  # 定位误差慢慢变（一阶自回归），时间常数（秒）


@dataclass
class Fix:
    t: int
    lat: float
    lon: float
    acc: float
    provider: str
    speed: float | None
    bearing: float | None
    tags: list[str] = field(default_factory=list)


@dataclass
class Report:
    seq: int
    t: float  # 手机发出这条的真实时刻（场景秒）
    arrive: float  # 服务器收到的真实时刻（场景秒）
    timestamp_ms: int  # 手机写的时间戳（含时钟偏差）
    state: str
    fix: Fix | None
    batt: float
    charge: bool
    steps: int | None
    bumps: int
    wifi: str
    net: str = "cellular"
    motion: str = ""
    tags: list[str] = field(default_factory=list)
    net_fix: Fix | None = None  # 网络定位的估算（netLat 等附加字段）；主坐标只放卫星点
    wifi_same: bool | None = None

    def params(self, device_id: str) -> dict[str, str]:
        """OsmAnd 协议的表单参数，和 sdk/core 的 HttpUploader 一致（速度按节）"""
        p = {"id": device_id}
        if self.fix:
            p["lat"] = f"{self.fix.lat:.7f}"
            p["lon"] = f"{self.fix.lon:.7f}"
        p["timestamp"] = str(self.timestamp_ms // 1000)
        if self.fix:
            p["accuracy"] = f"{self.fix.acc:.1f}"
            if self.fix.speed is not None:
                p["speed"] = str(round(self.fix.speed * 1.94384 * 100) / 100)
            if self.fix.bearing is not None:
                p["bearing"] = f"{self.fix.bearing:.1f}"
        p["batt"] = f"{self.batt:.1f}"
        p["charge"] = "true" if self.charge else "false"
        if self.fix:
            p["provider"] = self.fix.provider
        p["state"] = self.state
        if self.motion:
            p["motion"] = self.motion
        if self.steps is not None:
            p["steps"] = str(self.steps)
        p["bumps"] = str(self.bumps)
        p["wifiType"] = self.wifi
        p["net"] = self.net
        if self.net_fix:
            p["netLat"] = f"{self.net_fix.lat:.7f}"
            p["netLon"] = f"{self.net_fix.lon:.7f}"
            p["netAcc"] = f"{self.net_fix.acc:.1f}"
            p["netSource"] = self.net_fix.provider
        if self.wifi_same is not None:
            p["wifiSame"] = "true" if self.wifi_same else "false"
        return p


class Phone:
    def __init__(self, sc: Scenario):
        self.sc = sc
        self.rng = random.Random(sc.seed * 7919 + 1)
        n = sc.duration
        self.err = {k: self._ar_series(n, tau) for k, tau in ERR_TAU.items()}
        self.seg_acc = [{k: self.rng.uniform(*seg.acc.get(k, ACC_RANGE[k])) for k in ACC_RANGE} for seg in sc.segments]
        self.jumps = [dict(e, left=int(e.get("points", 1))) for e in sc.events_of("side_jump")]
        self.wrong_at: dict[int, tuple[float, float]] = {}
        self.battery = self._battery_by_minute()

    def _ar_series(self, n: int, tau: float) -> list[tuple[float, float]]:
        a = math.exp(-1 / tau)
        b = math.sqrt(1 - a * a)
        x = y = 0.0
        out = []
        for _ in range(n):
            x = a * x + b * self.rng.gauss(0, 1)
            y = a * y + b * self.rng.gauss(0, 1)
            out.append((x, y))
        return out

    def _down(self, t: float) -> bool:
        return in_window(self.sc.events_of("killed") + self.sc.events_of("airplane"), t) is not None

    def source(self, t: int) -> str | None:
        """t 这一秒手机能拿到哪种定位：gps / wifi / cell / None"""
        sc = self.sc
        if self._down(t) or in_window(sc.events_of("no_fix"), t):
            return None
        cold = in_window(sc.events_of("cold_start"), t)
        if cold:
            return "cell" if (t - cold["from"]) % COLD_FIX_EVERY == COLD_FIX_EVERY - 1 else None
        if in_window(sc.events_of("misjudge_moving"), t):
            return "wifi"
        src = sc.segments[sc.at(t).seg].fix
        return None if src == "none" else src

    def fix(self, t: int, network: bool = False, satellite: bool = False) -> Fix | None:
        """t 这一秒手机拿到的定位（真实位置 + 慢慢变的误差 + 干扰）；[network] 只要网络定位（不开卫星时取的点，室外按 Wi-Fi 定位算），
        [satellite] 要一个卫星点（场景开始前最后一次卫星定位）"""
        src = self.source(t) if t < self.sc.duration else None
        if src is None:
            return None
        if network and src == "gps":
            src = "wifi"
        if satellite:
            src = "gps"
        s = self.sc.at(t)
        # 精度每秒小幅变化；按 t 定下来，同一秒怎么问都一样（传感器时间线和上报用的是同一个点）
        acc = self.seg_acc[s.seg][src] * random.Random(self.sc.seed * 1_000_003 + t).uniform(0.85, 1.15)
        kind = "gps" if src == "gps" else "net"
        ex, ey = self.err[kind][t]
        sigma = acc / 2
        lat, lon = geo.offset(s.lat, s.lon, ex * sigma, ey * sigma)
        tags: list[str] = []
        if src != "gps":
            drift = in_window(self.sc.events_of("drift"), t)
            if drift:
                frac = (t - drift["from"]) / max(1, drift["to"] - drift["from"])
                lat, lon = geo.move(lat, lon, drift.get("bearing", 45), drift["meters"] * frac)
                tags.append("drift")
            wrong = in_window(self.sc.events_of("wifi_wrong"), t)
            if wrong:
                key = wrong["from"]
                if key not in self.wrong_at:
                    w = self.sc.at(wrong["from"])
                    self.wrong_at[key] = geo.offset(w.lat, w.lon, wrong.get("east", 0), wrong.get("north", 0))
                lat, lon = geo.offset(*self.wrong_at[key], ex * sigma, ey * sigma)
                tags.append("wrong")
        if in_window(self.sc.events_of("cold_start"), t):
            tags.append("cold")
        speed = s.mps if src == "gps" else None
        bearing = s.bearing if src == "gps" and s.mps > 0 else None
        return Fix(t, lat, lon, acc, src, speed, bearing, tags)

    def _jump(self, f: Fix) -> Fix:
        """侧跳点：at 之后报出去的前几个卫星点往行进方向右侧偏"""
        if f.provider != "gps":
            return f
        for j in self.jumps:
            if j["at"] <= f.t and j["left"] > 0:
                j["left"] -= 1
                s = self.sc.at(f.t)
                meters = j.get("meters", 100) * self.rng.uniform(0.85, 1.15)
                lat, lon = geo.move(f.lat, f.lon, s.bearing + 90, meters)
                return Fix(f.t, lat, lon, f.acc, f.provider, f.speed, f.bearing, [*f.tags, "jump"])
        return f

    def _battery_by_minute(self) -> list[tuple[float, bool]]:
        """电量：从 80% 起，在路上每小时掉 4%，其他每小时 1%，充电每小时涨 20%"""
        level = 80.0
        out = []
        for i in range(0, self.sc.duration + 60, 60):
            s = self.sc.at(i)
            charging = self.sc.segments[s.seg].charging
            level = max(1.0, min(100.0, level + (20 / 60 if charging else -(4 / 60 if s.moving else 1 / 60))))
            out.append((level, charging))
        return out

    def batt(self, t: int) -> tuple[float, bool]:
        return self.battery[t // 60]

    def reports(self, log: list[str] | None = None) -> list[Report]:
        """按判断层算出手机会报的点。[log] 给了就把喂给判断层的输入和它的判断按金样本格式记进去（sim.py golden）"""
        sc = self.sc
        out: list[Report] = []
        timeline = dict(sensors.rows(sc, self))
        judge = reporter.Judge(restored=reporter.STILL if sc.restored else None)
        if sc.restored and log is not None:
            log.append(f"X;0;{sc.restored}")
        delays = [dict(e) for e in sc.events_of("breath_delay")]
        restarted = False
        was_down = False
        misjudged = False
        last_misjudge = -1e9
        fixes: dict[tuple[str, int], Fix] = {}
        fetching: list[int] = []

        def rfix(f: Fix) -> reporter.Fix:
            fixes[(f.provider, f.t)] = f
            return reporter.Fix(f.provider, f.t * 1000, f.lat, f.lon, f.acc, f.speed)

        def emit(
            t: int,
            fix: Fix | None,
            state: str,
            motion: str,
            steps: int | None,
            bumps: int,
            net: str,
            tags: list[str],
            net_fix: Fix | None = None,
            wifi_same: bool | None = None,
        ):
            nonlocal restarted
            if fix:
                fix = self._jump(fix)
            if restarted:
                tags = ["restart", *tags]
                restarted = False
            clock = in_window(sc.events_of("clock"), t)
            skew = int(clock["seconds"]) if clock else 0
            batt, charging = self.batt(t)
            seg = sc.segments[sc.at(t).seg]
            # 卫星点用定位时间；网络估算当主坐标时和 App 一样用报出的那一刻
            stamp_t = fix.t if fix and fix.provider == "gps" else t
            out.append(
                Report(
                    seq=len(out),
                    t=t,
                    arrive=t + LATENCY,
                    timestamp_ms=sc.start_ms + (stamp_t + skew) * 1000,
                    state=state,
                    fix=fix,
                    batt=batt,
                    charge=charging,
                    steps=steps,
                    bumps=bumps,
                    wifi=seg.wifi,
                    net=net,
                    motion=motion,
                    tags=tags + (fix.tags if fix else []) + (net_fix.tags if net_fix else []),
                    net_fix=net_fix,
                    wifi_same=wifi_same,
                )
            )

        def take(t: int, d: reporter.Decision | None, quiet: bool) -> None:
            if d is None:
                if log is not None:
                    log.append(f"d;{t * 1000}")
                return
            if d.report:
                r = judge.describe(d.report)
                if log is not None:
                    log.append(golden_report(r))
                if not quiet:
                    f = fixes.get((r.fix.provider, r.fix.time // 1000)) if r.fix else None
                    nf = fixes.get((r.net_fix.provider, r.net_fix.time // 1000)) if r.net_fix else None
                    tags = [] if r.reason in ("gps", "net", "zone") else [r.reason]
                    if r.estimate and r.fix and r.fix.provider == "anchor":
                        # 卫星锚点顶上主坐标：场景里没有这个点，按锚点造一个，来源 anchor
                        f = Fix(t, r.fix.lat, r.fix.lon, r.fix.acc, "anchor", None, None, [])
                    elif r.estimate and f:
                        # 确认过的网络估算当主坐标：App 报的来源是 network，具体是 Wi-Fi 还是基站在 netSource
                        f = dataclasses.replace(f, provider="network")
                    if r.estimate and f and "estimate" not in tags:
                        tags.append("estimate")
                    emit(t, f, r.state, r.motion, r.steps, r.bumps, r.net, tags, nf, r.wifi_same)
            if d.fetch:
                if log is not None:
                    log.append(f"f;{t * 1000}")
                fetching.append(t)

        def fetched(quiet: bool) -> None:
            # 取网络定位在引擎里是另起协程做的：结果在这一步的判断之后才回来
            while fetching:
                t = fetching.pop(0)
                f = self.fix(t, network=True)
                got = rfix(f) if f else None
                if log is not None:
                    log.append(golden_fix("R", t * 1000, got))
                take(t, judge.on_fetched(t * 1000, got), quiet)

        for t in range(sc.duration):
            if in_window(sc.events_of("killed"), t):
                was_down = True
                continue
            now = t * 1000
            if was_down:
                # 进程重启：判断层从头来，Wi-Fi 记忆和上一个主坐标存在状态库里接着用
                judge = reporter.Judge(judge.wifi.memory, main=judge.fallback.main)
                restarted = True
                was_down = False
                if log is not None:
                    log.append(f"X;{now}")
            seg = sc.segments[sc.at(t).seg]
            misjudge = in_window(sc.events_of("misjudge_moving"), t)
            quiet = misjudge is not None
            if misjudge:
                # 干扰：旧版上报端把屋里误判成移动中，每 interval 秒报一个网络定位点（这段判断层照跑，它的点不报）
                misjudged = True
                if t - last_misjudge >= int(misjudge.get("interval", MOVING_EVERY)):
                    f = self.fix(t)
                    if f:
                        # 人没在走：旧版误判的点运动摘要是 0 步、0 颠簸（家长页 domain/fixQuality.atRest 靠这个认出误判）
                        emit(
                            t,
                            f,
                            "moving",
                            "walk",
                            0,
                            0,
                            "hotspot" if seg.wifi == "hotspot" else "wifi" if seg.wifi == "fixed" else "cellular",
                            ["misjudge"],
                        )
                        last_misjudge = t
            elif misjudged:
                # 误判结束：按判断层现在的状态报一条，带一个刚取的网络定位当坐标（注入的是旧版上报端，旧版这样纠正回来）
                misjudged = False
                r = judge.describe(reporter.Outgoing(now, None, "state"))
                emit(t, self.fix(t, network=True), r.state, r.motion, r.steps, r.bumps, r.net, ["state", "misjudge"])
            if t == 0 and not sc.fresh and (f := self.fix(0, satellite=True)):
                # 场景开始时手机早就在跑：手里有刚才最后一次卫星定位的点，第一条带上它（服务器上有上一个卫星位置可沿用）；
                # 全新装（fresh）没有
                g = rfix(f)
                if log is not None:
                    log.append(golden_fix("F", now, g))
                take(t, judge.on_fix(now, g), quiet)
            if judge.gps:
                f = self.fix(t)
                if f:
                    g = rfix(f)
                    if log is not None:
                        log.append(golden_fix("F", now, g))
                    take(t, judge.on_fix(now, g), quiet)
                    fetched(quiet)
            cols = timeline.get(t)
            if cols is None:
                continue
            if judge.policy.state == reporter.STILL_STATE and now >= judge.policy.next_breath_at:
                # 干扰：华为推迟呼吸闹钟，这一次晚 seconds 秒
                d = next((e for e in delays if e["at"] <= t and not e.get("used")), None)
                if d:
                    d["used"] = True
                    judge.policy.next_breath_at = now + int(d["seconds"]) * 1000
            snap = sensors.snap(t, cols)
            if log is not None:
                log.append(golden_snap(snap))
            take(t, judge.on_snapshot(now, snap), quiet)
            if log is not None:
                log.append(f"m;{now};{judge.motion};{int(judge.gps)}")
            fetched(quiet)
        self._deliver(out)
        return out

    def _deliver(self, out: list[Report]) -> None:
        """断网：窗口里的上报攒到恢复时一次补传；resend_first 时断网前最后一条再发一次（手机没收到响应）。
        离线队列先进先出：恢复后新报的点排在补传的后面，补传发完才轮到它"""
        for e in self.sc.events_of("offline"):
            queued = [r for r in out if e["from"] <= r.t < e["to"]]
            before = [r for r in out if r.t < e["from"]]
            k = 0
            if e.get("resend_first") and before:
                r = before[-1]
                dup = Report(
                    **{**r.__dict__, "seq": len(out), "arrive": e["to"] + BURST_GAP * k, "tags": [*r.tags, "resend"]}
                )
                out.append(dup)
                k += 1
            for r in queued:
                r.arrive = e["to"] + BURST_GAP * k
                r.tags.append("backfill")
                k += 1
            for r in sorted((r for r in out if r.t >= e["to"]), key=lambda r: r.seq):
                if r.arrive >= e["to"] + BURST_GAP * k:
                    break
                r.arrive = e["to"] + BURST_GAP * k
                k += 1
        out.sort(key=lambda r: (r.arrive, r.seq))


# ---- 金样本（sim.py golden）：一行一件事，SDK 的 SimulatorGoldenTest 按同样的格式重放 ----


def _num(v: float | None) -> str:
    return "" if v is None else repr(v)


def golden_snap(s: reporter.Snap) -> str:
    """S;时间;飞行模式;加速度均值;波动;样本数;节奏峰;陀螺;累计步数;联网;BSSID;SSID;信号;计费;网关"""
    metered = "" if s.wifi_metered is None else str(int(s.wifi_metered))
    peaks = "" if s.acc_peaks is None else str(s.acc_peaks)
    steps = "" if s.steps_total is None else str(s.steps_total)
    rssi = "" if s.wifi_rssi is None else str(s.wifi_rssi)
    return (
        f"S;{s.time};{int(s.airplane)};{_num(s.acc_mean)};{_num(s.acc_std)};{s.acc_n};{peaks};{_num(s.gyro_mean)};{steps};"
        f"{s.net};{s.wifi_bssid};{s.wifi_ssid};{rssi};{metered};{s.wifi_gateway}"
    )


def golden_fix(tag: str, now: int, f: reporter.Fix | None) -> str:
    """F（连续定位来的点）/ R（取网络定位的结果）;此刻;来源;定位时间;纬度;经度;精度;速度；没取到时来源写 -"""
    if f is None:
        return f"{tag};{now};-"
    return f"{tag};{now};{f.provider};{f.time};{_num(f.lat)};{_num(f.lon)};{_num(f.acc)};{_num(f.speed)}"


def golden_report(r: reporter.Report) -> str:
    """r;时间;原因;state;motion;主坐标来源（gps，或顶上的网络估算 network、卫星锚点 anchor）;策略给的点的定位时间;步数;晃动秒数;net;netSource;wifiSame"""
    if r.estimate:
        provider = "anchor" if r.fix and r.fix.provider == "anchor" else "network"
    else:
        provider = r.fix.provider if r.fix else "-"
    fix_time = "-" if r.fix_time is None else str(r.fix_time)
    steps = "-" if r.steps is None else str(r.steps)
    net_source = r.net_fix.provider if r.net_fix else "-"
    same = "-" if r.wifi_same is None else str(r.wifi_same).lower()
    return f"r;{r.time};{r.reason};{r.state};{r.motion};{provider};{fix_time};{steps};{r.bumps};{r.net};{net_source};{same}"
