import { Context } from '@deepseek-ai/cordis'
import SessionStore, { SessionId } from '@deepseek-ai/dsh-session'
import SessionProjectionRegistry from '@deepseek-ai/dsh-session-projection'

async function main(): Promise<void> {
  const ctx = new Context()
  const fibers = [
    await ctx.plugin(SessionStore),
    await ctx.plugin(SessionProjectionRegistry),
  ]

  const projections = ctx.sessionProjections as any
  const firstDispose = projections.register({
    key: '777/advanced-count',
    stateSchema: { parse: (value: unknown) => value },
    init: () => 0,
    apply: (state: number) => state + 1,
    stateVersion: 0,
  })
  const secondDispose = projections.register({
    key: '777/advanced-count',
    stateSchema: { parse: (value: unknown) => value },
    init: () => 100,
    apply: (state: number) => state + 100,
    stateVersion: 0,
  })

  let differentVersionRejected = false
  try {
    projections.register({
      key: '777/advanced-count',
      stateSchema: { parse: (value: unknown) => value },
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

  const sharedRow = projections.checkpoint(session)['777/advanced-count']
  if (sharedRow === undefined) throw new Error('advanced projection checkpoint missing')

  firstDispose()
  const survivesFirstDispose = projections.checkpoint(session)['777/advanced-count'] !== undefined
  secondDispose()
  const removedAfterLastDispose = projections.checkpoint(session)['777/advanced-count'] === undefined

  process.stdout.write(`${JSON.stringify({
    projection: {
      stateVersion: sharedRow.ver,
      asOfSequence: Number(sharedRow.seq),
      value: sharedRow.val,
      sameVersionSharedFirstDefinition: sharedRow.val === 2,
      differentVersionRejected,
      survivesFirstDispose,
      removedAfterLastDispose,
    },
  }, null, 2)}\n`)

  for (const fiber of fibers.reverse()) await fiber.dispose()
}

await main()
