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
  projections.register({
    key: '777/advanced-count',
    stateSchema: z.number().int().nonnegative(),
    init: () => 0,
    apply: (state: number) => state + 1,
    stateVersion: 7,
  })

  let duplicateKeyRejected = false
  try {
    projections.register({
      key: '777/advanced-count',
      stateSchema: z.number().int().nonnegative(),
      init: () => 0,
      apply: (state: number) => state,
      stateVersion: 7,
    })
  } catch {
    duplicateKeyRejected = true
  }

  const session = ctx.sessions.create(SessionId('777-advanced-projection'))
  session.append('turn/start', { turn: 1 })
  session.append('turn/end', { turn: 1, reason: { kind: 'completed' } })

  const row = projections.checkpoint(session)['777/advanced-count']
  if (row === undefined) throw new Error('advanced projection checkpoint missing')

  process.stdout.write(`${JSON.stringify({
    projection: {
      stateVersion: row.ver,
      asOfSequence: Number(row.seq),
      value: row.val,
      duplicateKeyRejected,
    },
  }, null, 2)}\n`)

  for (const fiber of fibers.reverse()) await fiber.dispose()
}

await main()
