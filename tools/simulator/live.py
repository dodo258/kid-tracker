"""实时上报和服务器核对。

- live：按真实时间把场景里手机会报的点用 OsmAnd 协议 POST 到服务器（表单参数和 App 的 HttpUploader 一样），
  逐条记下发送时间和响应，写 sent.jsonl。默认只允许本机地址；要报到生产必须加 --prod，且设备号只能是测试设备 90000001、
  地址只能是生产上报地址，防止误报到孩子的设备上。
- verify：从生产服务器取回测试设备存下的点（服务器上拷一份 H2 数据库到 /tmp 只读打开，查完删掉），
  按「实际发出去的上报 + 服务器模型」算出应该存下哪些，对照有没有丢、有没有多、顺序对不对、呼吸点有没有抄上一个坐标，
  再查测试设备没有关联任何 Telegram 提醒。
  运行中的 H2 文件直接拷，副本可能缺最后写的几行（H2 不支持这样备份）：数据库副本里没有、只在服务器日志里有的点，
  日志一行只能顶一个点，记成「待确认」，结果写「待确认」不写「一致」，过几分钟用 `sim.py verify <目录>` 再核对一次。
"""

import argparse
import csv
import io
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

import scenario
import traccar
from build import Bundle, build, report_dict, write
from phone import Report

PROD_URL = "https://gps.example.com/osmand"
PROD_DEVICE = "90000001"
LOCAL_URL = "http://127.0.0.1:5055"
LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1"}
SSH = ["ssh", "-i", str(Path.home() / ".ssh/tracker_ed25519"), "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes"]
SSH_HOST = "root@203.0.113.10"
CST = timezone(timedelta(hours=8))
HTTP_TIMEOUT = 10


class GuardError(Exception):
    pass


def guard(url: str | None, device: str, prod: bool) -> str:
    """返回要上报的地址；不安全的组合直接报错"""
    if prod:
        if device != PROD_DEVICE:
            raise GuardError(f"--prod 只能报给测试设备 {PROD_DEVICE}，不能报给 {device!r}")
        if url not in (None, PROD_URL):
            raise GuardError(f"--prod 只能报到 {PROD_URL}")
        return PROD_URL
    target = url or LOCAL_URL
    host = urllib.parse.urlsplit(target).hostname
    if host not in LOCAL_HOSTS:
        raise GuardError(f"{target} 不是本机地址；报到生产要加 --prod（只认测试设备 {PROD_DEVICE}）")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", device):
        raise GuardError(f"设备号 {device!r} 不合法")
    return target


@dataclass
class Sent:
    seq: int
    at: float  # 真实发出时刻（Unix 秒）
    status: int  # HTTP 状态码，0 是网络出错
    error: str


def post(url: str, params: dict[str, str]) -> tuple[int, str]:
    data = urllib.parse.urlencode(params).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
            return resp.status, ""
    except urllib.error.HTTPError as e:
        return e.code, str(e)
    except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
        return 0, str(e)


def run_live(b: Bundle, url: str, device: str, out: Path, speed: float = 1.0) -> list[Sent]:
    """按到达时刻把每条上报发出去（speed>1 只给本机自测用）；边发边写 sent.jsonl"""
    t0 = time.time()
    sent: list[Sent] = []
    reports = sorted(b.reports, key=lambda r: (r.arrive, r.seq))
    with (out / "sent.jsonl").open("w") as log:
        for i, r in enumerate(reports):
            wait = t0 + r.arrive / speed - time.time()
            if wait > 0:
                time.sleep(wait)
            at = time.time()
            status, err = post(url, r.params(device))
            s = Sent(r.seq, at, status, err)
            sent.append(s)
            log.write(json.dumps({**s.__dict__, "report": report_dict(r)}, ensure_ascii=False) + "\n")
            log.flush()
            mark = "ok" if 200 <= status < 300 else f"失败 {status} {err}"
            print(
                f"[{i + 1}/{len(reports)}] {datetime.fromtimestamp(at, CST):%H:%M:%S} {r.state:6} "
                f"{'有坐标' if r.fix else '只带时间'} {','.join(r.tags)} {mark}",
                flush=True,
            )
    return sent


# ---- 取回生产服务器存下的点 ----

REMOTE = r"""set -eu
d=$(mktemp -d /tmp/simchk.XXXXXX)
trap 'rm -rf "$d"' EXIT
cp /opt/traccar/data/database.mv.db "$d/db.mv.db"
q() { /opt/traccar/jre/bin/java -cp /opt/traccar/lib/h2-*.jar org.h2.tools.Shell -url "jdbc:h2:$d/db;ACCESS_MODE_DATA=r" -user sa -password "" -sql "$1" >/dev/null; }
q "CALL CSVWRITE('$d/pos.csv', 'SELECT p.id, p.servertime, p.devicetime, p.fixtime, p.latitude, p.longitude, p.accuracy, p.attributes FROM tc_positions p JOIN tc_devices d ON p.deviceid = d.id WHERE d.uniqueid = ''@DEVICE@'' AND p.servertime >= ''@SINCE@'' AND p.servertime <= ''@UNTIL@'' ORDER BY p.id')"
q "CALL CSVWRITE('$d/ev.csv', 'SELECT e.id, e.type, e.eventtime FROM tc_events e JOIN tc_devices d ON e.deviceid = d.id WHERE d.uniqueid = ''@DEVICE@'' AND e.eventtime >= ''@SINCE@'' AND e.eventtime <= ''@UNTIL@'' ORDER BY e.id')"
q "CALL CSVWRITE('$d/nt.csv', 'SELECT (SELECT COUNT(*) FROM tc_notifications WHERE always) AS always_on, (SELECT COUNT(*) FROM tc_device_notification x JOIN tc_devices d ON x.deviceid = d.id WHERE d.uniqueid = ''@DEVICE@'') AS device_links, (SELECT COUNT(*) FROM tc_group_notification x JOIN tc_devices d ON x.groupid = d.groupid WHERE d.uniqueid = ''@DEVICE@'') AS group_links')"
echo '@@pos'; cat "$d/pos.csv"; echo
echo '@@ev'; cat "$d/ev.csv"; echo
echo '@@nt'; cat "$d/nt.csv"; echo
echo '@@accepted'; grep -h "\] id: @DEVICE@, time: " /opt/traccar/logs/tracker-server.log 2>/dev/null | awk -v s='@SINCE@' -v u='@UNTIL@' '$0 >= s && $0 <= u' || true
echo '@@log'; grep -h "filtered.*device: @DEVICE@" /opt/traccar/logs/tracker-server.log 2>/dev/null | awk -v s='@SINCE@' -v u='@UNTIL@' '$0 >= s && $0 <= u' || true
"""


def fetch_server(since: datetime, until: datetime, device: str = PROD_DEVICE) -> dict[str, list]:
    """SSH 到生产服务器，查测试设备 since–until 存下的点、事件、提醒关联、过滤日志"""
    if not re.fullmatch(r"\d{1,20}", device):
        raise GuardError(f"设备号 {device!r} 不合法")
    fmt = "%Y-%m-%d %H:%M:%S"
    script = (
        REMOTE.replace("@DEVICE@", device)
        .replace("@SINCE@", since.astimezone(CST).strftime(fmt))
        .replace("@UNTIL@", until.astimezone(CST).strftime(fmt))
    )
    proc = subprocess.run([*SSH, SSH_HOST, "bash -s"], input=script, capture_output=True, text=True, timeout=180)
    if proc.returncode != 0:
        raise RuntimeError(f"取服务器数据失败（退出码 {proc.returncode}）：{proc.stderr.strip()[-500:]}")
    parts: dict[str, list] = {}
    name = None
    buf: list[str] = []
    for line in proc.stdout.splitlines() + ["@@end"]:
        if line.startswith("@@"):
            if name:
                text = "\n".join(buf).strip()
                parts[name] = (
                    text.splitlines() if name in ("log", "accepted") else list(csv.DictReader(io.StringIO(text)))
                )
            name, buf = line[2:], []
        else:
            buf.append(line)
    return parts


def _ms(s: str) -> int:
    return int(datetime.fromisoformat(s).replace(tzinfo=CST).timestamp() * 1000)


def compare(b: Bundle, sent: list[Sent], server: dict[str, list]) -> tuple[list[str], list[str], list[str]]:
    """返回（不一致的问题，待确认的点，说明）。按实际发出时刻重放服务器模型，和服务器真存下的逐条对照"""
    by_seq = {r.seq: r for r in b.reports}
    delivered: list[Report] = []
    problems: list[str] = []
    pending: list[str] = []
    notes: list[str] = []
    failed = [s for s in sent if not 200 <= s.status < 300]
    if failed:
        problems.append(f"{len(failed)} 条上报没发成功（网络或服务器报错），例：{failed[0].error or failed[0].status}")
    for s in sent:
        if 200 <= s.status < 300:
            r = by_seq[s.seq]
            delivered.append(Report(**{**r.__dict__, "arrive": s.at - b.sc.start_ms / 1000}))
    expect, dropped = traccar.process(delivered, b.sc.start_ms)
    rows = server.get("pos", [])
    actual = [
        {
            "id": int(x["ID"]),
            "device_ms": _ms(x["DEVICETIME"]),
            "fix_ms": _ms(x["FIXTIME"]),
            "lat": float(x["LATITUDE"]),
            "lon": float(x["LONGITUDE"]),
            "attrs": json.loads(x["ATTRIBUTES"] or "{}"),
        }
        for x in rows
    ]
    notes.append(
        f"发出 {len(sent)} 条，成功 {len(delivered)} 条；模型预计存 {len(expect)} 条、丢 {len(dropped)} 条；服务器实际存 {len(actual)} 条"
    )
    for r, why in dropped:
        notes.append(
            f"模型预计被服务器丢掉：{datetime.fromtimestamp(r.timestamp_ms / 1000, CST):%H:%M:%S} {r.state} "
            f"{'有坐标' if r.fix else '只带时间'}（{why}）"
        )
    # 服务器日志里每个通过过滤、处理完的点有一行「id: 设备号, time: 定位时间, lat: …, lon: …」（被过滤的没有）
    accepted = []
    for line in server.get("accepted", []):
        m = re.search(r"time: (\S+ \S+), lat: ([-\d.]+), lon: ([-\d.]+)", line)
        if m:
            accepted.append((_ms(m.group(1)), float(m.group(2)), float(m.group(3))))
    used = set()
    used_log: set[int] = set()
    matched_ids: list[int] = []
    for e in expect:
        match = next(
            (
                i
                for i, a in enumerate(actual)
                if i not in used
                and a["device_ms"] == e.device_ms
                and a["attrs"].get("state") == e.attributes.get("state")
                and abs(a["lat"] - e.lat) < 1e-6
                and abs(a["lon"] - e.lon) < 1e-6
            ),
            None,
        )
        if match is None:
            # 数据库副本里没有：服务器日志里有一行对得上（一行只能顶一个点）就记待确认，不能当成已入库
            log = next(
                (
                    i
                    for i, (t, la, lo) in enumerate(accepted)
                    if i not in used_log
                    and t in (e.fix_ms, e.device_ms)
                    and abs(la - e.lat) < 2e-5
                    and abs(lo - e.lon) < 2e-5
                ),
                None,
            )
            if log is not None:
                used_log.add(log)
                pending.append(
                    f"{traccar.iso(e.device_ms)} {e.attributes.get('state')}{'（只带时间）' if e.outdated else ''}"
                    "：服务器日志里处理过，数据库副本里还没有"
                )
                continue
        if match is None:
            problems.append(
                f"应该存下但服务器上没有：{traccar.iso(e.device_ms)} {e.attributes.get('state')}"
                f"{'（只带时间）' if e.outdated else ''}"
            )
            continue
        used.add(match)
        a = actual[match]
        matched_ids.append(a["id"])
        if a["fix_ms"] != e.fix_ms:
            problems.append(
                f"{traccar.iso(e.device_ms)} 定位时间不对：服务器 {traccar.iso(a['fix_ms'])}，预计 {traccar.iso(e.fix_ms)}"
            )
    if pending:
        notes.append(
            f"{len(pending)} 条只有服务器日志能证明（运行中拷出来的 H2 副本缺最后写的几行，或者真没入库），"
            "过几分钟用 sim.py verify <目录> 再核对"
        )
    extra = [a for i, a in enumerate(actual) if i not in used]
    for a in extra:
        problems.append(
            f"服务器多存了：{traccar.iso(a['device_ms'])} {a['attrs'].get('state')}（模型预计会被丢掉或没发）"
        )
    if matched_ids != sorted(matched_ids):
        problems.append("服务器存的顺序和发出的顺序不一致")
    no_state = [a for a in actual if "state" not in a["attrs"]]
    if no_state:
        problems.append(f"{len(no_state)} 个存下的点没有 state 属性")
    nt = (server.get("nt") or [{}])[0]
    links = {k: int(nt.get(k.upper(), "-1")) for k in ("always_on", "device_links", "group_links")}
    if any(v != 0 for v in links.values()):
        problems.append(
            f"测试设备可能推 Telegram：全局提醒 {links['always_on']} 条、设备关联 {links['device_links']} 条、"
            f"分组关联 {links['group_links']} 条"
        )
    else:
        notes.append("测试设备没有关联任何提醒（全局 0、设备 0、分组 0），不会推 Telegram")
    events = server.get("ev", [])
    if events:
        notes.append("服务器事件：" + "、".join(f"{x['TYPE']} {x['EVENTTIME'][11:19]}" for x in events))
    if server.get("log"):
        notes.append(f"服务器日志里被过滤的：{len(server['log'])} 条，例：{server['log'][0][-120:]}")
    return problems, pending, notes


def verdict(problems: list[str], pending: list[str]) -> str:
    return "**不一致**" if problems else "待确认" if pending else "一致"


def verify_md(name: str, problems: list[str], pending: list[str], notes: list[str]) -> str:
    lines = [f"# 实时上报核对：{name}", "", f"结果：{verdict(problems, pending)}", ""]
    lines += [f"- 问题：{p}" for p in problems]
    lines += [f"- 待确认：{p}" for p in pending]
    lines += [f"- {n}" for n in notes]
    return "\n".join(lines) + "\n"


def load_sent(path: Path) -> list[Sent]:
    out = []
    for line in path.read_text().splitlines():
        x = json.loads(line)
        out.append(Sent(x["seq"], x["at"], x["status"], x["error"]))
    return out


def cmd_live(a: argparse.Namespace) -> int:
    try:
        url = guard(a.url, a.device, a.prod)
        if a.prod and a.speed != 1:
            raise GuardError("--prod 只能按真实时间报（服务器按自己的时钟过滤和判失联）")
    except GuardError as e:
        print(f"拒绝：{e}", file=sys.stderr)
        return 2
    start = datetime.now(CST).replace(microsecond=0) + timedelta(seconds=2)
    sc = scenario.load(a.scenario, start=start)
    b = build(sc)
    out = (
        Path(a.out).resolve()
        if a.out
        else Path(__file__).resolve().parents[2] / "build" / "simulator" / f"live-{sc.name}-{start:%m%d-%H%M%S}"
    )
    write(b, out)
    (out / "meta.json").write_text(
        json.dumps({"scenario": sc.name, "start": start.isoformat(), "url": url, "device": a.device})
    )
    print(
        f"{sc.name}：{len(b.reports)} 条，约 {sc.duration / 60 / a.speed:.0f} 分钟，报到 {url} 设备 {a.device}；记录在 {out}"
    )
    while time.time() < start.timestamp():
        time.sleep(0.05)
    sent = run_live(b, url, a.device, out, a.speed)
    if not a.prod:
        return 0 if all(200 <= s.status < 300 for s in sent) else 1
    return verify_dir(out)


def verify_dir(out: Path) -> int:
    meta = json.loads((out / "meta.json").read_text())
    if meta["url"] != PROD_URL or meta["device"] != PROD_DEVICE:
        print(f"只核对报到生产测试设备 {PROD_DEVICE} 的记录", file=sys.stderr)
        return 2
    start = datetime.fromisoformat(meta["start"])
    b = build(scenario.load(meta["scenario"], start=start))
    sent = load_sent(out / "sent.jsonl")
    last = datetime.fromtimestamp(max(s.at for s in sent), CST)
    server = fetch_server(start - timedelta(seconds=5), last + timedelta(seconds=3), meta["device"])
    problems, pending, notes = compare(b, sent, server)
    md = verify_md(meta["scenario"], problems, pending, notes)
    (out / "server.json").write_text(json.dumps(server, ensure_ascii=False, indent=1))
    (out / "verify.md").write_text(md)
    print(md)
    return 1 if problems or pending else 0


def cmd_verify(a: argparse.Namespace) -> int:
    return verify_dir(Path(a.dir).resolve())


def add_commands(sub) -> None:
    lv = sub.add_parser("live", help="按真实时间上报一个场景")
    lv.add_argument("scenario")
    lv.add_argument("--device", required=True, help=f"设备号；--prod 时只能是 {PROD_DEVICE}")
    lv.add_argument("--url", help=f"上报地址，默认 {LOCAL_URL}（本机 Traccar）")
    lv.add_argument("--prod", action="store_true", help=f"报到生产 {PROD_URL}，只认测试设备 {PROD_DEVICE}")
    lv.add_argument("--speed", type=float, default=1.0, help="加速（只能本机自测用）")
    lv.add_argument("--out")
    lv.set_defaults(fn=cmd_live)
    vf = sub.add_parser("verify", help="重新核对一次 live --prod 的记录目录")
    vf.add_argument("dir", help="live 写的记录目录（含 meta.json、sent.jsonl）")
    vf.set_defaults(fn=cmd_verify)
