"""传感器时间线：和 P20 上「传感器录制（测试用）」写的 CSV 同一个格式（每 2 秒一行，表头见 app-v2/sdk 的 SnapshotCsv.COLUMNS），
手机（phone.py）把同一份时间线喂给判断层（reporter.py），和上报端 v2 一样按它开卫星、上报。数值按活动类型给典型量级，带一点随机。
"""

from __future__ import annotations

import random
from datetime import datetime, timedelta, timezone
from typing import TYPE_CHECKING

from reporter import Snap
from scenario import MOVING_KINDS, Scenario

if TYPE_CHECKING:
    from phone import Phone

HEADER = (
    "time,screen,charging,battery,airplane,"
    "acc_mean,acc_std,acc_max,acc_n,gyro_mean,gyro_max,heading,heading_n,"
    "steps_total,step_events,proximity,light,pressure,"
    "net,wifi_bssid,wifi_ssid,wifi_rssi,wifi_metered,scan_n,scan_overlap,"
    "fix_provider,fix_time,fix_lat,fix_lon,fix_acc,fix_speed,fix_bearing,"
    "acc_peaks,wifi_gateway,cell_tac,cell_id,"
    "sat_used,cn0_top4,mag_std,cell_dbm,"
    "net_link,elapsed,fix_elapsed"
)
COLUMNS = HEADER.split(",")
COL = {k: i for i, k in enumerate(COLUMNS)}
ROW_SECONDS = 2
CST = timezone(timedelta(hours=8))

# 每种活动的加速度（去掉重力，m/s²：均值、标准差、最大）和陀螺仪（rad/s：均值、最大）
ACC = {
    "still": (0.01, 0.02, 0.06),
    "indoor": (0.25, 1.1, 3.2),
    "walk": (0.5, 2.2, 6.0),
    "bike": (0.2, 1.1, 4.0),
    "bus": (0.05, 0.45, 1.8),
    "bus_stop": (0.02, 0.12, 0.5),
}
GYRO = {
    "still": (0.005, 0.02),
    "indoor": (0.5, 1.8),
    "walk": (0.8, 2.5),
    "bike": (0.4, 1.5),
    "bus": (0.08, 0.4),
    "bus_stop": (0.02, 0.1),
}
FIXED_BSSID_PREFIX = "3c:8c:93:10"


def fixed_bssid(place: int) -> str:
    """固定 Wi-Fi 的 BSSID：一个地方一个路由器（中间走过、骑过、坐过车就是换了地方）"""
    return f"{FIXED_BSSID_PREFIX}:{place:02x}:30"


def place_of(sc: Scenario, seg: int) -> int:
    """第几段在第几个地方：数这一段之前有几段在路上"""
    return sum(1 for s in sc.segments[:seg] if s.kind in MOVING_KINDS)


HOTSPOT_BSSID = "ae:12:34:56:78:9a"
# 车机自带的 Wi-Fi：跟着车走，名字、计费都不像热点，手机看来和家里路由器一样（方案 2026-11-19-moving-wifi）
CAR_BSSID = "3c:8c:93:20:00:01"
GATEWAY = {"fixed": "192.168.1.1", "hotspot": "192.168.43.1", "car": "192.168.1.1"}


def in_window(events: list[dict], t: float) -> dict | None:
    for e in events:
        if e["from"] <= t < e["to"]:
            return e
    return None


def _activity(kind: str, mps: float, walking: bool) -> str:
    if kind == "indoor":
        return "indoor" if walking else "still"
    if kind == "bus" and mps == 0:
        return "bus_stop"
    if kind in ("walk", "bike", "bus"):
        return kind
    return "still"


def timeline(sc: Scenario, phone: Phone) -> list[str]:
    """CSV 各行（含表头）。进程被杀时录制也停，那段没有行；飞行模式 airplane=1"""
    return [HEADER] + [",".join(cols) for _, cols in rows(sc, phone)]


def snap(t: int, cols: list[str]) -> Snap:
    """一行时间线做成判断层的快照（时间是离场景开始的毫秒；不带被动定位，卫星点由手机按判断层开没开卫星另给）"""

    def num(k: str) -> float | None:
        return float(cols[COL[k]]) if cols[COL[k]] else None

    net = cols[COL["net"]]
    return Snap(
        time=t * 1000,
        airplane=cols[COL["airplane"]] == "1",
        acc_mean=num("acc_mean"),
        acc_std=num("acc_std"),
        acc_n=int(cols[COL["acc_n"]]),
        acc_peaks=int(cols[COL["acc_peaks"]]) if cols[COL["acc_peaks"]] else None,
        gyro_mean=num("gyro_mean"),
        steps_total=int(cols[COL["steps_total"]]) if cols[COL["steps_total"]] else None,
        net=net,
        wifi_bssid=cols[COL["wifi_bssid"]],
        wifi_ssid=cols[COL["wifi_ssid"]],
        wifi_rssi=int(cols[COL["wifi_rssi"]]) if cols[COL["wifi_rssi"]] else None,
        wifi_metered={"1": True, "0": False}.get(cols[COL["wifi_metered"]]),
        wifi_gateway=cols[COL["wifi_gateway"]],
    )


def rows(sc: Scenario, phone: Phone) -> list[tuple[int, list[str]]]:
    """每 2 秒一行：(离场景开始的秒数, 各列文本)"""
    rng = random.Random(sc.seed * 31 + 7)
    out: list[tuple[int, list[str]]] = []
    steps_total = 12000.0
    last_fix = None
    for t in range(0, sc.duration, ROW_SECONDS):
        if in_window(sc.events_of("killed"), t):
            continue
        s = sc.at(t)
        seg = sc.segments[s.seg]
        act = _activity(s.kind, s.mps, s.walking)
        am, asd, amax = (v * rng.uniform(0.8, 1.2) for v in ACC[act])
        gm, gmax = (v * rng.uniform(0.8, 1.2) for v in GYRO[act])
        step_events = 0
        for i in range(ROW_SECONDS):
            x = sc.at(t + i)
            if x.walking:
                step_events += 1.8 if x.kind == "walk" else 1.2
        step_events = int(round(step_events))
        steps_total += step_events
        airplane = in_window(sc.events_of("airplane"), t) is not None
        offline = in_window(sc.events_of("offline"), t) is not None
        wifi = "none" if airplane else seg.wifi
        net = "none" if airplane or offline else ("wifi" if wifi in ("fixed", "hotspot", "car") else "cell")
        bssid = {"fixed": fixed_bssid(place_of(sc, s.seg)), "hotspot": HOTSPOT_BSSID, "car": CAR_BSSID}.get(wifi, "")
        ssid = {"fixed": "HOME-5G", "hotspot": "AndroidAP", "car": "CAR-8899"}.get(wifi, "")
        for i in range(ROW_SECONDS):
            f = phone.fix(t + i) if not airplane else None
            if f:
                last_fix = f
        clock = in_window(sc.events_of("clock"), t)
        skew = timedelta(seconds=int(clock["seconds"]) if clock else 0)
        stamp = datetime.fromtimestamp(sc.start_ms / 1000 + t, CST) + skew
        batt, charging = phone.batt(t)
        moving = s.moving
        cols = [
            stamp.strftime("%Y-%m-%d %H:%M:%S"),
            "0",
            "1" if charging else "0",
            str(int(batt)),
            "1" if airplane else "0",
            f"{am:.3f}",
            f"{asd:.3f}",
            f"{amax:.3f}",
            "50",
            f"{gm:.3f}",
            f"{gmax:.3f}",
            f"{(s.bearing + rng.gauss(0, 8)) % 360:.0f}",
            "10",
            f"{steps_total:.0f}",
            str(step_events),
            "5.0",
            "0" if moving and s.kind == "walk" else "120",
            f"{1013.2 + rng.gauss(0, 0.05):.2f}",
            net,
            bssid if net == "wifi" else "",
            ssid if net == "wifi" else "",
            str(-55 + rng.randint(-5, 5)) if net == "wifi" else "",
            ("1" if wifi == "hotspot" else "0") if net == "wifi" else "",
            str(14 if not moving else rng.randint(3, 20)),
            f"{(0.92 if not moving else 0.2) + rng.uniform(-0.05, 0.05):.2f}",
        ]
        if last_fix:
            ft = datetime.fromtimestamp(sc.start_ms / 1000 + last_fix.t, CST) + skew
            cols += [
                "gps" if last_fix.provider == "gps" else "network",
                ft.strftime("%Y-%m-%d %H:%M:%S"),
                f"{last_fix.lat:.6f}",
                f"{last_fix.lon:.6f}",
                f"{last_fix.acc:.0f}",
                f"{last_fix.speed:.1f}" if last_fix.speed is not None else "",
                f"{last_fix.bearing:.0f}" if last_fix.bearing is not None else "",
            ]
        else:
            cols += [""] * 7
        # 合走路节奏的加速度峰（SDK 的 GaitRhythm）：真走路时一步一个，别的活动没有节奏
        cols.append(str(step_events if act in ("walk", "indoor") else 0))
        cols.append(GATEWAY.get(wifi, "") if net == "wifi" else "")
        cols += ["", ""]  # 基站（跟踪区、小区号）：模拟的手机没卡，和 P20 一样读不到
        cols += ["", "", "", ""]  # 卫星数、载噪比、磁场波动、基站信号：判断层还不读，模拟的手机不造
        # 连接代次、开机时长：模拟的 Wi-Fi 不断开重连、不改钟（时间偏差只改时间列），判断层按时间判
        cols += ["", "", ""]
        out.append((t, cols))
    return out
