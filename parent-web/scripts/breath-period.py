#!/usr/bin/env python3
# 量模拟器录屏里圆点光晕的呼吸节奏（只用 Python 标准库和 ffmpeg）：取圆点周围一个圆环里像素的「绿色程度」随时间的曲线，
# 自相关找周期；「单帧最大上升」是光晕一帧里亮起多少（呼吸分好几帧，一闪一两帧就亮满）。正常是 2.40 秒一圈。
# 用法：python3 scripts/breath-period.py 录屏.mp4 圆心x 圆心y 内半径 外半径 起始秒 结束秒 [标签]（坐标是录屏像素）
import subprocess, sys
video, cx, cy, rin, rout, t0, t1 = sys.argv[1], *map(int, sys.argv[2:6]), *map(float, sys.argv[6:8])
label = sys.argv[8] if len(sys.argv) > 8 else ''
FPS = 30
size = rout * 2
x0, y0 = cx - rout, cy - rout
raw = subprocess.run(['ffmpeg', '-v', 'error', '-ss', str(t0), '-to', str(t1), '-i', video,
                      '-vf', f'fps={FPS},crop={size}:{size}:{x0}:{y0}', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                     capture_output=True, check=True).stdout
frame = size * size * 3
idx = [(y * size + x) * 3 for y in range(size) for x in range(size)
       if rin * rin <= (x - rout) ** 2 + (y - rout) ** 2 <= rout * rout]
sig = []
for k in range(len(raw) // frame):
    f = raw[k * frame:(k + 1) * frame]
    sig.append(sum(f[i + 1] - (f[i] + f[i + 2]) / 2 for i in idx) / len(idx))
n = len(sig)
mean = sum(sig) / n
d = [v - mean for v in sig]
var = sum(v * v for v in d) or 1
best, lag_best = -2, 0
for lag in range(int(1.5 * FPS), min(int(3.5 * FPS), n // 2)):
    c = sum(d[i] * d[i + lag] for i in range(n - lag)) / var * n / (n - lag)
    if c > best:
        best, lag_best = c, lag
amp = max(sig) - min(sig)
# 光晕亮起来有多快：一帧里最大的上升占幅度的多少（一闪是一两帧就亮满，呼吸是分好几帧）
rise = max((sig[i + 1] - sig[i] for i in range(n - 1)), default=0) / (amp or 1)
print(f'{label} {t0:.0f}-{t1:.0f}s 帧数 {n} 幅度 {amp:.1f} 周期 {lag_best / FPS:.2f}s 自相关 {best:.2f} 单帧最大上升 {rise:.0%}')
