import { Context } from '@deepseek-ai/cordis'
import { z } from 'zod'
import SessionStore, { SessionId } from '@deepseek-ai/dsh-session'
import SessionProjectionRegistry from '@deepseek-ai/dsh-session-projection'

function unit(stateVersion: number, increment: number) {
  return {
    key: '777/advanced-count',
    stateSchema: z.number().int().nonnegative(),
    init: () => 0,
    apply: (state: number) => state + increment,
    stateVersion,
  }
}

async function main(): Promise<void> {
  const ctx = new Context()
  const fibers = [
    await ctx.plugin(SessionStore),
    await ctx.plugin(SessionProjectionRegistry),
  ]

  const projections = ctx.sessionProjections as any
  const firstDispose = projections.register(unit(0, 1))
  const secondDispose = projections.register(unit(0, 100))

  const session = ctx.sessions.create(SessionId('777-advanced-projection'))
  session.append('turn/start', { turn: 1 })
  session.append('turn/end', { turn: 1, reason: { kind: 'completed' } })

  const firstCut = projections.snapshot(session)
  const sameVersionShared = firstCut.values['777/advanced-count'] === 2

  let differentVersionRejected = false
  try {
    projections.register(unit(1, 1))
  } catch {
    differentVersionRejected = true
  }

  firstDispose()
  session.append('turn/start', { turn: 2 })
  const secondCut = projections.snapshot(session)
  const survivesFirstDispose =
    Object.prototype.hasOwnProperty.call(secondCut.values, '777/advanced-count') &&
    secondCut.values['777/advanced-count'] === 3

  secondDispose()
  const finalCut = projections.snapshot(session)
  const removedAfterLastDispose =
    !Object.prototype.hasOwnProperty.call(finalCut.values, '777/advanced-count')

  process.stdout.write(`${JSON.stringify({
    projection: {
      stateVersionZeroAccepted: true,
      sameVersionShared,
      differentVersionRejected,
      asOfSequence: Number(secondCut.asOfSeq),
      value: secondCut.values['777/advanced-count'] ?? -1,
      survivesFirstDispose,
      removedAfterLastDispose,
    },
  }, null, 2)}\n`)

  for (const fiber of fibers.reverse()) await fiber.dispose()
}

await main()
