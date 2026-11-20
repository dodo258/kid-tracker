#!/usr/bin/env python3
"""上报端 v2（app-v2/）加速压测：几台无 GMS 模拟器同时跑，按随机时间线换着「静止 / 屋里走动 / 坐车」，期间随机注入
方案「可靠性要求」第 6 条的全部故障，统计内存曲线、卡死次数、每类故障的恢复时间、丢点数。

用法见 tools/stability/v2_stress.sh。做法沿用 tools/stability/stability.py：一次性只读模拟器实例（不碰别的任务的模拟器）、
本机记录服务（只听 127.0.0.1，模拟器里是 10.0.2.2），App 的服务器地址在第一次启动前写进它的配置文件，启动后先核对
SDK 库里的地址是本机记录服务才开追踪，测试数据不会发到生产服务器。

「加速」指两样：几台模拟器并行累计设备时长（4 台 × 6.5 小时 = 26 设备小时）；故障密度远高于真实使用（平均每 8 分钟一次，
真机一天碰上的断网、重启、被杀、改时间加起来不过几次）。

2026-11-19 交付前最终压测加了审计第二批修过的四类故障（改钟后先网络点再卫星点、存储满时已发点删不掉、队列满到上限、
旧版迁移失败）和两个场景（连着锚 Wi-Fi 屋里走动、连着车载 Wi-Fi 上车），说明见各自的注入代码和 analyze_scenarios。

结果写到 build/stability-v2/<时间>/：每台一个 lane-N 目录（points.jsonl 服务器收到的、sdk-log.txt App 日志、events.json
注入的故障、memory.csv 内存采样、logcat.txt），汇总 report.md 和 memory.svg。
"""

from __future__ import annotations

import argparse
import json
import math
import random
import re
import sqlite3
import subprocess
import sys
import threading
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import stability as st  # noqa: E402

ROOT = st.ROOT
PKG = st.PKG
DE_DB = f"/data/user_de/0/{PKG}/databases/tracker.db"
START = (30.2741, 120.1551)

CE_DIR = f"/data/data/{PKG}/databases"

# 状态：静止、屋里（轻晃）、坐车（关 Wi-Fi），另有三个场景，场景里不注入故障，只看判断：
# - walk_home：连着锚 Wi-Fi 屋里走动（像在动、不挪地方、Wi-Fi 开着）；
# - car_wifi_join：连着车载 Wi-Fi 上车。模拟器只有一个 Wi-Fi，上车时断开 15 秒再连上，等于离开家里的 Wi-Fi、连上车上的；
# - car_wifi：带着连着的 Wi-Fi 上车，Wi-Fi 一直不断（随身 Wi-Fi）。模拟器没有基站和网络定位，App 收不到「离开信号」，
#   刚在家量过还在冷却中就上车时要等冷却过了才量（见 docs/stability-report.md 交付前最终压测一节）
KINDS = ["still", "moving", "zone", "walk_home", "car_wifi", "car_wifi_join"]
KIND_WEIGHTS = [40, 30, 16, 7, 4, 4]
KIND_LENGTH = {
    "still": (360, 900),
    "moving": (300, 600),
    "zone": (240, 480),
    "walk_home": (1200, 1800),
    "car_wifi": (300, 600),
    "car_wifi_join": (300, 600),
}
SCENARIOS = ("walk_home", "car_wifi", "car_wifi_join")
# 卫星点跟着往北走的状态
DRIVING = ("moving", "car_wifi", "car_wifi_join")

# 卡死的判法：没有在挡上报的故障、也不在故障恢复宽限里，服务器这么久没收到点就算卡住一次
# （车载 Wi-Fi 那段转到在路上以后按坐车算）
GAP_LIMIT = {"moving": 180, "still": 450, "zone": 450, "walk_home": 450, "car_wifi": 450, "car_wifi_join": 450}
# 车载 Wi-Fi：上车后多久内要转在路上（单测是开车后 3 分钟，模拟器的晃法不是真车，留 2 分钟余量）
CAR_WIFI_LIMIT = 300
# 屋里走动：头几分钟是从上一个状态切过来（刚从路上回来时还在路上），不算；之后每 30 分钟最多开 3 次卫星，
# 不报卫星点（主坐标）、不转在路上
WALK_SETTLE = 300
WALK_PROBES_PER_30MIN = 3
# 每类故障恢复时间的门槛（从清掉故障到服务器收到下一个点）：静止时 5 分钟呼吸一次，再留余量；5xx、超时后退避最多 5 分钟
RECOVERY_LIMIT = 390
REBOOT_LIMIT = 300

# 挡上报的故障：期间收不到是应该的
# 电池受限：系统把前台服务降成普通服务，后台定位、闹钟都被限，期间收不到是应该的；清掉后要按时回到前台（审计高 7）
BLOCKING = {
    "no_network",
    "flaky_network",
    "server_5xx",
    "server_hang",
    "airplane",
    "reboot",
    "process_kill",
    "battery_restricted",
    "queue_full",
    "migration_fail",
}
# 只在坐车时注入：要连续的卫星点（GPS 不回、改钟后卫星点照报）或密集的上报（队列满）
MOVING_ONLY = {"gps_silent", "clock_net_gps", "queue_full"}
ALL_FAULTS = [
    "no_network",
    "flaky_network",
    "server_5xx",
    "server_hang",
    "gps_silent",
    "amap_error",
    "no_sensors",
    "airplane",
    "clock_change",
    "storage_full",
    "process_kill",
    "reboot",
    "low_battery",
    "battery_restricted",
    "clock_net_gps",
    "storage_full_sent",
    "queue_full",
    "migration_fail",
]
FAULT_TITLE = {
    "no_network": "没网",
    "flaky_network": "网时断时续",
    "server_5xx": "服务器 5xx",
    "server_hang": "服务器超时（收了不回）",
    "gps_silent": "GPS 永远不回",
    "amap_error": "高德报错（Key 无效）",
    "no_sensors": "传感器没数据",
    "airplane": "飞行模式",
    "clock_change": "时间被改",
    "storage_full": "存储满",
    "process_kill": "进程被杀",
    "reboot": "重启",
    "low_battery": "低电",
    "battery_restricted": "电池受限（开了又关）",
    "clock_net_gps": "改钟后先网络点再卫星点",
    "storage_full_sent": "存储满时已发点删不掉",
    "queue_full": "队列满到上限",
    "migration_fail": "旧版迁移失败",
}


def now() -> float:
    return time.time()


@dataclass
class Event:
    kind: str
    start: float
    end: float | None = None
    segment: str = ""
    note: str = ""
    # 重启：模拟器真的关下去的时刻（之后服务器收到的点只能是开机后发的）
    down: float | None = None
    # 电池受限：取消受限后多少秒前台服务回到前台（等不到是 None）
    fg_back: float | None = None
    # 存储满时已发点删不掉：恢复联网的时刻（之前断网挡上报，之后存储满着也要照常报）
    mid: float | None = None
    # 故障自己的检查（旧版迁移失败：旧库改名备份、里面的点还在），没有是 None
    check: bool | None = None


@dataclass
class Segment:
    kind: str  # KINDS 之一
    start: float
    end: float | None = None


class Mover(threading.Thread):
    """模拟手机的动静：moving、car_wifi 时每 0.25 秒换一次加速度（车上晃）、每秒一个往北的卫星点（15 节）；zone 时轻晃、
    不动；walk_home 时晃得像在走、不挪地方；still 时不晃。gps=False 时停发卫星点（GPS 永远不回）。"""

    def __init__(self, emu: st.Emulator):
        super().__init__(daemon=True)
        self.emu = emu
        self.mode = "still"
        self.gps = True
        self.lat, self.lon = START
        self.stopped = threading.Event()
        self.rng = random.Random()

    def run(self) -> None:
        last_geo = 0.0
        resting = False
        while not self.stopped.is_set():
            mode = self.mode
            if mode == "still":
                if not resting:
                    self.emu.adb("emu", "sensor", "set", "acceleration", "0:9.81:0", timeout=15)
                    resting = True
            else:
                resting = False
                amp = {"zone": 0.6, "walk_home": 3.0}.get(mode, 6.0)
                x = self.rng.uniform(-amp, amp)
                y = 9.81 + self.rng.uniform(-amp * 0.6, amp * 0.6)
                z = self.rng.uniform(-amp, amp)
                self.emu.adb("emu", "sensor", "set", "acceleration", f"{x:.2f}:{y:.2f}:{z:.2f}", timeout=15)
            if self.gps and now() - last_geo >= 1:
                last_geo = now()
                if mode in DRIVING:
                    self.lat += 7.7 / 111320
                self.emu.adb(
                    "emu",
                    "geo",
                    "fix",
                    f"{self.lon:.6f}",
                    f"{self.lat:.6f}",
                    "10",
                    "8",
                    "15" if mode in DRIVING else "0",
                    timeout=15,
                )
            self.stopped.wait(0.25 if mode != "still" else 1.0)


class Lane:
    def __init__(
        self,
        idx: int,
        base: Path,
        apk: Path,
        hours: float,
        seed: int,
        kill_faults: bool,
        scale: float,
        only: list[str] | None,
        kinds: list[str] | None = None,
    ):
        self.idx = idx
        self.dir = base / f"lane-{idx}"
        self.dir.mkdir(parents=True, exist_ok=True)
        self.apk = apk
        self.hours = hours
        self.rng = random.Random(seed)
        self.faults = only or [
            f for f in ALL_FAULTS if kill_faults or f not in ("process_kill", "reboot", "migration_fail")
        ]
        self.scale = scale
        self.kinds = kinds or KINDS
        self.device_id = str(91000000 + idx * 1000 + seed % 1000)
        self.mode_file = self.dir / "server-mode"
        self.mode_file.write_text("ok")
        self.events: list[Event] = []
        self.segments: list[Segment] = []
        self.notes: list[str] = []
        # 日志按（第几台模拟器，日志 id）存：模拟器被外部结束重开后库是新的，id 从头来
        self.log_rows: dict[tuple[int, int], tuple[int, str]] = {}
        self.generation = 0
        self.last_log_id = 0
        self.memory: list[tuple[float, str, int]] = []
        self.emu: st.Emulator | None = None
        self.recorder: st.Recorder | None = None
        self.mover: Mover | None = None
        self.logcat: st.Logcat | None = None
        self.sampler_stop = threading.Event()
        self.lock = threading.Lock()
        self.start_at = 0.0
        self.end_at = 0.0
        self.lost_emulator: list[float] = []
        # 断网的时刻（没网、断续每次断开、飞行模式、重启、被杀）：这一刻在途的上传服务器收到了、手机没收到回复，会重发一次
        self.cuts: list[float] = []
        # 正在注入断网类故障（这时不按状态去开关 Wi-Fi）
        self.net_fault = False

    # ---------------------------------------------------------------- 小工具
    def say(self, msg: str) -> None:
        st.say(f"[lane {self.idx}] {time.strftime('%H:%M:%S')} {msg}")

    def sh(self, cmd: str, timeout: int = 60) -> str:
        return self.emu.sh(cmd, timeout=timeout)

    def sql(self, query: str, timeout: int = 60) -> str:
        return self.sh(f'sqlite3 {DE_DB} "{query}"', timeout=timeout)

    def alive(self) -> bool:
        return self.emu.proc.poll() is None and self.sh("getprop sys.boot_completed", timeout=10) == "1"

    def root(self) -> None:
        self.emu.adb("root", timeout=30)
        time.sleep(3)
        self.emu.adb("wait-for-device", timeout=60)

    # ---------------------------------------------------------------- 准备
    def boot(self) -> None:
        self.emu = st.Emulator(self.dir)
        took = self.emu.start()
        self.root()
        self.say(f"模拟器 {self.emu.serial} 开机 {took:.0f} 秒")

    def setup(self) -> None:
        self.boot()
        self.recorder = st.Recorder(self.dir)
        # 记录服务换成能注入故障的那一版（同一个脚本，多一个模式文件参数）
        self.recorder.proc.terminate()
        self.recorder.proc.wait(10)
        self.recorder.proc = subprocess.Popen(
            [
                sys.executable,
                str(st.TOOLS / "recorder.py"),
                str(self.recorder.port),
                str(self.recorder.path),
                str(self.mode_file),
            ]
        )
        time.sleep(1)
        self.logcat = st.Logcat(self.emu, self.dir / "logcat.txt")
        self.logcat.start()
        self.install()
        self.mover = Mover(self.emu)
        self.mover.start()
        threading.Thread(target=self.sample_loop, daemon=True).start()

    def install(self) -> None:
        self.emu.adb("uninstall", PKG, timeout=60)
        r = self.emu.adb("install", "-r", "-g", str(self.apk), timeout=300)
        if "Success" not in r.stdout:
            raise RuntimeError(f"装包失败：{r.stdout}{r.stderr}")
        url = f"http://10.0.2.2:{self.recorder.port}/"
        prefs = (
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            f'    <string name="id">{self.device_id}</string>\n'
            f'    <string name="url">{url}</string>\n'
            '    <string name="accuracy">medium</string>\n'
            '    <long name="interval" value="300" />\n'
            '    <long name="distance" value="75" />\n'
            '    <boolean name="buffer" value="true" />\n'
            '    <boolean name="stop_detection" value="true" />\n'
            f'    <long name="update_checked_at" value="{int(now() * 1000)}" />\n'
            "</map>\n"
        )
        local = self.dir / "prefs.xml"
        local.write_text(prefs)
        data = f"/data/data/{PKG}"
        self.sh(f"mkdir -p {data}/shared_prefs")
        self.emu.adb("push", str(local), f"{data}/shared_prefs/{PKG}_preferences.xml")
        uid = self.sh(f"stat -c %u {data}")
        self.sh(f"chown -R {uid}:{uid} {data}/shared_prefs && restorecon -R {data}/shared_prefs")
        self.sh(f"dumpsys deviceidle whitelist +{PKG}")
        self.sh(f"am start -W -n {PKG}/.MainActivity", timeout=60)
        config = ""
        for _ in range(20):
            time.sleep(1)
            config = self.sql("select json from Config")
            # 刚装好、库还没建时 sqlite3 回的是报错文字，不是配置
            if config.startswith("{"):
                break
        if url not in config or "example" in config:
            self.emu.adb("uninstall", PKG, timeout=60)
            raise RuntimeError(f"SDK 的服务器地址不是本机记录服务，已卸载：{config}")
        self.start_tracking()
        self.sh("input keyevent KEYCODE_HOME")
        self.say(f"开始追踪（设备号 {self.device_id}，服务器 {url}）")

    def start_tracking(self) -> None:
        self.sh(f"am start -W -a android.intent.action.VIEW -d {PKG}://action/start", timeout=60)

    def stop_tracking(self) -> None:
        self.sh(f"am start -W -a android.intent.action.VIEW -d {PKG}://action/stop", timeout=60)

    # ---------------------------------------------------------------- 采样
    def pull_logs(self) -> None:
        out = self.sql(
            f"select id, time, replace(message, char(10), ' ') from LogEntry where id > {self.last_log_id} order by id"
        )
        for line in out.splitlines():
            parts = line.split("|", 2)
            if len(parts) == 3 and parts[0].isdigit() and parts[1].isdigit():
                self.log_rows[(self.generation, int(parts[0]))] = (int(parts[1]), parts[2])
                self.last_log_id = max(self.last_log_id, int(parts[0]))

    def sample_loop(self) -> None:
        n = 0
        while not self.sampler_stop.wait(60):
            try:
                if not self.alive():
                    continue
                pid = self.sh(f"pidof {PKG}", timeout=15)
                m = re.search(r"TOTAL PSS:\s+(\d+)", self.sh(f"dumpsys meminfo {PKG}", timeout=60))
                if pid and m:
                    with self.lock:
                        self.memory.append((now(), pid, int(m.group(1))))
                n += 1
                if n % 3 == 0:
                    with self.lock:
                        self.pull_logs()
            except Exception as e:  # 采样失败不影响压测，记下来
                self.notes.append(f"{time.strftime('%H:%M:%S')} 采样失败：{e}")

    # ---------------------------------------------------------------- 时间线
    def set_segment(self, kind: str) -> None:
        t = now()
        if self.segments and self.segments[-1].end is None:
            self.segments[-1].end = t
        self.segments.append(Segment(kind, t))
        self.mover.mode = kind
        self.sh(self.net_on())

    def net_on(self) -> str:
        """联网的命令：坐车时出了门，连不上家里的 Wi-Fi，只开移动数据；别的状态 Wi-Fi 和移动数据都开。
        车载 Wi-Fi（car_wifi）那段故意开着 Wi-Fi 开车，看判断层几分钟内量出离开了锚点、转在路上。"""
        wifi = "disable" if self.mover.mode == "moving" else "enable"
        return f"svc wifi {wifi}; svc data enable"

    def wait_recovered(self, ev: Event) -> None:
        """清掉故障后等服务器收到下一个点（最多等到门槛再多 30 秒），再往下走：下一个故障不压在恢复上，
        压测结束前最后一个故障也量得到恢复时间。等不到就照常往下，报告里按超门槛算。"""
        since = ev.down or ev.end or now()
        limit = REBOOT_LIMIT if ev.kind == "reboot" else RECOVERY_LIMIT
        while now() < since + limit + 30:
            if any(
                r["recv"] >= since and r["params"].get("id") == self.device_id and not r["params"].get("alarm")
                for r in self.recorder.rows()
            ):
                return
            self.wait(5)

    def foreground(self) -> bool:
        return "isForeground=true" in self.sh(f"dumpsys activity services {PKG}", timeout=30)

    def wait_foreground(self, since: float, timeout: float) -> float | None:
        """等前台服务回到前台，返回从 since 起的秒数，超时返回 None"""
        while now() - since < timeout:
            if self.foreground():
                return round(now() - since, 1)
            self.wait(2)
        return None

    def rows_since(self, t: float) -> list[dict]:
        return [r for r in self.recorder.rows() if r["recv"] >= t and r["params"].get("id") == self.device_id]

    def wait(self, seconds: float) -> None:
        end = now() + seconds
        while now() < end:
            time.sleep(min(5, max(0, end - now())))
            self.check_emulator()
            self.keep_wifi()

    def keep_wifi(self) -> None:
        """Wi-Fi 要跟着状态：刚开机时 Wi-Fi 服务晚一步起来，会把关掉的 Wi-Fi 又打开，所以每次等待都核对一下"""
        if self.net_fault or not self.mover:
            return
        want = "0" if self.mover.mode == "moving" else "1"
        got = self.sh("settings get global wifi_on", timeout=10)
        if got in ("0", "1") and got != want:
            self.sh(self.net_on())

    def check_emulator(self) -> None:
        """模拟器被外面关了（别的任务清理模拟器）：重开一台接着跑，记下来，这段不算 App 的问题"""
        if self.emu.proc.poll() is None:
            return
        t = now()
        self.lost_emulator.append(t)
        self.say("模拟器进程没了（外部原因），重开一台接着跑")
        self.notes.append(f"{time.strftime('%H:%M:%S')} 模拟器进程被外部结束，重开（之前 10 分钟产出的点不计丢点）")
        st.release_port(self.emu.port)
        self.mover.stopped.set()
        self.logcat.stopped.set()
        self.boot()
        self.logcat = st.Logcat(self.emu, self.dir / "logcat.txt")
        self.logcat.start()
        self.last_log_id = 0
        self.generation += 1
        self.install()
        self.mover = Mover(self.emu)
        self.mover.start()
        self.mover.mode = self.segments[-1].kind if self.segments else "still"
        self.sh(self.net_on())

    def run(self) -> None:
        self.start_at = now()
        deadline = self.start_at + self.hours * 3600
        bag: list[str] = []
        while now() < deadline - 120:
            kind = self.rng.choices(KINDS, weights=[w if k in self.kinds else 0 for k, w in zip(KINDS, KIND_WEIGHTS)])[
                0
            ]
            last = self.segments[-1].kind if self.segments else None
            # 屋里走动要先有锚点（坐过车、回来连上 Wi-Fi 时记下的）；车载 Wi-Fi 要从屋里、静止上车
            if kind == "walk_home" and not any(s.kind in DRIVING for s in self.segments):
                kind = "zone"
            if kind in ("car_wifi", "car_wifi_join") and last in (None, *DRIVING):
                kind = "still"
            seg_len = min(self.rng.uniform(*KIND_LENGTH[kind]) * self.scale, deadline - now())
            self.set_segment(kind)
            seg_end = now() + seg_len
            if kind == "car_wifi_join":
                self.net_fault = True
                self.sh("svc wifi disable")
                self.wait(15)
                self.net_fault = False
                self.sh(self.net_on())
            if kind in SCENARIOS:
                self.say(f"场景 {kind}（{seg_len:.0f} 秒），不注入故障")
                self.wait(max(0, seg_end - now()))
                continue
            # 进入新状态先稳一会（判断层要 1–2 分钟判出来），再注入
            self.wait(min(150, seg_len / 3))
            if not bag:
                bag = self.faults[:]
                self.rng.shuffle(bag)
                bag.reverse()
            fault = bag.pop()
            if fault in MOVING_ONLY and kind != "moving":
                # 放回去，换一个能在这个状态注入的；袋里只剩坐车才注入的就这一段不注入
                bag.insert(0, fault)
                rest = [f for f in bag if f not in MOVING_ONLY]
                if not rest:
                    self.wait(max(0, seg_end - now()))
                    continue
                bag.remove(rest[-1])
                fault = rest[-1]
            try:
                self.inject(fault, kind)
            except Exception as e:
                self.notes.append(f"{time.strftime('%H:%M:%S')} 注入 {fault} 出错：{e}")
                self.say(f"注入 {fault} 出错：{traceback.format_exc()}")
                self.restore_all()
            if self.events and self.events[-1].end is not None:
                if fault in MOVING_ONLY:
                    # 清掉后还要坐一会儿车，看卫星点接着报
                    seg_end = max(seg_end, self.events[-1].end + 240)
                self.wait_recovered(self.events[-1])
            self.wait(max(0, seg_end - now()))
        self.end_at = now()
        if self.segments and self.segments[-1].end is None:
            self.segments[-1].end = self.end_at

    # ---------------------------------------------------------------- 故障
    def inject(self, fault: str, segment: str) -> None:
        ev = Event(fault, now(), segment=segment)
        self.events.append(ev)
        hold = self.rng.uniform(120, 300) * self.scale
        self.say(f"注入 {FAULT_TITLE[fault]}（{segment}，{hold:.0f} 秒）")
        if fault in ("no_network", "airplane", "reboot", "process_kill", "storage_full_sent", "migration_fail"):
            self.cuts.append(now())
        self.net_fault = fault in ("no_network", "flaky_network", "airplane", "reboot")
        if fault == "no_network":
            self.sh("svc wifi disable; svc data disable")
            self.wait(hold)
            self.sh(self.net_on())
        elif fault == "flaky_network":
            end = now() + hold
            on = False
            while now() < end:
                if not on:
                    self.cuts.append(now())
                self.sh(self.net_on() if on else "svc wifi disable; svc data disable")
                on = not on
                self.wait(self.rng.uniform(10, 30))
            self.sh(self.net_on())
        elif fault in ("server_5xx", "server_hang"):
            self.mode_file.write_text("5xx" if fault == "server_5xx" else "hang")
            self.wait(hold)
            self.mode_file.write_text("ok")
        elif fault == "gps_silent":
            self.mover.gps = False
            self.wait(max(hold, 240 * self.scale))
            self.mover.gps = True
        elif fault == "amap_error":
            # 关开一次追踪：新的一段先试高德，Key 无效连着报错，切系统定位
            self.stop_tracking()
            time.sleep(5)
            self.start_tracking()
            self.sh("input keyevent KEYCODE_HOME")
            ev.note = "关开追踪，新一段先试高德"
        elif fault == "no_sensors":
            self.sh(f"cmd sensorservice set-uid-state {PKG} idle")
            self.wait(hold)
            self.sh(f"cmd sensorservice reset-uid-state {PKG}")
        elif fault == "airplane":
            self.sh("cmd connectivity airplane-mode enable")
            self.wait(hold)
            self.sh("cmd connectivity airplane-mode disable")
        elif fault == "clock_change":
            shift = self.rng.choice([-7200, -86400, 3600])
            self.sh("settings put global auto_time 0")
            self.set_clock(now() + shift)
            ev.note = f"系统时间 {shift:+d} 秒"
            self.wait(hold)
            self.set_clock(now())
        elif fault == "storage_full":
            ev.note = "填满 /data：" + self.fill_storage(1024)
            self.wait(hold)
            self.sh("rm -f /data/local/tmp/stress_fill")
        elif fault == "storage_full_sent":
            # 审计高 5：先断网攒几条进离线队列，再填满存储、恢复联网。发出去的点从库里删不掉（删也要写日志文件），
            # 以前删不掉就停在这一条、后面的全卡住；现在记下发过的、接着发后面的，存储好了再补删
            self.net_fault = True
            self.sh("svc wifi disable; svc data disable")
            self.wait(self.rng.uniform(120, 180) * self.scale)
            queued = self.sql("select count(*) from Position")
            before = self.sql("select max(id) from LogEntry") or "0"
            # 一点不留：留十几 KB 时删除还写得进（库的日志文件够用），碰不到「删不掉」
            df = self.fill_storage(0)
            self.sh(self.net_on())
            self.net_fault = False
            ev.mid = now()
            self.wait(hold)
            # 存储满着时日志多半也写不进，App 记没记「删不掉、跳过」不一定看得到；看库里还剩几条：都发出去了还剩着就是删不掉
            left = self.sql("select count(*) from Position")
            self.sh("rm -f /data/local/tmp/stress_fill")
            self.wait(20)
            skipped = self.sql(
                f"select count(*) from LogEntry where id > {before} and message like '%sent point not removed%'"
            )
            ev.note = (
                f"断网攒了 {queued} 条后填满 /data（{df}）再联网；清掉填充前库里还剩 {left} 条；"
                f"App 日志记「发出去的删不掉、跳过」{skipped} 次"
            )
        elif fault == "clock_net_gps":
            # 审计高 10：卫星点和网络点以前共用「报过的定位时间」，时间改快一天时报了一个网络点，门槛就被抬高一天，
            # 改回来后卫星点一天报不出去。坐车时只有卫星断了 3 分钟（ReportPolicy.GPS_GIVE_UP）才去取网络定位：
            # 先停卫星、改快一天，模拟器没有网络定位，装一个测试定位源 network 往里推点，等 App 报出网络点，
            # 再改回时间、恢复卫星，看卫星点接着报。模拟器的 GPS 停发 geo fix 后还按最后位置每秒出点，停卫星要用测试定位源
            # 顶替 gps（不往里推点就是没卫星），撤掉后回到模拟器的 GPS
            before = self.sql("select max(id) from LogEntry") or "0"
            self.sh(
                "cmd location providers add-test-provider gps --requiresSatellite; "
                "cmd location providers set-test-provider-enabled gps true; "
                "cmd location providers add-test-provider network --requiresNetwork; "
                "cmd location providers set-test-provider-enabled network true"
            )
            self.sh("settings put global auto_time 0")
            self.mover.gps = False
            self.set_clock(now() + 86400)
            net = "0"
            t0 = now()
            while now() - t0 < 420 and net == "0":
                self.sh(
                    "cmd location providers set-test-provider-location network "
                    f"--location {self.mover.lat:.6f},{self.mover.lon + 0.0004:.6f} --accuracy 40"
                )
                self.wait(5)
                net = self.sql(
                    f"select count(*) from LogEntry where id > {before} and message like 'Report moving % network (net only) at %'"
                )
            self.set_clock(now())
            self.mover.gps = True
            self.sh(
                "cmd location providers remove-test-provider gps; cmd location providers remove-test-provider network"
            )
            ev.check = net != "0"
            ev.note = f"停卫星、时间改快一天，{now() - t0:.0f} 秒后" + (
                "报出了网络点，改回时间、恢复卫星" if ev.check else "还没报出网络点（这次没测到）"
            )
        elif fault == "queue_full":
            # 审计中 1：队列到 5 万条上限时，正在发的那条被裁掉，确认时以前按 MIN(id) 删，删掉的是下一条没发的（丢点）。
            # 不真塞 5 万条：队列条数按首尾行号之差算（Position.sq 的 span），一条上传在途（记录服务慢回）、库里只剩它时
            # 把自增号往后拨 5 万，下一个点进来就超上限、裁掉在途那条。被裁的那条服务器已经收到了，丢点检查照常算
            before = self.sql("select max(id) from LogEntry") or "0"
            self.mode_file.write_text("slow")
            end = now() + hold
            rounds = 0
            seen = len(self.recorder.rows())
            while now() < end:
                rows = self.recorder.rows()
                fresh = [r for r in rows[seen:] if r["params"].get("id") == self.device_id]
                seen = len(rows)
                ts = fresh[-1]["params"].get("timestamp", "") if fresh else ""
                if ts.isdigit():
                    bumped = self.sql(
                        # App 的库日志文件常驻（0 字节的 -journal）：命令行默认提交后删掉它，改成截断，不动 App 的文件
                        "PRAGMA journal_mode = TRUNCATE; "
                        "UPDATE sqlite_sequence SET seq = seq + 50000 WHERE name = 'Position' "
                        "AND (SELECT count(*) FROM Position) = 1 "
                        f"AND (SELECT time / 1000 FROM Position) = {ts}; SELECT changes();"
                    )
                    rounds += bumped.strip().endswith("1")
                self.wait(1)
            self.mode_file.write_text("ok")
            trims = self.sql(
                f"select count(*) from LogEntry where id > {before} and message like '%offline queue over%'"
            )
            ev.note = f"拨了 {rounds} 次自增号，App 记「超上限丢最旧」{trims} 次"
        elif fault == "migration_fail":
            # 审计高 1：覆盖安装旧 App 后第一次启动，旧库迁移失败以前直接删库（没发出去的点和告警全丢）；
            # 现在改名放进 legacy-backup/。放一个迁移必失败的旧版库（有上次迁移留下的 Position_v2），杀进程让它重新起来跑迁移
            local = self.dir / "legacy.db"
            make_legacy_db(local)
            self.sh(f"mkdir -p {CE_DIR}")
            self.emu.adb("push", str(local), f"{CE_DIR}/tracker.db", timeout=60)
            uid = self.sh(f"stat -c %u /data/data/{PKG}")
            self.sh(f"chown -R {uid}:{uid} {CE_DIR} && restorecon -R {CE_DIR}")
            backups = self.sh(f"ls {CE_DIR}/legacy-backup 2>/dev/null").split()
            pid = self.sh(f"pidof {PKG}")
            self.sh(f"kill -9 {pid}")
            t0 = now()
            while now() - t0 < 180 and not self.sh(f"pidof {PKG}"):
                self.wait(2)
            self.wait(15)
            # 旧库连同 -journal、-wal、-shm 一起改名，只数库本身
            fresh = [
                b
                for b in self.sh(f"ls {CE_DIR}/legacy-backup 2>/dev/null").split()
                if b not in backups and not b.endswith(("-journal", "-wal", "-shm"))
            ]
            kept = (
                self.sh(f"sqlite3 {CE_DIR}/legacy-backup/{fresh[0]} 'select count(*) from Position'")
                if len(fresh) == 1
                else ""
            )
            left = self.sh(f"ls {CE_DIR}/tracker.db 2>/dev/null")
            ev.check = len(fresh) == 1 and kept == str(LEGACY_ROWS) and not left
            ev.note = (
                f"kill -9 {pid}，{now() - t0:.0f} 秒内新进程 {self.sh(f'pidof {PKG}') or '没起来'}；"
                f"新备份 {fresh or '没有'}，里面旧点 {kept or '?'} 条（放进去 {LEGACY_ROWS} 条）；"
                f"原位置的旧库{'还在' if left else '已挪走'}"
            )
        elif fault == "process_kill":
            pid = self.sh(f"pidof {PKG}")
            self.sh(f"kill -9 {pid}")
            ev.note = f"kill -9 {pid}"
        elif fault == "reboot":
            self.emu.adb("reboot", timeout=60)
            self.emu.wait_offline()
            ev.down = now()
            self.emu.adb("wait-for-device", timeout=300)
            self.emu.wait_boot()
            self.root()
            ev.note = "adb reboot"
        elif fault == "low_battery":
            self.sh("dumpsys battery unplug; dumpsys battery set level 5")
            self.wait(hold)
            self.sh("dumpsys battery reset")
        elif fault == "battery_restricted":
            # 设置里电池「受限」= RUN_ANY_IN_BACKGROUND ignore。装包时加了电池优化豁免，系统只把前台服务降成普通服务、不停掉它，
            # 服务实例一直在；取消受限后要靠呼吸闹钟问系统、重新拉成前台（旧版压测 5b 修前 20 分钟不恢复）
            self.sh(f"cmd appops set {PKG} RUN_ANY_IN_BACKGROUND ignore")
            self.wait(hold)
            demoted = not self.foreground()
            self.sh(f"cmd appops set {PKG} RUN_ANY_IN_BACKGROUND allow")
            ev.end = now()
            ev.fg_back = self.wait_foreground(ev.end, RECOVERY_LIMIT + 30)
            ev.note = (
                f"受限 {hold:.0f} 秒后前台服务{'被降级' if demoted else '还在前台'}；"
                f"取消受限后{f' {ev.fg_back} 秒回到前台' if ev.fg_back is not None else ' 没回到前台'}"
            )
        self.net_fault = False
        if ev.end is None:
            ev.end = now()

    def fill_storage(self, leave_kb: int) -> str:
        """把 /data 填到只剩 leave_kb，返回填完的 df 一行"""
        free = self.sh("df -k /data | tail -1").split()
        kb = int(free[3]) if len(free) > 3 and free[3].isdigit() else 0
        self.sh(f"fallocate -l {max(kb - leave_kb, 1)}K /data/local/tmp/stress_fill", timeout=120)
        return self.sh("df -k /data | tail -1")

    def set_clock(self, t: float) -> None:
        self.sh("date " + time.strftime("%m%d%H%M%Y.%S", time.localtime(t)))

    def restore_all(self) -> None:
        self.net_fault = False
        self.mode_file.write_text("ok")
        self.mover.gps = True
        for cmd in (
            self.net_on(),
            f"cmd sensorservice reset-uid-state {PKG}",
            "cmd connectivity airplane-mode disable",
            "rm -f /data/local/tmp/stress_fill",
            "dumpsys battery reset",
            f"cmd appops set {PKG} RUN_ANY_IN_BACKGROUND allow",
            "cmd location providers remove-test-provider gps; cmd location providers remove-test-provider network",
        ):
            self.sh(cmd)
        self.set_clock(now())

    # ---------------------------------------------------------------- 收尾
    def drain(self) -> None:
        """全部恢复，等离线队列发完（最多 10 分钟），再取最后一次日志"""
        self.restore_all()
        if self.events and self.events[-1].end is None:
            self.events[-1].end = now()
        t0 = now()
        while now() - t0 < 600:
            left = self.sql("select count(*) from Position")
            if left == "0":
                break
            time.sleep(10)
        time.sleep(15)
        with self.lock:
            self.pull_logs()

    def teardown(self) -> None:
        self.sampler_stop.set()
        for t in (self.mover, self.logcat):
            if t:
                t.stopped.set()
        if self.emu and self.emu.proc:
            self.emu.stop()
        if self.recorder:
            self.recorder.stop()
        logs = [
            f"{time.strftime('%m-%d %H:%M:%S', time.localtime(t / 1000))} {m}"
            for _, (t, m) in sorted(self.log_rows.items())
        ]
        (self.dir / "sdk-log.txt").write_text("\n".join(logs) + "\n")
        (self.dir / "events.json").write_text(
            json.dumps(
                {
                    "device": self.device_id,
                    "start": self.start_at,
                    "end": self.end_at,
                    "faults": [asdict(e) for e in self.events],
                    "segments": [asdict(s) for s in self.segments],
                    "lost_emulator": self.lost_emulator,
                    "cuts": self.cuts,
                    "notes": self.notes,
                },
                ensure_ascii=False,
                indent=1,
            )
        )
        with open(self.dir / "memory.csv", "w") as f:
            f.write("time,pid,pss_kb\n")
            for t, pid, kb in self.memory:
                f.write(f"{t:.0f},{pid},{kb}\n")


LEGACY_ROWS = 3


def make_legacy_db(path: Path) -> None:
    """根目录旧 App 的库（第 7 版，Position 带 alarm、provider 列），外加上一次迁移半截留下的 Position_v2：
    LegacyTrackerDb.migrate 一建 Position_v2 就失败、整个回滚，走「迁移失败」那条路。里面 2 个卫星点、1 条没发出去的告警"""
    path.unlink(missing_ok=True)
    db = sqlite3.connect(path)
    try:
        db.executescript(
            """
            CREATE TABLE Position (id INTEGER PRIMARY KEY AUTOINCREMENT, latitude REAL, longitude REAL, accuracy REAL,
                time INTEGER NOT NULL, altitude REAL, speed REAL, bearing REAL, battery INTEGER, charging INTEGER,
                alarm TEXT, powerSave INTEGER, provider TEXT, state TEXT);
            CREATE TABLE Config (id INTEGER PRIMARY KEY, json TEXT NOT NULL);
            CREATE TABLE State (id INTEGER PRIMARY KEY, json TEXT NOT NULL);
            CREATE TABLE LogEntry (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, message TEXT NOT NULL);
            CREATE TABLE Position_v2 (id INTEGER PRIMARY KEY);
            PRAGMA user_version = 7;
            """
        )
        t = int(now() * 1000) - 3_600_000
        db.executemany(
            "INSERT INTO Position(latitude, longitude, accuracy, time, battery, charging, alarm, provider) "
            "VALUES (?, ?, ?, ?, 50, 0, ?, 'gps')",
            [
                (START[0], START[1], 10.0, t, None),
                (START[0], START[1], 10.0, t + 60_000, None),
                (None, None, None, t + 90_000, "lowBattery"),
            ],
        )
        db.commit()
    finally:
        db.close()


# ---------------------------------------------------------------- 分析


REPORT_RE = re.compile(r"^Report (\S+) (\S+) .* at (\d+)$")


def analyze_lane(d: Path) -> dict:
    meta = json.loads((d / "events.json").read_text())
    device = meta["device"]
    rows = []
    with open(d / "points.jsonl", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                r = json.loads(line)
                rows.append(r)
    foreign = [r for r in rows if r["params"].get("id") != device]
    rows = [r for r in rows if r["params"].get("id") == device]
    points = [r for r in rows if not r["params"].get("alarm")]
    alarms = [r["params"]["alarm"] for r in rows if r["params"].get("alarm")]
    recv = sorted(r["recv"] for r in points)

    # 丢点：App 日志里每条「Report … at <毫秒>」都要在服务器收到的时间戳里（秒，按多重集合对）
    produced: list[int] = []
    rescues: list[str] = []
    crashes = 0
    for line in (d / "sdk-log.txt").read_text().splitlines():
        msg = line[15:]
        m = REPORT_RE.match(msg)
        if m:
            produced.append(int(m.group(3)) // 1000)
        if msg.startswith("Fault rescue"):
            rescues.append(line)
        if msg.startswith("Fault crash"):
            crashes += 1
    lost_window = [(t - 600, t) for t in meta["lost_emulator"]]
    got: dict[int, int] = {}
    for r in points:
        ts = r["params"].get("timestamp", "")
        if ts.isdigit():
            got[int(ts)] = got.get(int(ts), 0) + 1
    need: dict[int, int] = {}
    for t in produced:
        need[t] = need.get(t, 0) + 1
    lost = []
    for t, n in need.items():
        if got.get(t, 0) < n:
            lost.append(t)
    lost_excused = [t for t in lost if any(a <= t <= b for a, b in lost_window)]
    lost = [t for t in lost if t not in lost_excused]
    # 服务器多收的：日志写不进去（存储满）时发出的点在日志里对不上；断网那一刻在途的会重发一次（服务器收到了、手机没收到回复）
    extra = sorted(t for t, n in got.items() if n > need.get(t, 0))
    # 重复按整条参数比：同一秒里 App 可以发两条不同的点（比如退出飞行模式那一刻「换状态」和「进屋取点」各一条，
    # bumps 不同），那不是重复；重发的那条和原来的一模一样
    first_recv: dict[tuple, float] = {}
    keys = []
    for r in points:
        k = tuple(sorted(r["params"].items()))
        keys.append(k)
        first_recv.setdefault(k, r["recv"])
    counts: dict[tuple, int] = {}
    for k in keys:
        counts[k] = counts.get(k, 0) + 1
    # 坐车进出时脚本切 Wi-Fi / 移动数据，这一刻在途的上传也会断、重发一次，和断网一样算
    segs = meta["segments"]
    cuts = meta.get("cuts", []) + [
        b["start"]
        for a, b in zip([{"kind": "still"}, *segs], segs)
        if (a["kind"] == "moving") != (b["kind"] == "moving")
    ]
    resent = sum(n - 1 for k, n in counts.items() if n > 1 and any(abs(first_recv[k] - c) <= 5 for c in cuts))
    # App 同一秒里真报了几条一模一样的（日志里有几条 Report），多出来的那几条不算重复
    extra_by_second: dict[int, int] = {}
    keys_by_second: dict[int, int] = {}
    for k, n in counts.items():
        ts = dict(k).get("timestamp", "")
        if n > 1 and ts.isdigit() and not any(abs(first_recv[k] - c) <= 5 for c in cuts):
            extra_by_second[int(ts)] = extra_by_second.get(int(ts), 0) + n - 1
        if ts.isdigit():
            keys_by_second[int(ts)] = keys_by_second.get(int(ts), 0) + 1
    duplicates = sum(
        max(0, extra - max(0, need.get(sec, 0) - keys_by_second[sec])) for sec, extra in extra_by_second.items()
    )
    log_text = (d / "sdk-log.txt").read_text()
    unwritten = sum(int(m.group(1)) for m in re.finditer(r"Log: (\d+) entries not written", log_text))
    sent_not_removed = log_text.count("sent point not removed")
    queue_trims = log_text.count("offline queue over")

    # 卡死：不在挡上报的故障里、也不在恢复宽限里，空档超过所在状态的上限
    faults = meta["faults"]
    blocked = []
    for e in faults:
        end = e["end"] or meta["end"]
        if e["kind"] in BLOCKING:
            blocked.append((e["start"], end + RECOVERY_LIMIT))
        elif e["kind"] in ("amap_error", "clock_change"):
            blocked.append((e["start"], end + RECOVERY_LIMIT))
        elif e["kind"] == "clock_net_gps":
            # 改回来以后卫星点要照常报：不给宽限，按坐车的上限查卡死
            blocked.append((e["start"], end))
        elif e["kind"] == "storage_full_sent":
            # 断网那一段挡上报；恢复联网后存储满着也要照常发
            blocked.append((e["start"], e.get("mid") or end))
    for t in meta["lost_emulator"]:
        blocked.append((t - 600, t + 600))

    scenarios = analyze_scenarios(meta, points, log_text)
    # 车载 Wi-Fi 那段转到在路上以后按坐车查
    driving_from = {sc["start"]: sc["moving_at"] for sc in scenarios if sc["moving_at"]}

    def seg_kind(t: float) -> str:
        for s in segs:
            if s["start"] <= t <= (s["end"] or meta["end"]):
                if t >= driving_from.get(s["start"], math.inf):
                    return "moving"
                return s["kind"]
        return "still"

    marks = [meta["start"] + 180] + [t for t in recv if t > meta["start"] + 180] + [meta["end"]]
    stuck = []
    for a, b in zip(marks, marks[1:]):
        if any(x < b and y > a for x, y in blocked):
            continue
        # 换状态后判断层要一会儿才判出来：按两头里宽的上限
        limit = max(GAP_LIMIT[seg_kind(a)], GAP_LIMIT[seg_kind(b)])
        if b - a > limit:
            stuck.append({"from": a, "to": b, "gap": round(b - a), "limit": limit})

    # 每次故障的恢复时间：清掉故障后服务器收到下一个点用了多久；不挡上报的另看故障期间的最大空档
    recoveries = []
    gps_recv = sorted(r["recv"] for r in points if r["params"].get("provider") == "gps")
    for e in faults:
        end = e["end"] or meta["end"]
        # 重启从模拟器关下去那一刻起找下一个点：开机后 App 可能在压测脚本等完开机、拿回 root 之前就报了；
        # 改钟后网络点那一类看的是下一个卫星点
        pool = gps_recv if e["kind"] == "clock_net_gps" else recv
        after = next((t for t in pool if t >= (e.get("down") or end)), None)
        since = e.get("mid") or e["start"]
        during = [t for t in recv if since <= t <= end]
        inner = [since] + during + [end]
        max_gap = max(b - a for a, b in zip(inner, inner[1:])) if e["kind"] not in BLOCKING else None
        limit = {"reboot": REBOOT_LIMIT, "clock_net_gps": GAP_LIMIT["moving"]}.get(e["kind"], RECOVERY_LIMIT)
        rec = None if after is None else round(after - end, 1)
        if e["kind"] == "reboot":
            rec = None if after is None else round(after - e["start"], 1)
        ok = rec is not None and rec <= limit
        # 恢复前模拟器被外部关掉、重开重装了：这一次量不到，不计
        lost_in = [t for t in meta["lost_emulator"] if e["start"] <= t <= end + limit and (after is None or after > t)]
        if e["kind"] == "battery_restricted":
            # 还要按时回到前台：只出点、一直是降级的普通服务不算恢复
            ok = ok and e.get("fg_back") is not None and e["fg_back"] <= limit
        if e["kind"] == "storage_full_sent" and max_gap is not None:
            # 恢复联网后存储满着也要照常报：期间的空档不超过所在状态的上限
            ok = ok and max_gap <= GAP_LIMIT.get(e["segment"], 450)
        if e.get("check") is not None:
            ok = ok and e["check"]
        if lost_in:
            ok = True
        recoveries.append(
            {
                "kind": e["kind"],
                "segment": e["segment"],
                "start": e["start"],
                "end": end,
                "recovery": rec,
                "max_gap_during": None if max_gap is None else round(max_gap),
                "ok": ok,
                "note": e.get("note", "") + ("；恢复前模拟器被外部关掉重开，量不到、不计" if lost_in else ""),
            }
        )

    logcat = d / "logcat.txt"
    fatal = anr = 0
    ui_opened: set[float] = set()
    if logcat.exists():
        seen = set()
        with open(logcat, encoding="utf-8", errors="replace") as f:
            for line in f:
                if line in seen:
                    continue
                seen.add(line)
                if "FATAL EXCEPTION" in line or ("AndroidRuntime" in line and PKG in line and "Process:" in line):
                    fatal += PKG in line
                if f"ANR in {PKG}" in line:
                    anr += 1
                if "wm_create_activity" in line and f"{PKG}/.MainActivity" in line:
                    ui_opened.add(float(line.split()[0]))

    mem = []
    with open(d / "memory.csv") as f:
        next(f)
        for line in f:
            t, pid, kb = line.strip().split(",")
            mem.append((float(t), pid, int(kb)))

    return {
        "dir": d.name,
        "device": device,
        "hours": (meta["end"] - meta["start"]) / 3600,
        "points": len(points),
        "alarms": alarms,
        "foreign": len(foreign),
        "produced": len(produced),
        "lost": lost,
        "lost_excused": lost_excused,
        "duplicates": duplicates,
        "resent": resent,
        "extra": len(extra),
        "unwritten_logs": unwritten,
        "sent_not_removed": sent_not_removed,
        "queue_trims": queue_trims,
        "scenarios": scenarios,
        "stuck": stuck,
        "recoveries": recoveries,
        "rescues": rescues,
        "component_crashes": crashes,
        "fatal": fatal,
        "anr": anr,
        "memory": mem,
        # 「高德报错」注入用 am start 关开追踪，会把界面拉起来；logcat 不一定记到那次建界面（进程刚被杀过重起时漏过），一起算
        "ui_opened": sorted(ui_opened | {e["start"] for e in faults if e["kind"] == "amap_error"}),
        "lost_emulator": meta["lost_emulator"],
        "notes": meta["notes"],
        "start": meta["start"],
    }


def log_times(log_text: str, year: int, needle: str) -> list[float]:
    """sdk-log.txt 里含 needle 的行的时刻（行首是「月-日 时:分:秒」，年份取压测开始那年）"""
    out = []
    for line in log_text.splitlines():
        if needle in line[15:]:
            try:
                out.append(time.mktime(time.strptime(f"{year}-{line[:14]}", "%Y-%m-%d %H:%M:%S")))
            except ValueError:
                continue
    return out


def analyze_scenarios(meta: dict, points: list[dict], log_text: str) -> list[dict]:
    """两个场景各段的结果：
    - 车载 Wi-Fi 上车（car_wifi）：从这段开始到服务器收到第一个在路上的点用了多久，门槛 CAR_WIFI_LIMIT；
      上车前最后一个点已经是在路上的（上一段还没判出停下）不算数。
    - 屋里走动（walk_home）：去掉头 WALK_SETTLE 秒，期间开卫星（日志 GPS on）的次数每 30 分钟不超过 WALK_PROBES_PER_30MIN，
      服务器没收到卫星点、没收到在路上的点。"""
    year = time.localtime(meta["start"]).tm_year
    gps_on = log_times(log_text, year, "GPS on")
    out = []
    for s in meta["segments"]:
        start, end = s["start"], s["end"] or meta["end"]
        # 这段中途模拟器被外部关掉、重开重装（锚点、冷却都清了）：判断从头来，不算这一段
        if any(start <= t <= end for t in meta["lost_emulator"]):
            continue
        inside = [r for r in points if start <= r["recv"] <= end]
        if s["kind"] in ("car_wifi", "car_wifi_join"):
            before = [r for r in points if r["recv"] < start]
            was_moving = bool(before) and before[-1]["params"].get("state") == "moving"
            moving_at = next((r["recv"] for r in inside if r["params"].get("state") == "moving"), None)
            took = None if moving_at is None else round(moving_at - start, 1)
            out.append(
                {
                    "kind": s["kind"],
                    "start": start,
                    "end": end,
                    "moving_at": moving_at,
                    "took": took,
                    "counted": not was_moving,
                    "ok": was_moving or (took is not None and took <= CAR_WIFI_LIMIT),
                    "note": "上车前还在路上，不算" if was_moving else "",
                }
            )
        elif s["kind"] == "walk_home":
            a = start + WALK_SETTLE
            if end - a < 300:
                continue
            probes = sum(a <= t <= end for t in gps_on)
            allowed = WALK_PROBES_PER_30MIN * math.ceil((end - a) / 1800)
            sat = sum(1 for r in inside if r["recv"] >= a and r["params"].get("provider") == "gps")
            moving = sum(1 for r in inside if r["recv"] >= a and r["params"].get("state") == "moving")
            out.append(
                {
                    "kind": "walk_home",
                    "start": start,
                    "end": end,
                    "moving_at": None,
                    "minutes": round((end - a) / 60, 1),
                    "probes": probes,
                    "allowed": allowed,
                    "sat_points": sat,
                    "moving_points": moving,
                    "counted": True,
                    "ok": probes <= allowed and sat == 0 and moving == 0,
                    "note": "",
                }
            )
    return out


def memory_stats(mem: list[tuple[float, str, int]], ui_opened: list[float] = (), lost: list[float] = ()) -> list[dict]:
    """每个进程（pid）按「打开界面」切段，每段：活了多久、开头和最后一小时的中位数、线性斜率（MB/小时）。

    切段是因为压测用 `am start` 关开追踪（开头、模拟器重开、注入「高德报错」时），界面一起来进程里就多一个 Flutter 引擎
    （压测是 debug 包，JIT，一下多 250–280 MB），是一次性的台阶，不是越跑越涨；不切的话进程先只有服务、后开界面，就被当成增长。
    """
    out = []
    # 模拟器被外部关掉重开后进程号会和上一台的撞上（开机后起进程的顺序差不多），按第几台模拟器分开
    by_pid: dict[tuple[int, str, float | None], list[tuple[float, int]]] = {}
    for t, pid, kb in mem:
        opened = max((u for u in ui_opened if u <= t), default=None)
        generation = sum(x <= t for x in lost)
        by_pid.setdefault((generation, pid, opened), []).append((t, kb))
    for (_, pid, opened), xs in by_pid.items():
        xs.sort()
        # 同一个进程里界面打开之前的样本另成一段；界面打开早于进程（上一个进程里开的）就不算这一段的起点
        if opened is not None and opened < xs[0][0] - 120:
            opened = None
        hours = (xs[-1][0] - xs[0][0]) / 3600
        if hours < 1.5:
            continue

        def median(vals: list[int]) -> float:
            vals = sorted(vals)
            return vals[len(vals) // 2] / 1024

        first = median([kb for t, kb in xs if t - xs[0][0] <= 3600])
        last = median([kb for t, kb in xs if xs[-1][0] - t <= 3600])
        n = len(xs)
        mx = sum(t for t, _ in xs) / n
        my = sum(kb for _, kb in xs) / n
        var = sum((t - mx) ** 2 for t, _ in xs)
        slope = (sum((t - mx) * (kb - my) for t, kb in xs) / var) * 3600 / 1024 if var else 0.0
        out.append(
            {
                "pid": pid,
                "since": f"打开界面 {fmt(opened)}" if opened is not None else "进程起来",
                "hours": round(hours, 1),
                "first_mb": round(first, 1),
                "last_mb": round(last, 1),
                "slope_mb_h": round(slope, 2),
                "ok": last <= first * 1.15 + 5,
            }
        )
    return out


def memory_svg(lanes: list[dict], path: Path) -> None:
    """小多图：每台一张，横轴小时、纵轴 PSS（MB），同一纵轴范围；单条线不用图例，标题写是哪台"""
    w, h, pad_l, pad_b, pad_t = 760, 150, 52, 26, 22
    all_kb = [kb for lane in lanes for _, _, kb in lane["memory"]] or [1]
    top = math.ceil(max(all_kb) / 1024 / 50) * 50
    max_h = max((lane["hours"] for lane in lanes), default=1)
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h * len(lanes)}" '
        f'font-family="-apple-system, Segoe UI, sans-serif" font-size="11">',
        f'<rect width="{w}" height="{h * len(lanes)}" fill="#ffffff"/>',
    ]
    for i, lane in enumerate(lanes):
        y0 = i * h
        plot_w, plot_h = w - pad_l - 16, h - pad_t - pad_b

        def x(t: float, lane: dict = lane) -> float:
            return pad_l + (t - lane["start"]) / 3600 / max_h * plot_w

        def y(kb: int) -> float:
            return y0 + pad_t + plot_h - kb / 1024 / top * plot_h

        parts.append(
            f'<text x="{pad_l}" y="{y0 + 14}" fill="#1f2328" font-weight="600">{lane["dir"]} App 内存 PSS（MB），{lane["hours"]:.1f} 小时，'
            f"换进程处断开</text>"
        )
        for mb in range(0, top + 1, 50):
            yy = y(mb * 1024)
            parts.append(
                f'<line x1="{pad_l}" x2="{w - 16}" y1="{yy:.1f}" y2="{yy:.1f}" stroke="#eaeef2" stroke-width="1"/>'
            )
            parts.append(f'<text x="{pad_l - 6}" y="{yy + 4:.1f}" text-anchor="end" fill="#656d76">{mb}</text>')
        for hr in range(0, int(max_h) + 1):
            xx = pad_l + hr / max_h * plot_w
            parts.append(f'<text x="{xx:.1f}" y="{y0 + h - 8}" text-anchor="middle" fill="#656d76">{hr}h</text>')
        runs: list[list[tuple[float, int]]] = []
        last_pid = None
        for t, pid, kb in lane["memory"]:
            if pid != last_pid:
                runs.append([])
                last_pid = pid
            runs[-1].append((t, kb))
        for run in runs:
            pts = " ".join(f"{x(t):.1f},{y(kb):.1f}" for t, kb in run)
            parts.append(
                f'<polyline points="{pts}" fill="none" stroke="#3b6fb6" stroke-width="2" stroke-linejoin="round"/>'
            )
    parts.append("</svg>")
    path.write_text("\n".join(parts))


def fmt(t: float) -> str:
    return time.strftime("%H:%M:%S", time.localtime(t))


def report(base: Path) -> bool:
    lanes = [analyze_lane(d) for d in sorted(base.glob("lane-*")) if (d / "events.json").exists()]
    memory_svg(lanes, base / "memory.svg")
    total_h = sum(lane["hours"] for lane in lanes)
    stuck = sum(len(lane["stuck"]) for lane in lanes)
    lost = sum(len(lane["lost"]) for lane in lanes)
    produced = sum(lane["produced"] for lane in lanes)
    recs = [r for lane in lanes for r in lane["recoveries"]]
    bad_rec = [r for r in recs if not r["ok"]]
    mems = [
        (lane["dir"], m)
        for lane in lanes
        for m in memory_stats(lane["memory"], lane.get("ui_opened", []), lane["lost_emulator"])
    ]
    mem_ok = all(m["ok"] for _, m in mems) and bool(mems)
    fatal = sum(lane["fatal"] + lane["anr"] for lane in lanes)
    foreign = sum(lane["foreign"] for lane in lanes)
    dups = sum(lane["duplicates"] for lane in lanes)
    scenarios = [(lane["dir"], sc) for lane in lanes for sc in lane["scenarios"]]
    # 随身 Wi-Fi（car_wifi）只记结果、不计入达标：模拟器没有基站和网络定位，App 收不到离开信号，测不了真机上的那条路，
    # 结果单独写在报告里
    bad_sc = [sc for _, sc in scenarios if not sc["ok"] and sc["kind"] != "car_wifi"]
    passed = (
        total_h >= 24
        and stuck == 0
        and lost == 0
        and not bad_rec
        and mem_ok
        and fatal == 0
        and foreign == 0
        and dups == 0
        and not bad_sc
    )

    by_kind: dict[str, list[dict]] = {}
    for r in recs:
        by_kind.setdefault(r["kind"], []).append(r)
    lines = [
        "# 上报端 v2 加速压测",
        "",
        f"{'**全部达标**' if passed else '**没有全部达标**'}：{len(lanes)} 台模拟器并行，累计 **{total_h:.1f} 设备小时**；"
        f"注入故障 {len(recs)} 次（{len(by_kind)} 类）；卡死 **{stuck}** 次；丢点 **{lost}** / 产出 {produced}；"
        f"恢复超时 {len(bad_rec)} 次；App 崩溃 / ANR {fatal} 次；内存{'不涨' if mem_ok else '有增长或样本不够'}；"
        f"场景 {len(scenarios)} 段、没过 {len(bad_sc)} 段（随身 Wi-Fi 那种只记结果、不计入）。",
        "",
        "判法：",
        f"- 卡死：不在挡上报的故障（{'、'.join(FAULT_TITLE[k] for k in sorted(BLOCKING))}、高德报错、改时间）期间和清掉后 "
        f"{RECOVERY_LIMIT} 秒宽限里，服务器两点间隔超过所在状态的上限（坐车 {GAP_LIMIT['moving']} 秒、静止和屋里 {GAP_LIMIT['still']} 秒）算一次。",
        "- 丢点：App 日志里每条 `Report … at <毫秒>` 的时间戳都要在服务器收到的点里（秒，按条数对）；压测结束先清掉所有故障、等离线队列发完再对。",
        f"- 恢复时间：清掉故障到服务器收到下一个点（重启从发重启命令算）；门槛 {RECOVERY_LIMIT} 秒（静止 5 分钟呼吸一次，5xx/超时退避最多 5 分钟），重启 {REBOOT_LIMIT} 秒；"
        "电池受限还要在门槛内回到前台服务（说明一栏写了几秒）。"
        f"改钟后先网络点再卫星点：改回来后 {GAP_LIMIT['moving']} 秒内要收到卫星点；存储满时已发点删不掉：恢复联网后存储满着期间空档不超过所在状态的上限；"
        "旧版迁移失败：还要旧库改名进 legacy-backup/、里面的点都在。",
        f"- 场景：车载 Wi-Fi 上车，{CAR_WIFI_LIMIT} 秒内服务器收到在路上的点；屋里走动，去掉头 {WALK_SETTLE} 秒，"
        f"每 30 分钟开卫星不超过 {WALK_PROBES_PER_30MIN} 次，不报卫星点、不转在路上。",
        "- 内存：每个进程按「打开界面」切段（压测用 `am start` 关开追踪，debug 包界面一起来多一个 Flutter 引擎，是一次性台阶），"
        "活够 1.5 小时的段，最后一小时 PSS 中位数不超过第一小时的 1.15 倍 + 5 MB。",
        "",
        "## 每类故障的恢复时间",
        "",
        "| 故障 | 次数 | 恢复时间 中位 / 最长（秒） | 超门槛 | 故障期间最大空档（秒，不挡上报的） |",
        "|---|---|---|---|---|",
    ]
    for kind in ALL_FAULTS:
        rs = by_kind.get(kind, [])
        if not rs:
            lines.append(f"| {FAULT_TITLE[kind]} | 0 | – | – | – |")
            continue
        vals = sorted(r["recovery"] for r in rs if r["recovery"] is not None)
        med = vals[len(vals) // 2] if vals else None
        gaps = [r["max_gap_during"] for r in rs if r["max_gap_during"] is not None]
        lines.append(
            f"| {FAULT_TITLE[kind]} | {len(rs)} | {med if med is not None else '–'} / {vals[-1] if vals else '–'} | "
            f"{sum(not r['ok'] for r in rs)} | {max(gaps) if gaps else '–'} |"
        )
    lines += ["", "## 场景", "", "| 模拟器 | 场景 | 开始 | 结果 | 过 |", "|---|---|---|---|---|"]
    for name, sc in scenarios:
        if sc["kind"] in ("car_wifi", "car_wifi_join"):
            what = f"上车后 {sc['took']} 秒转在路上" if sc["took"] is not None else "这段没转在路上"
            title = {"car_wifi": "带着连着的 Wi-Fi 上车（随身 Wi-Fi，不断）", "car_wifi_join": "连着车载 Wi-Fi 上车"}[
                sc["kind"]
            ]
        else:
            what = (
                f"{sc['minutes']} 分钟开卫星 {sc['probes']} 次（上限 {sc['allowed']}），"
                f"卫星点 {sc['sat_points']} 个、在路上的点 {sc['moving_points']} 个"
            )
            title = "连着锚 Wi-Fi 屋里走动"
        lines.append(
            f"| {name} | {title} | {fmt(sc['start'])} | {what}{'（' + sc['note'] + '）' if sc['note'] else ''} | {'✓' if sc['ok'] else '✗'} |"
        )
    lines += [
        "",
        "## 内存",
        "",
        "![每台的 App 内存（PSS）](memory.svg)",
        "",
        "| 模拟器 | 进程 | 这一段从 | 活了（小时） | 第一小时（MB） | 最后一小时（MB） | 斜率（MB/小时） | 判定 |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for name, m in mems:
        lines.append(
            f"| {name} | {m['pid']} | {m['since']} | {m['hours']} | {m['first_mb']} | {m['last_mb']} | {m['slope_mb_h']} | {'✓' if m['ok'] else '✗'} |"
        )
    lines += ["", "## 每台", ""]
    for lane in lanes:
        lines += [
            f"### {lane['dir']}（设备号 {lane['device']}，{lane['hours']:.1f} 小时）",
            "",
            f"- 服务器收到 {lane['points']} 个点、{len(lane['alarms'])} 条告警（{', '.join(sorted(set(lane['alarms']))) or '无'}）；App 产出 {lane['produced']} 个点，"
            f"丢 {len(lane['lost'])} 个{f'（另有 {len(lane['lost_excused'])} 个在模拟器被外部结束前 10 分钟内，不计）' if lane['lost_excused'] else ''}，"
            f"重复 {lane['duplicates']} 个（另有断网那一刻在途、重发一次的 {lane['resent']} 个）；"
            f"服务器比日志多收 {lane['extra']} 个（日志写不进去 {lane['unwritten_logs']} 条时发出的点，对不上日志）；收到别的设备的请求 {lane['foreign']} 个。",
            f"- App 记「发出去的删不掉、跳过」{lane['sent_not_removed']} 次，「离线队列超上限丢最旧」{lane['queue_trims']} 次。",
            f"- 卡死 {len(lane['stuck'])} 次"
            + (
                ": " + "; ".join(f"{fmt(s['from'])}→{fmt(s['to'])} {s['gap']}s>{s['limit']}s" for s in lane["stuck"])
                if lane["stuck"]
                else ""
            )
            + "。",
            f"- App 崩溃 {lane['fatal']} 次、ANR {lane['anr']} 次；组件异常（Fault crash）{lane['component_crashes']} 次；看门狗出手 {len(lane['rescues'])} 次。",
        ]
        if lane["rescues"]:
            lines += ["", "看门狗出手：", ""] + [f"- `{r}`" for r in lane["rescues"][:20]]
        if lane["lost"]:
            lines += ["", "丢的点（App 时间戳）：" + ", ".join(fmt(t) for t in lane["lost"][:30])]
        bad = [r for r in lane["recoveries"] if not r["ok"]]
        if bad:
            lines += ["", "恢复超门槛："] + [
                f"- {FAULT_TITLE[r['kind']]} {fmt(r['start'])}–{fmt(r['end'])}（{r['segment']}）恢复 {r['recovery']} 秒"
                for r in bad
            ]
        if lane["notes"]:
            lines += ["", "备注："] + [f"- {n}" for n in lane["notes"]]
        lines += [
            "",
            "<details><summary>注入记录</summary>",
            "",
            "| 开始 | 结束 | 故障 | 当时状态 | 恢复（秒） | 期间最大空档 | 说明 |",
            "|---|---|---|---|---|---|---|",
        ]
        for r in lane["recoveries"]:
            lines.append(
                f"| {fmt(r['start'])} | {fmt(r['end'])} | {FAULT_TITLE[r['kind']]} | {r['segment']} | {r['recovery']} | "
                f"{r['max_gap_during'] if r['max_gap_during'] is not None else '–'} | {r['note']} |"
            )
        lines += ["", "</details>", ""]
    (base / "report.md").write_text("\n".join(lines) + "\n")
    summary = {
        "passed": passed,
        "device_hours": round(total_h, 1),
        "faults": len(recs),
        "stuck": stuck,
        "lost": lost,
        "produced": produced,
        "recovery_over_limit": len(bad_rec),
        "fatal_or_anr": fatal,
        "duplicates": dups,
        "memory_ok": mem_ok,
        "scenarios": len(scenarios),
        "scenarios_failed": len(bad_sc),
        "sent_not_removed": sum(lane["sent_not_removed"] for lane in lanes),
        "queue_trims": sum(lane["queue_trims"] for lane in lanes),
        "by_kind": {
            k: {"count": len(v), "max": max((r["recovery"] for r in v if r["recovery"] is not None), default=None)}
            for k, v in by_kind.items()
        },
    }
    (base / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1))
    st.say(f"报告：{base / 'report.md'}（{'全部达标' if passed else '没有全部达标'}）")
    return passed


def run_lane(lane: Lane) -> None:
    try:
        lane.setup()
        lane.run()
        lane.drain()
    except Exception:
        lane.notes.append("压测中断：" + traceback.format_exc()[-800:])
        lane.say(traceback.format_exc())
        if not lane.end_at:
            lane.end_at = now()
    finally:
        lane.teardown()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--lanes", type=int, default=4, help="同时跑几台模拟器（每台约 4 GB 内存）")
    ap.add_argument("--hours", type=float, default=6.5, help="每台跑多少小时")
    ap.add_argument(
        "--apk",
        type=Path,
        default=ROOT / "build/stability-v2/app-v2-stress.apk",
        help="压测用的 debug 包（高德 Key 故意写错）",
    )
    ap.add_argument("--seed", type=int, default=int(time.time()))
    ap.add_argument("--out", type=Path, help="结果目录（默认 build/stability-v2/<时间>）")
    ap.add_argument("--report-from", type=Path, help="只按已有结果目录重出报告")
    ap.add_argument("--scale", type=float, default=1.0, help="状态和故障时长系数，只用来试跑脚本；小于 1 时结果不算数")
    ap.add_argument("--only", help="只注入这些故障（逗号分隔，按顺序），试跑用")
    ap.add_argument("--kinds", help=f"只换这些状态（逗号分隔，{','.join(KINDS)}），试跑用")
    args = ap.parse_args()
    if args.report_from:
        return 0 if report(args.report_from) else 1
    if not args.apk.exists():
        st.say(f"没有 {args.apk}，先按 v2_stress.sh 打包")
        return 2
    base = args.out or ROOT / "build/stability-v2" / time.strftime("%Y%m%d-%H%M%S")
    base.mkdir(parents=True, exist_ok=True)
    # 第 0 台不注入「进程被杀」「重启」「旧版迁移失败」：同一个进程活满全程，看内存曲线
    only = args.only.split(",") if args.only else None
    lanes = [
        Lane(
            i,
            base,
            args.apk,
            args.hours,
            args.seed + i,
            kill_faults=i != 0 or args.lanes == 1,
            scale=args.scale,
            only=only,
            kinds=args.kinds.split(",") if args.kinds else None,
        )
        for i in range(args.lanes)
    ]
    st.say(f"压测开始：{args.lanes} 台 × {args.hours} 小时，结果在 {base}")
    with ThreadPoolExecutor(args.lanes) as pool:
        list(pool.map(run_lane, lanes))
    return 0 if report(base) else 1


if __name__ == "__main__":
    sys.exit(main())
