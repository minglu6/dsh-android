import { useSyncExternalStore } from 'react'

type Listener = () => void

/** Tiny shared open-state for the overlay, shared by the footer trigger and overlay cell. */
export function createPanelStore(initial = false) {
  let open = initial
  const listeners = new Set<Listener>()
  const emit = () => { for (const l of listeners) l() }
  return {
    getSnapshot: () => open,
    subscribe: (l: Listener) => { listeners.add(l); return () => { listeners.delete(l) } },
    open: () => { if (!open) { open = true; emit() } },
    close: () => { if (open) { open = false; emit() } },
    toggle: () => { open = !open; emit() },
    useOpen: () => useSyncExternalStore(
      (l) => { listeners.add(l); return () => { listeners.delete(l) } },
      () => open,
      () => open,
    ),
  }
}

export type PanelStore = ReturnType<typeof createPanelStore>
