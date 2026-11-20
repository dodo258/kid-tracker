"""回放检查的判断：拿浏览器里逐帧记下的圆点和状态行，对照真实轨迹和服务器存下的点，按方案「期望」逐条给通过 / 不通过。

- 偏离：圆点离真实轨迹不超过 max(20 米, 2 倍当时精度)。圆点本来就晚一个上报间隔播放、隧道里会停在入口，
  所以量的是「离过去 10 分钟走过的路有多远」（偏到路外才算）。
- 不落后：页面 2 个上报间隔以前就收到的卫星点（跳点除外），圆点沿真实轨迹已经走到它那里（按路程量，往回不超过 max(20 米, 5 秒的路)；
  按秒量不行：原地等车时真实轨迹几分钟都在同一点，圆点在那点上也会算成落后几分钟）。
  隧道、断网时页面没有新点，自然不要求；恢复联网后补传快放的 3 分钟由「补传」那项管。只偏离那项挡不住圆点全程停在起点。
- 不动：真实在原地（stay 段，开头留 30 秒让圆点放完最后一段）时圆点一共挪动不超过 5 米。
- 侧跳点：跳点到达后 40 秒内，圆点离轨迹不超过 10 米 + 附近正常卫星点本身的误差。
- 拐弯：拐弯后第 2 个点到达、再过一个上报间隔（圆点晚一个间隔）加 3 秒时，圆点已经在拐弯之后的路上。
- 补传：恢复联网后 3 分钟内圆点沿真实轨迹只往前走（往回不超过 5 秒的路），并追到断网结束的位置。
- 测速接近 0（真实回放）：连着两个以上卫星点自己测的速度都接近 0（<3.6 km/h，等红灯、站着），从第二个到达起、到下一个不是
  这样的点到达为止，圆点一共挪动不超过 5 米 + 这几个点本身散开的距离（晚一个间隔播放，第二个到达时圆点该已经走到第一个）。
- 状态行：状态变了的点到达后 3 秒，第一行已经是新状态（移动中是「在路上」，已经刚停下的是「刚停下」）。
- 刚停下：手机还报移动中，最新连着的卫星点测速都接近 0、都在最新那个点 15 米内、跨了 30 秒以上（和 parent-web domain/stopped.ts
  同一条规则），这样的点到达后 3 秒第一行是「刚停下」；第一行写「刚停下」时，页面收到的最新一个点也得符合这条（不在路上乱说停了）。
- 失联：最后一次上报不到 15 分钟时不出现「已失联」；超过 15 分钟要出现（页面 30 秒刷新一次「现在」，加速回放时按倍速放宽）。
页面实时收到的只是 Traccar 推送的点（traccar.mark_pushed：晚到的旧点不推），各项都按推送的点算。
服务器一个点都没存、浏览器没采到圆点帧时，报「没有数据」不通过，不抛异常（同一批的其他场景照常出报告）。
"""

import bisect
import math
from dataclasses import dataclass, field

import geo
from build import Bundle
from phone import MOVING_EVERY
from scenario import Scenario

TOL_MIN = 20.0
LOOKBACK = 600
LOOKAHEAD = 5
STILL_SETTLE = 30
STILL_MAX_M = 5.0
JUMP_EXTRA = 10.0
JUMP_WINDOW = 40
TURN_SLACK = 3
BACKFILL_WINDOW = 180
BACKFILL_BACK = 5.0
STATUS_WITHIN = 3.0
LOST_AFTER = 900
PAGE_TICK_S = 30  # parent-web useTracker 的 TICK_MS
CLOCK_AHEAD_S = 300
STATUS_RENDER_S = 0.15  # 页面收到点到第一行变，真实时间最多这么久
PROGRESS_DELAY = 2 * MOVING_EVERY  # 页面收到一个点后，圆点最晚这么久走到它（晚一个间隔播放，再留一个间隔）
PROGRESS_BACK = 5.0  # 圆点比该到的地方往回最多这么多秒的路（按那几秒真实走了多远换成米），至少 TOL_MIN 米

STATE_TEXT = {"moving": ("在路上",), "zone": ("附近活动",), "still": ("静止",)}
STOPPED_TEXT = "刚停下"
STOPPED_MIN_S = 30  # parent-web domain/stopped.ts 的 STOPPED_MIN_MS
STOPPED_RADIUS_M = 15  # parent-web domain/stopped.ts 的 STOPPED_RADIUS_M


@dataclass
class Check:
    name: str
    ok: bool
    detail: str


@dataclass
class Result:
    name: str
    title: str
    checks: list[Check] = field(default_factory=list)
    stats: dict = field(default_factory=dict)
    shots: list[dict] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return all(c.ok for c in self.checks)

    def add(self, name: str, ok: bool, detail: str) -> None:
        self.checks.append(Check(name, ok, detail))


CRAWL_KMH = 3.6  # parent-web domain/fixQuality.ts 的 CRAWL_KMH：卫星测速低于这个算几乎停着
ROUGH_ACCURACY_M = 50  # parent-web 的 ROUGH_ACCURACY_M：精度差于这个是大概位置
KN_TO_KMH = 1.852

COVER_TICK_MS = 200  # replay.mjs 多久查一次圆点有没有被卡片挡住
COVER_MAX_MS = 1000  # 连续被挡这么久（真实时间）以上才算卡片挡住了圆点


class Judge:
    def __init__(self, b: Bundle, trace: dict):
        self.b = b
        sc = b.sc
        self.sc = sc
        lat0, lon0 = sc.origin
        self.xy = [geo.enu(lat0, lon0, s.lat, s.lon) for s in sc.truth]
        # 沿真实轨迹走过的路程（米），第 i 秒
        self.cum = [0.0]
        for (ax, ay), (bx, by) in zip(self.xy, self.xy[1:]):
            self.cum.append(self.cum[-1] + math.hypot(bx - ax, by - ay))
        start = sc.start_ms
        self.frames = sorted(
            ((f[0] - start) / 1000, *geo.enu(lat0, lon0, f[1], f[2]), f[3]) for f in trace["frames"] if f[0] >= start
        )
        self.frame_t = [f[0] for f in self.frames]
        # 页面打开要几秒（加速后是场景里几十秒），第一帧之前看不到页面，不判；第一帧之前记下的状态行算在第一帧
        self.observed = self.frame_t[0] if self.frame_t else math.inf
        status = [((s[0] - start) / 1000 if s[0] is not None else self.observed, s[1] or "") for s in trace["status"]]
        self.status = sorted(status, key=lambda s: s[0])
        self.status_t = [s[0] for s in self.status]
        self.cover = trace.get("cover")
        # 页面实时收到的点：Traccar 推送的（晚到的旧点只在历史库里）
        self.stored = sorted((p for p in b.stored if p.pushed), key=lambda p: p.server_ms)
        self.arrive = [(p.server_ms - start) / 1000 for p in self.stored]
        self.fix_time: dict[int, float] = {}
        last = 0.0
        for p in self.stored:
            if p.report is not None and p.report.fix:
                last = float(p.report.fix.t)
            self.fix_time[p.id] = last

    def clock(self, t: float) -> str:
        return self.sc.start.fromtimestamp(self.sc.start_ms / 1000 + t, self.sc.start.tzinfo).strftime("%H:%M:%S")

    def near(
        self, x: float, y: float, t: float, back: float = LOOKBACK, ahead: float = LOOKAHEAD
    ) -> tuple[float, float]:
        """点 (x, y) 离 [t-back, t+ahead] 这段真实轨迹最近多远，最近处是第几秒"""
        n = len(self.xy)
        lo = max(0, int(t - back))
        hi = min(n - 1, int(t + ahead) + 1)
        best, best_t = math.inf, float(lo)
        for i in range(lo, max(lo + 1, hi)):
            ax, ay = self.xy[i]
            bx, by = self.xy[min(i + 1, n - 1)]
            dx, dy = bx - ax, by - ay
            seg2 = dx * dx + dy * dy
            k = 0.0 if seg2 == 0 else max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / seg2))
            d = math.hypot(ax + k * dx - x, ay + k * dy - y)
            if d <= best + 0.01:  # 并列（原地停着时每秒都一样近）取更晚的
                best, best_t = d, i + k
        return best, best_t

    def along(self, t: float) -> float:
        """真实轨迹第 t 秒（可带小数）时走过的路程，米"""
        i = max(0, min(len(self.cum) - 1, int(t)))
        j = min(len(self.cum) - 1, i + 1)
        return self.cum[i] + (t - i) * (self.cum[j] - self.cum[i])

    def latest(self, t: float):
        """t 时页面已经收到的最新一个点"""
        i = bisect.bisect_right(self.arrive, t) - 1
        return self.stored[i] if i >= 0 else None

    def dot(self, t: float):
        i = bisect.bisect_right(self.frame_t, t) - 1
        return self.frames[i] if i >= 0 else None

    def text(self, t: float) -> str:
        i = bisect.bisect_right(self.status_t, t) - 1
        return self.status[i][1] if i >= 0 else ""

    def true_time(self, p) -> float:
        """服务器存的这个点的坐标实际是场景第几秒测的（不受手机时间不准影响；只带时间的点抄的是上一个点的坐标）"""
        return self.fix_time[p.id]

    def truth_error(self, p) -> float:
        s = self.sc.at(p.report.fix.t)
        return geo.distance(s.lat, s.lon, p.lat, p.lon)

    # ---- 各项检查 ----

    def deviation(self, r: Result) -> None:
        worst = None
        over = 0
        devs: list[float] = []
        lags: list[float] = []
        for t, x, y, _live in self.frames:
            p = self.latest(t)
            if p is None:
                continue
            # 页面手里最新的点很久以前（隧道、服务器丢了后面的点）时，圆点停在那里不算偏，回看到那个点之前
            back = max(LOOKBACK, t - self.true_time(p) + 60)
            d, nt = self.near(x, y, t, back=back)
            tol = max(TOL_MIN, 2 * p.accuracy)
            devs.append(d)
            if self.sc.at(t).moving:
                lags.append(max(0.0, t - nt))  # 落后多少秒只在真实在路上时有意义
            if d > tol:
                over += 1
            if worst is None or d - tol > worst[0]:
                worst = (d - tol, t, d, tol)
        if not devs:
            r.add("偏离真实轨迹", False, "没有圆点帧")
            return
        devs_sorted = sorted(devs)
        lags = sorted(lags) or [0.0]
        r.stats.update(
            frames=len(self.frames),
            dev_p50=round(devs_sorted[len(devs) // 2], 1),
            dev_p95=round(devs_sorted[int(len(devs) * 0.95)], 1),
            dev_max=round(devs_sorted[-1], 1),
            lag_p50=round(lags[len(lags) // 2], 1),
            lag_max=round(lags[-1], 1),
        )
        _, t, d, tol = worst
        r.add(
            "偏离真实轨迹 ≤ max(20 米, 2 倍精度)",
            over == 0,
            f"{len(devs)} 帧，{over} 帧超出；最差 {self.clock(t)} 偏 {d:.0f} 米（允许 {tol:.0f} 米）；"
            f"中位 {r.stats['dev_p50']} 米，95% {r.stats['dev_p95']} 米",
        )

    def progress(self, r: Result) -> None:
        """圆点沿路线不落后于页面早就收到的点：t 时圆点在真实轨迹上的进度，不早于 t-PROGRESS_DELAY 以前收到的最后一个卫星点"""
        refs = [
            (a, p)
            for a, p in zip(self.arrive, self.stored)
            if not p.outdated
            and p.report is not None
            and p.report.fix.provider == "gps"
            and "jump" not in p.report.tags
        ]
        if not refs:
            return
        ref_t = [a for a, _ in refs]
        # 每个参考点在真实轨迹上的进度：投影到它测的那一刻前后
        ref_at = []
        for _, p in refs:
            x, y = geo.enu(*self.sc.origin, p.lat, p.lon)
            ref_at.append(self.near(x, y, self.true_time(p), back=30, ahead=5)[1])
        quiet = [(e["to"], e["to"] + BACKFILL_WINDOW) for e in self.sc.events_of("offline")]
        worst = None
        n = bad = 0
        for t, x, y, _ in self.frames:
            if any(a <= t <= b for a, b in quiet):
                continue
            i = bisect.bisect_right(ref_t, t - PROGRESS_DELAY) - 1
            if i < 0:
                continue
            want = ref_at[i]
            _, nt = self.near(x, y, t, back=max(LOOKBACK, t - want + LOOKBACK))
            n += 1
            behind = self.along(want) - self.along(nt)
            allow = max(TOL_MIN, self.along(want) - self.along(want - PROGRESS_BACK))
            if behind > allow:
                bad += 1
            if worst is None or behind - allow > worst[0]:
                worst = (behind - allow, t, ref_t[i], behind, allow)
        if n == 0:
            return
        _, t, got, behind, allow = worst
        r.stats.update(behind_max=round(max(0.0, behind), 1))
        r.add(
            f"圆点不落后于 {PROGRESS_DELAY} 秒前收到的点",
            bad == 0,
            f"{n} 帧，{bad} 帧落后超出；最差 {self.clock(t)} 圆点比 {self.clock(got)} 收到的点"
            f"还落后 {max(0.0, behind):.0f} 米的路（允许 {allow:.0f} 米：20 米或 {PROGRESS_BACK:.0f} 秒的路）",
        )

    def still(self, r: Result) -> None:
        sc = self.sc
        spans = []
        start = 0
        for i, s in enumerate(sc.truth):
            if i == len(sc.truth) - 1 or sc.truth[i + 1].kind != s.kind or sc.truth[i + 1].seg != s.seg:
                if s.kind == "stay":
                    spans.append((start, i + 1))
                start = i + 1
        for a, b in spans:
            lo = a + (5 if a == 0 else STILL_SETTLE)
            fr = [f for f in self.frames if lo <= f[0] < b]
            moved = sum(math.hypot(fr[i][1] - fr[i - 1][1], fr[i][2] - fr[i - 1][2]) for i in range(1, len(fr)))
            r.add(
                f"不动时圆点不挪（{self.clock(lo)}–{self.clock(b)}）",
                moved <= STILL_MAX_M,
                f"{len(fr)} 帧，圆点一共挪了 {moved:.1f} 米（允许 {STILL_MAX_M:.0f} 米）",
            )

    def jumps(self, r: Result) -> None:
        jumped = [(a, p) for a, p in zip(self.arrive, self.stored) if "jump" in p.report.tags]
        groups: list[list[tuple[float, object]]] = []
        for a, p in jumped:
            if groups and a - groups[-1][-1][0] <= 3 * MOVING_EVERY:
                groups[-1].append((a, p))
            else:
                groups.append([(a, p)])
        for g in groups:
            a0, a1 = g[0][0], g[-1][0]
            normal = [
                self.truth_error(p)
                for a, p in zip(self.arrive, self.stored)
                if a0 - 60 <= a <= a1 + 60
                and p.report.fix
                and p.report.fix.provider == "gps"
                and "jump" not in p.report.tags
            ]
            allow = JUMP_EXTRA + (max(normal) if normal else 0.0)
            fr = [f for f in self.frames if a0 <= f[0] <= a1 + JUMP_WINDOW]
            worst = max((self.near(f[1], f[2], f[0])[0] for f in fr), default=math.inf)
            r.add(
                f"侧跳点不把圆点拽偏（{self.clock(a0)} 起 {len(g)} 个点）",
                worst <= allow,
                f"跳点偏 {min(self.truth_error(p) for _, p in g):.0f}–{max(self.truth_error(p) for _, p in g):.0f} 米；"
                f"之后 {JUMP_WINDOW} 秒圆点最多离轨迹 {worst:.0f} 米（允许 10 米 + 附近正常点误差 = {allow:.0f} 米）",
            )

    def turns(self, r: Result) -> None:
        for turn in self.sc.turns:
            after = [(a, p) for a, p in zip(self.arrive, self.stored) if not p.outdated and p.report.fix.t > turn.t]
            if len(after) < 2:
                continue
            c = after[1][0] + MOVING_EVERY + TURN_SLACK
            f = self.dot(c)
            if f is None:
                continue
            d, nt = self.near(f[1], f[2], c)
            r.add(
                f"拐弯 2 个点内跟上（{self.clock(turn.t)} 转 {turn.angle:.0f}°）",
                nt >= turn.t - 2,
                f"第 2 个点 {self.clock(after[1][0])} 到达，{self.clock(c)} 时圆点在真实轨迹 {self.clock(nt)} 处"
                f"（拐弯在 {self.clock(turn.t)}），离轨迹 {d:.0f} 米",
            )

    def backfill(self, r: Result) -> None:
        for e in self.sc.events_of("offline"):
            a = e["to"]
            fr = [f for f in self.frames if a <= f[0] <= a + BACKFILL_WINDOW]
            back = a - e["from"] + 60
            best = -math.inf
            worst_back = 0.0
            caught = None
            n = 0
            for t, x, y, _ in fr:
                n += 1
                _, nt = self.near(x, y, t, back=back)
                worst_back = max(worst_back, best - nt)
                best = max(best, nt)
                if nt >= a - 30:
                    caught = t
                    break  # 追上了，补传快放到此为止；之后是正常播放
            ok = worst_back <= BACKFILL_BACK and caught is not None
            r.add(
                f"补传快放按顺序（{self.clock(a)} 恢复联网）",
                ok,
                f"{n} 帧；最多往回退 {worst_back:.0f} 秒的路；"
                + (
                    f"恢复后 {caught - a:.0f} 秒追到断网结束的位置"
                    if caught is not None
                    else "3 分钟内没追到断网结束的位置"
                ),
            )

    def status_changes(self, r: Result, speed: float) -> None:
        # 加速回放时页面渲染要几十毫秒真实时间，按倍速换成场景时间放宽（20 倍速还是 3 秒）
        within = max(STATUS_WITHIN, STATUS_RENDER_S * speed)
        prev = None
        bad = []
        n = skipped = 0
        for i, (a, p) in enumerate(zip(self.arrive, self.stored)):
            ahead = p.fix_ms - p.server_ms > CLOCK_AHEAD_S * 1000
            key = (p.attributes.get("state"), ahead)
            if key == prev:
                continue
            prev = key
            if key[0] not in STATE_TEXT and not ahead:
                continue  # 旧版 App 的点没有 state
            c = a + within
            if i + 1 < len(self.arrive) and self.arrive[i + 1] < c:
                skipped += 1  # 下一个点在这之前就到了，这个倍速下看不出来
                continue
            if c < self.observed or self.lost_expected(c):
                continue
            want = ("时间不对",) if ahead else (STOPPED_TEXT,) if self.stopped_at(i) else STATE_TEXT.get(key[0], ())
            got = self.text(c)
            n += 1
            if not any(w in got for w in want):
                bad.append(
                    f"{self.clock(a)} 到达 {key[0]}{'（手机时间快）' if ahead else ''}，{c - a:.0f} 秒后是「{got}」"
                )
        r.add(
            f"状态行在点到达后 {STATUS_WITHIN:.0f} 秒内变",
            not bad,
            f"查了 {n} 次变化（{speed:.0f} 倍速，按 {within:.0f} 秒看"
            + (f"；{skipped} 次下一个点紧跟着到、看不出来" if skipped else "")
            + "）"
            + ("；" + "；".join(bad[:4]) if bad else ""),
        )

    def still_reported(self, r: Result) -> None:
        """真实回放没有真实轨迹：手机报「静止」期间（静止点到达 30 秒后，到下一个不是静止的点到达）圆点不挪"""
        spans = []
        for i, (a, p) in enumerate(zip(self.arrive, self.stored)):
            if p.attributes.get("state") != "still":
                continue
            if i > 0 and self.stored[i - 1].attributes.get("state") == "still":
                continue
            end = next(
                (b for b, q in zip(self.arrive[i + 1 :], self.stored[i + 1 :]) if q.attributes.get("state") != "still"),
                self.sc.duration,
            )
            spans.append((a + STILL_SETTLE, end))
        bad = []
        for a, b in spans:
            fr = [f for f in self.frames if a <= f[0] < b]
            moved = sum(math.hypot(fr[i][1] - fr[i - 1][1], fr[i][2] - fr[i - 1][2]) for i in range(1, len(fr)))
            if moved > STILL_MAX_M:
                bad.append(f"{self.clock(a)}–{self.clock(b)} 挪了 {moved:.0f} 米")
        r.add("报静止时圆点不挪", not bad, f"{len(spans)} 段静止" + ("；" + "；".join(bad[:4]) if bad else ""))

    @staticmethod
    def rough(p) -> bool:
        """大概位置（parent-web fixQuality.isRough）：标了 wifi、network 等网络来源，或精度差于 50 米"""
        provider = p.attributes.get("provider")
        satellite = provider == "gps" or (provider is None and 0 < p.accuracy <= ROUGH_ACCURACY_M)
        return not satellite or p.accuracy > ROUGH_ACCURACY_M

    def crawling(self, p) -> bool:
        """这个点自己测的速度说人几乎停着：卫星点（标 gps，或没标来源、精度不差于 50 米）、有坐标、测速 <3.6 km/h"""
        return not self.rough(p) and not p.outdated and p.speed_kn * KN_TO_KMH < CRAWL_KMH

    def stopped_at(self, i: int) -> bool:
        """页面收到第 i 个推送的点时该不该说「刚停下」（parent-web domain/stopped.ts 的 stoppedSince，按定位时间）：
        最新一条报移动中；往前看这一轮移动中的卫星点，测速都接近 0、都在最新那个点 15 米内，跨 30 秒以上。网络点、没坐标的跳过"""
        if self.stored[i].attributes.get("state") != "moving":
            return False
        newest = first = None
        for q in reversed(self.stored[: i + 1]):
            if q.attributes.get("state") != "moving":
                break
            if q.outdated or self.rough(q):
                continue
            if not self.crawling(q):
                break
            newest = newest or q
            if geo.distance(q.lat, q.lon, newest.lat, newest.lon) > STOPPED_RADIUS_M:
                break
            first = q
        return newest is not None and first is not None and newest.fix_ms - first.fix_ms >= STOPPED_MIN_S * 1000

    def stopped(self, r: Result, speed: float) -> None:
        """网页自己判断的「刚停下」：该说的时候 3 秒内说出来，不该说的时候不说"""
        within = max(STATUS_WITHIN, STATUS_RENDER_S * speed)
        missed = []
        n = skipped = 0
        prev = False
        for i, a in enumerate(self.arrive):
            on = self.stopped_at(i)
            if on and not prev:
                c = a + within
                if i + 1 < len(self.arrive) and self.arrive[i + 1] < c:
                    skipped += 1
                elif c >= self.observed and not self.lost_expected(c):
                    n += 1
                    if STOPPED_TEXT not in self.text(c):
                        missed.append(f"{self.clock(a)} 到达后 {c - a:.0f} 秒还是「{self.text(c)}」")
            prev = on
        wrong = []
        for t, text in self.status:
            i = bisect.bisect_right(self.arrive, t) - 1
            if STOPPED_TEXT in text and (i < 0 or not self.stopped_at(i)):
                wrong.append(f"{self.clock(t)}「{text}」")
        r.add(
            "停下 30 秒后第一行写「刚停下」，在动时不写",
            not missed and not wrong,
            f"该说 {n} 次（{speed:.0f} 倍速，按 {within:.0f} 秒看"
            + (f"；{skipped} 次下一个点紧跟着到、看不出来" if skipped else "")
            + "）"
            + ("；没说：" + "；".join(missed[:4]) if missed else "")
            + ("；不该说：" + "；".join(wrong[:4]) if wrong else ""),
        )

    def crawl_spans(self) -> list[tuple[float, float, float]]:
        """测速连续接近 0 的几段：(第二个点到达, 下一个不是这样的点到达, 这几个点两两最远差几米)；没坐标的心跳不打断"""
        spans = []
        run: list[int] = []

        def close(end: float) -> None:
            if len(run) >= 2:
                pts = [self.stored[i] for i in run]
                spread = max(geo.distance(a.lat, a.lon, b.lat, b.lon) for a in pts for b in pts)
                spans.append((self.arrive[run[1]], end, spread))

        for i, p in enumerate(self.stored):
            if p.outdated:
                continue
            if self.crawling(p):
                run.append(i)
                continue
            close(self.arrive[i])
            run = []
        close(self.sc.duration)
        return spans

    def crawl_still(self, r: Result) -> None:
        """真实回放：报的测速连续接近 0 时圆点不挪（P20 11-18 等红灯，以前冲出停车点 47 米再拉回）"""
        spans = self.crawl_spans()
        bad = []
        worst = 0.0
        for a, b, spread in spans:
            fr = [f for f in self.frames if a <= f[0] < b]
            moved = sum(math.hypot(fr[i][1] - fr[i - 1][1], fr[i][2] - fr[i - 1][2]) for i in range(1, len(fr)))
            worst = max(worst, moved)
            if moved > STILL_MAX_M + spread:
                bad.append(f"{self.clock(a)}–{self.clock(b)} 挪了 {moved:.0f} 米（允许 {STILL_MAX_M + spread:.0f} 米）")
        r.add(
            "报的测速连续接近 0 时圆点不挪",
            not bad,
            f"{len(spans)} 段，圆点最多挪 {worst:.1f} 米" + ("；" + "；".join(bad[:4]) if bad else ""),
        )

    def lost_expected(self, t: float) -> bool:
        p = self.latest(t)
        return p is not None and t - (p.server_ms - self.sc.start_ms) / 1000 > LOST_AFTER

    def lost(self, r: Result, speed: float) -> None:
        late_ok = 5 + PAGE_TICK_S * speed * 1.1
        false_lost = []
        missed = []
        for t in range(max(int(self.arrive[0]) + 1, math.ceil(self.observed)), self.sc.duration):
            p = self.latest(t)
            if "state" not in p.attributes:
                continue  # 旧版 App 的点：失联写「可能没网、关机或 App 被停」，按旧规则，不在这里判
            age = t - (p.server_ms - self.sc.start_ms) / 1000
            shown = "已失联" in self.text(t)
            if shown and age < LOST_AFTER - 2:
                false_lost.append((t, age))
            if not shown and age > LOST_AFTER + late_ok:
                missed.append((t, age))
        gaps = [b - a for a, b in zip(self.arrive, self.arrive[1:])]
        detail = f"最长两次上报间隔 {max(gaps, default=0) / 60:.1f} 分钟"
        if false_lost:
            t, age = false_lost[0]
            detail += f"；{self.clock(t)} 离上次上报才 {age / 60:.1f} 分钟就显示已失联"
        if missed:
            t, age = missed[0]
            detail += (
                f"；{self.clock(t)} 离上次上报 {age / 60:.1f} 分钟还没显示已失联（加速回放允许晚 {late_ok:.0f} 秒）"
            )
        r.add("失联只在超过 15 分钟没上报时出现", not false_lost and not missed, detail)

    def uncovered(self, r: Result) -> None:
        """选中的圆点在底部状态卡上面（卡片默认收起，镜头按露出的高度留边；方案 2026-11-18-parent-web-info-sheet）。
        加速回放时圆点一下挪几百米，跟随镜头按真实时间缓动追过去，中间一两帧圆点会落到卡片后面：连续被挡不到 1 秒（真实时间）算镜头在追，
        连续 1 秒以上才是卡片挡住了圆点（留边算错、卡片太高）"""
        if not self.cover:
            return  # 旧的 trace.json 没记
        covered = self.cover["covered"]
        runs: list[list] = []
        for c in covered:
            if runs and len(c) > 3 and c[3] - runs[-1][-1][3] <= COVER_TICK_MS * 1.5:
                runs[-1].append(c)
            else:
                runs.append([c])
        long = [g for g in runs if len(g[0]) <= 3 or g[-1][3] - g[0][3] >= COVER_MAX_MS]
        detail = f"查了 {self.cover['samples']} 次"
        if covered:
            detail += f"，镜头追赶时短暂被挡 {len(runs) - len(long)} 回"
        if long:
            clock, bottom, top = long[0][0][:3]
            when = self.clock((clock - self.sc.start_ms) / 1000) if clock is not None else "打开时"
            detail += f"；{len(long)} 回连续被挡 1 秒以上，第一回 {when} 圆点底 {bottom} 像素、卡片顶 {top} 像素"
        r.add("圆点没被底部卡片挡住", self.cover["samples"] > 0 and not long, detail)


def judge(b: Bundle, trace: dict, speed: float) -> Result:
    sc: Scenario = b.sc
    r = Result(sc.name, sc.title, shots=trace.get("shots", []))
    j = Judge(b, trace)
    r.stats.update(reports=len(b.reports), stored=len(b.stored), dropped=len(b.dropped))
    if not j.stored:
        why = "、".join(sorted({w for _, w in b.dropped})) or "没有上报"
        r.add("服务器存下的点", False, f"没有数据：服务器一个点都没存（{len(b.reports)} 条上报，丢掉的原因：{why}）")
        return r
    if not j.frames:
        r.add("圆点帧", False, "没有数据：浏览器没采到圆点帧（页面没出圆点或回放没跑）")
        return r
    if sc.real:
        r.stats.update(frames=len(j.frames))
        j.still_reported(r)
        j.crawl_still(r)
        j.status_changes(r, speed)
        j.stopped(r, speed)
        j.lost(r, speed)
        j.uncovered(r)
        return r
    j.deviation(r)
    j.progress(r)
    j.still(r)
    j.jumps(r)
    j.turns(r)
    j.backfill(r)
    j.status_changes(r, speed)
    j.stopped(r, speed)
    j.lost(r, speed)
    j.uncovered(r)
    return r
