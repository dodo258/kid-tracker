"""把一个场景算成全套数据并写盘：真实轨迹、手机上报、服务器存下的点（家长页 fixtures 格式）、被服务器丢掉的、传感器时间线。"""

import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import scenario
import sensors
import traccar
from phone import Phone, Report
from scenario import Scenario
from traccar import Stored

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "parent-web" / "fixtures"
FIXTURE_DIR = FIXTURES / "sim"


@dataclass
class Bundle:
    sc: Scenario
    reports: list[Report]
    stored: list[Stored]
    dropped: list[tuple[Report, str]]


def _ms(s: str) -> int:
    return int(datetime.fromisoformat(s).timestamp() * 1000)


def load_real(sc: Scenario) -> list[Stored]:
    """真实回放：读 fixtures 里服务器真实存下的点（Traccar /api/positions 格式）"""
    rows = json.loads((FIXTURES / sc.real).read_text())
    out = []
    for x in rows:
        server = _ms(x.get("serverTime") or x["deviceTime"])
        device, fix = _ms(x["deviceTime"]), _ms(x["fixTime"])
        out.append(
            Stored(
                x["id"],
                None,
                server,
                device,
                fix,
                x["latitude"],
                x["longitude"],
                x.get("accuracy", 0.0),
                x.get("speed", 0.0),
                x.get("course", 0.0),
                device - fix > 60_000,
                x.get("attributes", {}),
            )
        )
    traccar.mark_pushed(out)
    return out


def build(sc: Scenario) -> Bundle:
    if sc.real:
        return Bundle(sc, [], load_real(sc), [])
    reports = Phone(sc).reports()
    stored, dropped = traccar.process(reports, sc.start_ms)
    return Bundle(sc, reports, stored, dropped)


def fixture_text(stored: list[Stored]) -> str:
    """和 fixtures/p20.json 一样一行一个点"""
    return "[\n" + ",\n".join(json.dumps(s.fixture(), ensure_ascii=False) for s in stored) + "\n]\n"


def report_dict(r: Report) -> dict:
    f = r.fix
    return {
        "seq": r.seq,
        "t": r.t,
        "arrive": round(r.arrive, 2),
        "timestamp": traccar.iso(r.timestamp_ms),
        "state": r.state,
        "lat": round(f.lat, 7) if f else None,
        "lon": round(f.lon, 7) if f else None,
        "accuracy": round(f.acc, 1) if f else None,
        "provider": f.provider if f else None,
        "speed": f.speed if f else None,
        "bearing": f.bearing if f else None,
        "batt": round(r.batt, 1),
        "charge": r.charge,
        "steps": r.steps,
        "bumps": r.bumps,
        "wifiType": r.wifi,
        "net": r.net,
        "tags": r.tags,
    }


def window(sc: Scenario) -> tuple[str, str]:
    """家长页回放的 from / to：第一条上报 0.3 秒到，from 取 1 秒，打开时最新位置就是第一条"""
    if sc.real:
        return sc.start.isoformat(), sc.end.isoformat()
    return traccar.iso(sc.start_ms + 1000), traccar.iso(sc.start_ms + sc.duration * 1000)


def write(b: Bundle, out: Path) -> Path:
    """写到 out/：truth.json、reports.json、dropped.json、positions.json（fixtures）、sensors.csv；返回 positions.json"""
    out.mkdir(parents=True, exist_ok=True)
    sc = b.sc
    if sc.real:
        return FIXTURES / sc.real
    truth = [[s.t, round(s.lat, 7), round(s.lon, 7), s.kind, s.moving] for s in sc.truth]
    (out / "truth.json").write_text(json.dumps({"start": sc.start.isoformat(), "samples": truth}))
    (out / "reports.json").write_text(json.dumps([report_dict(r) for r in b.reports], ensure_ascii=False, indent=0))
    (out / "dropped.json").write_text(
        json.dumps([{**report_dict(r), "reason": why} for r, why in b.dropped], ensure_ascii=False, indent=0)
    )
    (out / "sensors.csv").write_text("\n".join(sensors.timeline(sc, Phone(sc))) + "\n")
    positions = out / "positions.json"
    positions.write_text(fixture_text(b.stored))
    return positions


# 和上报端 v2 判断层对齐的金样本（见 reporter.py）：不带模拟器自己的干扰（旧版误判、华为推迟呼吸），判断层原样跑的场景
GOLDEN_DIR = ROOT / "app-v2/sdk/core/src/commonTest/resources/simulator"
GOLDEN_SCENARIOS = [
    "airplane-20min",
    "arrive-wifi-wrong",
    "bus-lights-tunnel",
    "car-wifi-wait",
    "fresh-install-indoor",
    "gps-cold-start",
    "home-net-only",
    "process-killed",
    "still-after-update",
    "walk-school-jumps",
]


def golden_text(name: str) -> str:
    """一个场景的金样本：大写开头是喂给判断层的输入，小写开头是它该给的判断（格式见 phone.golden_*）"""
    sc = scenario.load(name)
    log: list[str] = [f"# {name}：{sc.title}", "# python3 tools/simulator/sim.py golden 生成，别手改"]
    Phone(sc).reports(log)
    return "\n".join(log) + "\n"
