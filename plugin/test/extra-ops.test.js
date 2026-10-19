import { test } from 'node:test'
import assert from 'node:assert/strict'
import { zipSync, strToU8, unzipSync, strFromU8 } from 'fflate'
import { Context, Service } from '@deepseek-ai/cordis'
import { commandOps, skillOps, filesOps, jobsOps, exportOps, terminalOps } from '../src/extra-ops.js'

test('commands.execute prefixes slash and returns Host result', async () => {
  const calls = []
  const ops = commandOps({
    execute: async (agent, line, attachments) => {
      calls.push({ agent, line, attachments })
      return { commandId: 'c1', result: { kind: 'success', text: 'ok' } }
    },
  }, { get: (id) => id === 's1' ? { session: {} } : undefined })
  const r = await ops['commands.execute']({ sessionId: 's1', line: 'compact' }, AbortSignal.timeout(1000))
  assert.deepEqual(calls[0].line, '/compact')
  assert.equal(r.result.kind, 'success')
  await assert.rejects(() => ops['commands.execute']({ sessionId: 'missing', line: '/plan' }), /no live agent/)
})

test('commands catalog is scoped to the requested live agent', async () => {
  const ops = commandOps({
    list: (agent) => agent.id === 'standard'
      ? [{ name: 'goal', definitionId: '@deepseek-ai/dsh-command-goal', description: 'Objective', input: { hint: 'text' } }]
      : [],
  }, { get: (id) => ['standard', 'minimal'].includes(id) ? { id } : undefined })
  assert.equal((await ops['commands.list']({ sessionId: 'minimal' })).commands.length, 0)
  assert.equal((await ops['commands.list']({ sessionId: 'standard' })).commands[0].input.hint, 'text')
  await assert.rejects(() => ops['commands.list']({ sessionId: 'gone' }), /no live agent/)
})

test('commands.list without sessionId uses the default preset standing scope', async () => {
  const listed = []
  const released = []
  const catalog = [{ name: 'compact', description: 'Compact' }]
  const ops = commandOps({
    list: (scope) => {
      listed.push(scope)
      return catalog
    },
  }, { get: () => undefined }, {
    acquireScope: async (id) => {
      assert.equal(id, undefined)
      return {
        key: { agentPreset: 'standard' },
        async [Symbol.asyncDispose]() { released.push('acquireScope') },
      }
    },
  })
  assert.deepEqual(await ops['commands.list']({}), { commands: catalog })
  assert.deepEqual(listed, [{ agentPreset: 'standard' }])
  assert.deepEqual(released, ['acquireScope'])
})

test('commands.list without sessionId keeps a given sessionId on the live-agent path', async () => {
  const listed = []
  const ops = commandOps({
    list: (agent) => {
      listed.push(agent)
      return [{ name: 'plan', description: 'Plan' }]
    },
  }, { get: (id) => id === 's1' ? { id: 's1' } : undefined }, {
    acquireScope: async () => {
      throw new Error('default profile must not be used when sessionId is given')
    },
  })
  assert.equal((await ops['commands.list']({ sessionId: 's1' })).commands[0].name, 'plan')
  assert.deepEqual(listed, [{ id: 's1' }])
})

test('commands.list without sessionId throws when the default profile cannot be resolved', async () => {
  const ops = commandOps({ list: () => [] }, { get: () => undefined })
  await assert.rejects(
    () => ops['commands.list']({}),
    (err) => err.code === 'unavailable' && /default agent profile/.test(err.message),
  )
})

test('skills.list forwards Host sessionSkillCatalog and returns { skills }', async () => {
  const catalog = [
    { name: 'commit-helper', description: 'Git commits', modelInvocable: true },
    { name: 'manual-review', description: 'Ask a person', whenToUse: 'after a patch', modelInvocable: false },
  ]
  const calls = []
  const ops = skillOps({
    list: async (request, signal) => {
      calls.push({ request, aborted: signal?.aborted })
      if (request.sessionId === 'gone') {
        throw Object.assign(new Error('session "gone" not found'), { code: 'session/not-found' })
      }
      return { skills: request.sessionId === 'empty' ? [] : catalog }
    },
  }, {
    skills: { list: async () => { throw new Error('default catalog must not be used when sessionId is given') } },
    workingDirectory: { defaultDirectory: '/unused' },
    agentPresets: { acquireScope: async () => { throw new Error('default profile must not be used when sessionId is given') } },
  })
  const signal = AbortSignal.timeout(1000)
  assert.deepEqual(await ops['skills.list']({ sessionId: 's1' }, signal), { skills: catalog })
  assert.deepEqual(calls[0].request, { sessionId: 's1' })
  assert.equal(calls[0].aborted, false)
  assert.deepEqual(await ops['skills.list']({ sessionId: 'empty' }, signal), { skills: [] })
  await assert.rejects(
    () => ops['skills.list']({ sessionId: 'gone' }, signal),
    (err) => err.code === 'session/not-found',
  )
})

test('skills.list without sessionId uses default workspace cwd and default preset scope', async () => {
  const signal = AbortSignal.timeout(1000)
  const listed = []
  const released = []
  const ops = skillOps({
    list: async () => { throw new Error('sessionSkillCatalog must not be used without sessionId') },
  }, {
    workingDirectory: { defaultDirectory: '/default/ws' },
    agentPresets: {
      acquireScope: async (id) => {
        assert.equal(id, undefined)
        return {
          key: { agentPreset: 'standard' },
          async [Symbol.asyncDispose]() { released.push('acquireScope') },
        }
      },
    },
    skills: {
      list: async (options) => {
        listed.push(options)
        return [
          {
            name: 'commit-helper', description: 'Git commits', path: '/skills/commit.md',
            whenToUse: 'after edits', invocation: { modelInvocable: true, userInvocable: true },
          },
          {
            name: 'hidden', description: 'model only',
            invocation: { modelInvocable: true, userInvocable: false },
          },
        ]
      },
    },
  })
  assert.deepEqual(await ops['skills.list']({}, signal), {
    skills: [{
      name: 'commit-helper', path: '/skills/commit.md', description: 'Git commits',
      whenToUse: 'after edits', modelInvocable: true,
    }],
  })
  assert.equal(listed.length, 1)
  assert.equal(listed[0].cwd, '/default/ws')
  assert.deepEqual(listed[0].scope, { agentPreset: 'standard' })
  assert.equal(listed[0].signal, signal)
  assert.deepEqual(released, ['acquireScope'])
})

test('commands.list without sessionId accepts standingKeyFor when acquireScope is absent', async () => {
  const listed = []
  const ops = commandOps({
    list: (scope) => {
      listed.push(scope)
      return [{ name: 'goal', description: 'Goal' }]
    },
  }, { get: () => undefined }, {
    standingKeyFor: async (id) => {
      assert.equal(id, undefined)
      return { agentPreset: 'standard' }
    },
  })
  assert.equal((await ops['commands.list']({})).commands[0].name, 'goal')
  assert.deepEqual(listed, [{ agentPreset: 'standard' }])
})

test('skills.list without sessionId throws when the default workspace is missing', async () => {
  const ops = skillOps({
    list: async () => { throw new Error('sessionSkillCatalog must not be used without sessionId') },
  }, {
    workingDirectory: {},
    agentPresets: { acquireScope: async () => ({ key: { agentPreset: 'standard' } }) },
    skills: { list: async () => [] },
  })
  await assert.rejects(
    () => ops['skills.list']({}),
    (err) => err.code === 'unavailable' && /default workspace/.test(err.message),
  )
})

test('files.list builds scope from session header cwd', async () => {
  const ops = filesOps({
    list: async (scope, path) => ({ path, scope, entries: [{ name: 'a.ts', type: 'file' }] }),
    read: async () => ({ text: 'hi' }),
    readBytes: async () => ({ absolutePath: '/tmp/ws/a.png', version: 'v1', bytes: 3, offset: 0, eof: true, data: Uint8Array.from([1, 2, 3]) }),
  }, { get: (id) => id === 's1' ? { header: { cwd: '/tmp/ws' } } : undefined })
  const listed = await ops['files.list']({ sessionId: 's1', path: 'src' }, AbortSignal.timeout(1000))
  assert.equal(listed.scope.workspaceRoot, '/tmp/ws')
  assert.equal(listed.path, 'src')
  assert.equal(listed.entries[0].name, 'a.ts')
})

test('files.readBytes base64-encodes host Uint8Array for JSON transport', async () => {
  const ops = filesOps({
    list: async () => ({ path: '.', entries: [] }),
    read: async () => ({ text: '' }),
    readBytes: async (scope, path) => ({
      absolutePath: `${scope.workspaceRoot}/${path}`,
      version: 'v1',
      bytes: 4,
      offset: 0,
      eof: true,
      data: Uint8Array.from([0x89, 0x50, 0x4e, 0x47]),
    }),
  }, { get: (id) => id === 's1' ? { header: { cwd: '/tmp/ws' } } : undefined })
  const r = await ops['files.readBytes']({ sessionId: 's1', path: 'a.png' }, AbortSignal.timeout(1000))
  assert.equal(r.encoding, 'base64')
  assert.equal(r.eof, true)
  assert.equal(Buffer.from(r.data, 'base64').equals(Buffer.from([0x89, 0x50, 0x4e, 0x47])), true)
})

test('jobs.list returns first rows frame then stops', async () => {
  const ops = jobsOps({
    list: async function* () {
      yield { type: 'rows', jobs: [{ id: 'j1', status: 'running' }] }
      yield { type: 'rows', jobs: [{ id: 'j1', status: 'done' }] }
    },
    kill: ({ sessionId, jobId }) => ({ outcome: 'requested', sessionId, jobId }),
  })
  const r = await ops['jobs.list']({ sessionId: 's1' }, AbortSignal.timeout(1000))
  assert.equal(r.jobs.length, 1)
  assert.equal(r.jobs[0].id, 'j1')
  const k = ops['jobs.kill']({ sessionId: 's1', jobId: 'j1' })
  assert.equal(k.outcome, 'requested')
})

test('jobs.list empty rows frame is an empty roster, not unavailable', async () => {
  const ops = jobsOps({
    list: async function* () { yield { type: 'rows', jobs: [] } },
  })
  const r = await ops['jobs.list']({ sessionId: 's1' })
  assert.deepEqual(r.jobs, [])
})

test('jobs.list abort and timeout throw instead of an empty roster', async () => {
  const hang = async function* (_req, signal) {
    yield { type: 'heartbeat' }
    await new Promise((_, reject) => {
      const fail = () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))
      if (signal.aborted) fail()
      else signal.addEventListener('abort', fail, { once: true })
    })
  }
  const ops = jobsOps({ list: hang })
  await assert.rejects(
    ops['jobs.list']({ sessionId: 's1' }, AbortSignal.abort()),
    (err) => {
      assert.equal(err.code, 'jobs/unavailable')
      assert.match(err.message, /timed out or was aborted/)
      return true
    },
  )
  const ac = new AbortController()
  const timer = setTimeout(() => ac.abort(), 20)
  try {
    await assert.rejects(
      ops['jobs.list']({ sessionId: 's1' }, ac.signal),
      (err) => err.code === 'jobs/unavailable',
    )
  } finally {
    clearTimeout(timer)
  }
})

test('jobs.list throws when the Host stream ends without a rows frame', async () => {
  const ops = jobsOps({
    list: async function* () { yield { type: 'heartbeat' } },
  })
  await assert.rejects(
    ops['jobs.list']({ sessionId: 's1' }),
    (err) => err.code === 'jobs/unavailable' && /without a rows frame/.test(err.message),
  )
})

test('job output subscription forwards retained offsets and cancels its source on unfollow', async () => {
  let disposed
  const closed = new Promise((resolve) => { disposed = resolve })
  let observed
  const opened = new Promise((resolve) => { observed = resolve })
  const ops = jobsOps({
    async *follow(request, signal) {
      try {
        yield { type: 'opened', job: { id: request.jobId }, from: request.from }
        await new Promise((resolve) => {
          signal.addEventListener('abort', resolve, { once: true })
          observed()
        })
      } finally { disposed() }
    },
  })
  const pushed = []
  const client = { nextSub: 1, subs: new Map(), push: (frame) => pushed.push(frame) }
  const receipt = ops['jobs.follow']({ sessionId: 's1', jobId: 'bash-1', from: 24 }, null, client)
  await opened
  assert.equal(pushed[0].frame.from, 24)
  client.subs.get(receipt.subId).abort()
  await closed
  await new Promise((resolve) => setImmediate(resolve))
  assert.equal(client.subs.has(receipt.subId), false)
  assert.equal(pushed.some((frame) => frame.ev === 'job.end'), false)
})

function waitAbort(signal) {
  return new Promise((resolve) => {
    if (signal.aborted) resolve()
    else signal.addEventListener('abort', resolve, { once: true })
  })
}

/** Desktop TerminalController surface: retain + attach, no follow/write/resize. */
function desktopTerminalController({ frames = [], onInput } = {}) {
  const retained = []
  const attached = []
  // Mirror Cordis: `invocation` is a get-only root accessor, so assigning it
  // throws in strict mode; Gateway binds it on a call-derived child Context.
  const rootCtx = {
    get invocation() { return undefined },
    extend(meta) {
      const child = Object.create(this)
      for (const key of Reflect.ownKeys(meta)) {
        Object.defineProperty(child, key, Reflect.getOwnPropertyDescriptor(meta, key))
      }
      return child
    },
    get(name) {
      if (name !== 'terminalController') return undefined
      const callCtx = this
      return new Proxy(controller, {
        get: (target, prop, receiver) => (prop === 'ctx' ? callCtx : Reflect.get(target, prop, receiver)),
      })
    },
  }
  const controller = {
    ctx: rootCtx,
    list: () => [{ id: 'term1', state: 'running' }],
    create: async (_agent, req) => ({ id: req.id, state: 'running', cols: req.cols, rows: req.rows }),
    close: async () => {},
    follow() { throw new Error('desktop TerminalController has no follow()') },
    write() { throw new Error('desktop TerminalController has no write()') },
    resize() { throw new Error('desktop TerminalController has no resize()') },
    retain: async function* (sessionId, id, signal) {
      retained.push({ sessionId, id })
      yield { type: 'retained' }
      await waitAbort(signal)
    },
    attach(agent, id, signal) {
      const uplink = this.ctx.invocation?.uplink()
      if (uplink === undefined) throw new Error('Terminal attachment requires a Remote invocation')
      attached.push({ agent, id })
      const input = uplink[Symbol.asyncIterator]()
      return (async function* () {
        for (const frame of frames) {
          signal.throwIfAborted()
          yield frame
        }
        try {
          while (!signal.aborted) {
            const next = await Promise.race([
              input.next(),
              waitAbort(signal).then(() => ({ done: true })),
            ])
            if (next.done === true) break
            const command = next.value
            onInput?.(command)
            yield { type: 'ack', sequence: command.sequence }
            if (command.type === 'write') {
              yield { type: 'output', sequence: command.sequence + 100, data: command.data }
            }
          }
        } finally {
          await input.return?.()
        }
      })()
    },
  }
  return { controller, retained, attached }
}

test('terminal.create/write/follow pumps frames then unfollow aborts', async () => {
  const frames = [
    { type: 'snapshot', sequence: 1, screen: '$ ', info: { id: 'term1', state: 'running' }, writable: true },
    { type: 'output', sequence: 2, data: 'hi\n' },
  ]
  const inputs = []
  const { controller, retained, attached } = desktopTerminalController({
    frames,
    onInput: (command) => inputs.push(command),
  })
  const ops = terminalOps(controller, { get: (id) => id === 's1' ? { session: {} } : undefined })

  assert.equal((await ops['terminal.list']({ sessionId: 's1' })).terminals[0].id, 'term1')
  assert.equal((await ops['terminal.list']({ sessionId: 's1' })).platform, process.platform)
  const created = await ops['terminal.create']({ sessionId: 's1', cols: 40, rows: 12 }, AbortSignal.timeout(1000))
  assert.equal(created.terminal.state, 'running')
  assert.equal(created.platform, process.platform)

  const pushed = []
  const client = {
    nextSub: 1,
    subs: new Map(),
    push: (m) => pushed.push(m),
  }
  const sub = await ops['terminal.follow']({ sessionId: 's1', id: created.terminal.id }, null, client)
  assert.ok(sub.subId.startsWith('t'))
  assert.ok(sub.attachmentId)
  await new Promise((r) => setTimeout(r, 40))
  assert.deepEqual(retained, [{ sessionId: 's1', id: created.terminal.id }])
  assert.equal(attached.length, 1)
  assert.equal(attached[0].id, created.terminal.id)
  assert.ok(pushed.some((m) => m.ev === 'terminal' && m.frame?.type === 'snapshot'))
  assert.ok(pushed.some((m) => m.ev === 'terminal' && m.frame?.type === 'output'))
  assert.equal(pushed.some((m) => m.frame?.type === 'ack'), false)

  await ops['terminal.write']({ sessionId: 's1', id: created.terminal.id, attachmentId: sub.attachmentId, data: 'ls\r' })
  assert.deepEqual(inputs[0], { type: 'write', sequence: 1, data: 'ls\r' })
  await new Promise((r) => setTimeout(r, 20))
  assert.ok(pushed.some((m) => m.ev === 'terminal' && m.frame?.type === 'output' && m.frame.data === 'ls\r'))

  await ops['terminal.resize']({ sessionId: 's1', id: created.terminal.id, attachmentId: sub.attachmentId, cols: 40, rows: 12 })
  assert.deepEqual(inputs[1], { type: 'resize', sequence: 2, cols: 40, rows: 12 })

  client.subs.get(sub.subId).abort()
  await new Promise((r) => setTimeout(r, 20))
  assert.equal(client.subs.has(sub.subId), false)
})

test('terminal.follow uses attach/retain and never calls Host follow()', async () => {
  const { controller, retained, attached } = desktopTerminalController({
    frames: [{ type: 'snapshot', sequence: 1, screen: '', info: { id: 'term1', state: 'running' }, writable: true }],
  })
  const ops = terminalOps(controller, { get: (id) => id === 's1' ? { session: {} } : undefined })
  const client = { nextSub: 1, subs: new Map(), push: () => {} }
  const sub = await ops['terminal.follow']({ sessionId: 's1', id: 'term1' }, null, client)
  await new Promise((r) => setTimeout(r, 20))
  assert.deepEqual(retained, [{ sessionId: 's1', id: 'term1' }])
  assert.equal(attached.length, 1)
  client.subs.get(sub.subId).abort()
})

test('terminal.write without an attach throws instead of calling Host write()', async () => {
  const { controller } = desktopTerminalController()
  const ops = terminalOps(controller, { get: (id) => id === 's1' ? { session: {} } : undefined })
  await assert.rejects(
    () => ops['terminal.write']({ sessionId: 's1', id: 'term1', attachmentId: 'missing', data: 'x' }),
    (err) => err.code === 'terminal/unavailable' && /not attached/.test(err.message),
  )
})

test('terminal roster keeps owner scope and retained metadata without requiring a live agent', () => {
  const retained = { id: 'same-id', title: 'Retained shell', cwd: '/prior-workspace', state: 'exited' }
  const queried = []
  const ops = terminalOps({
    list: (sessionId) => {
      queried.push(sessionId)
      return sessionId === 'prior' ? [retained] : []
    },
  }, { get: () => undefined })
  assert.deepEqual(ops['terminal.list']({ sessionId: 'current' }).terminals, [])
  assert.deepEqual(ops['terminal.list']({ sessionId: 'prior' }), {
    terminals: [retained], platform: process.platform,
  })
  assert.deepEqual(queried, ['current', 'prior'])
})

test('session.export pages history into a zip of jsonl', async () => {
  const pages = [
    { records: [{ type: 'event', event: { type: 'user/message', seq: 2 } }, { type: 'event', event: { type: 'assistant/message', seq: 3 } }], hasMore: false },
  ]
  const ops = exportOps({
    page: async () => pages.shift(),
  }, { zipSync, strToU8 })
  const r = await ops['session.export']({ sessionId: 'abc' }, AbortSignal.timeout(1000))
  assert.match(r.filename, /^dsh-session-abc\.zip$/)
  assert.equal(r.mediaType, 'application/zip')
  const files = unzipSync(Buffer.from(r.data, 'base64'))
  const text = strFromU8(files['abc.jsonl'])
  assert.match(text, /user\/message/)
  assert.match(text, /assistant\/message/)
})

test('terminal.follow binds invocation on a call-derived Cordis Context instead of assigning ctx.invocation', async () => {
  const root = new Context()
  // typert-protocol provideInvocationAccessor: get-only, so `ctx.invocation = x` throws.
  root.accessor('invocation', { get: () => undefined })
  const seen = []
  class TerminalController extends Service {
    constructor(ctx) { super(ctx, 'terminalController') }
    list() { return [] }
    async *retain() { yield { type: 'retained' }; await new Promise(() => {}) }
    attach(_agent, id, signal) {
      const invocation = this.ctx.invocation
      if (invocation === undefined) throw new Error('Terminal attachment requires a Remote invocation')
      seen.push({ id, uplink: invocation.uplink() !== undefined })
      return (async function* () {
        yield { type: 'ready', sequence: 0, writable: true }
        await new Promise((resolve) => signal.addEventListener('abort', resolve, { once: true }))
      })()
    }
  }
  root.plugin(TerminalController)
  await new Promise((resolve) => setTimeout(resolve, 20))
  const controller = await new Promise((resolve) => {
    root.inject(['terminalController'], (c) => resolve(c.terminalController))
  })
  assert.throws(() => { controller.ctx.invocation = {} }, /trap returned falsish for property 'invocation'/)
  const ops = terminalOps(controller, { get: (id) => (id === 's1' ? { session: {} } : undefined) })
  const pushed = []
  const client = { subs: new Map(), nextSub: 1, push: (frame) => pushed.push(frame) }
  const receipt = await ops['terminal.follow']({ sessionId: 's1', id: 'term1' }, AbortSignal.timeout(5000), client)
  await new Promise((resolve) => setTimeout(resolve, 20))
  assert.equal(JSON.stringify(pushed).includes('invocation'), false, JSON.stringify(pushed))
  assert.ok(pushed.some((frame) => frame.ev === 'terminal' && frame.frame?.type === 'ready'), JSON.stringify(pushed))
  assert.deepEqual(seen, [{ id: 'term1', uplink: true }])
  assert.equal(controller.ctx.invocation, undefined)
  client.subs.get(receipt.subId)?.abort()
})
