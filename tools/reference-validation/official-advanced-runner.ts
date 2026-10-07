import { Context } from '@deepseek-ai/cordis'
import { z } from 'zod'
import SessionStore, { SessionId } from '@deepseek-ai/dsh-session'
import SessionProjectionRegistry from '@deepseek-ai/dsh-session-projection'

async function main(): Promise<void> {
  const ctx = new Context()
  const fibers = [
    await ctx.plugin(SessionStore),
    await ctx.plugin(SessionProjectionRegistry),
  ]

  const projections = ctx.sessionProjections as any
  const disposeFirst = projections.register({
    key: '777/advanced-count',
    stateSchema: z.number().int().nonnegative(),
    init: () => 0,
    apply: (state: number) => state + 1,
    stateVersion: 0,
  })
  const disposeSecond = projections.register({
    key: '777/advanced-count',
    stateSchema: z.number().int().nonnegative(),
    init: () => 999,
    apply: (state: number) => state + 999,
    stateVersion: 0,
  })

  let differentVersionRejected = false
  try {
    projections.register({
      key: '777/advanced-count',
      stateSchema: z.number().int().nonnegative(),
      init: () => 0,
      apply: (state: number) => state,
      stateVersion: 1,
    })
  } catch {
    differentVersionRejected = true
  }

  const session = ctx.sessions.create(SessionId('777-advanced-projection'))
  session.append('turn/start', { turn: 1 })
  session.append('turn/end', { turn: 1, reason: { kind: 'completed' } })

  const row = projections.checkpoint(session)['777/advanced-count']
  if (row === undefined) throw new Error('advanced projection checkpoint missing')
  const sameVersionShared = row.val === 2 && row.ver === 0

  disposeFirst()
  const survivesFirstDispose =
    projections.checkpoint(session)['777/advanced-count'] !== undefined
  disposeSecond()
  const removedAfterLastDispose =
    projections.checkpoint(session)['777/advanced-count'] === undefined

  process.stdout.write(`${JSON.stringify({
    projection: {
      stateVersion: row.ver,
      asOfSequence: Number(row.seq),
      value: row.val,
      sameVersionShared,
      differentVersionRejected,
      survivesFirstDispose,
      removedAfterLastDispose,
    },
  }, null, 2)}\n`)

  for (const fiber of fibers.reverse()) await fiber.dispose()
}

await main()
