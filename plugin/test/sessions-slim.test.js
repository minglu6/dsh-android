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
    agentPreset: 'standard',
    model: { provider: 'deepseek', id: 'deepseek-v4.1-flash' },
  }
}

test('slimSessionSummary keeps SessionSummary.parse fields only', () => {
  const slim = slimSessionSummary(fatSession(3))
  assert.equal(slim.sessionId, 'session-3')
  assert.equal(slim.projections.values.title, 'Session title 3')
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

test('slimSessionsList shrinks a 33-session fixture well under 64 KiB', () => {
  const fat = { items: Array.from({ length: 33 }, (_, i) => fatSession(i)), nextCursor: null }
  const fatBytes = Buffer.byteLength(JSON.stringify(fat), 'utf8')
  assert.ok(fatBytes > 64 * 1024, `fixture should be >64KiB before slim (got ${fatBytes})`)

  const slim = slimSessionsList(fat)
  const slimBytes = Buffer.byteLength(JSON.stringify(slim), 'utf8')
  assert.ok(slimBytes < 16 * 1024, `slim list should be <<64KiB (got ${slimBytes})`)
  assert.equal(slim.items.length, 33)
  assert.equal(slim.items[0].projections.values.title, 'Session title 0')
  // Envelope as the phone would see it over iroh (rpc reply wrapper).
  const wire = JSON.stringify({ id: 2, ok: true, result: slim })
  assert.ok(Buffer.byteLength(wire, 'utf8') < 64 * 1024)
})

test('documented iroh MAX_LINE is at least 1 MiB (keep in sync with pocket-iroh)', async () => {
  // Mirror the Rust constant so a JS-only CI job still catches a doc drift if someone
  // lowers the comment without rebuilding. The authoritative value is in
  // plugin/iroh/pocket-iroh/src/lib.rs (MAX_LINE = 1024 * 1024).
  const EXPECTED_MIN = 1024 * 1024
  const { readFileSync } = await import('node:fs')
  const { join, dirname } = await import('node:path')
  const { fileURLToPath } = await import('node:url')
  const lib = readFileSync(
    join(dirname(fileURLToPath(import.meta.url)), '../iroh/pocket-iroh/src/lib.rs'),
    'utf8',
  )
  const m = lib.match(/pub const MAX_LINE: usize = ([^;]+);/)
  assert.ok(m, 'MAX_LINE declaration missing')
  const expr = m[1].replace(/\s/g, '')
  assert.ok(
    expr === '1024*1024' || expr === `${EXPECTED_MIN}`,
    `MAX_LINE should be ≥1MiB, got ${expr}`,
  )
})
