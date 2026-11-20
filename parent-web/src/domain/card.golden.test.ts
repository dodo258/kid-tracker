// 重写解读层（status / stopped / presence / track）前钉住的行为：三份真实回放逐条喂进 30 分钟窗口，
// 每收到一条（和收到后 20 分钟没有新消息时）卡片第一行和第三行写什么。按 100 行一段算 SHA-256，对不上时打印那一段，看哪里变了。
// 有意改说法时：跑 GOLDEN_PRINT=1 npx vitest run src/domain/card.golden.test.ts，看清每一处变化是想要的，再换掉下面的摘要。
import { describe, expect, it } from 'vitest'
import p20 from '../../fixtures/p20.json'
import ride from '../../fixtures/p20-ride-1118.json'
import jump from '../../fixtures/p20-wifi-jump-1118.json'
import { parsePositions } from '../api/parse'
import type { Position } from '../api/types'
import { acceptedFix, smoothTrack } from '../motion/smooth'
import { contactTime, movingSince, presenceOf } from './presence'
import { statusLine, whereLine } from './status'
import { addPositions, EMPTY_HISTORY, steadyOf } from './track'

const CHUNK = 100

/** 每收到一条（按服务器收到的顺序）和之后 20 分钟没消息时，卡片两行；和上一行一样的不记 */
function transcript(raw: unknown): string[] {
  const rows = parsePositions(raw).sort((a, b) => contactTime(a).getTime() - contactTime(b).getTime() || a.id - b.id)
  let h = EMPTY_HISTORY
  let latest: Position | undefined
  const out: string[] = []
  for (const p of rows) {
    h = addPositions(h, [p])
    if (!latest || contactTime(p) >= contactTime(latest)) latest = p
    for (const late of [0, 20]) {
      const now = new Date(contactTime(p).getTime() + late * 60_000)
      const presence = presenceOf(latest, now, movingSince(h.reports))
      const s = statusLine({ position: latest, presence, history: h, placeName: undefined, offlineSince: null, updating: false, now })
      const shown = acceptedFix(smoothTrack(steadyOf(h))) ?? latest
      const w = whereLine(latest, shown, undefined, presence.freshness === 'lost', now)
      const line = `${late ? '+20 ' : ''}${s.text}|${s.tone}|${w.where}|${w.ago}`
      if (out.at(-1) !== line) out.push(line)
    }
  }
  return out
}

async function digests(lines: string[]): Promise<string[]> {
  const out: string[] = []
  for (let i = 0; i < lines.length; i += CHUNK) {
    const bytes = new TextEncoder().encode(lines.slice(i, i + CHUNK).join('\n'))
    const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))
    out.push([...hash.slice(0, 8)].map((b) => b.toString(16).padStart(2, '0')).join(''))
  }
  return out
}

const GOLDEN: Record<string, { raw: unknown; lines: number; digests: string[] }> = {
  p20: {
    raw: p20,
    lines: 996,
    digests: ['825f299e1e441f7b', 'd3a6c58b824de966', '56bbd8d7cd26f5e0', 'c6b41e118dc35295', '607083951f058de1', 'c25f829d7c7590a5', '34beae0a0c38879c', '22d27680741fece8', 'dc5f1510ffd4b7e9', 'a0c82fc839666439'],
  },
  ride: { raw: ride, lines: 122, digests: ['541f8b1f1a6ed176', '88257b8c99b8a935'] },
  jump: { raw: jump, lines: 150, digests: ['6651db651014785b', 'c44b04f570ed22b6'] },
}

describe('卡片说法和重写前一样（P20 真实回放）', () => {
  for (const [name, g] of Object.entries(GOLDEN)) {
    it(name, async () => {
      const lines = transcript(g.raw)
      const got = await digests(lines)
      if (import.meta.env.GOLDEN_PRINT) console.log(name, JSON.stringify({ lines: lines.length, digests: got }))
      const bad = got.findIndex((d, i) => d !== g.digests[i])
      if (bad >= 0) console.log(`${name} 第 ${bad * CHUNK + 1} 行起对不上：\n${lines.slice(bad * CHUNK, (bad + 1) * CHUNK).join('\n')}`)
      expect(lines.length).toBe(g.lines)
      expect(got).toEqual(g.digests)
    })
  }
})
