import { readFileSync } from 'node:fs'
import { Context } from '@deepseek-ai/cordis'
import LlmRuntime, { createUserMessage, ToolCallId, type StreamChunk } from '@deepseek-ai/dsh-llm'
import SessionStore, { SessionId } from '@deepseek-ai/dsh-session'
import SystemPrompt from '@deepseek-ai/dsh-system-prompt'
import ToolRuntime, { defineContentToolFixture } from '@deepseek-ai/dsh-tools'
import AgentRegistry, { type Agent } from '@deepseek-ai/dsh-agent'
import AgentLoop from '@deepseek-ai/dsh-agent-loop'
import SessionProjectionRegistry from '@deepseek-ai/dsh-session-projection'
import { MockAdapter, textResponse, toolCallResponse } from './packages/core/agent-loop/tests/mock-adapter.ts'

interface ToolCallFixture {
  id: string
  name: string
  arguments: Record<string, unknown>
}

interface ModelReplyFixture {
  content?: string
  toolCalls?: ToolCallFixture[]
}

interface ConformanceVector {
  id: string
  input: string
  modelReplies: ModelReplyFixture[]
  toolOutputs?: Record<string, string>
}

interface CanonicalEvent {
  type: string
  step?: number
  callId?: string
  name?: string
  arguments?: string
  content?: string
  reason?: string
}

function toolCallsResponse(calls: ToolCallFixture[], text?: string): StreamChunk[] {
  const chunks: StreamChunk[] = []
  let index = 0
  if (text) {
    chunks.push(
      { type: 'block-start', index, blockType: 'text' },
      { type: 'text-delta', index, text },
      { type: 'block-end', index, block: { type: 'text', text } },
    )
    index += 1
  }
  for (const call of calls) {
    const id = ToolCallId(call.id)
    const argumentsJson = JSON.stringify(call.arguments)
    chunks.push(
      { type: 'block-start', index, blockType: 'tool-call' },
      { type: 'tool-call-delta', index, id, name: call.name, argumentsDelta: argumentsJson.slice(0, 5) },
      { type: 'tool-call-delta', index, id, argumentsDelta: argumentsJson.slice(5) },
      {
        type: 'block-end',
        index,
        block: { type: 'tool-call', id, name: call.name, arguments: argumentsJson },
      },
    )
    index += 1
  }
  chunks.push(
    { type: 'usage', usage: { inputTokens: 10, outputTokens: Math.max(5, calls.length * 5) } },
    { type: 'finish', reason: { kind: 'tool-calls' } },
  )
  return chunks
}

function waitForIdle(ctx: Context, agent: Agent): Promise<void> {
  return new Promise((resolve) => {
    const dispose = ctx.on('agent/status', ({ agent: subject, status }) => {
      if (subject === agent && status === 'idle') {
        dispose()
        resolve()
      }
    })
  })
}

function textContent(content: unknown): string | undefined {
  if (!Array.isArray(content)) return undefined
  const text = content
    .map((block) => {
      if (block === null || typeof block !== 'object') return ''
      const record = block as Record<string, unknown>
      if (record.type === 'text' && typeof record.text === 'string') return record.text
      if (record.type === 'tool-result') return textContent(record.content) ?? ''
      return ''
    })
    .join('')
  return text === '' ? undefined : text
}

function canonicalize(events: readonly any[]): CanonicalEvent[] {
  const result: CanonicalEvent[] = []
  for (const event of events) {
    switch (event.type) {
      case 'turn/start':
        result.push({ type: 'turn/start' })
        break
      case 'step/start':
        result.push({ type: 'step/start', step: event.data.step })
        break
      case 'assistant/message':
        result.push({
          type: 'assistant/message',
          step: event.data.step,
          content: textContent(event.data.message?.content),
        })
        break
      case 'tool/call':
        result.push({
          type: 'tool/call',
          step: event.data.step,
          callId: event.data.callId,
          name: event.data.name,
          arguments: event.data.arguments,
        })
        break
      case 'tool/result':
        result.push({
          type: 'tool/result',
          step: event.data.step,
          callId: event.data.message?.source?.callId,
          content: textContent(event.data.message?.content),
        })
        break
      case 'step/end':
        result.push({ type: 'step/end', step: event.data.step })
        break
      case 'turn/end':
        result.push({
          type: 'turn/end',
          reason: event.data.reason?.kind,
        })
        break
    }
  }
  return result
}

async function main(): Promise<void> {
  const vectorPath = process.argv[2]
  if (!vectorPath) throw new Error('usage: official-runner.ts <vector.json>')

  const vector = JSON.parse(readFileSync(vectorPath, 'utf8')) as ConformanceVector
  const script = vector.modelReplies.map((reply) => {
    const calls = reply.toolCalls ?? []
    if (calls.length > 0) {
      return toolCallsResponse(calls, reply.content)
    }
    return textResponse(reply.content ?? '')
  })

  const ctx = new Context()
  const fibers = [
    await ctx.plugin(LlmRuntime),
    await ctx.plugin(SessionStore),
    await ctx.plugin(SessionProjectionRegistry),
    await ctx.plugin(SystemPrompt),
    await ctx.plugin(ToolRuntime),
    await ctx.plugin(AgentRegistry),
    await ctx.plugin(AgentLoop, { agents: [] }),
  ]
  ctx.llm.registerAdapter(['mock'], new MockAdapter(script))

  const toolNames = new Set(
    vector.modelReplies.flatMap((reply) => (reply.toolCalls ?? []).map((call) => call.name)),
  )
  for (const name of toolNames) {
    ctx.tools.register(defineContentToolFixture({
      name,
      description: '777 differential reference tool',
      parameters: {},
      async execute() {
        const text = vector.toolOutputs?.[name]
        if (text === undefined) throw new Error(`missing tool output: ${name}`)
        return [{ type: 'text', text }]
      },
    }))
  }

  const agent = await ctx.agentLoop.create(
    SessionId(`777-reference-${vector.id}`),
    { provider: 'mock', model: 'mock' },
  )
  agent.followup(createUserMessage({
    content: [{ type: 'text', text: vector.input }],
    source: { kind: 'user' },
  }))
  await waitForIdle(ctx, agent)

  process.stdout.write(`${JSON.stringify(canonicalize(agent.session.snapshotEvents()), null, 2)}\n`)
  for (const fiber of fibers.reverse()) await fiber.dispose()
}

await main()
