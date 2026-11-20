#!/usr/bin/env python3
"""稳定性压测：在无 GMS 模拟器 gps-nogms 的一次性只读实例上跑保活和轨迹场景，统计轨迹空档。

用法见 tools/stability/run.sh。每个场景起自己的模拟器实例（-read-only，不碰别的任务）和本机记录服务
（只听 127.0.0.1，模拟器里是 10.0.2.2）；App 的服务器地址在第一次启动前写进它的配置文件，
测试数据不会发到生产服务器。结果写到 build/stability/<时间>/：每个场景一个目录（points.jsonl、
sdk-log.txt、logcat.txt、result.json），汇总 report.md。
"""

import argparse
import json
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOLS = Path(__file__).resolve().parent
ANDROID_HOME = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
EMULATOR = ANDROID_HOME / "emulator" / "emulator"
ADB = shutil.which("adb") or str(ANDROID_HOME / "platform-tools" / "adb")
AVD = "gps-nogms"
PKG = "com.dodo258.tracker"
ACTIVITY = f"{PKG}/.MainActivity"

START_POINT = (30.2741, 120.1551)
STEP_SECONDS = 15  # 每 15 秒一个 geo fix
SPEED = 4.0  # 移动时 4 m/s（每步 60 米），60 秒窗口里位移 > 100 米，不会被判静止
HEARTBEAT = 300  # 三种状态：静止时 5 分钟呼吸一次
LIMITS = {"moving": 240, "still": int(HEARTBEAT * 1.5), "excused": None}
INVALID_AMAP_KEY = "stability-test-invalid-key"
HOG_TAG = "sleep 99999"

_print_lock = threading.Lock()
_port_lock = threading.Lock()
_claimed_ports = set()


def say(msg):
    with _print_lock:
        print(msg, flush=True)


def _port_free(port):
    with socket.socket() as s:
        try:
            s.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


def claim_port(first, last, pair=False):
    with _port_lock:
        for port in range(first, last, 2 if pair else 1):
            if port in _claimed_ports:
                continue
            if _port_free(port) and (not pair or _port_free(port + 1)):
                _claimed_ports.add(port)
                return port
    raise RuntimeError(f"{first}-{last} 没有空闲端口")


def release_port(port):
    with _port_lock:
        _claimed_ports.discard(port)


class Emulator:
    def __init__(self, outdir):
        self.port = claim_port(5600, 5680, pair=True)
        self.serial = f"emulator-{self.port}"
        self.outdir = outdir
        self.proc = None

    def adb(self, *args, timeout=120):
        try:
            return subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired as e:
            return subprocess.CompletedProcess(e.cmd, 124, "", f"timeout after {timeout}s")

    def sh(self, cmd, timeout=120):
        r = self.adb("shell", cmd, timeout=timeout)
        return (r.stdout + r.stderr).replace("\r", "").strip()

    def start(self):
        log = open(self.outdir / "emulator.txt", "w")
        self.proc = subprocess.Popen(
            [
                str(EMULATOR),
                "-avd",
                AVD,
                "-port",
                str(self.port),
                "-read-only",
                "-no-snapshot",
                "-no-window",
                "-no-audio",
                "-no-boot-anim",
            ],
            stdout=log,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
        )
        return self.wait_boot()

    def wait_boot(self, timeout=300):
        t0 = time.time()
        while time.time() - t0 < timeout:
            if self.proc.poll() is not None:
                raise RuntimeError("模拟器进程退出了，见 emulator.txt")
            if self.sh("getprop sys.boot_completed", timeout=10) == "1":
                return time.time() - t0
            time.sleep(2)
        raise RuntimeError(f"模拟器 {timeout} 秒没开完机")

    def wait_offline(self, timeout=60):
        t0 = time.time()
        while time.time() - t0 < timeout:
            if self.sh("getprop sys.boot_completed", timeout=5) != "1":
                return
            time.sleep(1)

    def stop(self):
        self.adb("emu", "kill", timeout=20)
        if self.proc:
            try:
                self.proc.wait(30)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        release_port(self.port)


class Recorder:
    def __init__(self, outdir):
        self.port = claim_port(5700, 5800)
        self.path = outdir / "points.jsonl"
        self.path.touch()
        self.proc = subprocess.Popen([sys.executable, str(TOOLS / "recorder.py"), str(self.port), str(self.path)])
        for _ in range(50):
            with socket.socket() as s:
                if s.connect_ex(("127.0.0.1", self.port)) == 0:
                    return
            time.sleep(0.1)
        raise RuntimeError("记录服务起不来")

    def rows(self):
        with open(self.path, encoding="utf-8") as f:
            return [json.loads(line) for line in f if line.strip()]

    def stop(self):
        self.proc.terminate()
        self.proc.wait(10)
        release_port(self.port)


class Mover(threading.Thread):
    """每 STEP_SECONDS 发一次 geo fix；移动时往东北走，并晃一下加速度计（静止检测靠它判断重新出发）。"""

    def __init__(self, emu):
        super().__init__(daemon=True)
        self.emu = emu
        self.lat, self.lon = START_POINT
        self.mode = "still"
        self.stopped = threading.Event()
        rest = re.search(r"acceleration = (\S+)", emu.adb("emu", "sensor", "get", "acceleration").stdout)
        self.rest = rest.group(1) if rest else "0:9.81:0"

    def jiggle(self):
        self.emu.adb("emu", "sensor", "set", "acceleration", "2.5:9.0:2.0", timeout=15)
        time.sleep(1)
        self.emu.adb("emu", "sensor", "set", "acceleration", self.rest, timeout=15)

    def set(self, mode):
        if mode == "move" and self.mode != "move":
            self.jiggle()
        self.mode = mode

    def run(self):
        bearing = math.radians(45)
        while not self.stopped.is_set():
            if self.mode == "move":
                d = SPEED * STEP_SECONDS
                self.lat += d * math.cos(bearing) / 111320
                self.lon += d * math.sin(bearing) / (111320 * math.cos(math.radians(self.lat)))
                self.jiggle()
            self.emu.adb("emu", "geo", "fix", f"{self.lon:.6f}", f"{self.lat:.6f}", timeout=15)
            self.stopped.wait(STEP_SECONDS)


class Logcat(threading.Thread):
    """一直收 logcat（重启后重连，重连时整个缓冲区会再写一遍，统计时按行去重）。"""

    def __init__(self, emu, path):
        super().__init__(daemon=True)
        self.emu, self.path = emu, path
        self.stopped = threading.Event()

    def run(self):
        with open(self.path, "a", encoding="utf-8", errors="replace") as f:
            while not self.stopped.is_set():
                p = subprocess.Popen(
                    [ADB, "-s", self.emu.serial, "logcat", "-v", "epoch", "-b", "main,system,events,crash"],
                    stdout=f,
                    stderr=subprocess.DEVNULL,
                )
                while p.poll() is None and not self.stopped.is_set():
                    time.sleep(1)
                if p.poll() is None:
                    p.terminate()
                self.stopped.wait(2)


class Run:
    def __init__(self, sid, base, apk, scale):
        spec = SCENARIOS[sid]
        self.sid, self.title, self.expected = sid, spec["title"], spec["expected"]
        self.dir = base / f"scenario-{sid}"
        self.dir.mkdir(parents=True, exist_ok=True)
        self.apk, self.scale = apk, scale
        self.device_id = str(90000000 + abs(hash((sid, time.time()))) % 9999999)
        self.events, self.segments, self.commands, self.notes, self.checks = ([], [], [], [], [])
        self.emu = self.recorder = self.mover = self.logcat = None
        self.track_start = None
        self.cut_at: list[float] = []  # 断网时刻：这一刻在途的上传服务器收到了、手机没收到响应，会重发

    # ---- 记录 ----
    def mark(self, label):
        now = time.time()
        self.events.append((now, label))
        say(f"[{self.sid}] {time.strftime('%H:%M:%S', time.localtime(now))} {label}")

    def note(self, text):
        self.notes.append(text)
        self.mark(text)

    def check(self, desc, ok, detail=""):
        self.checks.append({"check": desc, "ok": bool(ok), "detail": str(detail)})
        self.mark(f"{'✓' if ok else '✗'} {desc} {detail}")

    def cmd(self, shell_cmd, timeout=120):
        """场景里的操作命令：记进报告并执行"""
        self.commands.append(f"adb shell {shell_cmd}")
        out = self.emu.sh(shell_cmd, timeout=timeout)
        self.mark(f"$ {shell_cmd}" + (f" → {' / '.join(out.splitlines())[:200]}" if out else ""))
        return out

    def adb_cmd(self, *args, timeout=300):
        shown = [os.path.relpath(a, ROOT) if a.startswith(str(ROOT)) else a for a in args]
        self.commands.append("adb " + " ".join(shown))
        r = self.emu.adb(*args, timeout=timeout)
        out = (r.stdout + r.stderr).strip()
        self.mark(f"$ adb {' '.join(args)} → {out[-200:]}")
        return r

    # ---- 时间线 ----
    def segment(self, kind, label=""):
        now = time.time()
        if self.segments and self.segments[-1]["end"] is None:
            self.segments[-1]["end"] = now
        self.segments.append({"kind": kind, "label": label, "start": now, "end": None})

    def hold(self, minutes):
        time.sleep(minutes * 60 * self.scale)

    def move(self, minutes, label=""):
        self.mover.set("move")
        self.segment("moving", label)
        self.hold(minutes)

    def still(self, minutes, label=""):
        self.mover.set("still")
        self.segment("still", label)
        self.hold(minutes)

    # ---- 设备状态 ----
    def state(self):
        pid = self.emu.sh(f"pidof {PKG}", timeout=15) or None
        services = self.emu.sh(f"dumpsys activity services {PKG}", timeout=30)
        st = {"pid": pid, "foreground": "isForeground=true" in services, "adj": None, "procState": None}
        if pid and pid.isdigit():
            st["adj"] = self.emu.sh(f"cat /proc/{pid}/oom_score_adj", timeout=15)
            m = re.search(r"curProcState=(\d+)", self.emu.sh(f"dumpsys activity processes {PKG}", timeout=30))
            st["procState"] = m.group(1) if m else None
        return st

    def wait_service(self, since, timeout=180, other_than_pid=None):
        """等前台服务在（进程换了 pid 才算重启过）；返回从 since 起的秒数，超时返回 None"""
        while time.time() - since < timeout:
            st = self.state()
            if st["foreground"] and st["pid"] and st["pid"] != other_than_pid:
                return round(time.time() - since, 1), st
            time.sleep(2)
        return None, self.state()

    def wait_point(self, since, timeout=300):
        """等 since 之后记录服务收到的第一个点，返回秒数"""
        while time.time() - since < timeout:
            got = [
                r["recv"] for r in self.recorder.rows() if r["recv"] > since and r["params"].get("id") == self.device_id
            ]
            if got:
                return round(min(got) - since, 1)
            time.sleep(2)
        return None

    def sdk_log(self):
        out = self.emu.sh(
            f"run-as {PKG} sqlite3 databases/tracker.db 'select time, message from LogEntry order by id'", timeout=60
        )
        lines = []
        for line in out.splitlines():
            t, _, msg = line.partition("|")
            if t.isdigit():
                lines.append(f"{time.strftime('%H:%M:%S', time.localtime(int(t) / 1000))} {msg}")
        return lines

    def lmk_kills(self):
        path = self.dir / "logcat.txt"
        if not path.exists():
            return []
        with open(path, encoding="utf-8", errors="replace") as f:
            return sorted({line.strip() for line in f if "lowmemorykiller: Kill" in line and PKG in line})

    def kill9(self):
        pid = self.emu.sh(f"pidof {PKG}")
        self.cmd(f"run-as {PKG} kill -9 {pid}")
        return pid

    def screen_off(self):
        self.cmd("dumpsys battery unplug")
        self.cmd("input keyevent KEYCODE_SLEEP")

    # ---- 准备 ----
    def setup(self, system_only=True):
        self.emu = Emulator(self.dir)
        boot = self.emu.start()
        self.mark(f"模拟器 {self.emu.serial} 开机 {boot:.0f} 秒")
        self.recorder = Recorder(self.dir)
        self.logcat = Logcat(self.emu, self.dir / "logcat.txt")
        self.logcat.start()
        # 模拟器镜像里可能留着以前装过的 App（SDK 存着正式服务器的配置，会把测试点发到生产），先卸掉
        self.adb_cmd("uninstall", PKG)
        self.adb_cmd("install", "-r", "-g", str(self.apk))
        url = f"http://10.0.2.2:{self.recorder.port}/"
        prefs = {
            "id": ("string", self.device_id),
            "url": ("string", url),
            "accuracy": ("string", "high"),
            "interval": ("long", 10),
            "distance": ("long", 50),
            "heartbeat": ("long", HEARTBEAT),
            "min_interval": ("long", 5),
            "zone_radius": ("long", 200),
            "settings_version": ("long", 3),
            "buffer": ("boolean", "true"),
            "stop_detection": ("boolean", "true"),
            "prefer_platform_providers": ("boolean", "true" if system_only else "false"),
        }
        self.push_prefs(f"{PKG}_preferences.xml", prefs)
        # SDK 第一次开始跟踪会弹电池优化设置页，测试里不要它挡在前面；豁免由场景自己用 deviceidle 命令给
        self.push_prefs("traccar-client-sdk.xml", {"battery-prompted": ("boolean", "true")})
        written = self.emu.sh(f"run-as {PKG} cat shared_prefs/{PKG}_preferences.xml")
        if url not in written or "example" in written:
            raise RuntimeError(f"服务器地址没写成本机记录服务：{written}")
        self.mover = Mover(self.emu)
        self.mover.start()
        time.sleep(2)
        self.track_start = time.time()
        self.cmd(f"am start -W -a android.intent.action.VIEW -d {PKG}://action/start")
        first = self.wait_point(self.track_start, timeout=90)
        if first is None:
            raise RuntimeError("开始跟踪后 90 秒没收到第一个点")
        self.mark(f"开始跟踪，{first} 秒后收到第一个点（设备号 {self.device_id}，服务器 {url}）")
        self.cmd("input keyevent KEYCODE_HOME")

    def push_prefs(self, name, values):
        body = "".join(
            f'    <{kind} name="{key}">{value}</{kind}>\n'
            if kind == "string"
            else f'    <{kind} name="{key}" value="{value}" />\n'
            for key, (kind, value) in values.items()
        )
        local = self.dir / name
        local.write_text(f"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n{body}</map>\n")
        remote = f"/data/local/tmp/stab-{self.sid}-{name}"
        self.commands.append(f"adb push {name} {remote} && adb shell run-as {PKG} cp {remote} shared_prefs/{name}")
        self.emu.adb("push", str(local), remote)
        self.emu.sh(f"run-as {PKG} sh -c 'mkdir -p shared_prefs && cp {remote} shared_prefs/{name}'")

    def teardown(self):
        for t in (self.mover, self.logcat):
            if t:
                t.stopped.set()
        if self.emu and self.emu.proc:
            try:
                (self.dir / "sdk-log.txt").write_text("\n".join(self.sdk_log()) + "\n")
            except Exception as e:  # 设备已经坏了也要把别的结果留下
                self.note(f"取 SDK 日志失败：{e}")
            self.emu.stop()
        if self.recorder:
            self.recorder.stop()


# ---------------------------------------------------------------- 分析


def analyze(run, end):
    if run.segments and run.segments[-1]["end"] is None:
        run.segments[-1]["end"] = end
    rows = [r for r in run.recorder.rows() if r["params"].get("id") == run.device_id]
    foreign = [r for r in run.recorder.rows() if r["params"].get("id") != run.device_id]
    points = [r for r in rows if not r["params"].get("alarm")]
    times = sorted({int(r["params"]["timestamp"]) for r in points if r["params"].get("timestamp", "").isdigit()})

    # 空档：开始跟踪 → 每个点 → 结束，相邻两个时刻之差；按覆盖到的时段里最宽的上限判断
    marks = [run.track_start] + [t for t in times if run.track_start <= t <= end] + [end]
    gaps = [(a, b) for a, b in zip(marks, marks[1:])]

    def limit_for(a, b):
        limits = [LIMITS[s["kind"]] for s in run.segments if s["start"] < b and s["end"] > a]
        if not limits or None in limits:
            return None
        return max(limits)

    violations = []
    for a, b in gaps:
        lim = limit_for(a, b)
        if lim is not None and b - a > lim:
            violations.append({"from": a, "to": b, "gap": round(b - a), "limit": lim})

    seg_stats = []
    for s in run.segments:
        inside = [t for t in times if s["start"] <= t <= s["end"]]
        # 跨进「不计」时段的空档不算进相邻时段的最大值
        overlapping = [b - a for a, b in gaps if a < s["end"] and b > s["start"] and limit_for(a, b) is not None]
        seg_stats.append(
            {
                "kind": s["kind"],
                "label": s["label"],
                "from": s["start"],
                "to": s["end"],
                "minutes": round((s["end"] - s["start"]) / 60, 1),
                "points": len(inside),
                "max_gap": round(max(overlapping)) if overlapping else None,
                "limit": LIMITS[s["kind"]],
            }
        )

    # 不计时段（电池受限等）里系统限制造成的现象另行记录，不算重复
    excused = [(x["start"], x["end"]) for x in run.segments if x["kind"] == "excused"]
    counted = [r for r in rows if not any(a <= r["recv"] <= b for a, b in excused)]
    keys = [
        (r["params"].get("timestamp"), r["params"].get("lat"), r["params"].get("lon"), r["params"].get("alarm"))
        for r in counted
    ]
    first_recv: dict = {}
    for k, r in zip(keys, counted):
        first_recv.setdefault(k, r["recv"])
    dup_keys = [k for k in set(keys) if keys.count(k) > 1]
    resent = [k for k in dup_keys if any(abs(first_recv[k] - t) <= 5 for t in run.cut_at)]
    if resent:
        run.note(f"{RESENT_NOTE} {len(resent)} 个点（服务器第一次已收到，手机没收到响应，联网后照常重发）")
    duplicates = sum(keys.count(k) - 1 for k in dup_keys if k not in resent)
    stamps = [int(r["params"].get("timestamp", 0)) for r in points]
    inversions = sum(1 for x, y in zip(stamps, stamps[1:]) if y < x)

    run.check(
        f"轨迹空档都在上限内（移动 {LIMITS['moving']} 秒、静止 {LIMITS['still']} 秒）",
        not violations,
        "; ".join(f"{fmt(v['from'])}→{fmt(v['to'])} {v['gap']}s>{v['limit']}s" for v in violations) or "无超限空档",
    )
    run.check("没有重复的点", duplicates == 0, f"重复 {duplicates} 个")
    run.check("点按时间顺序到达", inversions == 0, f"逆序 {inversions} 处")
    run.check("记录服务只收到本设备的请求", not foreign, f"其他请求 {len(foreign)} 个")
    return {
        "points": len(points),
        "alarms": len(rows) - len(points),
        "segments": seg_stats,
        "violations": violations,
        "duplicates": duplicates,
        "inversions": inversions,
    }


def fmt(t):
    return time.strftime("%H:%M:%S", time.localtime(t))


# ---------------------------------------------------------------- 场景


def s1_baseline(r):
    r.move(30, "移动 30 分钟")
    r.still(30, "静止 30 分钟")
    log = r.sdk_log()
    r.check("进入过静止状态", any("-> still" in line for line in log))
    beats = sum("Heartbeat accepted" in line or "Breath alarm fired" in line for line in log)
    r.check("静止期间有心跳", beats >= int(30 * 60 * r.scale / HEARTBEAT) - 1, f"心跳 {beats} 个")


def visible_recents(r):
    out = r.emu.sh("dumpsys activity recents", timeout=30)
    m = re.search(r"Visible recent tasks.*?(?=\n\S|\Z)", out, re.S)
    return m.group(0) if m else out


def s2_recents(r):
    r.move(4, "清除前移动")
    r.cmd(f"am start -W -n {ACTIVITY}")
    r.cmd("am start -W -a android.settings.SETTINGS")
    r.cmd("input keyevent KEYCODE_HOME")
    r.check("按 Home 后最近任务里看不到本 App", PKG not in visible_recents(r))
    pid = r.state()["pid"]
    r.cmd("input keyevent KEYCODE_APP_SWITCH")
    time.sleep(3)
    tapped = False
    for _ in range(6):
        dump = r.emu.sh("uiautomator dump /data/local/tmp/ui.xml >/dev/null && cat /data/local/tmp/ui.xml", timeout=30)
        m = re.search(
            r'resource-id="com.android.launcher3:id/clear_all"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump
        )
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            r.cmd(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            tapped = True
            break
        r.emu.sh("input swipe 300 1200 1000 1200 300")
        time.sleep(1.5)
    r.check("点到了最近任务「CLEAR ALL」", tapped)
    time.sleep(5)
    st = r.state()
    r.check("清除后进程没换、前台服务还在", st["pid"] == pid and st["foreground"], st)
    r.check("清除后最近任务里没有本 App", PKG not in visible_recents(r))
    r.move(5, "清除后移动")
    r.still(7, "清除后静止")


HOG_CHUNK_MB = 100


def stop_hog(r):
    r.cmd(f"pkill -f '{HOG_TAG}'; pkill -x tail; true")


def mem_available_mb(r):
    m = re.search(r"MemAvailable:\s+(\d+)", r.emu.sh("cat /proc/meminfo"))
    return int(m.group(1)) // 1024 if m else 0


def squeeze(r, target_mb, max_chunks=40):
    """每次多占 100 MB 随机数据（不能被 zram 压缩），直到可用内存降到 target_mb 以下；返回加了几块"""
    chunk = HOG_CHUNK_MB * 1024 * 1024
    hog = (
        f"nohup sh -c '(head -c {chunk} /dev/urandom; exec {HOG_TAG}) | tail -c {chunk} > /dev/null' >/dev/null 2>&1 &"
    )
    n = 0
    while n < max_chunks and mem_available_mb(r) > target_mb:
        r.emu.sh(hog)
        n += 1
        time.sleep(4)
    return n


def hold_pressure(r, target_mb, seconds, pid):
    """保持可用内存在 target_mb 附近（系统杀掉缓存进程腾出内存后再补），返回 (最低可用, 本 App 一直在, 块数)"""
    lowest, survived, chunks, t0 = mem_available_mb(r), True, 0, time.time()
    while time.time() - t0 < seconds:
        chunks += squeeze(r, target_mb)
        time.sleep(10)
        lowest = min(lowest, mem_available_mb(r))
        st = r.state()
        survived = survived and st["pid"] == pid and st["foreground"]
    return lowest, survived, chunks


def boot_id(r):
    return r.emu.sh("cat /proc/sys/kernel/random/boot_id")


def s3_memory(r):
    r.move(3, "加压前移动")
    pid = r.state()["pid"]
    r.cmd(f"am send-trim-memory {PKG} RUNNING_CRITICAL")
    r.cmd("am kill-all")
    time.sleep(20)
    st = r.state()
    r.check("trim-memory 和 kill-all 后进程没换、前台服务还在", st["pid"] == pid and st["foreground"], st)
    r.check(
        "进程是前台服务级别（procState 4、oom_score_adj ≤ 250）",
        st["procState"] == "4" and st["adj"] and int(st["adj"]) <= 250,
        st,
    )
    hog_cmd = f"(head -c 100M /dev/urandom; exec {HOG_TAG}) | tail -c 100M  # 每块 100 MB，加到可用内存低于目标"

    # 中等压力：可用内存压到 300 MB 以下，保持 4 分钟（系统会先杀缓存和后台进程）
    avail, boot = mem_available_mb(r), boot_id(r)
    r.segment("moving", "中等内存压力（可用 < 300 MB，4 分钟）")
    r.commands.append(f"adb shell {hog_cmd}，目标 300 MB")
    lowest, survived, chunks = hold_pressure(r, 300, 4 * 60 * r.scale, pid)
    rebooted = boot_id(r) != boot
    r.note(
        f"中等压力：加压前可用 {avail} MB，占了 {chunks} 块，最低 {lowest} MB" + ("；整机重启了" if rebooted else "")
    )
    r.check("中等内存压力下本 App 没被杀", survived and not rebooted, r.state())
    stop_hog(r)
    time.sleep(20)

    # 极端压力：可用内存压到 80 MB 以下 60 秒，系统会连前台服务一起杀；看杀了以后多久恢复
    avail, boot, pid = mem_available_mb(r), boot_id(r), r.state()["pid"]
    r.segment("moving", "极端内存压力（可用 < 80 MB，60 秒）")
    r.commands.append(f"adb shell {hog_cmd}，目标 80 MB")
    hog_at = time.time()
    lowest, survived, chunks = hold_pressure(r, 80, 60, pid)
    stop_hog(r)
    restored, st = r.wait_service(hog_at, timeout=300)
    rebooted = boot_id(r) != boot
    r.note(
        f"极端压力：加压前可用 {avail} MB，占了 {chunks} 块，最低 {lowest} MB；本 App "
        f"{'没被杀' if survived else '被杀'}{'，整机重启了' if rebooted else ''}；"
        f"加压起 {restored} 秒时前台服务在（pid {pid} → {st['pid']}）"
    )
    r.check("极端内存压力后前台服务在（被杀的话自己重启）", restored is not None, st)
    r.move(5, "加压后移动")
    r.still(7, "加压后静止")
    kills = r.lmk_kills()
    r.note(f"lowmemorykiller 杀本 App {len(kills)} 次" + (f"：{kills[0][:160]}" if kills else ""))


def s4_kill(r):
    r.move(3, "移动")
    pid = r.kill9()
    t = time.time()
    restored, st = r.wait_service(t, timeout=180, other_than_pid=pid)
    point = r.wait_point(t, timeout=300)
    r.check("移动中 kill -9 后服务自己重启", restored is not None, f"{restored} 秒，{st}")
    r.note(f"移动中 kill -9：{restored} 秒服务重启，{point} 秒收到下一个点")
    r.move(5, "kill 后移动")
    r.still(5, "静止")
    pid = r.kill9()
    t = time.time()
    restored, st = r.wait_service(t, timeout=180, other_than_pid=pid)
    point = r.wait_point(t, timeout=400)
    r.check("静止中 kill -9 后服务自己重启", restored is not None, f"{restored} 秒，{st}")
    r.check("静止中 kill -9 后一个心跳周期内有点", point is not None and point <= HEARTBEAT, f"{point} 秒")
    r.note(f"静止中 kill -9：{restored} 秒服务重启，{point} 秒收到下一个点")
    r.still(4, "kill 后静止")

    r.move(2, "连续 kill 前移动")
    delays = []
    for _ in range(3):
        pid = r.kill9()
        t = time.time()
        restored, _ = r.wait_service(t, timeout=600, other_than_pid=pid)
        delays.append(restored)
        time.sleep(30)
    r.note(f"移动中连续 3 次 kill -9（每次服务回来 30 秒后再杀）：重启用时 {delays} 秒")
    r.check("连续 kill -9 后服务每次都能自己重启", all(d is not None for d in delays), delays)
    r.move(4, "连续 kill 后移动")

    # 强行停止是用户操作，系统不许自启，只记录现象
    r.segment("excused", "强行停止（系统不许自启，不算空档）")
    r.cmd(f"am force-stop {PKG}")
    time.sleep(5 * 60 * r.scale)
    st = r.state()
    r.note(f"强行停止 5 分钟后：pid {st['pid']}，前台服务 {'在' if st['foreground'] else '不在'}")
    r.cmd(f"am start -W -n {ACTIVITY}")
    t = time.time()
    restored, st = r.wait_service(t, timeout=60)
    r.note(f"强行停止后打开 App：{restored} 秒前台服务恢复")
    r.cmd("input keyevent KEYCODE_HOME")
    r.check("强行停止后再打开 App 跟踪恢复", restored is not None, st)
    r.move(4, "打开 App 后移动")


def s5_doze(r, exempt):
    if exempt:
        r.cmd(f"dumpsys deviceidle whitelist +{PKG}")
    r.cmd("dumpsys deviceidle enable all")
    r.screen_off()
    r.cmd("dumpsys deviceidle force-idle")
    r.check("进入深度打盹", "IDLE" in r.cmd("dumpsys deviceidle get deep"))
    r.still(12, "深度打盹，静止")
    r.move(10, "深度打盹，移动")
    r.check("20 分钟后仍在深度打盹", "IDLE" in r.cmd("dumpsys deviceidle get deep"))
    r.cmd("dumpsys deviceidle unforce")

    # 待机分桶：这个模拟器镜像关掉了 App Standby（mAppIdleEnabled=false），设了也不生效，只记录实际分桶
    idle = re.search(r"mAppIdleEnabled=\w+", r.emu.sh("dumpsys usagestats | grep -m1 mAppIdleEnabled"))
    for bucket in ("rare", "restricted"):
        r.cmd(f"am set-standby-bucket {PKG} {bucket}")
        actual = r.cmd(f"am get-standby-bucket {PKG}")
        r.note(f"set-standby-bucket {bucket} 后实际分桶 {actual}（5 = EXEMPTED；{idle.group(0) if idle else ''}）")
    r.cmd(f"am set-standby-bucket {PKG} active")

    # 设置里电池「受限」= RUN_ANY_IN_BACKGROUND ignore
    pid = r.state()["pid"]
    r.segment("excused", "电池「受限」（系统停掉或降级前台服务，不算空档）")
    r.cmd(f"cmd appops set {PKG} RUN_ANY_IN_BACKGROUND ignore")
    r.mover.set("still")
    time.sleep(60)
    st = r.state()
    r.note(f"电池「受限」1 分钟后：pid {pid} → {st['pid']}，前台服务 {'在' if st['foreground'] else '不在'}")
    r.hold(11)
    r.mover.set("move")
    r.segment(r.segments[-1]["kind"], "电池「受限」，移动")
    r.hold(10)
    st = r.state()
    r.note(f"电池「受限」22 分钟后：前台服务 {'在' if st['foreground'] else '不在'}")
    r.cmd(f"cmd appops set {PKG} RUN_ANY_IN_BACKGROUND allow")
    r.cmd("dumpsys battery reset")
    r.cmd("input keyevent KEYCODE_WAKEUP")
    r.segment("excused", "取消「受限」后等看门狗（最多 15 分钟）")
    t = time.time()
    restored, st = r.wait_service(t, timeout=17 * 60)
    r.note(f"取消「受限」后：{restored} 秒前台服务在")
    if restored is None:
        r.check("取消「受限」后看门狗一个周期（15 分钟）内恢复前台服务", False, st)
    r.move(4, "恢复后移动")


def s5a(r):
    s5_doze(r, exempt=False)


def s5b(r):
    s5_doze(r, exempt=True)


def reboot(r, label):
    pid = r.state()["pid"]
    t = time.time()
    r.adb_cmd("reboot", timeout=60)
    r.emu.wait_offline()
    boot = r.emu.wait_boot()
    restored, st = r.wait_service(t, timeout=300, other_than_pid=pid)
    point = r.wait_point(t, timeout=400)
    resumed = r.emu.sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'")
    r.note(
        f"{label}重启：开机 {boot:.0f} 秒，发出重启后 {restored} 秒前台服务在，{point} 秒收到第一个点；前台界面 {resumed or '无'}"
    )
    r.check(f"{label}重启后不打开 App，服务自己起来", restored is not None and PKG not in resumed, st)
    return point


def s6_reboot(r):
    r.move(3, "移动")
    r.segment("moving", "移动中重启")
    reboot(r, "移动中")
    r.move(5, "重启后移动")
    r.still(5, "静止")
    r.segment("still", "静止中重启")
    reboot(r, "静止中")
    r.still(6, "重启后静止")


def upgrade(r, label):
    pid = r.state()["pid"]
    t = time.time()
    r.adb_cmd("install", "-r", str(r.apk))
    restored, st = r.wait_service(t, timeout=300, other_than_pid=pid)
    point = r.wait_point(t, timeout=400)
    r.note(f"{label}升级：{restored} 秒前台服务在，{point} 秒收到第一个点")
    r.check(f"{label}升级后服务自己恢复", restored is not None, st)


def s7_upgrade(r):
    r.move(3, "移动")
    r.segment("moving", "移动中升级")
    upgrade(r, "移动中")
    r.move(4, "升级后移动")
    r.still(5, "静止")
    r.segment("still", "静止中升级")
    upgrade(r, "静止中")
    r.still(6, "升级后静止")
    r.check("收到过升级广播", any("MY_PACKAGE_REPLACED" in line for line in r.sdk_log()))


def s8_offline(r):
    r.move(3, "联网移动")
    r.cmd("svc wifi disable; svc data disable")
    off = time.time()
    r.cut_at.append(off)
    r.move(10, "断网移动 10 分钟")
    r.cmd("svc wifi enable; svc data enable")
    on = time.time()
    r.move(3, "联网后移动")
    r.still(5, "静止")
    rows = [x for x in r.recorder.rows() if x["params"].get("id") == r.device_id]
    during = [x for x in rows if off + 5 < x["recv"] < on]
    queued = [x for x in rows if off < int(x["params"].get("timestamp", 0)) < on]
    late = [x["recv"] - on for x in queued]
    r.check("断网期间记录服务一个请求都没收到（确实断了）", not during, f"{len(during)} 个")
    r.check(
        "断网期间的点联网后都补传了（每 2 分钟至少一个）",
        len(queued) >= int(10 * 60 * r.scale / 120),
        f"{len(queued)} 个",
    )
    r.note(f"断网 10 分钟记下 {len(queued)} 个点，联网后 {round(max(late), 1) if late else None} 秒内补传完")


def s9_amap(r):
    r.move(8, "移动")
    r.still(6, "静止")
    log = r.sdk_log()
    r.check("用的是高德源（带回退）", any("Using AMapLocationSource" in line for line in log))
    errors = sorted({m.group(1) for line in log if (m := re.search(r"AMap error (\d+)", line))})
    fallback = [line for line in log if "Location fallback" in line]
    r.note(f"高德报的错误码：{', '.join(errors) or '无'}；回退：{fallback[0] if fallback else '没有回退'}")
    r.check("高德报错了（Key 确实不可用）", bool(errors), errors)
    # 高德连续定位里 GPS 不需要鉴权，模拟器上可能照样出点（不触发回退）；要的是轨迹不断，空档检查管这个
    r.check(
        "高德出错后回退系统定位，或高德仍在出点",
        bool(fallback) or any("Location accepted" in line for line in log),
        fallback[0] if fallback else "高德仍在出点",
    )


SCENARIOS = {
    "1": {
        "title": "基线：移动 30 分钟 + 静止 30 分钟",
        "run": s1_baseline,
        "expected": "移动中约 30 秒一个点（最短间隔 30 秒、100 米），任何两点不超过 240 秒；静止约 180 秒一个心跳点，不超过 270 秒",
    },
    "2": {
        "title": "从最近任务全部清除",
        "run": s2_recents,
        "expected": "本 App 不在最近任务里；CLEAR ALL 后进程不换、前台服务还在，轨迹不断",
    },
    "3": {
        "title": "内存压力",
        "run": s3_memory,
        "expected": "trim-memory、kill-all、只留 250 MB 可用时本 App 是前台服务级别不被杀；吃光内存时系统可能连它一起杀，杀了要自己重启，轨迹空档不超过 240 秒",
    },
    "4": {
        "title": "进程被杀后恢复",
        "run": s4_kill,
        "expected": "kill -9 后系统按 START_STICKY 重启服务，移动中空档不超过 240 秒、静止中一个心跳周期内有点；force-stop 是用户强行停止，系统不许自启，只记录，打开 App 后恢复",
    },
    "5a": {
        "title": "打盹 / 待机分桶 / 电池受限（没有电池优化豁免）",
        "run": s5a,
        "expected": "深度打盹 22 分钟里上报不断（前台服务的网络和唤醒锁不受打盹限制）；电池「受限」时系统会撤掉前台服务（家长设置错误，稳定运行清单里有这一项），这段只记录现象；取消受限后看门狗 15 分钟内恢复前台服务",
    },
    "5b": {
        "title": "打盹 / 待机分桶 / 电池受限（有电池优化豁免）",
        "run": s5b,
        "expected": "同 5a；有豁免时系统把前台服务降成普通服务但不停掉，取消受限后看门狗也要在 15 分钟内把它拉回前台",
    },
    "6": {
        "title": "重启手机",
        "run": s6_reboot,
        "expected": "adb reboot 后不打开 App，开机后服务自己起来并继续上报，移动和静止中重启空档都不超过上限",
    },
    "7": {
        "title": "App 升级",
        "run": s7_upgrade,
        "expected": "adb install -r 后收到 MY_PACKAGE_REPLACED，服务自己恢复，空档不超过上限",
    },
    "8": {
        "title": "断网 10 分钟再联网",
        "run": s8_offline,
        "expected": "断网期间点进离线缓存，联网后按时间顺序补传、不重复，按点的时间算轨迹不断",
    },
    "9": {
        "title": "高德不可用（故意写错 AMAP_KEY）",
        "run": s9_amap,
        "amap": True,
        "expected": "高德报错（7 鉴权失败立即、其他错误连续 5 次）后自动回退系统定位；不管回没回退，轨迹不断",
    },
}


def run_scenario(sid, base, apks, scale):
    spec = SCENARIOS[sid]
    r = Run(sid, base, apks["amap"] if spec.get("amap") else apks["system"], scale)
    error = None
    try:
        r.setup(system_only=not spec.get("amap"))
        spec["run"](r)
        st = r.state()
        r.check("结束时前台服务在", st["foreground"], st)
    except Exception:
        error = traceback.format_exc()
        r.note(f"场景出错：{error.strip().splitlines()[-1]}")
        r.check("场景跑完", False, error.strip().splitlines()[-1])
    end = time.time()
    stats = None
    try:
        if r.recorder and r.track_start:
            stats = analyze(r, end)
    finally:
        r.teardown()
    result = {
        "id": sid,
        "title": r.title,
        "expected": r.expected,
        "device_id": r.device_id,
        "start": r.track_start,
        "end": end,
        "commands": r.commands,
        "notes": r.notes,
        "checks": r.checks,
        "stats": stats,
        "error": error,
        "passed": all(c["ok"] for c in r.checks) and not error,
        "events": [(fmt(t), label) for t, label in r.events],
        "cut_at": r.cut_at,
    }
    (r.dir / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=1))
    say(f"[{sid}] {'通过' if result['passed'] else '不通过'}")
    return result


# ---------------------------------------------------------------- 构建与报告


def build(base, need_amap):
    apks = {}
    props = ROOT / "android" / "local.properties"

    def flutter_build(name):
        subprocess.run(["flutter", "build", "apk", "--debug"], cwd=ROOT, check=True)
        out = base / name
        shutil.copy(ROOT / "build/app/outputs/flutter-apk/app-debug.apk", out)
        return out

    if need_amap:
        original = props.read_bytes() if props.exists() else None
        try:
            lines = [x for x in (original or b"").decode().splitlines() if not x.startswith("AMAP_KEY=")]
            props.write_text("\n".join(lines + [f"AMAP_KEY={INVALID_AMAP_KEY}"]) + "\n")
            apks["amap"] = flutter_build("app-amap-invalid-key.apk")
        finally:
            if original is None:
                props.unlink(missing_ok=True)
            else:
                props.write_bytes(original)
    apks["system"] = flutter_build("app.apk")
    return apks


def report(results, base, scale):
    lines = [
        f"# 稳定性压测结果 {base.name}",
        "",
        f"模拟器 `{AVD}`（AOSP 无 GMS，一次性只读实例），时长系数 {scale}。",
        "",
        "| 场景 | 结论 | 移动最大空档 | 静止最大空档 | 点数 |",
        "|---|---|---|---|---|",
    ]
    for res in results:
        segs = (res["stats"] or {}).get("segments", [])
        mv = max((s["max_gap"] or 0 for s in segs if s["kind"] == "moving"), default=None)
        sl = max((s["max_gap"] or 0 for s in segs if s["kind"] == "still"), default=None)
        lines.append(
            f"| {res['id']} {res['title']} | {'通过' if res['passed'] else '**不通过**'} | "
            f"{mv if mv is not None else '–'} 秒 | {sl if sl is not None else '–'} 秒 | {(res['stats'] or {}).get('points', '–')} |"
        )
    for res in results:
        lines += [
            "",
            f"## 场景 {res['id']}：{res['title']}",
            "",
            f"- 结论：{'通过' if res['passed'] else '**不通过**'}",
            f"- 预期：{res['expected']}",
            "- 操作命令：",
            "",
            "```",
        ]
        lines += res["commands"] + ["```", "", "- 实际："]
        lines += [f"  - {n}" for n in res["notes"]] or ["  - （无）"]
        if res["stats"]:
            lines += ["", "| 时段 | 类型 | 起止 | 分钟 | 点数 | 最大空档 | 上限 |", "|---|---|---|---|---|---|---|"]
            for s in res["stats"]["segments"]:
                lines.append(
                    f"| {s['label']} | {s['kind']} | {fmt(s['from'])}–{fmt(s['to'])} | {s['minutes']} | {s['points']} | "
                    f"{str(s['max_gap']) + ' 秒' if s['max_gap'] is not None else '–'} | {str(s['limit']) + ' 秒' if s['limit'] else '不计'} |"
                )
        lines += ["", "| 检查 | 结果 | 说明 |", "|---|---|---|"]
        lines += [
            f"| {c['check']} | {'✓' if c['ok'] else '✗'} | {c['detail'].replace('|', '/')[:300]} |"
            for c in res["checks"]
        ]
    (base / "report.md").write_text("\n".join(lines) + "\n")
    (base / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=1))


RESENT_NOTE = "断网瞬间在途的上传重发了"
GENERIC_CHECKS = ("轨迹空档都在上限内", "没有重复的点", "点按时间顺序到达", "记录服务只收到本设备的请求")


class Replay:
    """用一次运行保存下来的数据（result.json、points.jsonl）重新分析，给 --report-from 用"""

    def __init__(self, result: dict, scenario_dir: Path):
        self.sid, self.device_id, self.track_start = result["id"], result["device_id"], result["start"]
        self.segments = [
            {"kind": x["kind"], "label": x["label"], "start": x["from"], "end": x["to"]}
            for x in result["stats"]["segments"]
        ]
        self.checks = [c for c in result["checks"] if not c["check"].startswith(GENERIC_CHECKS)]
        self.notes = [n for n in result["notes"] if not n.startswith(RESENT_NOTE)]
        self.cut_at = result.get("cut_at") or self._cuts_from_events(result)
        self.recorder = Recorder.__new__(Recorder)
        self.recorder.path = scenario_dir / "points.jsonl"

    def _cuts_from_events(self, result: dict) -> list[float]:
        day = time.strftime("%Y-%m-%d ", time.localtime(result["start"]))
        return [
            time.mktime(time.strptime(day + t, "%Y-%m-%d %H:%M:%S"))
            for t, label in result["events"]
            if label.startswith("$ svc wifi disable")
        ]

    def check(self, desc: str, ok: bool, detail: str = "") -> None:
        self.checks.append({"check": desc, "ok": bool(ok), "detail": str(detail)})

    def note(self, text: str) -> None:
        self.notes.append(text)


def report_from(dirs: list[Path]) -> int:
    """合并几次运行的结果（后面目录里的同号场景覆盖前面的），按现在的分析规则重算，写新报告"""
    latest: dict[str, tuple[dict, Path]] = {}
    for d in dirs:
        for res in json.loads((d / "results.json").read_text()):
            latest[res["id"]] = (res, d / f"scenario-{res['id']}")
    results = []
    for sid in [s for s in SCENARIOS if s in latest]:
        res, sdir = latest[sid]
        if res["stats"]:
            replay = Replay(res, sdir)
            res["stats"] = analyze(replay, res["end"])
            res["checks"], res["notes"] = replay.checks, replay.notes
            res["passed"] = all(c["ok"] for c in res["checks"]) and not res["error"]
        res["source"] = sdir.parent.name
        results.append(res)
    base = ROOT / "build" / "stability" / (time.strftime("%Y%m%d-%H%M%S") + "-merged")
    base.mkdir(parents=True)
    report(results, base, 1.0)
    say(f"报告 {base / 'report.md'}")
    return 0 if all(r["passed"] for r in results) else 1


def main():
    ap = argparse.ArgumentParser(description="稳定性压测（无 GMS 模拟器）")
    ap.add_argument("scenarios", nargs="*", help=f"场景号，可选 {' '.join(SCENARIOS)}，不写跑全部（5 = 5a 5b）")
    ap.add_argument("--jobs", type=int, default=1, help="同时跑几个场景（每个一台模拟器，约 2.5 GB 内存、4 核）")
    ap.add_argument("--scale", type=float, default=1.0, help="时长系数，只用来调试脚本；小于 1 时空档检查没有意义")
    ap.add_argument("--skip-build", action="store_true", help="不重新构建，用 --apk-dir 里已有的 APK")
    ap.add_argument("--apk-dir", type=Path, help="已有 APK 的目录（app.apk、app-amap-invalid-key.apk）")
    ap.add_argument("--report-from", type=Path, nargs="+", metavar="DIR", help="不跑，合并这几次运行的结果重出报告")
    args = ap.parse_args()
    if args.report_from:
        sys.exit(report_from(args.report_from))

    chosen = []
    for s in args.scenarios or list(SCENARIOS):
        chosen += ["5a", "5b"] if s == "5" else [s]
    unknown = [s for s in chosen if s not in SCENARIOS]
    if unknown:
        ap.error(f"没有这些场景：{unknown}")
    base = ROOT / "build" / "stability" / time.strftime("%Y%m%d-%H%M%S")
    base.mkdir(parents=True)
    need_amap = any(SCENARIOS[s].get("amap") for s in chosen)
    if args.skip_build:
        d = args.apk_dir or base
        apks = {"system": d / "app.apk", "amap": d / "app-amap-invalid-key.apk"}
    else:
        apks = build(base, need_amap)
    say(f"结果目录 {base}")
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        results = list(pool.map(lambda s: run_scenario(s, base, apks, args.scale), chosen))
    report(results, base, args.scale)
    say(f"报告 {base / 'report.md'}")
    for res in results:
        say(f"  场景 {res['id']}：{'通过' if res['passed'] else '不通过'}")
    sys.exit(0 if all(r["passed"] for r in results) else 1)


if __name__ == "__main__":
    main()
