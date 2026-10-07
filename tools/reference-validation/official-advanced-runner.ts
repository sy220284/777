import { Context } from '@deepseek-ai/cordis'
import SessionStore, { SessionId } from '@deepseek-ai/dsh-session'
import SessionProjectionRegistry from '@deepseek-ai/dsh-session-projection'
import { teamProjectionDefinition } from './packages/experimental/agent-team/src/projection.ts'

async function main(): Promise<void> {
  const ctx = new Context()
  const fibers = [
    await ctx.plugin(SessionStore),
    await ctx.plugin(SessionProjectionRegistry),
  ]

  const projections = ctx.sessionProjections as any
  const teamDispose = projections.register(teamProjectionDefinition)

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

  const teamSession = ctx.sessions.create(SessionId('777-agent-team'))
  const teamId = '777-agent-team'
  const memberId = 'member-worker'
  teamSession.append('team/member', {
    version: 2,
    teamId,
    member: {
      id: memberId,
      name: 'worker',
      description: 'research',
      provider: 'local-subagent',
      context: 'fresh',
      phase: 'provisioning',
    },
  })
  teamSession.append('team/member', {
    version: 2,
    teamId,
    member: {
      id: memberId,
      name: 'worker',
      description: 'research',
      provider: 'local-subagent',
      context: 'fresh',
      phase: 'active',
    },
  })
  teamSession.append('team/task', {
    version: 2,
    teamId,
    task: {
      id: 'task-1',
      revision: 1,
      subject: 'first',
      description: '',
      status: 'pending',
      blockedBy: [],
      writeScopes: ['src'],
    },
  })
  teamSession.append('team/task', {
    version: 2,
    teamId,
    task: {
      id: 'task-2',
      revision: 1,
      subject: 'second',
      description: '',
      status: 'pending',
      blockedBy: ['task-1'],
      writeScopes: ['src/feature'],
    },
  })
  teamSession.append('team/message/queued', {
    version: 2,
    teamId,
    message: {
      id: 'team-msg-1',
      senderId: teamId,
      senderName: 'lead',
      targetId: memberId,
      content: [{ type: 'text', text: 'continue' }],
    },
  })
  teamSession.append('team/message/delivered', {
    version: 2,
    teamId,
    messageId: 'team-msg-1',
    targetId: memberId,
  })
  const teamRow = projections.checkpoint(teamSession).agentTeam
  if (teamRow === undefined) throw new Error('agentTeam projection checkpoint missing')
  const teamState = teamRow.val as any
  const teamView = teamProjectionDefinition.wire.view(teamState) as any

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
    agentTeam: {
      stateVersion: teamRow.ver,
      asOfSequence: Number(teamRow.seq),
      members: teamState.members,
      tasks: teamState.tasks.filter((task: any) => task.status !== 'deleted'),
      pendingMessageIds: teamState.messages
        .filter((message: any) => !teamState.delivered.includes(message.id))
        .map((message: any) => message.id),
      failure: teamView.failure ?? null,
    },
  }, null, 2)}\n`)

  teamDispose()
  for (const fiber of fibers.reverse()) await fiber.dispose()
}

await main()
