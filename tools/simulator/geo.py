"""WGS-84 上的小工具：按米平移、两点距离、方位角、点到折线的距离。几公里范围内用局部平面近似，误差远小于 1 米。"""

import math

EARTH_R = 6378137.0
M_PER_DEG = 111_320.0


def offset(lat: float, lon: float, east_m: float, north_m: float) -> tuple[float, float]:
    """从 (lat, lon) 往东 east_m 米、往北 north_m 米"""
    return (lat + north_m / M_PER_DEG, lon + east_m / (M_PER_DEG * math.cos(math.radians(lat))))


def move(lat: float, lon: float, bearing_deg: float, dist_m: float) -> tuple[float, float]:
    """从 (lat, lon) 沿方位角 bearing_deg（正北 0 顺时针）走 dist_m 米"""
    b = math.radians(bearing_deg)
    return offset(lat, lon, dist_m * math.sin(b), dist_m * math.cos(b))


def enu(lat0: float, lon0: float, lat: float, lon: float) -> tuple[float, float]:
    """(lat, lon) 相对 (lat0, lon0) 的东、北米数"""
    return ((lon - lon0) * M_PER_DEG * math.cos(math.radians(lat0)), (lat - lat0) * M_PER_DEG)


def distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """两点大圆距离（米），和 parent-web 的 replay.mjs 一样用 haversine"""
    r = math.pi / 180
    d_lat = (lat2 - lat1) * r
    d_lon = (lon2 - lon1) * r
    h = math.sin(d_lat / 2) ** 2 + math.cos(lat1 * r) * math.cos(lat2 * r) * math.sin(d_lon / 2) ** 2
    return EARTH_R * 2 * math.atan2(math.sqrt(h), math.sqrt(1 - h))


def bearing(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """从点 1 到点 2 的方位角（度，0–360）"""
    e, n = enu(lat1, lon1, lat2, lon2)
    return math.degrees(math.atan2(e, n)) % 360


def turn_angle(b1: float, b2: float) -> float:
    """两个方位角之间转了多少度（0–180）"""
    d = abs(b1 - b2) % 360
    return 360 - d if d > 180 else d


def point_segment(lat: float, lon: float, a: tuple[float, float], b: tuple[float, float]) -> float:
    """点到线段 a–b 的距离（米）"""
    ax, ay = enu(lat, lon, a[0], a[1])
    bx, by = enu(lat, lon, b[0], b[1])
    dx, dy = bx - ax, by - ay
    seg2 = dx * dx + dy * dy
    t = 0.0 if seg2 == 0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / seg2))
    return math.hypot(ax + t * dx, ay + t * dy)
