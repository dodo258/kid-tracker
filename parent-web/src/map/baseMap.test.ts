// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { BASE_LAYER, initialStyle } from './baseMap'

describe('设置里固定了底图，首屏直接用它（审计 11-18 #8）', () => {
  const tiles = (s: ReturnType<typeof initialStyle>) => {
    const src = s.sources[BASE_LAYER]
    return src.type === 'raster' ? (src.tiles ?? []) : []
  }
  it('固定腾讯、OSM：一开始的样式就是那家的瓦片，不先等高德', () => {
    expect(tiles(initialStyle(false, 'tencent'))[0]).toContain('map.gtimg.com')
    expect(tiles(initialStyle(true, 'osm'))[0]).toContain('tile.openstreetmap.org')
  })
  it('自动（先当高德）', () => {
    expect(tiles(initialStyle(false, 'amap'))[0]).toContain('autonavi.com')
  })
})
