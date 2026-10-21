import { test } from 'node:test'
import assert from 'node:assert/strict'
import { slimSessionSummary, slimSessionsList } from '../src/server.js'

/** Fat fixture shaped like a real dsh sessionController.list item. */
function fatSession(i) {
  return {
    sessionId: `session-${i}`,
    updatedAt: 1_700_000_000_000 + i,
    running: i % 5 === 0,
    blank: false,
    cwd: `/Users/me/projects/repo-${i % 7}`,
    parentSessionId: i % 11 === 0 ? `session-${i - 1}` : null,
    origin: i % 11 === 0 ? 'subagent' : undefined,
    projections: {
      values: {
        title: `Session title ${i}`,
        agentPreset: 'standard',
        tokenUsage: { input: 12_000, output: 4_000, cacheRead: 90_000, cacheWrite: 1_000 },
        turnOutline: Array.from({ length: 20 }, (_, j) => ({
          kind: 'tool',
          name: `tool-${j}`,
          detail: 'x'.repeat(200),
        })),
        contextBreakdown: {
          system: 'y'.repeat(500),
          history: 'z'.repeat(2000),
          tools: Array.from({ length: 30 }, () => ({ name: 'bash', schema: '{...}'.repeat(40) })),
        },
      },
    },
    model: { provider: 'deepseek', id: 'deepseek-v4.1-flash' },
  }
}

test('session summaries retain header metadata while dropping bulk projections', () => {
  const slim = slimSessionSummary(fatSession(3))
  assert.equal(slim.sessionId, 'session-3')
  assert.equal(slim.projections.values.title, 'Session title 3')
  assert.equal(slim.projections.values.agentPreset, 'standard')
  assert.equal(slim.cwd, '/Users/me/projects/repo-3')
  assert.equal(slim.running, false)
  assert.equal(slim.blank, false)
  assert.ok(slim.updatedAt > 0)
  assert.equal(slim.parentSessionId, null)
  assert.equal(slim.origin, undefined)
  assert.equal(slim.projections.values.tokenUsage, undefined)
  assert.equal(slim.projections.values.turnOutline, undefined)
  assert.equal(slim.projections.values.contextBreakdown, undefined)
  assert.equal(slim.agentPreset, undefined)
  assert.equal(slim.model, undefined)
})

test('untitled and missing-preset sessions do not invent header context', () => {
  const untitled = slimSessionSummary({
    sessionId: 'untitled',
    projections: { values: { title: null, agentPreset: 'custom-review' } },
  })
  assert.deepEqual(untitled.projections.values, { agentPreset: 'custom-review' })
  const absent = slimSessionSummary({ sessionId: 'unknown' })
  assert.equal(absent.projections, undefined)
  const cleared = slimSessionSummary({
    sessionId: 'no-preset',
    agentPreset: 'not-authoritative',
    projections: { values: { agentPreset: null } },
  })
  assert.deepEqual(cleared.projections.values, { agentPreset: null })
})

test('slimSessionsList shrinks a 33-session fixture well under 64 KiB', () => {
  const fat = { items: Array.from({ length: 33 }, (_, i) => fatSession(i)), nextCursor: null }
  const fatBytes = Buffer.byteLength(JSON.stringify(fat), 'utf8')
  assert.ok(fatBytes > 64 * 1024, `fixture should be >64KiB before slim (got ${fatBytes})`)

  const slim = slimSessionsList(fat)
  const slimBytes = Buffer.byteLength(JSON.stringify(slim), 'utf8')
  assert.ok(slimBytes < 16 * 1024, `slim list should be <<64KiB (got ${slimBytes})`)
  assert.equal(slim.items.length, 33)
  assert.equal(slim.items[0].projections.values.title, 'Session title 0')
  // Include the RPC envelope seen by the phone.
  const wire = JSON.stringify({ id: 2, ok: true, result: slim })
  assert.ok(Buffer.byteLength(wire, 'utf8') < 64 * 1024)
})

