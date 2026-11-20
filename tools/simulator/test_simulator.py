"""模拟上报端的单元测试：python3 -m unittest discover -s tools/simulator（几秒钟，不碰网络，不起浏览器）"""

import dataclasses
import http.server
import json
import math
import re
import struct
import tempfile
import threading
import unittest
from unittest import mock
from datetime import datetime
import zlib
import urllib.parse
from pathlib import Path

import build
import checks
import geo
import live
import phone
import reporter
import scenario
import sensors
import shots
import traccar

ROOT = Path(__file__).resolve().parents[2]


def sc_of(segments: list[dict], events: list[dict] | None = None, **kw) -> scenario.Scenario:
    return scenario.parse(
        {
            "name": "t",
            "start": "2026-11-20T08:00:00+08:00",
            "seed": 3,
            "segments": segments,
            "events": events or [],
            **kw,
        }
    )


def reports_of(sc: scenario.Scenario) -> list[phone.Report]:
    return phone.Phone(sc).reports()


class GeoTest(unittest.TestCase):
    def test_move_and_distance(self):
        lat, lon = geo.move(30.0, 120.0, 90, 500)
        self.assertAlmostEqual(geo.distance(30.0, 120.0, lat, lon), 500, delta=1)
        self.assertAlmostEqual(geo.bearing(30.0, 120.0, lat, lon), 90, delta=0.5)

    def test_point_segment(self):
        a, b = (30.0, 120.0), geo.move(30.0, 120.0, 0, 100)
        p = geo.move(*geo.move(30.0, 120.0, 0, 50), 90, 30)
        self.assertAlmostEqual(geo.point_segment(*p, a, b), 30, delta=0.5)


class ScenarioTest(unittest.TestCase):
    def test_all_scenarios_load(self):
        for n in scenario.names():
            sc = scenario.load(n)
            self.assertGreater(sc.duration, 0, n)

    def test_walk_length_and_turn(self):
        sc = sc_of([{"kind": "walk", "legs": [[0, 140], [90, 140]]}], tail=0)
        self.assertEqual(sc.duration, 200)  # 280 米 / 1.4 米每秒
        self.assertEqual(len(sc.turns), 1)
        self.assertEqual(sc.turns[0].t, 100)
        end = sc.truth[-1]
        self.assertAlmostEqual(geo.distance(*scenario.DEFAULT_ORIGIN, end.lat, end.lon), 140 * 2**0.5, delta=3)

    def test_bus_stops(self):
        sc = sc_of([{"kind": "bus", "legs": [[0, 900]], "stops": [[450, 60]]}], tail=0)
        self.assertEqual(sc.duration, 160)
        self.assertEqual(sum(1 for s in sc.truth if s.mps == 0), 60)
        self.assertTrue(all(s.moving for s in sc.truth))

    def test_rejects_bad_input(self):
        with self.assertRaises(ValueError):
            sc_of([{"kind": "fly", "seconds": 10}])
        with self.assertRaises(ValueError):
            sc_of([{"kind": "stay", "seconds": 10}], [{"type": "meteor"}])
        with self.assertRaises(ValueError):
            sc_of([{"kind": "walk"}])


class PhoneTest(unittest.TestCase):
    def test_still_breathes_every_5_minutes_without_coordinates(self):
        # 开始先算屋里走动（带手里刚拿到的点），不动 2 分钟转静止（人没换地方，只报状态），之后 5 分钟呼吸一次
        rs = reports_of(sc_of([{"kind": "stay", "seconds": 1300}]))
        self.assertEqual([r.t for r in rs], [0, 120, 420, 720, 1020, 1320])
        self.assertEqual([r.state for r in rs[:2]], ["zone", "still"])
        self.assertEqual([r.fix is not None for r in rs[:2]], [True, False])
        self.assertTrue(all(r.fix is None and r.state == "still" and "breath" in r.tags for r in rs[2:]))

    def test_breath_delay(self):
        rs = reports_of(
            sc_of([{"kind": "stay", "seconds": 1300}], [{"type": "breath_delay", "at": 300, "seconds": 300}])
        )
        self.assertEqual([r.t for r in rs], [0, 120, 720, 1020, 1320])

    def test_moving_reports_every_10_seconds_with_gps(self):
        rs = reports_of(sc_of([{"kind": "walk", "legs": [[0, 400]]}], tail=0))
        moving = [r for r in rs if r.state == "moving"]
        self.assertLessEqual(moving[0].t, 10, "走路几秒就判出来")
        self.assertIsNone(moving[0].fix, "刚开卫星：状态先报，只带时间")
        gaps = {b.t - a.t for a, b in zip(moving, moving[1:])}
        self.assertEqual(gaps, {10})
        self.assertTrue(all(r.fix and r.fix.provider == "gps" and r.fix.acc < 15 for r in moving[1:]))
        self.assertTrue(all(r.motion == "walk" for r in moving))

    def test_tunnel_sends_time_only_every_60_seconds(self):
        sc = sc_of([{"kind": "bus", "legs": [[0, 3000]]}], [{"type": "no_fix", "from": 100, "to": 250}], tail=0)
        inside = [r for r in reports_of(sc) if 100 < r.t < 250]
        self.assertEqual([r.t for r in inside], [160, 220])  # 100 秒那条带的是进隧道前最后一个点
        self.assertTrue(all(r.fix is None and r.state == "moving" for r in inside))

    def test_offline_backfill_and_resend(self):
        sc = sc_of(
            [{"kind": "bike", "legs": [[0, 2000]]}], [{"type": "offline", "from": 100, "to": 300, "resend_first": True}]
        )
        rs = reports_of(sc)
        late = [r for r in rs if "backfill" in r.tags]
        self.assertEqual(len(late), 20)
        self.assertTrue(all(300 <= r.arrive < 305 for r in late))
        self.assertEqual([r.t for r in late], sorted(r.t for r in late))
        dup = [r for r in rs if "resend" in r.tags]
        self.assertEqual(len(dup), 1)
        self.assertLess(dup[0].t, 100)
        self.assertEqual(dup[0].arrive, 300)
        # 离线队列先进先出：恢复后新报的点（含恰好 300 秒那一条）在补传的后面到
        fresh = [r for r in rs if r.t >= 300]
        self.assertTrue(fresh)
        self.assertTrue(all(r.arrive > max(x.arrive for x in late) for r in fresh))

    def test_killed_gap_and_restart(self):
        sc = sc_of([{"kind": "walk", "legs": [[0, 1000]]}], [{"type": "killed", "from": 200, "to": 400}])
        rs = reports_of(sc)
        self.assertFalse([r for r in rs if 200 <= r.t < 400])
        first = next(r for r in rs if r.t >= 400)
        self.assertEqual(first.t, 400)
        self.assertIn("restart", first.tags)

    def test_side_jump_offsets_reported_point(self):
        sc = sc_of(
            [{"kind": "walk", "legs": [[0, 600]]}], [{"type": "side_jump", "at": 200, "points": 2, "meters": 100}]
        )
        jumped = [r for r in reports_of(sc) if "jump" in r.tags]
        self.assertEqual(len(jumped), 2)
        for r in jumped:
            s = sc.at(r.fix.t)
            self.assertGreater(geo.distance(s.lat, s.lon, r.fix.lat, r.fix.lon), 70)

    def test_clock_skew_and_cold_start(self):
        sc = sc_of(
            [{"kind": "stay", "seconds": 60}, {"kind": "walk", "legs": [[0, 300]]}],
            [{"type": "clock", "from": 0, "to": 9999, "seconds": 420}, {"type": "cold_start", "from": 60, "to": 120}],
        )
        rs = reports_of(sc)
        for r in rs:
            stamp = r.fix.t if r.fix else r.t
            self.assertEqual(r.timestamp_ms, sc.start_ms + (stamp + 420) * 1000)
        # 冷启动只有基站定位：不放主坐标（方案 3a 定稿：主坐标只来自卫星），估算放 netLat 等附加字段
        cold = [r for r in rs if 60 <= r.t < 120 and (r.fix or r.net_fix)]
        self.assertTrue(cold)
        self.assertTrue(all(r.fix is None and r.net_fix.provider == "cell" for r in cold))
        p = cold[0].params("1")
        self.assertNotIn("lat", p)
        self.assertEqual(p["netSource"], "cell")

    def test_misjudged_moving_sends_wifi_points(self):
        sc = sc_of(
            [{"kind": "stay", "seconds": 600}], [{"type": "misjudge_moving", "from": 100, "to": 200, "interval": 10}]
        )
        bad = [r for r in reports_of(sc) if 100 <= r.t < 200]
        self.assertGreaterEqual(len(bad), 9)
        self.assertTrue(all(r.state == "moving" and r.fix.provider == "wifi" for r in bad))
        # 运动摘要 0 步、0 颠簸：家长页（domain/fixQuality.atRest）靠它认出是误判
        self.assertTrue(all(r.params("1")["steps"] == "0" and r.params("1")["bumps"] == "0" for r in bad))

    def test_params_match_app_uploader(self):
        r = reports_of(sc_of([{"kind": "walk", "legs": [[0, 200]]}]))[3]
        p = r.params("42")
        for k in (
            "id",
            "lat",
            "lon",
            "timestamp",
            "accuracy",
            "speed",
            "bearing",
            "batt",
            "charge",
            "provider",
            "state",
        ):
            self.assertIn(k, p)
        self.assertAlmostEqual(float(p["speed"]), 1.4 * 1.94384, places=1)  # 节
        # wifi、cell 是 OsmAnd 协议的保留参数（wifi=mac,rssi），用它们当运动摘要会让 Traccar 解析失败、丢掉整条
        self.assertNotIn("wifi", p)
        self.assertNotIn("cell", p)

    def test_net_follows_connected_network(self):
        # 方案 2026-11-18-parent-web-info-sheet 第 3 节：net 是上报那一刻的联网方式；家里固定 Wi-Fi、路上移动网络、热点算 hotspot
        sc = sc_of(
            [
                {"kind": "stay", "seconds": 400},
                {"kind": "walk", "legs": [[0, 300]]},
                {"kind": "stay", "seconds": 400, "wifi": "hotspot"},
            ]
        )
        rs = reports_of(sc)
        nets = {
            sc.segments[sc.at(int(r.t)).seg].kind + ":" + sc.segments[sc.at(int(r.t)).seg].wifi: r.params("1")["net"]
            for r in rs
        }
        self.assertEqual(nets["stay:fixed"], "wifi")
        self.assertEqual(nets["walk:none"], "cellular")
        self.assertEqual(nets["stay:hotspot"], "hotspot")
        self.assertTrue(all(r.params("1")["wifiType"] == r.wifi for r in rs))  # 旧字段先留着兼容
        stored, _ = traccar.process(rs, sc.start_ms)
        self.assertEqual({p.attributes["net"] for p in stored}, {"wifi", "cellular", "hotspot"})

    def test_main_coordinate_is_satellite_only(self):
        # 方案 3a 定稿：Wi-Fi、基站定位不放主坐标（Traccar 的围栏、通知、轨迹按主坐标算）；只有屋里一直没卫星、确认过的估算例外
        # （NetFallback，来源写 network），这几个场景卫星点都没断满 10 分钟，一个也不该有
        for name in ("home-wifi-drift", "arrive-wifi-wrong", "gps-cold-start", "bus-lights-tunnel"):
            for r in reports_of(scenario.load(name)):
                # 注入的旧版误判（misjudge）是旧版上报端，照旧版把网络点放主坐标
                if r.fix and "misjudge" not in r.tags:
                    self.assertEqual(r.fix.provider, "gps", f"{name} {r.t} {r.tags}")

    def test_hotspot_wifi_location_is_not_used(self):
        # 方案 3a：连着热点时高德按热点的 MAC 定位会报回家，不用；屋里取的网络定位就只报时间
        rs = reports_of(sc_of([{"kind": "stay", "seconds": 400, "wifi": "hotspot"}]))
        self.assertTrue(rs)
        self.assertTrue(all(r.fix is None or r.fix.provider != "wifi" for r in rs[1:]))
        self.assertTrue(all(r.net == "hotspot" for r in rs))

    def test_car_wifi_is_measured_and_followed_within_3_minutes(self):
        # 车载 Wi-Fi（不计费、名字不像热点）上车时被记成锚，车停着 2 分钟、开车每 90 秒等红灯：开卫星量出离锚点远了就转在路上
        sc = scenario.load("car-wifi-wait")
        rs = reports_of(sc)
        drive = next(s.t for s in sc.truth if s.kind == "bus")
        self.assertEqual([r for r in rs if r.t < drive][-1].state, "zone", "车停着等：屋里走动、关卫星")
        first = next(r for r in rs if r.t >= drive and r.state == "moving")
        self.assertLessEqual(first.t - drive, 180, "开车后 3 分钟内转在路上")
        self.assertEqual(first.fix.provider if first.fix else None, "gps", "转在路上那一刻报卫星点")
        before = [r for r in rs if drive <= r.t < first.t]
        self.assertTrue(all(r.fix is None for r in before), "量的时候不报卫星点")

    def test_probe_cooldown_only_blocks_the_measured_anchor(self):
        # 和 SDK 的 AnchorProbe 一样：在家量过冷却中，换到车载 Wi-Fi（新锚）照样能量
        p = reporter.AnchorProbe()
        self.assertTrue(p.start(0, "home", None))
        p.finish(30_000, here=True)
        self.assertFalse(p.start(60_000, "home", None), "家里的锚冷却中")
        self.assertTrue(p.start(60_000, "car", None), "车载 Wi-Fi 不受家里冷却影响")
        self.assertTrue(p.expire(150_000))
        self.assertFalse(p.start(200_000, "car", None), "车上没定到：5 分钟后再量")
        self.assertTrue(p.start(200_000, "home", None), "家里的冷却已经过了")

    def test_golden_samples_are_current(self):
        # 金样本是上报端 v2 的 Kotlin 判断层对齐用的（SimulatorGoldenTest）：改了 reporter.py、phone.py、sensors.py 就重新生成
        for name in build.GOLDEN_SCENARIOS:
            path = build.GOLDEN_DIR / f"{name}.golden"
            self.assertTrue(path.exists(), f"缺 {path}，跑 python3 sim.py golden")
            self.assertEqual(
                path.read_text(), build.golden_text(name), f"{name} 的金样本过期了，跑 python3 sim.py golden"
            )


class TraccarModelTest(unittest.TestCase):
    def test_breath_copies_last_fix(self):
        # 0 屋里（网络点）、120 转静止（只报状态）、420 和 720 呼吸
        sc = sc_of([{"kind": "stay", "seconds": 700}])
        stored, dropped = traccar.process(reports_of(sc), sc.start_ms)
        self.assertEqual(len(stored), 4)
        self.assertFalse(dropped)
        last, breath = stored[0], stored[2]
        self.assertTrue(breath.outdated)
        self.assertEqual((breath.lat, breath.lon, breath.fix_ms), (last.lat, last.lon, last.fix_ms))
        self.assertEqual(breath.device_ms, sc.start_ms + 420_000)

    def test_duplicate_filter_off_keeps_every_time_only_point(self):
        # 生产 11-18 12:28 起 filter.duplicate=false：隧道里 60 秒一个只带时间的点全部入库（以前 160 秒那个被当重复丢掉）
        sc = sc_of([{"kind": "bus", "legs": [[0, 3000]]}], [{"type": "no_fix", "from": 100, "to": 250}], tail=0)
        stored, dropped = traccar.process(reports_of(sc), sc.start_ms)
        self.assertFalse(dropped)
        self.assertTrue({160, 220} <= {p.report.t for p in stored if p.outdated})

    def test_state_change_and_skip_limit_pass_duplicate_filter(self):
        # 静止呼吸抄的定位时间相同，照样入库
        sc = sc_of([{"kind": "stay", "seconds": 700}])
        self.assertFalse(traccar.process(reports_of(sc), sc.start_ms)[1])

    def test_future_filter(self):
        # filter.future=86400：手机时间快 12 分钟的点照存，快一天以上才丢
        sc = sc_of([{"kind": "walk", "legs": [[0, 300]]}], [{"type": "clock", "from": 0, "to": 9999, "seconds": 720}])
        stored, dropped = traccar.process(reports_of(sc), sc.start_ms)
        self.assertTrue(stored)
        self.assertFalse(dropped)
        sc = sc_of([{"kind": "walk", "legs": [[0, 300]]}], [{"type": "clock", "from": 0, "to": 9999, "seconds": 90000}])
        stored, dropped = traccar.process(reports_of(sc), sc.start_ms)
        self.assertFalse(stored)
        # 带坐标的都因为太超前丢掉；只带时间的点没有可抄的上一个位置，也丢
        self.assertEqual({why for _, why in dropped}, {"future", "no-last"})
        self.assertTrue(all(why == "future" for r, why in dropped if r.fix))

    def test_resend_after_long_offline_is_stored_twice(self):
        sc = sc_of(
            [{"kind": "bike", "legs": [[0, 2000]]}], [{"type": "offline", "from": 100, "to": 400, "resend_first": True}]
        )
        stored, dropped = traccar.process(reports_of(sc), sc.start_ms)
        # 补传第一条就是重发：重复过滤关着，服务器存两遍（家长页按 id 去重）
        self.assertFalse(dropped)
        fix_times = [p.fix_ms for p in stored]
        self.assertEqual(len(fix_times) - len(set(fix_times)), 1)

    def test_late_old_point_stored_but_not_pushed(self):
        # 审计 #15：三个点按 0、2、1 的顺序到达：都入库，但 Traccar 只推 0、2（1 的定位时间早于当时最新位置）
        sc = sc_of([{"kind": "walk", "legs": [[0, 300]]}], tail=0)
        rs = [r for r in reports_of(sc) if r.fix][:3]
        late = [rs[0], dataclasses.replace(rs[1], arrive=rs[2].arrive + 1), rs[2]]
        stored, _ = traccar.process(late, sc.start_ms)
        self.assertEqual([p.report.seq for p in stored], [rs[0].seq, rs[2].seq, rs[1].seq])
        self.assertEqual([p.pushed for p in stored], [True, True, False])


class SensorsTest(unittest.TestCase):
    def test_header_matches_app_recorder(self):
        # 和上报端 v2 的 SDK（app-v2/sdk）的 SnapshotCsv.COLUMNS 一致：判断层读的是同一个格式
        kt = (ROOT / "app-v2/sdk/core/src/commonMain/kotlin/org/traccar/client/sense/SnapshotCsv.kt").read_text()
        m = re.search(r"COLUMNS = listOf\((.*?)\n    \)", kt, re.S)
        self.assertIsNotNone(m)
        header = ",".join(re.findall(r'"([^"]*)"', m.group(1)))
        self.assertEqual(sensors.HEADER, header)

    def test_rows(self):
        sc = sc_of(
            [{"kind": "stay", "seconds": 100}, {"kind": "walk", "legs": [[0, 140]]}],
            [{"type": "airplane", "from": 20, "to": 40}, {"type": "killed", "from": 150, "to": 170}],
        )
        rows = [r.split(",") for r in sensors.timeline(sc, phone.Phone(sc))]
        head = rows[0]
        self.assertTrue(all(len(r) == len(head) for r in rows))
        col = {k: i for i, k in enumerate(head)}
        self.assertEqual(len(rows) - 1, (sc.duration + 1) // 2 - 10)  # 进程被杀的 20 秒没有行
        self.assertEqual(rows[1 + 12][col["airplane"]], "1")
        walk = [r for r in rows[1:] if int(r[col["step_events"]]) > 0]
        self.assertTrue(walk)
        self.assertTrue(all(float(r[col["acc_std"]]) > 1 for r in walk))


class ChecksTest(unittest.TestCase):
    def setUp(self):
        self.sc = sc_of(
            [{"kind": "stay", "seconds": 100}, {"kind": "walk", "legs": [[0, 420]]}, {"kind": "stay", "seconds": 200}]
        )
        self.b = build.build(self.sc)

    def trace(self, offset_m: float = 0.0, lost_at: float | None = None) -> dict:
        frames = []
        for s in self.sc.truth[::2]:
            lag = self.sc.at(max(0, s.t - 10))
            lat, lon = geo.move(lag.lat, lag.lon, 90, offset_m)
            frames.append([self.sc.start_ms + s.t * 1000, lat, lon, None])
        status = []
        prev = None
        for p in self.b.stored:
            st = p.attributes["state"]
            if st != prev:
                status.append(
                    [p.server_ms + 500, {"moving": "在路上 · 5 km/h 向北", "zone": "附近活动", "still": "静止"}[st]]
                )
                prev = st
        if lost_at is not None:
            status.append([self.sc.start_ms + lost_at * 1000, "已失联 3 分钟"])
        return {"frames": frames, "status": status}

    def test_dot_on_track_passes(self):
        r = checks.judge(self.b, self.trace(), 20)
        self.assertTrue(r.ok, [c for c in r.checks if not c.ok])

    def test_dot_off_track_fails(self):
        r = checks.judge(self.b, self.trace(offset_m=80), 20)
        failed = {c.name.split("（")[0] for c in r.checks if not c.ok}
        self.assertIn("偏离真实轨迹 ≤ max(20 米, 2 倍精度)", failed)

    def test_false_lost_fails(self):
        r = checks.judge(self.b, self.trace(lost_at=200), 20)
        self.assertFalse(next(c for c in r.checks if c.name.startswith("失联")).ok)

    def test_dot_stuck_at_start_fails(self):
        # 审计 #14：真实走完 420 米、圆点全程停在起点。以前偏离、不动、失联三项全过（偏离只回看 10 分钟走过的路）
        lat, lon = self.sc.truth[0].lat, self.sc.truth[0].lon
        trace = self.trace()
        trace["frames"] = [[self.sc.start_ms + s.t * 1000, lat, lon, None] for s in self.sc.truth[::2]]
        r = checks.judge(self.b, trace, 20)
        c = next(c for c in r.checks if c.name.startswith("圆点不落后"))
        self.assertFalse(c.ok, c.detail)
        self.assertFalse(r.ok)

    def test_dot_one_interval_behind_passes_progress(self):
        r = checks.judge(self.b, self.trace(), 20)
        self.assertTrue(next(c for c in r.checks if c.name.startswith("圆点不落后")).ok)

    def test_no_stored_points_reports_no_data(self):
        # 审计 #16：手机时间快 25 小时，上报全被 future 过滤：报「没有数据」不通过，不抛 IndexError
        sc = sc_of([{"kind": "walk", "legs": [[0, 200]]}], [{"type": "clock", "from": 0, "to": 9999, "seconds": 90000}])
        b = build.build(sc)
        self.assertFalse(b.stored)
        r = checks.judge(b, {"frames": [], "status": []}, 20)
        self.assertFalse(r.ok)
        self.assertIn("没有数据", r.checks[0].detail)
        self.assertIn("future", r.checks[0].detail)

    def test_no_frames_reports_no_data(self):
        r = checks.judge(self.b, {"frames": [], "status": []}, 20)
        self.assertFalse(r.ok)
        self.assertEqual([c.name for c in r.checks], ["圆点帧"])
        self.assertIn("没有数据", r.checks[0].detail)

    def cover_check(self, covered: list[list[int]]) -> checks.Check:
        trace = {**self.trace(), "cover": {"samples": 500, "covered": covered}}
        r = checks.judge(self.b, trace, 20)
        return next(c for c in r.checks if c.name == "圆点没被底部卡片挡住")

    def test_dot_never_covered_passes(self):
        self.assertTrue(self.cover_check([]).ok)

    def test_dot_covered_briefly_while_camera_catches_up_passes(self):
        t0 = self.sc.start_ms + 200_000
        # 加速回放时圆点一下挪远，镜头追的那两帧（真实时间 0.2 秒）被挡
        self.assertTrue(self.cover_check([[t0, 799, 716, 5000], [t0 + 60_000, 760, 716, 5200]]).ok)

    def test_dot_covered_for_a_second_fails(self):
        t0 = self.sc.start_ms + 200_000
        covered = [[t0 + i * 4000, 780, 716, 5000 + i * 200] for i in range(6)]
        c = self.cover_check(covered)
        self.assertFalse(c.ok)
        self.assertIn("连续被挡 1 秒以上", c.detail)


class CrawlCheckTest(unittest.TestCase):
    """真实回放：报的测速连续接近 0 时圆点不挪（p20-ride-1118 等红灯 14:38:56–14:39:47）"""

    def setUp(self):
        self.sc = scenario.load("p20-ride-1118")
        self.b = build.build(self.sc)
        self.stop = next(p for p in self.b.stored if p.id == 932)

    def check(self, out_m: float) -> checks.Check:
        """圆点一直在停车点；out_m 不为 0 时等红灯中间往西冲出去 out_m 米再拉回"""
        frames = []
        for t in range(0, self.sc.duration * 1000, 500):
            ms = self.sc.start_ms + t
            lat, lon = self.stop.lat, self.stop.lon
            if out_m and 12 * 60_000 + 15_000 <= t < 12 * 60_000 + 35_000:  # 14:39:15–14:39:35
                lat, lon = geo.move(lat, lon, 254, out_m * (1 - abs(t - 12 * 60_000 - 25_000) / 10_000))
            frames.append([ms, lat, lon, None])
        r = checks.judge(self.b, {"frames": frames, "status": []}, 5)
        return next(c for c in r.checks if c.name == "报的测速连续接近 0 时圆点不挪")

    def test_span_is_the_red_light(self):
        j = checks.Judge(self.b, {"frames": [], "status": []})
        spans = j.crawl_spans()
        self.assertEqual(len(spans), 1)
        a, b, spread = spans[0]
        self.assertEqual((j.clock(a), j.clock(b)), ("14:39:06", "14:39:58"))
        self.assertLess(spread, 10)

    def test_dot_stays_passes(self):
        c = self.check(0)
        self.assertTrue(c.ok, c.detail)

    def test_dot_runs_out_and_back_fails(self):
        c = self.check(44)
        self.assertFalse(c.ok, c.detail)
        self.assertIn("14:39:06–14:39:58", c.detail)

    def test_no_speed_network_points_do_not_count(self):
        # 网络点（Wi-Fi、基站）一般不报速度，存成 0，不算「测速接近 0」
        p = dataclasses.replace(self.stop, attributes={**self.stop.attributes, "provider": "wifi"})
        j = checks.Judge(self.b, {"frames": [], "status": []})
        self.assertTrue(j.crawling(self.stop))
        self.assertFalse(j.crawling(p))


class StoppedCheckTest(unittest.TestCase):
    """网页自己判断的「刚停下」（parent-web domain/stopped.ts）：p20-ride-1118 等红灯，14:38:56 刹停，14:39:58 起步"""

    def setUp(self):
        self.sc = scenario.load("p20-ride-1118")
        self.b = build.build(self.sc)
        self.j = checks.Judge(self.b, {"frames": [], "status": []})

    def at(self, hms: str) -> int:
        return int(datetime.fromisoformat(f"2026-11-18T{hms}+08:00").timestamp() * 1000)

    def check(self, status: list[list]) -> checks.Check:
        frames = [
            [self.sc.start_ms + t * 1000, self.b.stored[0].lat, self.b.stored[0].lon, None]
            for t in range(self.sc.duration)
        ]
        r = checks.judge(self.b, {"frames": frames, "status": status}, 5)
        return next(c for c in r.checks if c.name.startswith("停下 30 秒后"))

    def test_rule_matches_the_red_light(self):
        on = [self.j.clock(a) for i, a in enumerate(self.j.arrive) if self.j.stopped_at(i)]
        self.assertEqual(on[0], "14:39:26")  # 14:38:56 刹停（2.3 km/h）起跨满 30 秒
        self.assertEqual(on[-1], "14:39:47")  # 14:39:58 起步那个点不算

    def test_page_says_stopped_passes(self):
        status = [
            [self.at("14:38:00"), "在路上 · 20 km/h 向西"],
            [self.at("14:39:26.5"), "刚停下"],
            [self.at("14:39:58.5"), "在路上 · 21 km/h 向西"],
        ]
        c = self.check(status)
        self.assertTrue(c.ok, c.detail)

    def test_page_never_says_stopped_fails(self):
        c = self.check([[self.at("14:38:00"), "在路上 · 20 km/h 向西"]])
        self.assertFalse(c.ok, c.detail)
        self.assertIn("没说：14:39:26", c.detail)

    def test_page_says_stopped_while_riding_fails(self):
        c = self.check([[self.at("14:38:30"), "刚停下"], [self.at("14:39:58.5"), "在路上"]])
        self.assertFalse(c.ok, c.detail)
        self.assertIn("不该说：14:38:30", c.detail)


class LiveTest(unittest.TestCase):
    def test_guard(self):
        self.assertEqual(live.guard(None, "90000001", True), live.PROD_URL)
        self.assertEqual(live.guard(None, "abc", False), live.LOCAL_URL)
        for url, dev, prod in [
            (None, "20000001", True),  # 生产只认测试设备
            ("https://evil.example/osmand", "90000001", True),
            ("https://gps.example.com/osmand", "90000001", False),  # 不加 --prod 不能报外网
            ("http://10.0.0.5:5055", "abc", False),
            (None, "a&b=1", False),
        ]:
            with self.assertRaises(live.GuardError, msg=(url, dev, prod)):
                live.guard(url, dev, prod)

    def compare_still(self, rows: int, logs: int):
        """审计 #13：静止场景 1 个定位点、2 个沿用它的（转静止的状态点、呼吸），数据库副本里有 rows 行、服务器日志里有 logs 行"""
        sc = sc_of([{"kind": "stay", "seconds": 700}], tail=0)
        b = build.build(sc)
        sent = [live.Sent(r.seq, sc.start_ms / 1000 + r.arrive, 200, "") for r in b.reports]
        expect, _ = traccar.process(b.reports, sc.start_ms)
        self.assertEqual(len(expect), 3)
        pos = [
            {
                "ID": str(i + 1),
                "DEVICETIME": traccar.iso(e.device_ms)[:23].replace("T", " "),
                "FIXTIME": traccar.iso(e.fix_ms)[:23].replace("T", " "),
                "LATITUDE": str(e.lat),
                "LONGITUDE": str(e.lon),
                "ATTRIBUTES": json.dumps(e.attributes),
            }
            for i, e in enumerate(expect[:rows])
        ]
        accepted = [
            f"[x] id: 90000001, time: {traccar.iso(e.fix_ms)[:19].replace('T', ' ')}, lat: {e.lat:.5f}, lon: {e.lon:.5f}"
            for e in expect[:logs]
        ]
        nt = [{"ALWAYS_ON": "0", "DEVICE_LINKS": "0", "GROUP_LINKS": "0"}]
        return live.compare(b, sent, {"pos": pos, "accepted": accepted, "nt": nt})

    def test_compare_all_rows_is_consistent(self):
        problems, pending, _ = self.compare_still(rows=3, logs=0)
        self.assertEqual((problems, pending), ([], []))
        self.assertEqual(live.verdict(problems, pending), "一致")

    def test_compare_zero_rows_one_log_is_not_consistent(self):
        # 以前：SQL 0 行、日志 1 行，一行日志顶了三个点，判「一致」
        problems, pending, _ = self.compare_still(rows=0, logs=1)
        self.assertEqual(len(pending), 1)
        self.assertEqual(len(problems), 2)
        self.assertEqual(live.verdict(problems, pending), "**不一致**")

    def test_compare_only_logs_is_pending_not_consistent(self):
        problems, pending, _ = self.compare_still(rows=0, logs=3)
        self.assertEqual((len(problems), len(pending)), (0, 3))
        self.assertEqual(live.verdict(problems, pending), "待确认")

    def test_posts_form_to_local_server(self):
        got: list[tuple[str, dict]] = []

        class H(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"])).decode()
                got.append((self.headers["Content-Type"], dict(urllib.parse.parse_qsl(body))))
                self.send_response(200)
                self.end_headers()

            def log_message(self, *a):
                pass

        srv = http.server.HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            sc = sc_of([{"kind": "stay", "seconds": 40}, {"kind": "walk", "legs": [[0, 100]]}], tail=0)
            b = build.build(sc)
            with tempfile.TemporaryDirectory() as d:
                sent = live.run_live(b, f"http://127.0.0.1:{srv.server_port}", "777", Path(d), speed=500)
                self.assertTrue((Path(d) / "sent.jsonl").exists())
        finally:
            srv.shutdown()
            srv.server_close()
        self.assertEqual(len(got), len(b.reports))
        self.assertTrue(all(s.status == 200 for s in sent))
        ctype, first = got[0]
        self.assertEqual(ctype, "application/x-www-form-urlencoded")
        self.assertEqual(first["id"], "777")
        self.assertEqual(first["state"], "zone")


def png(path: Path, w: int, h: int, pixel) -> None:
    """写一张 8 位 RGB 的 PNG：pixel(x, y) 返回 3 字节；每行轮流用 0–4 号过滤，解码要都对"""
    raw = bytearray()
    prev = bytes(w * 3)
    for y in range(h):
        line = b"".join(pixel(x, y) for x in range(w))
        f = y % 5
        out = bytearray(line)
        for i in range(len(line)):
            a = line[i - 3] if i >= 3 else 0
            b = prev[i]
            c = prev[i - 3] if i >= 3 else 0
            pred = [0, a, b, (a + b) >> 1, 0][f]
            if f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if pa <= pb and pa <= pc else b if pb <= pc else c
            out[i] = (line[i] - pred) & 255
        raw += bytes([f]) + out
        prev = line

    def chunk(t: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + t + body + struct.pack(">I", zlib.crc32(t + body))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw))) + chunk(b"IEND", b"")
    )


class ShotsTest(unittest.TestCase):
    def test_decode_all_filters(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "a.png"
            png(f, 7, 11, lambda x, y: bytes([x * 30, y * 20, (x * y) % 256]))
            w, h, bpp, rows = shots.decode(f)
            self.assertEqual((w, h, bpp), (7, 11, 3))
            self.assertEqual(rows[9][5 * 3 : 5 * 3 + 3], bytes([150, 180, 45]))

    def test_blank_cells_finds_unpainted_tiles(self):
        tile = lambda x, y: bytes([0xEE, 0xED, 0xE7]) if (x // 4 + y // 4) % 2 else bytes([0xF1, 0xF0, 0xEA])  # noqa: E731
        with tempfile.TemporaryDirectory() as d:
            full, gray = Path(d) / "full.png", Path(d) / "gray.png"
            png(full, 192, 192, tile)
            # 右边一列瓦片没画上：露出容器底色
            png(gray, 192, 192, lambda x, y: shots.MAP_BG if x >= 144 else tile(x, y))
            self.assertEqual(shots.blank_cells(full), (0, 8))
            self.assertEqual(shots.blank_cells(gray), (2, 8))


# ---- 判断层的 Python 版（reporter.py）和 Kotlin 版对着同样的输入、同样的期望（Kotlin 那边是 MotionClassifierTest、
# ReportPolicyTest、JudgeTest、AuditFixJudgeTest 里同名意思的用例）。金样本只覆盖模拟场景走到的分支，这里把别的分支钉住 ----

R = reporter
STILL_W = R.Snap(time=0, acc_mean=0.4, acc_std=0.007, acc_n=50, gyro_mean=0.001)
FIDGET_W = R.Snap(time=0, acc_mean=0.4, acc_std=0.3, acc_n=50, gyro_mean=0.08)
SWING_W = R.Snap(time=0, acc_mean=2.5, acc_std=6.0, acc_n=50, gyro_mean=2.0)
RIDE_W = R.Snap(time=0, acc_mean=-0.2, acc_std=1.2, acc_n=50, gyro_mean=0.3)
SMOOTH_W = R.Snap(time=0, acc_mean=0.4, acc_std=0.1, acc_n=50, gyro_mean=0.05)


def feed(c: "reporter.MotionClassifier", base: "reporter.Snap", seconds: int, t0: int, edit=lambda s: s) -> list[str]:
    """每 2 秒喂一份，从 t0 之后 2 秒起，返回每份的判断"""
    return [c.update(edit(dataclasses.replace(base, time=t0 + (i + 1) * 2_000))) for i in range(seconds // 2)]


def first_second(out: list[str], motion: str) -> int:
    return (out.index(motion) + 1) * 2 if motion in out else -1


def gps_fix(t: int, speed: float = 0.0, acc: float = 8.0) -> "reporter.Fix":
    return R.Fix("gps", t, 30.0, 120.0, acc, speed)


class ProbePhone:
    """照 Kotlin MovingWifiTest 的 Phone：每 2 秒一份快照，判断层说开卫星就冷启动 cold_start 后每 2 秒给一个点，说取网络定位就给一个"""

    LAT, LON = 30.2741, 120.1551

    def __init__(self, memory: "reporter.WifiMemory | None" = None) -> None:
        self.judge = R.Judge(memory)
        self.time = 1_000_000_000
        self.north = 0.0
        self.bssid: str | None = None
        self.cell: int | None = None
        self.cold_start: int | None = 30_000
        self.network: tuple[float, float] | None = None  # 估算往北多少米、精度
        self.network_provider = "cell"
        self.reports: list[reporter.Report] = []
        self.gps_starts: list[int] = []
        self._gps_on: int | None = None

    @classmethod
    def lat(cls, north: float) -> float:
        return cls.LAT + north / 111_320.0

    @classmethod
    def anchored(cls, bssid: str, **kw) -> "ProbePhone":
        return cls(R.WifiMemory(anchor=bssid, anchor_at=R.Fix("gps", 0, cls.lat(0.0), cls.LON, 10.0), **kw))

    def run(self, seconds: int, act: "reporter.Snap", mps: float = 0.0) -> None:
        for _ in range(seconds // 2):
            self.time += 2_000
            self.north += mps * 2
            if (
                self.judge.gps
                and self._gps_on is not None
                and self.cold_start is not None
                and self.time - self._gps_on >= self.cold_start
            ):
                fix = R.Fix("gps", self.time - 500, self.lat(self.north), self.LON, 8.0, mps)
                self._take(self.judge.on_fix(self.time, fix))
            self._take(self.judge.on_snapshot(self.time, self._snap(act)))
            self._track()

    def _snap(self, act: "reporter.Snap") -> "reporter.Snap":
        tac = 7 if self.cell is not None else None
        if self.bssid is None:
            return dataclasses.replace(act, time=self.time, net="cell", cell_tac=tac, cell_id=self.cell)
        return dataclasses.replace(
            act,
            time=self.time,
            net="wifi",
            wifi_bssid=self.bssid,
            wifi_ssid="TP-LINK_5G_1A2B",
            wifi_rssi=-50,
            wifi_metered=False,
            wifi_gateway="192.168.1.1",
            cell_tac=tac,
            cell_id=self.cell,
        )

    def _take(self, d: "reporter.Decision | None") -> None:
        if d is None:
            return
        if d.report:
            self.reports.append(self.judge.describe(d.report))
        if d.fetch:
            net = None
            if self.network:
                meters, acc = self.network
                net = R.Fix(self.network_provider, self.time, self.lat(self.north + meters), self.LON, acc)
            self._take(self.judge.on_fetched(self.time + 500, net))
        self._track()

    def _track(self) -> None:
        if self.judge.gps and self._gps_on is None:
            self._gps_on = self.time
            self.gps_starts.append(self.time)
        elif not self.judge.gps:
            self._gps_on = None

    def first_gps_report(self, since: int, state: str | None = None) -> "reporter.Report | None":
        return next(
            (r for r in self.reports if r.time >= since and r.fix is not None and (state is None or r.state == state)),
            None,
        )


SIT_W = R.Snap(time=0, acc_mean=0.4, acc_std=0.02, acc_n=50, gyro_mean=0.03)
POCKET = "c8:3a:35:12:34:56"


class ReporterContractTest(unittest.TestCase):
    def test_still_after_two_quiet_minutes(self):
        out = feed(R.MotionClassifier(), STILL_W, 200, 1_000_000)
        self.assertEqual(first_second(out, R.STILL), 122)

    def test_walking_and_vehicle_timing(self):
        self.assertLessEqual(first_second(feed(R.MotionClassifier(), SWING_W, 60, 1_000_000), R.WALK), 6)
        out = feed(R.MotionClassifier(), RIDE_W, 120, 1_000_000)
        self.assertEqual(first_second(out, R.VEHICLE), 62)

    def test_gps_saying_not_moving_ends_vehicle(self):
        c = R.MotionClassifier()
        feed(c, RIDE_W, 70, 1_000_000)
        self.assertEqual(c.raw, R.VEHICLE)
        parked = feed(
            c, RIDE_W, 200, 1_070_000, lambda s: dataclasses.replace(s, fix=R.Fix("gps", s.time - 1_000, speed=0.2))
        )
        self.assertEqual(first_second(parked, R.FIDGET), 182)

    def test_clock_set_fast_network_point_does_not_block_satellite_points(self):
        # 高 10
        p = R.ReportPolicy()
        reports = []
        day = 86_400_000

        def take(d):
            if d.report:
                reports.append(d.report)

        take(p.on_motion(0, R.WALK))
        take(p.on_fix(31_000, R.Fix("wifi", 31_000 + day, 30.0, 120.0, 40.0)))
        self.assertEqual(reports[-1].reason, "net")
        for t in range(40, 101):
            take(p.on_fix(t * 1000, gps_fix(t * 1000)))
        self.assertGreaterEqual(sum(1 for r in reports if r.fix and r.fix.provider == "gps"), 5)

    def test_late_satellite_point_within_one_batch_counts(self):
        # 高 8
        w = dataclasses.replace(SMOOTH_W, time=1_000_000, fix=R.Fix("gps", 1_007_000, speed=9.0))
        self.assertIsNotNone(w.fresh_gps(10_000))
        self.assertIsNone(dataclasses.replace(w, fix=R.Fix("gps", 1_011_000, speed=9.0)).fresh_gps(10_000))
        c = R.MotionClassifier()
        out = []
        end = 1_000_000
        for _ in range(12):
            end += 10_000
            fix = R.Fix("gps", end - 500, acc=8.0, speed=9.0)
            for back in (9_000, 7_000, 5_000, 3_000, 1_000):
                out.append(c.update(dataclasses.replace(SMOOTH_W, time=end - back, fix=fix)))
        self.assertTrue(all(m == R.VEHICLE for m in out), out)

    def test_quiet_phone_in_a_smooth_car_is_not_still(self):
        # 高 9
        c = R.MotionClassifier()

        def fast(s: "reporter.Snap") -> "reporter.Snap":
            return dataclasses.replace(
                s, acc_std=0.01, gyro_mean=0.005, fix=R.Fix("gps", s.time - 1_000, acc=8.0, speed=12.0)
            )

        self.assertTrue(all(m == R.VEHICLE for m in feed(c, STILL_W, 300, 1_000_000, fast)))
        self.assertEqual(feed(c, STILL_W, 140, 1_300_000)[-1], R.STILL)

    def test_indoor_satellite_point_with_huge_error(self):
        # 屋里卫星点分档：5.8 km、150 m 精度带 4 m/s 都不判坐车；在路上时被挡的点不报主坐标，半遮的照报
        for acc in (5_807.0, 150.0):
            out = feed(
                R.MotionClassifier(),
                FIDGET_W,
                20,
                1_000_000,
                lambda s, a=acc: dataclasses.replace(s, fix=R.Fix("gps", s.time - 1_000, acc=a, speed=4.0)),
            )
            self.assertNotIn(R.VEHICLE, out, acc)
        j = R.Judge()
        t = 2_000_000
        for _ in range(10):
            t += 2_000
            j.on_snapshot(t, dataclasses.replace(SWING_W, time=t))
        self.assertEqual(j.motion, R.WALK)
        main = []
        for _ in range(30):
            t += 1_000
            d = j.on_fix(t, gps_fix(t, speed=4.0, acc=5_807.0))
            if d and d.report:
                main.append(j.describe(d.report).fix)
        self.assertTrue(all(f is None for f in main))
        partial = None
        for _ in range(12):
            t += 1_000
            d = j.on_fix(t, gps_fix(t, acc=150.0))
            if d and d.report:
                partial = j.describe(d.report).fix
        self.assertEqual(partial.acc, 150.0)

    def test_sensors_stopping_while_walking_falls_back_to_zone(self):
        # 中 4
        j = R.Judge()
        t = 1_000_000
        for _ in range(10):
            t += 2_000
            j.on_snapshot(t, dataclasses.replace(SWING_W, time=t))
        self.assertTrue(j.gps)
        for _ in range(200):
            t += 1_000
            j.on_fix(t, gps_fix(t))
        self.assertEqual(j.motion, R.FIDGET)
        self.assertFalse(j.gps)
        for _ in range(5):
            t += 2_000
            j.on_snapshot(t, dataclasses.replace(SWING_W, time=t))
        self.assertEqual(j.motion, R.WALK)

    def test_probe_does_not_trust_the_speed_of_a_poor_indoor_point(self):
        home = dataclasses.replace(FIDGET_W, net="wifi", wifi_bssid="home-ap", wifi_ssid="Home", wifi_metered=False)
        j = R.Judge(R.WifiMemory(anchor="home-ap", anchor_at=R.Fix("gps", 0, 30.0, 120.0, 10.0)))
        t = 1_000_000
        for _ in range(5):
            t += 2_000
            j.on_snapshot(t, dataclasses.replace(home, time=t))
        self.assertEqual(j.motion, R.FIDGET)
        j.on_fetched(t, R.Fix("wifi", t, 30.03, 120.0, 40.0))
        self.assertTrue(j.gps)
        d = j.on_fix(t + 1_000, gps_fix(t + 1_000, speed=4.0, acc=150.0))
        self.assertIsNone(d.report if d else None)
        self.assertEqual(j.motion, R.FIDGET)
        self.assertEqual(j.wifi.memory.anchor, "home-ap")

    # 装机候选版审计（docs/specs/2026-11-19-v2-audit-fixes.md）：和 Kotlin MovingWifiTest 同名场景对齐

    def test_probe_far_away_reports_the_point_without_moving(self):
        # 1：车停着 / 开得平顺，网络估算 3 km 外触发探测，8 m 精度的点马上当主坐标报，状态不变
        ph = ProbePhone.anchored(POCKET)
        ph.bssid = POCKET
        ph.north = 3_000.0
        ph.run(60, SIT_W)
        ph.network = (0.0, 800.0)
        ph.run(300, SIT_W)
        self.assertEqual(len(ph.gps_starts), 1)
        r = ph.first_gps_report(ph.gps_starts[0])
        self.assertIsNotNone(r)
        self.assertLessEqual(r.time - ph.gps_starts[0], 32_000)
        self.assertEqual(r.state, "zone")
        self.assertAlmostEqual(r.fix.lat, ProbePhone.lat(3_000.0))

    def test_cooldown_at_home_does_not_delay_leaving_signals(self):
        # 2：在家冷却中、不断开上车，陌生基站不受冷却限制，5 分钟内跟上
        ph = ProbePhone.anchored(POCKET, cells=["7-1"])
        ph.bssid, ph.cell = POCKET, 1
        ph.run(60, SIT_W)
        ph.run(120, SWING_W)
        ph.run(90, SIT_W)
        self.assertEqual(len(ph.gps_starts), 1)
        self.assertFalse(ph.judge.gps)
        ph.run(60, SWING_W, 1.4)
        drive = ph.time
        for _ in range(30):
            ph.run(10, RIDE_W, 10.0)
            if ph.north > 1_000.0:
                ph.cell = 2
        r = ph.first_gps_report(drive, "moving")
        self.assertIsNotNone(r)
        self.assertLessEqual(r.time - drive, 300_000)

    def test_reconnecting_the_same_wifi_elsewhere_clears_the_cooldown(self):
        ph = ProbePhone.anchored(POCKET)
        ph.bssid = POCKET
        ph.run(60, SIT_W)
        for i in range(3):
            ph.run(120, SWING_W)
            ph.run(90, SIT_W)
            if i < 2:
                ph.run(1_200, SIT_W)
        self.assertEqual(len(ph.gps_starts), 3)
        ph.bssid = None
        ph.run(120, SWING_W, 1.4)
        ph.bssid = POCKET
        ph.run(120, SIT_W)
        drive = ph.time
        for _ in range(2):
            ph.run(90, RIDE_W, 10.0)
            ph.run(40, SIT_W)
        r = next((r for r in ph.reports if r.time >= drive and r.state == "moving"), None)
        self.assertIsNotNone(r)
        self.assertLessEqual(r.time - drive, 180_000)

    def test_pocket_wifi_walked_away_first_fix_late(self):
        # 5：一边走一边量不凭一个近点下结论；首点 2 / 10 / 30 秒、跟着走过或没有，都 3 分钟内报出卫星点
        for hinted in (False, True):
            for cold in (2_000, 10_000, 30_000):
                with self.subTest(hinted=hinted, cold=cold):
                    ph = ProbePhone.anchored(POCKET, moving=[POCKET] if hinted else [])
                    ph.bssid = POCKET
                    ph.cold_start = cold
                    ph.run(60, FIDGET_W)
                    walk = ph.time
                    ph.run(900, SWING_W, 1.4)
                    r = ph.first_gps_report(walk, "moving")
                    self.assertIsNotNone(r)
                    self.assertLessEqual(r.time - walk, 180_000)

    def test_wifi_estimates_under_a_followed_wifi_are_dropped(self):
        # 6：跟着走过的 Wi-Fi 下 Wi-Fi 定位不用，基站照用
        def zone_net(followed: bool, provider: str) -> "reporter.Fix | None":
            ph = ProbePhone(R.WifiMemory(moving=[POCKET] if followed else []))
            ph.bssid = POCKET
            ph.network = (3_000.0, 30.0)
            ph.network_provider = provider
            ph.run(20, FIDGET_W)
            self.assertEqual(ph.reports[-1].state, "zone")
            return ph.reports[-1].net_fix

        self.assertIsNone(zone_net(True, "wifi"))
        self.assertIsNotNone(zone_net(True, "cell"))
        self.assertIsNotNone(zone_net(False, "wifi"))

    def test_sensors_stay_awake_while_probing(self):
        # 8：量的时候转静止也先不睡，量完再睡
        ph = ProbePhone.anchored("home-ap", cells=["7-1"])
        ph.bssid, ph.cell = "home-ap", 1
        ph.cold_start = None
        ph.run(60, STILL_W)
        ph.cell = 2
        ph.run(2, STILL_W)
        self.assertTrue(ph.judge.gps)
        while ph.judge.motion != R.STILL:
            ph.run(2, STILL_W)
        self.assertFalse(ph.judge.sleeping)
        while ph.judge.gps:
            ph.run(2, STILL_W)
        self.assertTrue(ph.judge.sleeping)


KOTLIN = ROOT / "app-v2/sdk/core/src/commonMain/kotlin/org/traccar/client"
# Kotlin 文件 → Python 里放这些常量的类（文件顶层的常量放 reporter 模块上）
KOTLIN_OWNERS = {
    "sense/Snapshot.kt": "Snap",
    "sense/Sky.kt": "Sky",
    "motion/MotionClassifier.kt": "MotionClassifier",
    "policy/ReportPolicy.kt": "ReportPolicy",
    "policy/WifiBook.kt": "WifiBook",
    "policy/AnchorProbe.kt": "AnchorProbe",
    "policy/Judge.kt": "Judge",
    "policy/NetFallback.kt": "NetFallback",
}


def kotlin_value(text: str) -> object:
    text = text.strip()
    if text.startswith('"'):
        return text.strip('"')
    text = text.replace("_", "").rstrip("L")
    return float(text) if "." in text else int(text)


class Audit311ContractTest(unittest.TestCase):
    """装机版 11.0.11 审计判断层几条，和 Kotlin 的 AuditFix311JudgeTest 同样的输入、同样的期望"""

    AP = "3c:8c:93:20:00:01"
    T0 = 1_800_000_000_000

    @staticmethod
    def lat(north: float) -> float:
        return 30.0 + north / (6_371_000.0 * math.pi / 180)

    def home(self) -> "reporter.WifiMemory":
        return R.WifiMemory(anchor=self.AP, anchor_at=R.Fix("gps", 0, 30.0, 120.0, 8.0))

    def on_ap(
        self, w: "reporter.Snap", t: int, link: int | None = None, fix: "reporter.Fix | None" = None
    ) -> "reporter.Snap":
        return dataclasses.replace(
            w,
            time=t,
            net="wifi",
            wifi_bssid=self.AP,
            wifi_ssid="CAR-8899",
            wifi_rssi=-50,
            wifi_metered=False,
            wifi_gateway="192.168.1.1",
            net_link=link,
            fix=fix,
        )

    def gps(self, t: int, north: float, speed: float = 0.0) -> "reporter.Fix":
        return R.Fix("gps", t, self.lat(north), 120.0, 8.0, speed)

    def probing(self, j: "reporter.Judge", reports: list) -> None:
        def take(d):
            if d and d.report:
                reports.append(j.describe(d.report))

        take(j.on_snapshot(self.T0, self.on_ap(SIT_W, self.T0)))
        take(j.on_fetched(self.T0 + 500, R.Fix("cell", self.T0 + 500, self.lat(3_000.0), 120.0, 100.0)))
        self.assertTrue(j.gps)

    def test_far_probe_point_is_reported_whichever_way_it_arrives(self):
        # 2：被动定位先进快照、定位回调先到、只有被动定位，都马上报出远处的卫星点（一次）
        for order in ("fix", "snapshot", "passive-only"):
            j = R.Judge(self.home())
            reports: list = []
            self.probing(j, reports)
            t = self.T0 + 2_000
            far = self.gps(t - 500, 3_000.0)
            steps = {
                "fix": [lambda: j.on_fix(t, far), lambda: j.on_snapshot(t, self.on_ap(SIT_W, t, fix=far))],
                "snapshot": [lambda: j.on_snapshot(t, self.on_ap(SIT_W, t, fix=far)), lambda: j.on_fix(t, far)],
                "passive-only": [lambda: j.on_snapshot(t, self.on_ap(SIT_W, t, fix=far))],
            }[order]
            for step in steps:
                d = step()
                if d and d.report:
                    reports.append(j.describe(d.report))
            with self.subTest(order):
                self.assertEqual([r.fix.lat for r in reports if r.fix], [self.lat(3_000.0)])

    def test_probe_compares_with_the_anchor_point_it_started_from(self):
        # 2：说不清的 150 米点把锚点挪过去之后，离原锚点 330 米的点照样算超出
        j = R.Judge(self.home())
        reports: list = []
        self.probing(j, reports)
        t = self.T0
        for north in (150.0, 330.0):
            t += 2_000
            for d in (j.on_fix(t, self.gps(t - 500, north)), j.on_snapshot(t, self.on_ap(SIT_W, t))):
                if d and d.report:
                    reports.append(j.describe(d.report))
        self.assertEqual([r.fix.lat for r in reports if r.fix], [self.lat(330.0)])

    def gps_on_after_cooldown(self, reconnect: bool) -> int | None:
        j = R.Judge(self.home())
        t = self.T0
        link = 1
        measured = False
        while not measured or j.gps:
            t += 2_000
            if j.gps:
                j.on_fix(t, self.gps(t - 500, 0.0))
                measured = True
            j.on_snapshot(t, self.on_ap(RIDE_W, t, link))
        if reconnect:
            link += 1
        back = t
        while t - back < 300_000:
            t += 2_000
            j.on_snapshot(t, self.on_ap(RIDE_W, t, link))
            if j.gps:
                return t - back
        return None

    def test_reconnecting_the_same_wifi_clears_the_cooldown(self):
        # 3：同一个 Wi-Fi 在两份快照之间断开又连上，断开前的冷却不再挡
        after = self.gps_on_after_cooldown(reconnect=True)
        self.assertIsNotNone(after)
        self.assertLessEqual(after, 10_000)
        self.assertIsNone(self.gps_on_after_cooldown(reconnect=False), "一直连着的冷却照挡")

    def test_setting_the_clock_a_day_fast_does_not_stop_a_drive(self):
        # 6：快照间隔、卫星点新不新鲜按开机时长算
        j = R.Judge()
        day = 86_400_000
        st = {"wall": self.T0, "elapsed": 3_600_000, "sat": self.T0, "north": 0.0}
        reports: list = []

        def drive(seconds: int) -> None:
            for _ in range(seconds // 2):
                for k in ("wall", "elapsed", "sat"):
                    st[k] += 2_000
                st["north"] += 24.0
                fix = self.gps(st["sat"] - 500, st["north"], 12.0)
                if j.gps:
                    d = j.on_fix(st["wall"], fix, st["elapsed"] - 500)
                    if d and d.report:
                        reports.append((st["wall"], j.describe(d.report)))
                passive = dataclasses.replace(fix, elapsed=st["elapsed"] - 500)
                d = j.on_snapshot(
                    st["wall"], dataclasses.replace(SMOOTH_W, time=st["wall"], elapsed=st["elapsed"], fix=passive)
                )
                if d.report:
                    reports.append((st["wall"], j.describe(d.report)))

        drive(120)
        self.assertEqual(j.motion, R.VEHICLE)
        st["wall"] += day
        j.clock_shifted(day)
        jumped = st["wall"]
        drive(600)
        self.assertTrue(j.gps)
        fresh = [r.fix.time for at, r in reports if at >= jumped and r.fix]
        self.assertGreaterEqual(len(fresh), 55)
        self.assertEqual(len(fresh), len(set(fresh)))

    def test_a_reliable_fast_point_gets_out_of_still(self):
        # 7：静止中，8 米精度、12 m/s 的新鲜卫星点马上出静止；200 米精度的不算
        c = R.MotionClassifier()
        c.seed_still()
        fast = feed(
            c,
            STILL_W,
            302,
            self.T0,
            lambda s: dataclasses.replace(s, fix=R.Fix("gps", s.time - 500, acc=8.0, speed=12.0)),
        )
        self.assertTrue(all(m == R.VEHICLE for m in fast), fast[:3])
        c = R.MotionClassifier()
        c.seed_still()
        bad = feed(
            c,
            STILL_W,
            60,
            self.T0,
            lambda s: dataclasses.replace(s, fix=R.Fix("gps", s.time - 500, acc=200.0, speed=12.0)),
        )
        self.assertTrue(all(m == R.STILL for m in bad))


class ReporterConstantsTest(unittest.TestCase):
    """reporter.py 的常量和 Kotlin 一字不差：读 Kotlin 源码里每个 const val，在同名类上找同名常量比对"""

    def test_every_kotlin_constant_matches(self):
        if not KOTLIN.is_dir():
            self.skipTest("没有 app-v2 源码")
        checked = 0
        for rel, owner in KOTLIN_OWNERS.items():
            cls = getattr(R, owner)
            for name, value in re.findall(r"const val (\w+) = (.+)", (KOTLIN / rel).read_text()):
                with self.subTest(f"{rel} {name}"):
                    got = getattr(cls, name, getattr(R, name, None))
                    self.assertIsNotNone(got, f"reporter.{owner} 缺 {name}")
                    self.assertEqual(got, kotlin_value(value))
                    checked += 1
        self.assertGreater(checked, 50)

    def test_hotspot_names_match(self):
        if not KOTLIN.is_dir():
            self.skipTest("没有 app-v2 源码")
        pattern = re.search(r'HOTSPOT_NAMES = Regex\("""(.+?)"""', (KOTLIN / "policy/WifiBook.kt").read_text()).group(1)
        self.assertEqual(R.WifiBook.HOTSPOT_NAMES.pattern, pattern)


class ReporterParityTest(unittest.TestCase):
    """模拟器用不到、但 Kotlin 有的入口，照 Kotlin 的 JudgeTest、ReportPolicyTest 同名用例"""

    def test_no_sensor_data_at_all_still_reports_from_the_first_breath(self):
        j = R.Judge()
        d = j.on_tick(1_000_000)
        self.assertEqual(j.describe(d.report).state, "zone")
        self.assertTrue(d.fetch)
        j.on_fetched(1_001_000, None)
        self.assertTrue(j.on_tick(1_300_000).fetch, "之后 5 分钟取一次")

    def test_restored_still_breathes_at_once_and_stays_still(self):
        j = R.Judge(restored=R.STILL)
        # 没有主坐标：这次呼吸顺带取网络定位，取回来（这里没取到）再报
        d = j.on_tick(2_000_000)
        self.assertTrue(d.fetch)
        self.assertIsNone(d.report)
        self.assertEqual(j.describe(j.on_fetched(2_000_001, None).report).state, "still")
        d = j.on_snapshot(2_000_002, dataclasses.replace(STILL_W, time=2_000_002))
        self.assertIsNone(d.report)
        self.assertEqual(j.motion, R.STILL)

    def test_clock_set_back_keeps_breathing_every_five_minutes(self):
        p = R.ReportPolicy()
        reports = []

        def tick(sec: int) -> None:
            d = p.on_tick(sec * 1000)
            if d.report:
                reports.append(d.report)

        p.on_motion(0, R.STILL)
        tick(300)
        self.assertEqual(len(reports), 1)
        back = 300 - 86_400
        tick(back + 10)
        tick(back + 309)
        self.assertEqual(len(reports), 1, "间隔没到不报")
        tick(back + 310)
        self.assertEqual(len(reports), 2, "往回拨后仍按 5 分钟呼吸")

    def test_known_clock_shift_keeps_the_exact_cadence(self):
        j = R.Judge()
        for i in range(70):
            t = 1_000_000 + i * 2_000
            j.on_snapshot(t, dataclasses.replace(STILL_W, time=t))
        self.assertEqual(j.motion, R.STILL)
        nxt = j.policy.next_breath_at
        j.clock_shifted(-86_400_000)
        self.assertEqual(j.policy.next_breath_at, nxt - 86_400_000)
        self.assertIsNone(j.on_tick(nxt - 86_400_000 - 1).report)
        d = j.on_tick(nxt - 86_400_000)
        self.assertTrue(d.report is not None or d.fetch, "到点呼吸（主坐标过时时先取网络定位、回来再报）")

    def test_probe_expires_when_the_clock_went_back(self):
        # Kotlin 的 expire 用 now - since in 0 until LIMIT：时间往回改、差是负的也算到时限，不让探测一直开着
        p = R.AnchorProbe()
        self.assertTrue(p.start(1_000_000, "home", None))
        self.assertTrue(p.expire(900_000))
        self.assertFalse(p.active)
        p.shift(-100_000)
        self.assertEqual(p.blocked_until, 900_000 + R.AnchorProbe.RETRY - 100_000)


NF_T0 = 1_800_000_000_000
NF_AP = "3c:8c:aa:00:00:01"
NF_LAT, NF_LON = 30.27, 120.15


def nf_lat(meters: float) -> float:
    return NF_LAT + meters / (6_371_000.0 * math.pi / 180)


def nf_estimate(t: int, north: float, acc: float = 86.0, provider: str = "wifi") -> "reporter.Fix":
    return R.Fix(provider, t, nf_lat(north), NF_LON, acc)


NF_MORNING = R.MainFix(nf_lat(2_800.0), NF_LON, 8.0, True, NF_T0 - 12 * 3_600_000)
NF_HOME = R.WifiMemory(
    anchor=NF_AP, anchor_at=R.Fix("gps", 0, nf_lat(0.0), NF_LON, 20.0)
)  # 家里 Wi-Fi 被 20 米的卫星点锚过


class NfPhone:
    """和 Kotlin NetFallbackTest 的 Phone 一样：每 2 秒一份快照（坐着拿手机，一直屋里走动），说取网络定位就按 network 给一个估算"""

    def __init__(self, memory: "reporter.WifiMemory | None" = None, main: "reporter.MainFix | None" = None):
        self.judge = R.Judge(memory, main=main)
        self.time = NF_T0
        self.snap = dataclasses.replace(
            SIT_W,
            net="wifi",
            wifi_bssid=NF_AP,
            wifi_ssid="HOME-5G",
            wifi_rssi=-50,
            wifi_metered=False,
            wifi_gateway="192.168.1.1",
        )
        self.network = lambda t: None
        self.reports: list = []

    def run(self, seconds: int) -> None:
        for _ in range(seconds // 2):
            self.time += 2_000
            self.take(self.judge.on_snapshot(self.time, dataclasses.replace(self.snap, time=self.time)))

    def take(self, d) -> None:
        if d is None:
            return
        if d.report:
            self.reports.append(self.judge.describe(d.report))
        if d.fetch:
            self.take(self.judge.on_fetched(self.time, self.network(self.time)))

    @property
    def estimates(self) -> list:
        return [r for r in self.reports if r.estimate]


class NetFallbackContractTest(unittest.TestCase):
    """屋里定不到卫星时用网络估算当主坐标：照 Kotlin 的 NetFallbackTest 同名用例"""

    def test_far_morning_point_is_replaced_within_two_minutes_of_the_first_estimate(self):
        phone = NfPhone(main=NF_MORNING)
        phone.network = lambda t: nf_estimate(t, 20.0 + (t - NF_T0) / 10_000.0)
        phone.run(180)
        self.assertTrue(phone.estimates)
        first = phone.estimates[0]
        self.assertLessEqual(first.time - NF_T0, 120_000)
        self.assertEqual(first.fix.acc, 86.0)
        self.assertEqual(first.net_fix.provider, "wifi")
        self.assertEqual(len(phone.estimates), 1, "同一个地方不反复当主坐标报")
        self.assertFalse(phone.judge.fallback.main.gps)
        memory = phone.judge.wifi.memory
        self.assertEqual(memory.anchor, NF_AP)
        self.assertTrue(memory.temp)
        self.assertEqual(memory.anchor_at.acc, 86.0)

    def test_fresh_install_gets_a_main_coordinate_indoors(self):
        phone = NfPhone()
        phone.network = lambda t: nf_estimate(t, 0.0, acc=50.0)
        phone.run(150)
        self.assertTrue(phone.estimates)
        first = phone.estimates[0]
        self.assertLessEqual(first.time - NF_T0, 120_000)
        self.assertTrue(all(r.fix is None for r in phone.reports[: phone.reports.index(first)]))

    def test_estimates_of_a_moving_wifi_never_become_the_main_coordinate(self):
        followed = NfPhone(R.WifiMemory(moving=[NF_AP]), main=NF_MORNING)
        followed.network = lambda t: nf_estimate(t, 0.0, acc=40.0)
        followed.run(900)
        self.assertFalse(followed.estimates)
        self.assertTrue(all(r.net_fix is None for r in followed.reports))
        hotspot = NfPhone(main=NF_MORNING)
        hotspot.snap = dataclasses.replace(hotspot.snap, wifi_ssid="小宝的iPhone", wifi_gateway="172.20.10.1")
        hotspot.network = lambda t: nf_estimate(t, 0.0, acc=40.0)
        hotspot.run(900)
        self.assertFalse(hotspot.estimates)
        cell = NfPhone(main=NF_MORNING)
        cell.network = lambda t: nf_estimate(t, 0.0, acc=500.0, provider="cell")
        cell.run(900)
        self.assertFalse(cell.estimates)
        self.assertFalse(cell.judge.wifi.memory.temp)

    def test_satellite_point_within_ten_minutes_wins(self):
        phone = NfPhone(main=dataclasses.replace(NF_MORNING, at=NF_T0 - 300_000))
        phone.network = lambda t: nf_estimate(t, 0.0)
        phone.run(240)
        self.assertFalse(phone.estimates)
        phone.run(600)
        self.assertTrue(phone.estimates)
        self.assertGreaterEqual(phone.estimates[0].time, NF_T0 + 300_000)

    def test_estimate_near_the_last_main_coordinate_keeps_it(self):
        phone = NfPhone(main=dataclasses.replace(NF_MORNING, lat=nf_lat(250.0)))
        phone.network = lambda t: nf_estimate(t, 0.0)
        phone.run(900)
        self.assertFalse(phone.estimates)
        self.assertTrue(phone.judge.wifi.memory.temp)

    def test_wifi_with_a_satellite_anchor_trusts_the_anchor(self):
        anchored = R.WifiMemory(anchor=NF_AP, anchor_at=R.Fix("gps", 0, nf_lat(0.0), NF_LON, 8.0))
        phone = NfPhone(anchored, main=dataclasses.replace(NF_MORNING, lat=nf_lat(0.0)))
        phone.network = lambda t: nf_estimate(t, 600.0, acc=40.0)
        phone.run(900)
        self.assertFalse(phone.estimates)
        self.assertFalse(phone.judge.wifi.memory.temp)

    def test_wifi_with_a_satellite_anchor_does_not_fetch_every_minute(self):
        # 门第 1 轮审查：锚点 Wi-Fi 下确认了也顶不上的估算不催取，屋里照常 5 分钟取一次
        anchored = R.WifiMemory(anchor=NF_AP, anchor_at=R.Fix("gps", 0, nf_lat(0.0), NF_LON, 8.0))
        phone = NfPhone(anchored, main=dataclasses.replace(NF_MORNING, lat=nf_lat(0.0)))
        fetches = []

        def network(t):
            fetches.append(t)
            return nf_estimate(t, 600.0, acc=40.0)

        phone.network = network
        phone.run(900)
        self.assertFalse(phone.estimates)
        self.assertLessEqual(len(fetches), 4, f"15 分钟取了 {len(fetches)} 次")

    def test_anchored_home_wifi_reports_the_anchor_when_the_main_coordinate_is_far_away(self):
        # 11.0.15 核对：家里 Wi-Fi 有卫星锚点，主坐标停在 2.8 公里外，屋里估算和锚点对得上：一个估算就顶锚点本身
        phone = NfPhone(NF_HOME, main=NF_MORNING)
        fetches = []

        def network(t):
            fetches.append(t)
            return nf_estimate(t, 25.0 + (t - NF_T0) / 10_000.0)

        phone.network = network
        phone.run(60)
        first = next(r for r in phone.reports if r.estimate)
        self.assertEqual(first.time, fetches[0], "一个估算就顶，不等第二次")
        self.assertEqual((first.fix.provider, first.fix.lat, first.fix.acc), ("anchor", nf_lat(0.0), 20.0))
        self.assertFalse(phone.judge.fallback.main.gps)
        phone.run(900)
        self.assertEqual(len(phone.estimates), 1, "已经在家了：不反复顶")
        self.assertLessEqual(len(fetches), 5, f"顶上以后照常 5 分钟取一次：15 分钟取了 {len(fetches)} 次")
        self.assertFalse(phone.judge.wifi.memory.temp)

    def test_anchored_home_waits_for_ten_minutes_without_satellites_then_hurries(self):
        phone = NfPhone(NF_HOME, main=dataclasses.replace(NF_MORNING, at=NF_T0 - 300_000))
        phone.network = lambda t: nf_estimate(t, 10.0)
        phone.run(240)
        self.assertFalse(phone.estimates, "10 分钟内有过卫星点")
        phone.run(600)
        first = phone.estimates[0]
        self.assertEqual(first.fix.provider, "anchor")
        self.assertTrue(
            NF_T0 + 300_000 <= first.time <= NF_T0 + 310_000,
            f"卫星点满 10 分钟就催取、顶上：{(first.time - NF_T0) // 1000} 秒",
        )

    def test_anchored_home_with_the_main_coordinate_already_there_keeps_it(self):
        phone = NfPhone(NF_HOME, main=dataclasses.replace(NF_MORNING, lat=nf_lat(40.0)))
        phone.network = lambda t: nf_estimate(t, 30.0)
        phone.run(900)
        self.assertFalse(phone.estimates)

    def test_estimate_far_from_the_satellite_anchor_is_neither_promoted_nor_hurried(self):
        anchor = R.Fix("gps", NF_T0 - 86_400_000, nf_lat(0.0), NF_LON, 20.0)
        f = R.NetFallback(NF_MORNING)
        self.assertEqual(f.estimate(NF_T0, nf_estimate(NF_T0, 600.0, acc=40.0), anchor), R.NetFallback.NONE)
        self.assertFalse(f.due(NF_T0 + 600_000))
        self.assertIsNone(f.take_pending())
        self.assertEqual(
            f.estimate(NF_T0 + 1_000, nf_estimate(NF_T0 + 1_000, 150.0, acc=40.0), anchor), R.NetFallback.PROMOTED
        )
        p = f.take_pending()
        self.assertEqual((p.provider, p.lat, p.acc), ("anchor", nf_lat(0.0), 20.0))

    def test_first_open_satellite_fix_replaces_the_temporary_anchor(self):
        phone = NfPhone(main=NF_MORNING)
        phone.network = lambda t: nf_estimate(t, 0.0)
        phone.run(180)
        self.assertTrue(phone.judge.wifi.memory.temp)
        t = phone.time + 2_000
        phone.time = t
        snap = dataclasses.replace(phone.snap, time=t, fix=R.Fix("gps", t - 500, nf_lat(30.0), NF_LON, 6.0))
        phone.take(phone.judge.on_snapshot(t, snap))
        memory = phone.judge.wifi.memory
        self.assertEqual(memory.anchor, NF_AP)
        self.assertFalse(memory.temp)
        self.assertEqual(memory.anchor_at.acc, 6.0)

    def test_temporary_anchor_found_far_away_is_not_remembered_as_a_moving_wifi(self):
        book = R.WifiBook()
        home = R.Snap(time=NF_T0, net="wifi", wifi_bssid=NF_AP, wifi_ssid="HOME-5G", wifi_metered=False)
        self.assertTrue(book.temp_anchor(home, R.Fix("wifi", NF_T0, nf_lat(0.0), NF_LON, 150.0)))
        self.assertEqual(book.kind(home), R.ANCHOR)
        self.assertTrue(book.release(followed=True))
        self.assertEqual(book.memory.moving, [])
        self.assertFalse(book.memory.temp)
        followed = R.WifiBook(R.WifiMemory(moving=[NF_AP]))
        self.assertFalse(followed.temp_anchor(home, R.Fix("wifi", NF_T0, nf_lat(0.0), NF_LON, 150.0)))

    def test_every_report_carries_the_recent_trusted_estimate(self):
        phone = NfPhone(main=dataclasses.replace(NF_MORNING, lat=nf_lat(0.0)))
        calls = []

        def network(t):
            calls.append(t)
            return nf_estimate(t, 10.0, acc=40.0) if len(calls) == 1 else None

        phone.network = network
        phone.run(20)
        got = phone.reports[-1]
        self.assertEqual(got.net_fix.lat, nf_lat(10.0))
        phone.run(300)
        nxt = phone.reports[-1]
        self.assertGreaterEqual(nxt.time - got.time, 290_000)
        self.assertIsNone(nxt.fix)
        self.assertEqual(nxt.net_fix.lat, nf_lat(10.0), "5 分钟前的估算照带")
        phone.run(700)
        self.assertIsNone(phone.reports[-1].net_fix, "超过 10 分钟的不带")

    def test_restart_remembers_the_main_coordinate(self):
        phone = NfPhone(main=NF_MORNING)
        phone.network = lambda t: nf_estimate(t, 0.0)
        phone.run(180)
        self.assertEqual(len(phone.estimates), 1)
        again = NfPhone(phone.judge.wifi.memory, phone.judge.fallback.main)
        again.time = phone.time
        again.network = phone.network
        again.run(900)
        self.assertFalse(again.estimates)

    def test_satellite_report_cancels_a_waiting_estimate_and_restarts_the_ten_minutes(self):
        f = R.NetFallback(NF_MORNING)
        self.assertEqual(f.estimate(NF_T0, nf_estimate(NF_T0, 0.0), None), R.NetFallback.NONE)
        self.assertTrue(f.due(NF_T0 + 60_000))
        f.asked()
        self.assertFalse(f.due(NF_T0 + 62_000), "同一个候选只催一次")
        self.assertEqual(f.estimate(NF_T0 + 60_000, nf_estimate(NF_T0 + 60_000, 30.0), None), R.NetFallback.PROMOTED)
        f.reported(NF_T0 + 61_000, R.Fix("gps", NF_T0 + 61_000, nf_lat(5.0), NF_LON, 6.0), True)
        self.assertIsNone(f.take_pending())
        self.assertEqual(f.estimate(NF_T0 + 200_000, nf_estimate(NF_T0 + 200_000, 2_000.0), None), R.NetFallback.NONE)
        self.assertEqual(f.estimate(NF_T0 + 300_000, nf_estimate(NF_T0 + 300_000, 2_000.0), None), R.NetFallback.NONE)

    def test_bouncing_estimates_stop_hurrying_after_three_tries(self):
        f = R.NetFallback(NF_MORNING)
        t = NF_T0
        for i in range(R.NetFallback.MAX_ASKS):
            f.estimate(t, nf_estimate(t, i * 1_000.0), None)
            self.assertTrue(f.due(t + 60_000))
            f.asked()
            t += 60_000
        f.estimate(t, nf_estimate(t, 9_000.0), None)
        self.assertFalse(f.due(t + 60_000))


class NetFallbackScenarioTest(unittest.TestCase):
    """验收：模拟场景里网络估算当主坐标"""

    def test_home_with_only_network_estimates_switches_within_two_minutes(self):
        # 最后一个卫星点在 2.8 公里外（场景开始那一刻），坐地铁一路没卫星，回家屋里只有网络估算
        sc = scenario.load("home-net-only")
        rs = reports_of(sc)
        home = next(s.t for s in sc.truth if s.kind == "indoor")
        last_gps = max(r.t for r in rs if r.fix and r.fix.provider == "gps")
        due = max(home, last_gps + R.NetFallback.NO_GPS // 1000)
        first = next(r for r in rs if r.fix and "estimate" in r.tags)
        self.assertGreaterEqual(first.t, home, "到家前没有估算")
        self.assertLessEqual(
            first.t - due, 120, f"到家且卫星点满 10 分钟后 2 分钟内换成估算：{first.t} 秒（应在 {due} 后）"
        )
        self.assertEqual(first.fix.provider, "network")
        truth = sc.at(int(first.t))
        self.assertLess(
            geo.distance(truth.lat, truth.lon, first.fix.lat, first.fix.lon), 2 * first.fix.acc, "估算就在家附近"
        )
        stored, _ = traccar.process(rs, sc.start_ms)
        p = next(p for p in stored if p.report is first)
        self.assertEqual((p.attributes["provider"], p.accuracy), ("network", round(first.fix.acc, 1)))
        later = [p for p in stored if p.report.t > first.t]
        self.assertTrue(
            later and all(geo.distance(p.lat, p.lon, first.fix.lat, first.fix.lon) < 1 for p in later),
            "之后服务器沿用这个位置",
        )

    def test_fresh_install_indoors_gets_a_position_on_the_server(self):
        sc = scenario.load("fresh-install-indoor")
        rs = reports_of(sc)
        self.assertFalse(any(r.fix and r.fix.provider == "gps" for r in rs), "从没有过卫星点")
        first = next(r for r in rs if r.fix and "estimate" in r.tags)
        self.assertLessEqual(first.t, 120, "装好 2 分钟内有主坐标")
        stored, dropped = traccar.process(rs, sc.start_ms)
        self.assertTrue(all(r.t < first.t for r, _ in dropped), "有了主坐标以后不再因为没有位置被丢")
        self.assertTrue(any(p.report is first for p in stored))

    def test_still_after_update_is_located_within_ten_minutes(self):
        # 11.0.16 装机实测：装完更新、App 重启后接着静止在屋里，上报全是只带时间的呼吸，没有估算、顶不上（三星一直 0,0）
        sc = scenario.load("still-after-update")
        log: list[str] = []
        rs = phone.Phone(sc).reports(log)
        self.assertTrue(all(r.state == "still" for r in rs), "一直是静止")
        first = next((r for r in rs if r.fix and "estimate" in r.tags), None)
        self.assertIsNotNone(first, "静止时呼吸顺带取网络定位，两次一致就当主坐标")
        self.assertLessEqual(first.t, 600, f"10 分钟内顶上：{first.t} 秒")
        self.assertTrue(all(r.net == "wifi" for r in rs), "联网方式是 Wi-Fi，不是 none")
        fetched = [int(line.split(";")[1]) for line in log if line.startswith("f;")]
        busiest = max(sum(1 for t in fetched if 0 <= t - f < 3_600_000) for f in fetched)
        self.assertLessEqual(busiest, R.ReportPolicy.STILL_FETCH_MAX, f"任意一小时取网络定位 {busiest} 次：{fetched}")

    def test_home_wifi_with_a_satellite_anchor_reports_the_anchor(self):
        # 11.0.15 核对（原 repro_anchored_still.py）：同一场景，家里 Wi-Fi 一天前被卫星点锚在家。以前连着锚 Wi-Fi 一律不顶，
        # 顶上 0 次，主坐标一直停在 2.8 公里外
        sc = scenario.load("home-net-only")
        home = sc.truth[-1]
        seeded = []

        class Anchored(R.Judge):
            def on_snapshot(self, now, raw):
                if not seeded and raw.net == "wifi" and raw.wifi_bssid:
                    seeded.append(raw.wifi_bssid)
                    self.wifi.anchor = raw.wifi_bssid
                    self.wifi.anchor_at = R.Fix("gps", now - 86_400_000, home.lat, home.lon, 20.0)
                return super().on_snapshot(now, raw)

        with mock.patch.object(reporter, "Judge", Anchored):
            rs = reports_of(sc)
        self.assertTrue(seeded)
        at_home = next(s.t for s in sc.truth if s.kind == "indoor")
        last_gps = max(r.t for r in rs if r.fix and r.fix.provider == "gps")
        due = max(at_home, last_gps + R.NetFallback.NO_GPS // 1000)
        first = next(r for r in rs if r.fix and "estimate" in r.tags)
        self.assertEqual(first.fix.provider, "anchor")
        self.assertLessEqual(
            first.t - due, 120, f"到家且卫星点满 10 分钟后 2 分钟内顶上：{first.t} 秒（应在 {due} 后）"
        )
        last = [r for r in rs if r.fix][-1]
        self.assertLess(geo.distance(home.lat, home.lon, last.fix.lat, last.fix.lon), 50, "主坐标在家")
        stored, _ = traccar.process(rs, sc.start_ms)
        p = next(p for p in stored if p.report is first)
        self.assertEqual((p.attributes["provider"], p.accuracy), ("anchor", 20.0))

    def test_misplaced_wifi_database_at_an_anchored_place_is_not_promoted(self):
        # 同学家 Wi-Fi 位置库记错（估算都在 400 米外）：到的时候卫星点把这个 Wi-Fi 记成了锚，估算不当主坐标
        sc = dataclasses.replace(scenario.load("arrive-wifi-wrong"))
        sc.segments[-1].seconds = 1800
        scenario.build_truth(sc)
        rs = reports_of(sc)
        self.assertFalse([r.t for r in rs if r.fix and "estimate" in r.tags])


if __name__ == "__main__":
    unittest.main()
