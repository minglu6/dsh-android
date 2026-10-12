const str = (v) => typeof v === 'string' && v.length > 0
const bad = (m) => Object.assign(new Error(m), { code: 'bad-request' })

/** Session object of a live (or auto-resumed) Agent. */
function sessionOf(agents, sessionId) {
  if (!str(sessionId)) throw bad('sessionId required')
  const agent = agents.get(sessionId)
  if (!agent) throw Object.assign(new Error('session has no live agent'), { code: 'session/not-found' })
  return agent.session
}

/** permissions.* over the in-process permissionPresets service. */
export function permissionOps(presets, agents) {
  return {
    'permissions.catalog': () => presets.catalog(),
    'permissions.get': (a) => ({ current: presets.current(sessionOf(agents, a.sessionId)) }),
    'permissions.set': (a) => {
      if (!str(a.name)) throw bad('name required')
      const session = sessionOf(agents, a.sessionId)
      presets.set(session, a.name)
      return { current: presets.current(session) }
    },
  }
}

/** presets.list over the agentPresets registry (same roster the web UI uses). */
export function presetOps(agentPresets) {
  return { 'presets.list': () => agentPresets.remoteExportList() }
}
