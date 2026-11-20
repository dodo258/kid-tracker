"""截图里地图有没有画完：地图上露出容器底色（parent-web index.css 的 --map，浅色 #ece9e1）的整块就是瓦片还没画上的灰块。

只用标准库解 PNG（Chrome 截图是 8 位 RGB / RGBA、不隔行）。只看图上半部分（地图区，底部状态卡盖不到），
按 CELL×CELL 设备像素分格，一格里 98% 以上是底色就算一块灰块。高德瓦片的底色是 #eeede7、#f1f0ea 这类，和容器底色不一样。
"""

import struct
import zlib
from pathlib import Path

MAP_BG = bytes.fromhex("ece9e1")
CELL = 48
BLANK_SHARE = 0.98
MAP_SHARE = 0.5  # 图上面这一半是地图


def decode(path: Path, max_rows: int | None = None) -> tuple[int, int, int, list[bytes]]:
    """返回（宽、高、每像素字节数、前 max_rows 行的像素）"""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path} 不是 PNG")
    pos, idat, w, h, bpp = 8, bytearray(), 0, 0, 0
    while pos < len(data):
        n, typ = struct.unpack(">I4s", data[pos : pos + 8])
        body = data[pos + 8 : pos + 8 + n]
        pos += 12 + n
        if typ == b"IHDR":
            w, h, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or ctype not in (2, 6) or interlace:
                raise ValueError(f"{path}：只支持 8 位 RGB/RGBA 不隔行")
            bpp = 3 if ctype == 2 else 4
        elif typ == b"IDAT":
            idat += body
    raw = zlib.decompress(bytes(idat))
    stride = w * bpp
    rows: list[bytes] = []
    prev = bytearray(stride)
    i = 0
    for _ in range(min(h, max_rows or h)):
        f = raw[i]
        line = bytearray(raw[i + 1 : i + 1 + stride])
        i += 1 + stride
        if f == 1:
            for x in range(bpp, stride):
                line[x] = (line[x] + line[x - bpp]) & 255
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                b = prev[x]
                c = prev[x - bpp] if x >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(bytes(line))
        prev = line
    return w, h, bpp, rows


def blank_cells(path: Path) -> tuple[int, int]:
    """地图区里灰块有几格、一共几格"""
    w, h, bpp, _ = decode(path, max_rows=1)
    _, _, _, rows = decode(path, max_rows=int(h * MAP_SHARE))
    blank = total = 0
    for y0 in range(0, len(rows) - CELL + 1, CELL):
        for x0 in range(0, w - CELL + 1, CELL):
            total += 1
            hits = 0
            for y in range(y0, y0 + CELL):
                row = rows[y]
                for x in range(x0, x0 + CELL):
                    if row[x * bpp : x * bpp + 3] == MAP_BG:
                        hits += 1
            if hits >= BLANK_SHARE * CELL * CELL:
                blank += 1
    return blank, total


def check(r, out: Path) -> None:
    """回放结果 r 的每张截图查灰块，结果记成一条检查（checks.Result.add）"""
    bad = []
    for s in r.shots:
        f = out / s["file"]
        if not f.exists():
            continue
        blank, total = blank_cells(f)
        if blank:
            late = "" if s.get("settled", True) else "，等了 4 秒瓦片还在下载"
            bad.append(f"{s['file']} {blank}/{total} 格{late}")
    r.add("截图里地图画完（没有灰块）", not bad, f"{len(r.shots)} 张" + ("；" + "；".join(bad) if bad else ""))
