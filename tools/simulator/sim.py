#!/usr/bin/env python3
"""模拟上报端：按场景造「真实轨迹 + 手机会报的点 + 传感器时间线」，回放检查家长页，或按真实时间报给服务器。

  python3 tools/simulator/sim.py list                       看有哪些场景
  python3 tools/simulator/sim.py generate [场景…] [--out 目录]  只生成数据（真实轨迹、上报、服务器存的点、传感器 CSV）
  python3 tools/simulator/sim.py export [场景…]              把服务器存的点导成家长页 fixtures（parent-web/fixtures/sim/）
  python3 tools/simulator/sim.py replay [场景…] [--web 目录]  无头 Chrome 回放，逐帧对比真实轨迹，出通过 / 不通过
  python3 tools/simulator/sim.py live 场景 --device 号 [--url 地址] [--prod]   按真实时间上报（默认本机 Traccar）
  python3 tools/simulator/sim.py verify --since 时间 [--prod] 取回生产服务器存下的测试设备的点，和发送记录核对

详细说明见 README.md。
"""

import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

import build
import checks
import live
import scenario
import shots

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parents[1]
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
MAX_SHOTS = 8


def out_dir(arg: str | None, kind: str) -> Path:
    if arg:
        return Path(arg).resolve()
    art = os.environ.get("AGENTQ_ARTIFACTS")
    if art:
        return Path(art).resolve() / f"simulator-{kind}"
    return ROOT / "build" / "simulator" / f"{kind}-{datetime.now():%m%d-%H%M%S}"


def pick(names: list[str]) -> list[scenario.Scenario]:
    return [scenario.load(n) for n in (names or scenario.names())]


def cmd_list(_: argparse.Namespace) -> int:
    for n in scenario.names():
        sc = scenario.load(n)
        print(f"{n:22} {sc.duration / 60:5.1f} 分钟  {sc.title}")
    return 0


def cmd_generate(a: argparse.Namespace) -> int:
    out = out_dir(a.out, "data")
    for sc in pick(a.scenarios):
        if sc.real:
            continue
        b = build.build(sc)
        build.write(b, out / sc.name)
        print(
            f"{sc.name}：{len(b.reports)} 条上报，服务器存 {len(b.stored)} 条，丢 {len(b.dropped)} 条 → {out / sc.name}"
        )
    return 0


def cmd_export(a: argparse.Namespace) -> int:
    build.FIXTURE_DIR.mkdir(parents=True, exist_ok=True)
    for sc in pick(a.scenarios):
        if sc.real:
            continue  # 真实回放本来就读 fixtures 里的文件
        b = build.build(sc)
        path = build.FIXTURE_DIR / f"{sc.name}.json"
        path.write_text(build.fixture_text(b.stored))
        print(f"{path.relative_to(ROOT)}：{len(b.stored)} 个点")
    return 0


def shot_times(sc: scenario.Scenario) -> list[int]:
    """截图时刻（场景秒）：每段开始后 40 秒、每个干扰开始和结束后 20 秒，最多 MAX_SHOTS 张"""
    ts = set()
    for s in sc.truth:
        if s.t > 0 and sc.truth[s.t - 1].seg != s.seg:
            ts.add(s.t + 40)
    for e in sc.events:
        for k in ("at", "from", "to"):
            if k in e and e[k] < sc.duration:
                ts.add(int(e[k]) + 20)
    ts = sorted(x for x in ts if 0 < x < sc.duration)
    if len(ts) > MAX_SHOTS:
        step = len(ts) / MAX_SHOTS
        ts = [ts[int(i * step)] for i in range(MAX_SHOTS)]
    return ts


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def start_vite(web: Path, log: Path) -> tuple[subprocess.Popen, str]:
    if not (web / "node_modules").is_dir():
        subprocess.run(["npm", "ci", "--no-audit", "--no-fund", "--loglevel=error"], cwd=web, check=True, timeout=600)
    port = free_port()
    url = f"http://127.0.0.1:{port}/m/"
    with log.open("w") as f:
        proc = subprocess.Popen(
            ["npx", "vite", "--mode", "fixtures", "--port", str(port), "--strictPort"], cwd=web, stdout=f, stderr=f
        )
    for _ in range(120):
        try:
            with urllib.request.urlopen(url, timeout=2):
                return proc, url
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            time.sleep(0.5)
    proc.terminate()
    raise RuntimeError(f"家长页 dev server 没起来，见 {log}")


def report_md(results: list[checks.Result], web: Path, out: Path) -> str:
    lines = [
        "# 回放检查报告",
        "",
        f"- 家长页：`{web}`（{git_head(web)}）",
        f"- 时间：{datetime.now():%Y-%m-%d %H:%M}；输出：`{out}`",
        "",
        "| 场景 | 结果 | 上报 / 服务器存 / 丢 | 偏离中位 / 95% / 最大（米） | 圆点落后中位 / 最大（秒） |",
        "|---|---|---|---|---|",
    ]
    for r in results:
        s = r.stats
        lines.append(
            f"| {r.name} | {'通过' if r.ok else '**不通过**'} | {s.get('reports')} / {s.get('stored')} / {s.get('dropped')} | "
            f"{s.get('dev_p50', '-')} / {s.get('dev_p95', '-')} / {s.get('dev_max', '-')} | {s.get('lag_p50', '-')} / {s.get('lag_max', '-')} |"
        )
    for r in results:
        lines += ["", f"## {r.name}：{'通过' if r.ok else '不通过'}", "", r.title, ""]
        lines += [f"- {'通过' if c.ok else '**不通过**'}　{c.name}：{c.detail}" for c in r.checks]
        if r.shots:
            lines += ["", "截图：" + "、".join(f"`{r.name}/{s['file']}`" for s in r.shots)]
    return "\n".join(lines) + "\n"


def git_head(path: Path) -> str:
    try:
        out = subprocess.run(
            ["git", "-C", str(path), "log", "-1", "--format=%h %s"],
            capture_output=True,
            text=True,
            timeout=10,
            check=True,
        )
        return out.stdout.strip()
    except (subprocess.SubprocessError, OSError):
        return "不是 git 目录"


def cmd_replay(a: argparse.Namespace) -> int:
    if not Path(CHROME).exists():
        print(f"没有 Chrome：{CHROME}")
        return 125
    web = Path(a.web).resolve()
    out = out_dir(a.out, "replay")
    out.mkdir(parents=True, exist_ok=True)
    runs = []
    for sc in pick(a.scenarios):
        d = out / sc.name
        fixture = build.write(build.build(sc), d)
        speed = a.speed or sc.speed
        frm, to = build.window(sc)
        runs.append(
            {
                "name": sc.name,
                "fixture": str(fixture),
                "from": frm,
                "to": to,
                "speed": speed,
                "shots": [sc.start_ms + t * 1000 for t in shot_times(sc)],
                "out": str(d),
            }
        )
    job = out / "job.json"
    proc, url = start_vite(web, out / "vite.log")
    try:
        job.write_text(
            json.dumps({"url": url, "cdp": str(web / "scripts" / "cdp.mjs"), "runs": runs}, ensure_ascii=False)
        )
        total = sum(scenario.load(r["name"]).duration / r["speed"] for r in runs)
        print(f"回放 {len(runs)} 个场景，约 {total / 60:.0f} 分钟……")
        subprocess.run(["node", str(TOOLS / "replay.mjs"), str(job)], check=True, timeout=total + 600)
    finally:
        proc.terminate()
        proc.wait(timeout=10)
    return judge_dir(out, web)


def judge_dir(out: Path, web: Path) -> int:
    """按 job.json 和各场景的 trace.json 判通过不通过，写 report.md、result.json；有不通过的返回 1"""
    runs = json.loads((out / "job.json").read_text())["runs"]
    results = []
    for run in runs:
        b = build.build(scenario.load(run["name"]))
        trace = json.loads((Path(run["out"]) / "trace.json").read_text())
        r = checks.judge(b, trace, run["speed"])
        shots.check(r, Path(run["out"]))
        results.append(r)
    (out / "report.md").write_text(report_md(results, web, out))
    (out / "result.json").write_text(
        json.dumps(
            [
                {
                    "scenario": r.name,
                    "ok": r.ok,
                    "stats": r.stats,
                    "checks": [c.__dict__ for c in r.checks],
                    "shots": r.shots,
                }
                for r in results
            ],
            ensure_ascii=False,
            indent=2,
        )
    )
    print()
    for r in results:
        print(f"{'通过  ' if r.ok else '不通过'} {r.name}")
        for c in r.checks:
            if not c.ok:
                print(f"        ✗ {c.name}：{c.detail}")
    print(f"\n报告：{out / 'report.md'}")
    return 0 if all(r.ok for r in results) else 1


def cmd_golden(_: argparse.Namespace) -> int:
    build.GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    for name in build.GOLDEN_SCENARIOS:
        path = build.GOLDEN_DIR / f"{name}.golden"
        path.write_text(build.golden_text(name))
        print(f"{path.relative_to(ROOT)}")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list").set_defaults(fn=cmd_list)
    g = sub.add_parser("generate")
    g.add_argument("scenarios", nargs="*")
    g.add_argument("--out")
    g.set_defaults(fn=cmd_generate)
    e = sub.add_parser("export")
    e.add_argument("scenarios", nargs="*")
    e.set_defaults(fn=cmd_export)
    r = sub.add_parser("replay")
    r.add_argument("scenarios", nargs="*")
    r.add_argument("--web", default=str(ROOT / "parent-web"), help="家长页目录（默认本仓库 parent-web）")
    r.add_argument("--out")
    r.add_argument("--speed", type=float, help="统一的回放倍速（默认按场景文件）")
    r.set_defaults(fn=cmd_replay)
    jd = sub.add_parser("judge", help="不重跑浏览器，按 replay 的输出目录重新判")
    jd.add_argument("dir")
    jd.add_argument("--web", default=str(ROOT / "parent-web"), help="报告里写的家长页目录")
    jd.set_defaults(fn=lambda a: judge_dir(Path(a.dir).resolve(), Path(a.web).resolve()))
    sub.add_parser("golden", help="重新生成和上报端 v2 判断层对齐的金样本").set_defaults(fn=cmd_golden)
    live.add_commands(sub)
    a = p.parse_args()
    if shutil.which("node") is None and a.cmd == "replay":
        print("没有 node")
        return 125
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
