"""场景：读 scenarios/*.json，生成「真实轨迹」（每秒一个真实位置）。

场景文件格式见 README.md。时间一律用「离场景开始多少秒」，坐标 WGS-84。
"""

import json
import math
import random
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

import geo

SCENARIO_DIR = Path(__file__).resolve().parent / "scenarios"

# 测试城市里的虚构起点：和 parent-web/fixtures 里小宝的位置一致（杭州），不是孩子真实的家或学校
DEFAULT_ORIGIN = (30.27439, 120.140581)

# 每种段的默认速度（米/秒）、定位来源、连着的 Wi-Fi
MOVING_KINDS = ("walk", "bike", "bus")
DEFAULT_MPS = {"walk": 1.4, "bike": 4.5, "bus": 9.0}
DEFAULT_FIX = {"stay": "wifi", "indoor": "wifi", "walk": "gps", "bike": "gps", "bus": "gps"}
DEFAULT_WIFI = {"stay": "fixed", "indoor": "fixed", "walk": "none", "bike": "none", "bus": "none"}
INDOOR_RADIUS = 8.0
TURN_DEG = 60.0

EVENT_TYPES = {
    "side_jump",  # 侧跳点：at 之后报的 points 个卫星点往旁边偏 meters 米
    "drift",  # 整段漂移：from–to 网络定位误差一路漂到 meters 米外（bearing 方向）
    "no_fix",  # 隧道 / 地铁：from–to 没有任何定位
    "cold_start",  # GPS 冷启动：from–to 没有卫星点，只有基站定位（每 15 秒一个）
    "offline",  # 断网：from–to 的上报攒着，to 时一次补传；resend_first 时断网前最后一个点再发一次
    "breath_delay",  # 华为推迟呼吸：at 之后的第一次呼吸晚 seconds 秒
    "killed",  # 进程被杀：from–to 不定位、不上报，to 时重启
    "airplane",  # 飞行模式：from–to 全停（不定位、不上报），to 时恢复
    "clock",  # 手机时间不准：from–to 上报的时间戳快 seconds 秒（负数是慢）
    "wifi_wrong",  # Wi-Fi 位置库记错：from–to 的网络定位都落在 from 时真实位置往东 east、往北 north 米的固定点
    "misjudge_moving",  # 旧版上报端把屋里误判成移动中：from–to 每 interval 秒报一个网络定位点，state=moving
}


@dataclass
class Segment:
    kind: str
    seconds: float = 0.0
    legs: list[tuple[float, float]] = field(default_factory=list)
    mps: float = 0.0
    stops: list[tuple[float, float]] = field(default_factory=list)  # 公交等红灯：[走了多少米后, 停几秒]
    fix: str = ""
    acc: dict[str, tuple[float, float]] = field(default_factory=dict)  # 按定位来源改精度范围，如 {"cell": [500, 1200]}
    wifi: str = ""
    charging: bool = False


@dataclass
class Sample:
    """真实轨迹的一秒"""

    t: int
    lat: float
    lon: float
    mps: float
    bearing: float
    kind: str  # stay | indoor | walk | bike | bus
    seg: int
    moving: bool  # 在路上（走、骑、坐车，含等红灯）
    walking: bool  # 这一秒在迈步（走路、屋里走动）


@dataclass
class Turn:
    t: int
    lat: float
    lon: float
    angle: float


@dataclass
class Scenario:
    name: str
    title: str
    start: datetime
    origin: tuple[float, float]
    seed: int
    speed: float
    segments: list[Segment]
    events: list[dict]
    expect: dict
    tail: int  # 最后一段结束后再放多少秒（看失联、补传结束）
    truth: list[Sample] = field(default_factory=list)
    turns: list[Turn] = field(default_factory=list)
    real: str = ""  # 真实回放：用 parent-web/fixtures/ 下这个文件里服务器真实存下的点，没有真实轨迹
    end: datetime | None = None
    fresh: bool = False  # 全新装：手机手里没有卫星点，服务器上也还没有这台设备的位置
    restored: str = ""  # 场景开始时 App 刚重启（装更新、进程被杀），接着重启前的运动状态（只认 still，和 App 一样）

    @property
    def start_ms(self) -> int:
        return int(self.start.timestamp() * 1000)

    @property
    def duration(self) -> int:
        if self.real and self.end:
            return int((self.end - self.start).total_seconds())
        return len(self.truth)

    def events_of(self, kind: str) -> list[dict]:
        return [e for e in self.events if e["type"] == kind]

    def at(self, t: float) -> Sample:
        return self.truth[max(0, min(len(self.truth) - 1, int(t)))]


def _segment(raw: dict) -> Segment:
    kind = raw["kind"]
    if kind not in DEFAULT_FIX:
        raise ValueError(f"不认识的段类型 {kind!r}")
    if kind in MOVING_KINDS and not raw.get("legs"):
        raise ValueError(f"{kind} 段要写 legs")
    if kind not in MOVING_KINDS and not raw.get("seconds"):
        raise ValueError(f"{kind} 段要写 seconds")
    acc = raw.get("acc")
    return Segment(
        kind=kind,
        seconds=float(raw.get("seconds", 0)),
        legs=[(float(b), float(d)) for b, d in raw.get("legs", [])],
        mps=float(raw.get("mps", DEFAULT_MPS.get(kind, 0.0))),
        stops=[(float(a), float(s)) for a, s in raw.get("stops", [])],
        fix=raw.get("fix", DEFAULT_FIX[kind]),
        acc={k: (float(v[0]), float(v[1])) for k, v in (acc or {}).items()},
        wifi=raw.get("wifi", DEFAULT_WIFI[kind]),
        charging=bool(raw.get("charging", False)),
    )


def parse(raw: dict) -> Scenario:
    """校验并读入一个场景（JSON 解出来的 dict），生成真实轨迹"""
    if raw.get("real"):
        return Scenario(
            name=raw["name"],
            title=raw.get("title", raw["name"]),
            start=datetime.fromisoformat(raw["from"]),
            origin=DEFAULT_ORIGIN,
            seed=0,
            speed=float(raw.get("speed", 300)),
            segments=[],
            events=[],
            expect={},
            tail=0,
            real=raw["real"],
            end=datetime.fromisoformat(raw["to"]),
        )
    for e in raw.get("events", []):
        if e.get("type") not in EVENT_TYPES:
            raise ValueError(f"{raw.get('name')}: 不认识的干扰 {e.get('type')!r}")
    sc = Scenario(
        name=raw["name"],
        title=raw.get("title", raw["name"]),
        start=datetime.fromisoformat(raw["start"]),
        origin=tuple(raw.get("origin", DEFAULT_ORIGIN)),
        seed=int(raw.get("seed", 1)),
        speed=float(raw.get("speed", 20)),
        segments=[_segment(s) for s in raw["segments"]],
        events=list(raw.get("events", [])),
        expect=dict(raw.get("expect", {})),
        tail=int(raw.get("tail", 30)),
        fresh=bool(raw.get("fresh", False)),
        restored=str(raw.get("restored", "")),
    )
    if sc.restored not in ("", "still"):
        raise ValueError(f"{sc.name}: restored 只能是 still")
    if sc.start.tzinfo is None:
        raise ValueError(f"{sc.name}: start 要带时区，如 +08:00")
    build_truth(sc)
    return sc


def load(name: str, start: datetime | None = None) -> Scenario:
    """读 scenarios/<name>.json；start 换开始时间（实时上报从现在开始）"""
    path = SCENARIO_DIR / f"{name}.json"
    if not path.exists():
        raise FileNotFoundError(f"没有场景 {name}（{SCENARIO_DIR}）")
    raw = json.loads(path.read_text(encoding="utf-8"))
    if start is not None:
        raw["start"] = start.isoformat()
    return parse(raw)


def names() -> list[str]:
    return sorted(p.stem for p in SCENARIO_DIR.glob("*.json"))


def build_truth(sc: Scenario) -> None:
    rng = random.Random(sc.seed)
    lat, lon = sc.origin
    heading = 0.0
    out: list[Sample] = []
    turns: list[Turn] = []
    for i, seg in enumerate(sc.segments):
        if seg.kind == "stay":
            for _ in range(int(seg.seconds)):
                out.append(Sample(len(out), lat, lon, 0.0, heading, "stay", i, False, False))
        elif seg.kind == "indoor":
            _indoor(out, rng, i, lat, lon, int(seg.seconds), heading)
        else:
            lat, lon, heading = _legs(out, turns, i, seg, lat, lon, heading)
    # 最后一段的位置再放 tail 秒
    last = out[-1]
    for _ in range(sc.tail):
        out.append(Sample(len(out), last.lat, last.lon, 0.0, last.bearing, last.kind, last.seg, False, False))
    sc.truth = out
    sc.turns = turns


def _indoor(out: list[Sample], rng: random.Random, seg: int, lat: float, lon: float, seconds: int, heading: float):
    """屋里来回走：在半径 8 米内走走停停（0.6 米/秒），最后回到起点附近"""
    x = y = 0.0
    target: tuple[float, float] | None = None
    pause = rng.randint(5, 20)
    for _ in range(seconds):
        walking = False
        if pause > 0:
            pause -= 1
        else:
            if target is None:
                r = INDOOR_RADIUS * math.sqrt(rng.random())
                a = rng.random() * 2 * math.pi
                target = (r * math.cos(a), r * math.sin(a))
            dx, dy = target[0] - x, target[1] - y
            d = math.hypot(dx, dy)
            if d <= 0.6:
                x, y = target
                target = None
                pause = rng.randint(5, 30)
            else:
                x, y = x + dx / d * 0.6, y + dy / d * 0.6
                heading = math.degrees(math.atan2(dx, dy)) % 360
                walking = True
        p = geo.offset(lat, lon, x, y)
        out.append(Sample(len(out), p[0], p[1], 0.6 if walking else 0.0, heading, "indoor", seg, False, walking))


def _legs(out: list[Sample], turns: list[Turn], seg_i: int, seg: Segment, lat: float, lon: float, heading: float):
    """沿折线匀速走，公交按 stops 在走过多少米处停几秒"""
    pts = [(lat, lon)]
    for b, d in seg.legs:
        pts.append(geo.move(pts[-1][0], pts[-1][1], b, d))
    cum = [0.0]
    for b, d in seg.legs:
        cum.append(cum[-1] + d)
    total = cum[-1]
    stops = sorted(seg.stops)
    walking = seg.kind == "walk"
    dist = 0.0
    prev_leg = None
    while dist < total:
        k = next(j for j in range(len(seg.legs)) if dist < cum[j + 1])
        b, d = seg.legs[k]
        if prev_leg is not None and k != prev_leg:
            angle = geo.turn_angle(seg.legs[prev_leg][0], b)
            if angle >= TURN_DEG:
                turns.append(Turn(len(out), pts[k][0], pts[k][1], angle))
        prev_leg = k
        p = geo.move(pts[k][0], pts[k][1], b, dist - cum[k])
        out.append(Sample(len(out), p[0], p[1], seg.mps, b, seg.kind, seg_i, True, walking))
        nxt = dist + seg.mps
        while stops and stops[0][0] < nxt:
            at, secs = stops.pop(0)
            p = geo.move(pts[k][0], pts[k][1], b, min(at, cum[k + 1]) - cum[k])
            for _ in range(int(secs)):
                out.append(Sample(len(out), p[0], p[1], 0.0, b, seg.kind, seg_i, True, False))
        dist = nxt
    return pts[-1][0], pts[-1][1], seg.legs[-1][0]
