"""服务器模型：按生产 Traccar 6.17.0 的处理和 traccar.xml 的过滤配置，算出服务器会存下哪些点、存成什么样。

依据（v6.17.0 源码，docs/server-deploy.md 记了生产配置）：
- OsmAndProtocolDecoder：没有 lat/lon 时标 outdated，之后 OutdatedHandler 抄上一个点的坐标、精度、速度、方向和定位时间，
  deviceTime 是这次上报的 timestamp；速度参数按节；不认识的参数（state、provider、steps…）存进 attributes，数字串存成小数。
- FilterHandler：filter.future=86400 丢定位时间比服务器晚一天以上的点。filter.duplicate 2026-11-18 12:28 起关掉
  （docs/server-deploy.md「关重复过滤」：心跳抄上一个点的定位时间，补传时被当重复丢掉），手机重发的点存两遍，家长页按 id 去重。
- PostProcessHandler：定位时间不早于设备当前最新位置的点，才换成设备的最新位置（/api/positions 给的那条）并推给 WebSocket；
  晚到的旧点（乱序补传）只进历史库（pushed=False）。没坐标的心跳抄上一个点的定位时间，相等，照推。
生产配置 2026-11-18 08:20 由 future=600、skipLimit=240 改成 future=86400；12:28 关掉 duplicate（skipLimit、skipAttributes 随之不起作用）。
生产上实际存了什么由 `sim.py live` 取回核对，和这个模型对不上就是发现。
"""

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from phone import Report

FILTER_FUTURE = 86400
CST = timezone(timedelta(hours=8))


@dataclass
class Stored:
    id: int
    report: Report
    server_ms: int
    device_ms: int
    fix_ms: int
    lat: float
    lon: float
    accuracy: float
    speed_kn: float
    course: float
    outdated: bool
    attributes: dict
    pushed: bool = True  # 换成了设备最新位置、推给了网页（mark_pushed）

    def fixture(self) -> dict:
        """家长页 fixtures 的格式（和 fixtures/p20.json 一样：Traccar /api/positions 的字段）"""
        return {
            "id": self.id,
            "deviceId": 1,
            "serverTime": iso(self.server_ms),
            "deviceTime": iso(self.device_ms),
            "fixTime": iso(self.fix_ms),
            "valid": True,
            "outdated": self.outdated,
            "latitude": round(self.lat, 7),
            "longitude": round(self.lon, 7),
            "speed": self.speed_kn,
            "course": self.course,
            "accuracy": self.accuracy,
            "attributes": self.attributes,
        }


def iso(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, CST).isoformat(timespec="milliseconds")


def _attributes(params: dict[str, str]) -> dict:
    """OsmAnd 解码器怎么存参数：batt → batteryLevel，charge → charge，其余不认识的数字串存成小数"""
    known = {"id", "lat", "lon", "timestamp", "accuracy", "speed", "bearing", "batt", "charge"}
    attrs: dict = {}
    if "batt" in params:
        attrs["batteryLevel"] = float(params["batt"])
    if "charge" in params:
        attrs["charge"] = params["charge"] == "true"
    for k, v in params.items():
        if k in known:
            continue
        try:
            attrs[k] = float(v)
        except ValueError:
            attrs[k] = {"true": True, "false": False}.get(v, v)
    return attrs


def mark_pushed(stored: list[Stored]) -> None:
    """按服务器收到的先后，标出哪些点换成了设备最新位置、推给了网页（定位时间不早于当时的最新位置）"""
    last: Stored | None = None
    for p in sorted(stored, key=lambda p: (p.server_ms, p.id)):
        p.pushed = last is None or p.fix_ms >= last.fix_ms
        if p.pushed:
            last = p


def process(
    reports: list[Report], start_ms: int, device_id: str = "sim"
) -> tuple[list[Stored], list[tuple[Report, str]]]:
    """按到达顺序处理上报，返回（存下的点，被丢掉的上报和原因）"""
    stored: list[Stored] = []
    dropped: list[tuple[Report, str]] = []
    last: Stored | None = None
    for r in sorted(reports, key=lambda r: (r.arrive, r.seq)):
        params = r.params(device_id)
        server_ms = start_ms + int(r.arrive * 1000)
        device_ms = int(params["timestamp"]) * 1000
        attrs = _attributes(params)
        if r.fix:
            p = Stored(
                0,
                r,
                server_ms,
                device_ms,
                device_ms,
                r.fix.lat,
                r.fix.lon,
                float(params["accuracy"]),
                float(params.get("speed", 0)),
                float(params.get("bearing", 0)),
                False,
                attrs,
            )
        elif last is not None:
            p = Stored(
                0,
                r,
                server_ms,
                device_ms,
                last.fix_ms,
                last.lat,
                last.lon,
                last.accuracy,
                last.speed_kn,
                last.course,
                True,
                attrs,
            )
        else:
            dropped.append((r, "no-last"))
            continue
        if p.fix_ms > server_ms + FILTER_FUTURE * 1000:
            dropped.append((r, "future"))
            continue
        p.id = len(stored) + 1
        stored.append(p)
        if last is None or p.fix_ms >= last.fix_ms:
            last = p
    mark_pushed(stored)
    return stored, dropped
