import { homedir } from 'node:os'
import { dirname, join } from 'node:path'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createApprovalHook } from './src/approval-hook.js'
import { createUserQuestionHook } from './src/user-question-hook.js'
import { createDeviceStore } from './src/devices.js'
import { createPairing } from './src/pairing.js'
import { createPocketServer } from './src/server.js'
import { TaskEventHub, watchTaskCompletions } from './src/task-events.js'
import { TaskFallbackTracker } from './src/task-fallback.js'
import { loadOrCreateToken } from './src/token.js'
import { zipSync, strToU8 } from 'fflate'
import { permissionOps, presetOps, commandOps, filesOps, jobsOps, exportOps, terminalOps } from './src/extra-ops.js'
import {
  searchOps, workspaceOps, feedbackOps, fileReferenceOps, sessionReferenceOps,
  attachmentOps, workspaceChangesOps, uploadOps, userQuestionOps,
} from './src/parity-ops.js'
import {
  modelsSettingsOps, pluginsOps, pluginInventoryOps, scheduleOps, credentialsOps,
} from './src/settings-ops.js'
import { registerPairRoute } from './src/web-route.js'
import { createManageApi, pairingUri, pairingString, connectionTypeForHost } from './src/manage.js'
import { startIrohSidecar } from './src/iroh.js'
import { loadTransport, saveTransport, normalizeTransport } from './src/transport-pref.js'
import { pickAdvertiseHost, pickBindHost } from './src/advertise-host.js'
import { loadOrCreateInstanceId } from './src/instance-id.js'
import { startMdnsAdvertise } from './src/mdns-advertise.js'
import { collectConnectionDiagnostics } from './src/diagnostics.js'

export const name = 'dsh-plugin-pocket'
// webServer is optional: Cordis `inject` entries are all required, so the
// loopback pairing route is mounted through a nested ctx.inject(['webServer'])
// that only runs when (and while) a webServer service exists.
export const inject = ['sessionController']

const SESSION_EVENTS = ['added', 'removed', 'status', 'activity', 'error']
const PKG = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'package.json'), 'utf8'))

/**
 * @param {import('@deepseek-ai/cordis').Context} ctx
 * @param {{host?: string, port?: number, token?: string, dataDir?: string, pair?: boolean, iroh?: boolean, hostBinary?: string, transport?: string}} [config]
 */
export function apply(ctx, config = {}) {
  const log = (...a) => console.log('[pocket]', ...a)
  const dataDir = config.dataDir ?? join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'pocket')
  const { token, created, path } = loadOrCreateToken(dataDir, config.token)
  const devices = createDeviceStore({ dataDir, ownerToken: token })
  const pairing = createPairing()
  const listeners = new Set()

  // Phone path is host:port WS only (Tailscale or LAN). Config may lock transport=tailscale.
  let remoteTransport = loadTransport(dataDir, config.transport)
  const configLocksTransport = Boolean(normalizeTransport(config.transport))

  const port = config.port ?? 7690
  const advertiseHost = pickAdvertiseHost(config.host)
  const bindHost = pickBindHost(config.host)
  const instanceId = loadOrCreateInstanceId(dataDir)
  /** @type {{ stop: () => Promise<void>, deviceName: string } | null} */
  let mdns = null
  const buildDiagnostics = () => collectConnectionDiagnostics({
    version: PKG.version,
    plugin: PKG.name,
    transport: remoteTransport,
    host: advertiseHost,
    port,
    instanceId,
    connectionType: connectionTypeForHost(advertiseHost),
    running: server.address() !== null,
    mdns: mdns !== null,
    deviceName: mdns?.deviceName,
  })
  const taskHub = new TaskEventHub()
  /** @type {TaskFallbackTracker | null} */
  let taskFallback = null
  const server = createPocketServer({
    sessionController: ctx.sessionController, token, host: bindHost, port, devices, pairing,
    info: { plugin: PKG.name, version: PKG.version, instanceId },
    onSessionEvent: (fn) => { listeners.add(fn); return () => listeners.delete(fn) },
    getDiagnostics: buildDiagnostics,
    onSessionLifecycle: (kind, args) => {
      if (!taskFallback) return
      if (kind === 'status') taskFallback.onStatus(args[0], Boolean(args[1]))
      else if (kind === 'activity') taskFallback.markActivity(args[0], typeof args[1] === 'number' ? args[1] : undefined)
      else if (kind === 'removed') taskFallback.forget(args[0])
    },
  })
  taskHub.add({ broadcastTaskEvent: (event) => server.broadcastTaskEvent(event) })
  taskFallback = new TaskFallbackTracker({
    onTaskCompleted: (event) => taskHub.broadcast(event),
    isRootSession: (sessionId) => server.isRootSession(sessionId),
    hasPendingQuestion: (sessionId) => server.hasPendingUserQuestionForSession(sessionId),
  })
  // Adapted from saya-ch/dsh-mobile (Apache-2.0): exact root turn/end completions.
  const disposeTaskEvents = watchTaskCompletions(ctx, {
    onTaskCompleted: (event) => {
      taskFallback?.markAnnounced(event.sessionId)
      taskHub.broadcast(event)
    },
    log: (event, fields) => log(`task ${event}`, fields),
  })
  for (const kind of SESSION_EVENTS) {
    ctx.on(`api-session/${kind}`, (...args) => { for (const fn of listeners) fn(kind, args) })
  }
  // prepend: run outside the web UI forwarder so we can race it.
  ctx.on('approval/request', createApprovalHook(server, { log }), { prepend: true })
  ctx.on('user-questions/request', createUserQuestionHook(server, { log }), { prepend: true })

  /** Optional legacy desktop iroh sidecar — off unless config.iroh === true. Not on the phone path. */
  let iroh = null

  const getTransport = () => remoteTransport
  const setTransportMode = (mode) => {
    if (configLocksTransport) {
      throw new Error(`transport is locked by cordis config (${config.transport})`)
    }
    remoteTransport = saveTransport(dataDir, mode)
    log(`transport set to ${remoteTransport}`)
    return remoteTransport
  }

  const openPairing = () => {
    const mode = remoteTransport
    const w = pairing.open()
    const uri = pairingUri({ code: w.code, host: advertiseHost, port, transport: mode })
    const line = pairingString({ code: w.code, host: advertiseHost, port, transport: mode })
    log(`pairing (${mode}, ${Math.round(w.expiresInSec / 60)} min): ${line}`)
    return { ...w, transport: mode, host: advertiseHost, port, uri, pairingString: line }
  }
  const manage = createManageApi({
    host: advertiseHost, port, devices, pairing, server, openPairing,
    getTransport,
    setTransport: setTransportMode,
    instanceId,
    getDiagnostics: buildDiagnostics,
    getMdns: () => mdns !== null,
  })
  ctx.inject(['permissionPresets', 'agents'], (c) => { c.effect(() => server.registerOps(permissionOps(c.permissionPresets, c.agents))) })
  ctx.inject(['agentPresets'], (c) => { c.effect(() => server.registerOps(presetOps(c.agentPresets))) })
  ctx.inject(['commands', 'agents'], (c) => { c.effect(() => server.registerOps(commandOps(c.commands, c.agents))) })
  ctx.inject(['workspaceFiles', 'sessions'], (c) => {
    c.effect(() => server.registerOps(filesOps(c.workspaceFiles, c.sessions, c.get?.('sandboxPolicy'))))
  })
  ctx.inject(['jobController'], (c) => { c.effect(() => server.registerOps(jobsOps(c.jobController))) })
  ctx.inject(['terminalController', 'agents'], (c) => {
    c.effect(() => server.registerOps(terminalOps(c.terminalController, c.agents)))
  })
  server.registerOps(exportOps(ctx.sessionController, { zipSync, strToU8 }))
  server.registerOps(searchOps(ctx.sessionController))
  server.registerOps(attachmentOps(ctx.sessionController))
  ctx.inject(['workspaceChanges'], (c) => {
    c.effect(() => server.registerOps(workspaceChangesOps(c.workspaceChanges)))
  })
  // Prefer Host workspaceController when present; registry alone is enough in-process.
  ctx.inject(['workspaceRegistry'], (c) => {
    c.effect(() => {
      const controller = c.get?.('workspaceController') ?? null
      server.registerOps(workspaceOps(controller, c.workspaceRegistry))
    })
  })
  ctx.inject(['messageFeedback'], (c) => {
    c.effect(() => server.registerOps(feedbackOps(c.messageFeedback)))
  })
  ctx.inject(['fileReferences', 'agents'], (c) => {
    c.effect(() => server.registerOps(fileReferenceOps(c.fileReferences, c.agents)))
  })
  ctx.inject(['sessionReferenceResolver', 'agents'], (c) => {
    c.effect(() => server.registerOps(sessionReferenceOps(c.sessionReferenceResolver, c.agents)))
  })
  ctx.inject(['fileUploads', 'agents'], (c) => {
    c.effect(() => server.registerOps(uploadOps(c.fileUploads, c.agents)))
  })
  ctx.inject(['userQuestions', 'agents'], (c) => {
    c.effect(() => server.registerOps(userQuestionOps(c.userQuestions, c.agents, {
      settleOffer: (callId, answer) => server.settleUserQuestion(callId, answer),
    })))
  })
  // Desktop settings surfaces (models providers / plugins / schedules / credentials).
  // Each inject is optional; ops appear in hello only while the Host service is live.
  ctx.inject(['llm'], (c) => {
    c.effect(() => {
      const settings = c.get?.('settings') ?? null
      const settingsController = c.get?.('settingsController') ?? null
      const credentials = c.get?.('credentials') ?? null
      server.registerOps(modelsSettingsOps({
        llm: c.llm, settings, settingsController, credentials,
      }))
    })
  })
  ctx.inject(['pluginManager'], (c) => {
    c.effect(() => {
      server.registerOps(pluginsOps(c.pluginManager))
      const inventory = c.get?.('pluginInventory') ?? null
      server.registerOps(pluginInventoryOps(inventory, c.pluginManager))
    })
  })
  ctx.inject(['schedule'], (c) => {
    c.effect(() => server.registerOps(scheduleOps(c.schedule)))
  })
  ctx.inject(['credentials'], (c) => {
    c.effect(() => {
      const llm = c.get?.('llm') ?? null
      const settings = c.get?.('settings') ?? null
      const settingsController = c.get?.('settingsController') ?? null
      server.registerOps(credentialsOps({
        credentials: c.credentials, llm, settings, settingsController,
      }))
    })
  })
  ctx.inject(['webServer'], (webCtx) => registerPairRoute(webCtx, manage))

  ctx.effect(() => {
    const start = async () => {
      log(`remote transport: ${remoteTransport} (advertise ${advertiseHost}:${port}, bind ${bindHost})`)

      // Legacy opt-in only — phone APK no longer ships iroh; keep server code dormant by default.
      if (config.iroh === true) {
        iroh = await startIrohSidecar({
          server,
          dataDir: join(dataDir, 'iroh'),
          hostBinary: config.hostBinary,
          log,
        })
        if (iroh) {
          try {
            await Promise.race([
              iroh.endpointId,
              new Promise((_, rej) => setTimeout(() => rej(new Error('iroh ready timeout')), 60_000)),
            ])
            log('legacy iroh sidecar ready (not used for phone pairing)')
          } catch (e) {
            console.error('[pocket] iroh sidecar did not become ready:', e.message ?? e)
            await iroh.close()
            iroh = null
          }
        }
      }

      const addr = await server.listen()
      log(`ws listening on ws://${addr} (pairing advertises ${advertiseHost}:${port})`)
      log(`instance ${instanceId.slice(0, 8)}…`)
      log(created ? `new owner token written to ${path}: ${token}` : `owner token: ${path}`)

      try {
        mdns = startMdnsAdvertise({
          host: advertiseHost,
          port,
          instanceId,
          log,
          onError: (err) => console.error('[pocket] mdns:', err.message ?? err),
        })
      } catch (e) {
        console.error('[pocket] mdns advertise failed:', e.message ?? e)
        mdns = null
      }

      if (config.pair || devices.list().length === 0) {
        try { openPairing() } catch (e) { console.error('[pocket]', e.message ?? e) }
      }
    }
    start().catch((error) => console.error('[pocket] start failed:', error))
    return () => {
      void mdns?.stop()
      mdns = null
      disposeTaskEvents()
      taskFallback?.dispose()
      void iroh?.close()
      void server.close()
    }
  })
}
