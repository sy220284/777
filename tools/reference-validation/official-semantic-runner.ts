import { Context } from '@deepseek-ai/cordis'
import { z } from 'zod'
import SessionStore from '@deepseek-ai/dsh-session'
import SessionProjectionRegistry from '@deepseek-ai/dsh-session-projection'
import type { ProjectionDefinition } from '@deepseek-ai/dsh-session-projection'

declare module '@deepseek-ai/dsh-session-projection/types' {
  interface SessionProjectionStateMap {
    '777/reference': number
  }

  interface SessionProjectionMap {
    '777/reference': number
  }
}

declare module '@deepseek-ai/dsh-session/types' {
  interface SessionEventMap {
    '777/reference-mark': { value: number }
  }
}

interface ProjectionSemanticFixture {
  stateVersionZeroAccepted: boolean
  sameVersionShared: boolean
  differentVersionRejected: boolean
  asOfSeqAfterTwoEvents: number
  valueAfterTwoEvents: number
  survivesFirstDispose: boolean
  removedAfterLastDispose: boolean
}

function unit(
  stateVersion: number,
  multiplier: number,
): ProjectionDefinition<'777/reference', number> {
  return {
    key: '777/reference',
    stateSchema: z.number(),
    init: () => 0,
    apply: (state, event) =>
      event.type === '777/reference-mark'
        ? state + event.data.value * multiplier
        : state,
    wire: {
      viewSchema: z.number(),
      view: state => state,
    },
    stateVersion,
  }
}

async function main(): Promise<void> {
  const ctx = new Context()
  await ctx.plugin(SessionStore)
  await ctx.plugin(SessionProjectionRegistry)

  const session = ctx.sessions.create()
  const firstDispose = ctx.sessionProjections.register(unit(0, 1))
  const secondDispose = ctx.sessionProjections.register(unit(0, 100))

  session.append('777/reference-mark', { value: 2 })
  const firstCut = ctx.sessionProjections.snapshot(session)
  const sameVersionShared = firstCut.values['777/reference'] === 2

  let differentVersionRejected = false
  try {
    ctx.sessionProjections.register(unit(1, 1))
  } catch {
    differentVersionRejected = true
  }

  firstDispose()
  session.append('777/reference-mark', { value: 3 })
  const secondCut = ctx.sessionProjections.snapshot(session)
  const survivesFirstDispose =
    Object.prototype.hasOwnProperty.call(secondCut.values, '777/reference')

  secondDispose()
  const finalCut = ctx.sessionProjections.snapshot(session)
  const removedAfterLastDispose =
    !Object.prototype.hasOwnProperty.call(finalCut.values, '777/reference')

  const fixture: ProjectionSemanticFixture = {
    stateVersionZeroAccepted: true,
    sameVersionShared,
    differentVersionRejected,
    asOfSeqAfterTwoEvents: Number(secondCut.asOfSeq),
    valueAfterTwoEvents: secondCut.values['777/reference'] ?? -1,
    survivesFirstDispose,
    removedAfterLastDispose,
  }

  process.stdout.write(JSON.stringify(fixture, null, 2) + '\n')
}

await main()
