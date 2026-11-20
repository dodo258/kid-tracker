#!/usr/bin/env python3
"""三种状态（移动中 / 范围内活动 / 静止）的模拟器场景，按 docs/specs/2026-11-16-three-states.md「验收」做。

用法：tools/stability/three_states.sh [--apk <已构建的 debug APK>] [--scale 0.5]
在无 GMS 模拟器 gps-nogms 的一次性只读实例上跑，上报只发本机记录服务。时间线（scale=1 约 75 分钟）：

  1. 原地不动（不晃）：开始跟踪后 3 分钟内 MOVING → ZONE，再 10 分钟安静 → STILL；
  2. 静止 20 分钟、屏幕关、强制打盹：数定位请求、呼吸间隔、呼吸点不带坐标；
  3. 走路（一直晃、1.4 m/s 往东走 300 米）：STILL → ZONE → MOVING；
  4. 停下（不晃）：3 分钟后 MOVING → ZONE；
  5. 屋里走动（晃、位置 20 米内抖）：保持 ZONE；
  6. 安静 10 分钟：ZONE → STILL；
  7. 坐车（没有步数、只有轻微晃动，每分钟 500 米）：3 分钟内转 MOVING；
  8. 车停下：等转 ZONE。

结果写到 build/stability/three-states-<时间>/：points.jsonl、sdk-log.txt、logcat.txt、dumpsys-*.txt、report.md。
"""

import argparse
import json
import math
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import stability as st  # noqa: E402

PKG = st.PKG
START = (30.2741, 120.1551)


class Mover(threading.Thread):
    """每 step 秒发一次 geo fix；shake 时每 0.5 秒换一次加速度，模拟走路或车上的晃动。"""

    def __init__(self, emu):
        super().__init__(daemon=True)
        self.emu = emu
        self.lat, self.lon = START
        self.speed = 0.0  # m/s，往东
        self.shake = None  # None / "walk" / "car"
        self.jitter = 0.0  # 米，原地抖动
        self.step = 5
        self.stopped = threading.Event()
        self.lock = threading.Lock()
        self.shaker = threading.Thread(target=self._shake_loop, daemon=True)

    def set(self, speed=0.0, shake=None, jitter=0.0):
        with self.lock:
            self.speed, self.shake, self.jitter = speed, shake, jitter

    def _shake_loop(self):
        flip = False
        while not self.stopped.is_set():
            with self.lock:
                shake = self.shake
            if shake is None:
                self.emu.adb("emu", "sensor", "set", "acceleration", "0:9.81:0", timeout=15)
                self.stopped.wait(2)
                continue
            amp = 3.0 if shake == "walk" else 1.2
            flip = not flip
            x = amp if flip else -amp
            self.emu.adb("emu", "sensor", "set", "acceleration", f"{x:.2f}:{9.81 + x / 2:.2f}:{-x / 3:.2f}", timeout=15)
            self.stopped.wait(0.5)

    def run(self):
        self.shaker.start()
        while not self.stopped.is_set():
            with self.lock:
                speed, jitter = self.speed, self.jitter
            self.lon += speed * self.step / (111320 * math.cos(math.radians(self.lat)))
            lat, lon = self.lat, self.lon
            if jitter:
                a = time.time()
                lat += jitter * math.sin(a) / 111320
                lon += jitter * math.cos(a) / (111320 * math.cos(math.radians(lat)))
            self.emu.adb("emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}", timeout=15)
            self.stopped.wait(self.step)


class Scenario:
    def __init__(self, out, apk, scale):
        self.out, self.apk, self.scale = out, apk, scale
        self.device_id = str(80000000 + int(time.time()) % 9999999)
        self.events = []
        self.checks = []
        self.phases = []

    def mark(self, text):
        self.events.append((time.time(), text))
        st.say(f"{time.strftime('%H:%M:%S')} {text}")

    def check(self, desc, ok, detail=""):
        self.checks.append((desc, bool(ok), str(detail)))
        self.mark(f"{'✓' if ok else '✗'} {desc} {detail}")

    def sh(self, cmd, timeout=120):
        return self.emu.sh(cmd, timeout=timeout)

    def phase(self, name):
        self.phases.append((time.time(), name))
        self.mark(f"== {name}")

    def wait(self, minutes):
        time.sleep(minutes * 60 * self.scale)

    # ---- SDK 日志里的状态切换 ----
    def sdk_log(self):
        out = self.sh(f"run-as {PKG} sqlite3 databases/tracker.db 'select time, message from LogEntry order by id'", 60)
        rows = []
        for line in out.splitlines():
            t, _, msg = line.partition("|")
            if t.isdigit():
                rows.append((int(t) / 1000, msg))
        return rows

    def transitions(self, since=0):
        return [
            (t, m.group(1), m.group(2), m.group(3))
            for t, msg in self.sdk_log()
            if t >= since and (m := re.match(r"State (\w+) -> (\w+): (.*)", msg))
        ]

    def wait_state(self, to, since, timeout_min):
        """等 since 之后切到 to，返回 (秒数, 原因)；超时返回 (None, 最后的状态切换)"""
        deadline = time.time() + timeout_min * 60
        while time.time() < deadline:
            hit = [x for x in self.transitions(since) if x[2] == to]
            if hit:
                return round(hit[0][0] - since), f"{hit[0][1]}→{hit[0][2]}：{hit[0][3]}"
            time.sleep(10)
        last = self.transitions(since)
        return None, (f"最后一次切换 {last[-1][1]}→{last[-1][2]}" if last else "期间没有切换")

    def rows(self):
        return [r for r in self.recorder.rows() if r["params"].get("id") == self.device_id]

    # ---- 准备 ----
    def setup(self):
        self.emu = st.Emulator(self.out)
        self.mark(f"模拟器开机 {self.emu.start():.0f} 秒")
        self.recorder = st.Recorder(self.out)
        self.logcat = st.Logcat(self.emu, self.out / "logcat.txt")
        self.logcat.start()
        # 模拟器镜像里可能留着以前装过的 App（SDK 存着正式服务器的配置），先卸掉再装，保证用下面写的设置
        self.emu.adb("uninstall", PKG, timeout=120)
        r = self.emu.adb("install", "-r", "-g", str(self.apk), timeout=300)
        if r.returncode != 0:
            raise RuntimeError(f"装不上：{r.stdout}{r.stderr}")
        url = f"http://10.0.2.2:{self.recorder.port}/"
        prefs = {
            "id": ("string", self.device_id),
            "url": ("string", url),
            "accuracy": ("string", "high"),
            "interval": ("long", 10),
            "distance": ("long", 50),
            "heartbeat": ("long", 300),
            "min_interval": ("long", 5),
            "zone_radius": ("long", 200),
            "settings_version": ("long", 3),
            "buffer": ("boolean", "true"),
            "stop_detection": ("boolean", "true"),
            "prefer_platform_providers": ("boolean", "true"),
        }
        self.push_prefs(f"{PKG}_preferences.xml", prefs)
        self.push_prefs("traccar-client-sdk.xml", {"battery-prompted": ("boolean", "true")})
        written = self.sh(f"run-as {PKG} cat shared_prefs/{PKG}_preferences.xml")
        if url not in written or "example" in written:
            raise RuntimeError(f"服务器地址没写成本机记录服务：{written}")
        # 和真机一样：家长按清单给了电池优化豁免
        self.sh(f"dumpsys deviceidle whitelist +{PKG}")
        self.mover = Mover(self.emu)
        self.mover.start()
        time.sleep(3)
        self.t0 = time.time()
        self.sh(f"am start -W -a android.intent.action.VIEW -d {PKG}://action/start")
        deadline = time.time() + 90
        while time.time() < deadline and not self.rows():
            time.sleep(2)
        if not self.rows():
            raise RuntimeError("开始跟踪 90 秒没收到第一个点")
        if not any(f"Tracker start {url}" in m for _, m in self.sdk_log()):
            raise RuntimeError("SDK 没用本机记录服务的地址")
        self.mark(f"开始跟踪，{round(self.rows()[0]['recv'] - self.t0)} 秒收到第一个点（设备号 {self.device_id}）")
        self.sh("input keyevent KEYCODE_HOME")

    def push_prefs(self, name, values):
        body = "".join(
            f'    <string name="{key}">{v}</string>\n'
            if k == "string"
            else f'    <{k} name="{key}" value="{v}" />\n'
            for key, (k, v) in values.items()
        )
        local = self.out / name
        local.write_text(f"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n{body}</map>\n")
        remote = f"/data/local/tmp/three-{name}"
        self.emu.adb("push", str(local), remote)
        self.sh(f"run-as {PKG} sh -c 'mkdir -p shared_prefs && cp {remote} shared_prefs/{name}'")

    def dump(self, label):
        for name, cmd in (("location", "dumpsys location"), ("batterystats", f"dumpsys batterystats {PKG}"),
                          ("alarm", "dumpsys alarm")):
            (self.out / f"dumpsys-{name}-{label}.txt").write_text(self.sh(cmd, 120))

    # ---- 场景 ----
    def run(self):
        m = self.mover
        self.phase("1 原地不动")
        m.set()
        sec, why = self.wait_state("zone", self.t0, 6)
        self.check("开始跟踪后原地不动 6 分钟内转 ZONE（要求约 3 分钟）", sec is not None, f"{sec} 秒，{why}")
        t = time.time()
        sec, why = self.wait_state("still", t, 13)
        self.check("ZONE 里传感器安静约 10 分钟转 STILL", sec is not None and sec <= 12 * 60, f"{sec} 秒，{why}")

        self.phase("2 静止 20 分钟（灭屏、强制打盹）")
        self.sh("dumpsys battery unplug")
        self.sh("dumpsys batterystats --reset")
        self.sh("input keyevent KEYCODE_SLEEP")
        self.dump("still-start")
        still_from = time.time()
        self.sh("dumpsys deviceidle force-idle")
        self.wait(20)
        still_to = time.time()
        self.dump("still-end")
        self.sh("dumpsys deviceidle unforce")
        self.sh("dumpsys battery reset")
        self.sh("input keyevent KEYCODE_WAKEUP")
        self.analyze_still(still_from, still_to)

        self.phase("3 走出 300 米")
        t = time.time()
        m.set(speed=1.4, shake="walk")
        sec_zone, why_zone = self.wait_state("zone", t, 3)
        self.check("走路晃动后 STILL 被唤醒转 ZONE", sec_zone is not None, f"{sec_zone} 秒，{why_zone}")
        sec, why = self.wait_state("moving", t, 6)
        self.check("走出 200 米后转 MOVING（1.4 m/s 走 200 米约 2.4 分钟）", sec is not None and sec <= 5 * 60,
                   f"{sec} 秒，{why}")
        self.wait(max(0.0, (300 / 1.4 - (time.time() - t)) / 60))

        self.phase("4 停下")
        m.set()
        t = time.time()
        sec, why = self.wait_state("zone", t, 8)
        self.check("停下约 3 分钟后 MOVING → ZONE", sec is not None and sec <= 5 * 60, f"{sec} 秒，{why}")
        walk_from = next(t for t, name in self.phases if name.startswith("3 "))
        moving_rows = [r for r in self.rows() if r["params"].get("state") == "moving" and r["recv"] >= walk_from]
        self.analyze_moving(moving_rows)

        self.phase("5 屋里走动 3 分钟")
        t = time.time()
        m.set(shake="walk", jitter=15)
        self.wait(3)
        leaked = [x for x in self.transitions(t) if x[2] == "moving"]
        self.check("屋里走动（20 米内）不转 MOVING", not leaked, leaked[:1])

        self.phase("6 安静 10 分钟")
        m.set()
        t = time.time()
        sec, why = self.wait_state("still", t, 13)
        self.check("安静约 10 分钟 ZONE → STILL", sec is not None, f"{sec} 秒，{why}")

        self.phase("7 坐车（没步数，轻晃，每分钟 500 米）")
        t = time.time()
        m.set(speed=500 / 60, shake="car")
        sec, why = self.wait_state("moving", t, 5)
        self.check("坐车 3 分钟内转 MOVING", sec is not None and sec <= 3 * 60, f"{sec} 秒，{why}")
        self.wait(3)

        self.phase("8 车停下")
        m.set()
        t = time.time()
        sec, why = self.wait_state("zone", t, 14)
        self.check("坐过车后停下 8–10 分钟才转 ZONE", sec is not None and 7 * 60 <= sec <= 12 * 60, f"{sec} 秒，{why}")

    def analyze_still(self, a, b):
        rows = [r for r in self.rows() if a <= r["recv"] <= b]
        breaths = [r for r in rows if r["params"].get("state") == "still"]
        with_coords = [r for r in breaths if r["params"].get("lat") not in (None, "", "0", "0.0")]
        gaps = [round(y["recv"] - x["recv"]) for x, y in zip(breaths, breaths[1:])]
        self.check("静止 20 分钟里有呼吸点", len(breaths) >= 3, f"{len(breaths)} 个，间隔 {gaps} 秒")
        self.check("呼吸间隔不超过 6 分钟（打盹中）", gaps and max(gaps) <= 360, f"最大 {max(gaps) if gaps else None} 秒")
        self.check("呼吸点不带坐标", not with_coords, f"带坐标 {len(with_coords)} 个")
        log = [(t, msg) for t, msg in self.sdk_log() if a <= t <= b]
        fixes = [msg for _, msg in log if re.search(r"fix|GPS|Zone|Location", msg, re.I) and "Breath" not in msg]
        self.check("静止 20 分钟里 SDK 没有发起定位（30 分钟一次的网络定位不在窗口内）", not fixes, fixes[:3])
        (self.out / "still-window-log.txt").write_text("\n".join(f"{time.strftime('%H:%M:%S', time.localtime(t))} {m}" for t, m in log))

    def analyze_moving(self, rows):
        times = sorted(r["recv"] for r in rows)
        gaps = [round(y - x) for x, y in zip(times, times[1:])]
        no_course = [r for r in rows if r["params"].get("bearing") in (None, "")]
        self.check("移动中按 10 秒左右上报", gaps and sorted(gaps)[len(gaps) // 2] <= 15,
                   f"{len(rows)} 个点，间隔中位数 {sorted(gaps)[len(gaps) // 2] if gaps else None} 秒，最大 {max(gaps) if gaps else None} 秒")
        self.check("移动中的点带方向", not no_course, f"缺方向 {len(no_course)} 个")

    def teardown(self):
        for th in (getattr(self, "mover", None), getattr(self, "logcat", None)):
            if th:
                th.stopped.set()
        if getattr(self, "emu", None) and self.emu.proc:
            try:
                (self.out / "sdk-log.txt").write_text(
                    "\n".join(f"{time.strftime('%H:%M:%S', time.localtime(t))} {m}" for t, m in self.sdk_log()) + "\n")
                self.dump("end")
            except Exception as e:
                self.mark(f"取日志失败：{e}")
            self.emu.stop()
        if getattr(self, "recorder", None):
            self.recorder.stop()

    def report(self, error):
        rows = self.rows() if getattr(self, "recorder", None) else []
        states = {}
        for r in rows:
            states[r["params"].get("state", "(无)")] = states.get(r["params"].get("state", "(无)"), 0) + 1
        lines = [f"# 三种状态模拟器场景 {self.out.name}", "",
                 f"上报 {len(rows)} 个：" + "，".join(f"{k} {v}" for k, v in sorted(states.items())), ""]
        if error:
            lines += ["**场景出错**：", "```", error, "```", ""]
        lines += ["| 检查 | 结果 | 详情 |", "|---|---|---|"]
        lines += [f"| {d} | {'✓' if ok else '✗'} | {x} |" for d, ok, x in self.checks]
        lines += ["", "## 时间线", ""] + [f"- {time.strftime('%H:%M:%S', time.localtime(t))} {e}" for t, e in self.events]
        (self.out / "report.md").write_text("\n".join(lines) + "\n")
        return all(ok for _, ok, _ in self.checks) and not error


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", help="已构建的 debug APK；不给就现在构建")
    ap.add_argument("--scale", type=float, default=1.0, help="只缩放第 2、5 段的固定等待")
    args = ap.parse_args()
    out = st.ROOT / "build" / "stability" / time.strftime("three-states-%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=True)
    apk = Path(args.apk) if args.apk else None
    if apk is None:
        subprocess.run(["flutter", "build", "apk", "--debug"], cwd=st.ROOT, check=True)
        apk = out / "app.apk"
        shutil.copy(st.ROOT / "build/app/outputs/flutter-apk/app-debug.apk", apk)
    sc = Scenario(out, apk, args.scale)
    error = None
    try:
        sc.setup()
        sc.run()
    except Exception as e:  # 出错也要把已有结果和日志留下
        import traceback

        error = traceback.format_exc()
        sc.mark(f"出错：{e}")
    finally:
        sc.teardown()
    ok = sc.report(error)
    print(f"结果：{out / 'report.md'}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
