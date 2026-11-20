import { describe, expect, it } from 'vitest'
import inputtips from '../../fixtures/amap-inputtips.json'
import placeText from '../../fixtures/amap-place.json'
import regeo from '../../fixtures/amap-regeo.json'
import { amapLocation, parseAmapLocation, parseInputTips, parsePlaceText, parseRegeo } from './amap'

describe('高德 location', () => {
  it('「经度,纬度」→ 纬度、经度', () => {
    expect(parseAmapLocation('120.147358,30.278221')).toEqual({ latitude: 30.278221, longitude: 120.147358 })
  })
  it('空数组、空串、格式不对、超范围都算没有', () => {
    for (const v of [[], '', ' ', '120.1', '120.1,', ',30.2', 'a,b', '200,30', '120,95', '1,2,3', null, 12]) {
      expect(parseAmapLocation(v)).toBeNull()
    }
  })
})

describe('输入提示 inputtips', () => {
  it('fixtures 的 3 条：名字、区和地址、坐标；类别那条没有坐标', () => {
    const tips = parseInputTips(inputtips)
    expect(tips).toHaveLength(3)
    expect(tips[0].name).toBe('杭州市文三街小学')
    expect(tips[0].detail).toBe('浙江省杭州市西湖区 文三路 252 号')
    expect(tips[0].location).toEqual({ latitude: 30.278221, longitude: 120.147358 })
    expect(tips[2].location).toBeNull()
    expect(tips[2].detail).toBe('浙江省杭州市西湖区')
  })
  it('高德报错（status "0"）抛出它的原因', () => {
    expect(() => parseInputTips({ status: '0', info: 'INVALID_USER_KEY', infocode: '10001' })).toThrow('INVALID_USER_KEY')
    expect(() => parseInputTips('<html>')).toThrow()
  })
  it('没有结果、条目缺名字的丢掉', () => {
    expect(parseInputTips({ status: '1', count: '0', tips: [] })).toEqual([])
    expect(parseInputTips({ status: '1', tips: [{ name: [] }, { name: '学校', location: [] }, 5] })).toEqual([
      { name: '学校', detail: '', location: null },
    ])
  })
})

describe('关键字搜索 place/text', () => {
  it('取 pois，第二行是区和地址', () => {
    const pois = parsePlaceText(placeText)
    expect(pois[0]).toEqual({ name: '杭州市文三街小学', detail: '西湖区 文三路 252 号', location: { latitude: 30.278221, longitude: 120.147358 } })
  })
})

describe('逆地理 regeo', () => {
  it('地址和城市', () => {
    expect(parseRegeo(regeo)).toEqual({ address: '浙江省杭州市西湖区北山街道文三路 152 号浙江科贸大楼', city: '杭州市' })
  })
  it('直辖市 city 是空数组，用 province', () => {
    const r = { status: '1', regeocode: { formatted_address: '北京市东城区东华门街道天安门', addressComponent: { city: [], province: '北京市' } } }
    expect(parseRegeo(r)).toEqual({ address: '北京市东城区东华门街道天安门', city: '北京市' })
  })
  it('海上、国外：formatted_address 是空数组，地址为 null', () => {
    expect(parseRegeo({ status: '1', regeocode: { formatted_address: [], addressComponent: { city: [], province: [] } } })).toEqual({
      address: null,
      city: null,
    })
  })
  it('失败抛错', () => {
    expect(() => parseRegeo({ status: '0', info: 'DAILY_QUERY_OVER_LIMIT' })).toThrow('DAILY_QUERY_OVER_LIMIT')
  })
})

describe('高德 location 参数', () => {
  it('「经度,纬度」，最多 6 位小数', () => {
    expect(amapLocation({ latitude: 30.27813456789, longitude: 120.1473581234 })).toBe('120.147358,30.278135')
    expect(amapLocation({ latitude: 39.9, longitude: 116.4 })).toBe('116.400000,39.900000')
  })
})
