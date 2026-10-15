import { Component, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { ExcelBody } from '../vendor/excel/excel.tsx'
import { zh, en, type ExcelPreviewKey } from '../vendor/excel/locales.ts'
import { LoadingIndicator } from '../vendor/LoadingIndicator.tsx'
import { limits } from './contract.ts'
import './preview.css'

const root = createRoot(document.getElementById('root')!)
let lifetime = new AbortController()
let generation = 0
let dictionary = zh
const t = (key: ExcelPreviewKey, args?: { features: string }): string => dictionary[key].replace('{features}', args?.features ?? '')
let renderCurrent: (() => void) | undefined
class WorkbookBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() { return { failed: true } }
  render() { return this.state.failed ? <div className="status" role="alert">{t('invalid')}</div> : this.props.children }
}

async function setDocument(url: string, format: 'xls' | 'xlsx'): Promise<void> {
  lifetime.abort()
  lifetime = new AbortController()
  const signal = lifetime.signal
  const id = ++generation
  renderCurrent = () => root.render(<LoadingIndicator label={t('loading')} />)
  renderCurrent()
  try {
    if (!/^https:\/\/workbook-preview\.invalid\/document\/[a-f0-9-]+$/.test(url) || !['xls', 'xlsx'].includes(format)) throw new Error('invalid')
    const response = await fetch(url, { signal, cache: 'no-store', credentials: 'omit', redirect: 'error' })
    if (!response.ok) throw new Error('invalid')
    const size = Number(response.headers.get('content-length'))
    if (!Number.isSafeInteger(size) || size < 1 || size > limits.maxBytes) throw new Error(size > limits.maxBytes ? 'tooLarge' : 'invalid')
    const bytes = new Uint8Array(await response.arrayBuffer())
    if (signal.aborted || id !== generation) return
    if (bytes.length !== size) throw new Error('invalid')
    const content = { kind: 'bytes' as const, data: bytes }
    renderCurrent = () => root.render(<WorkbookBoundary key={id}><ExcelBody content={content} format={format} limits={limits}
      t={(key, args) => t(key, args)} loading={<LoadingIndicator label={t('loading')} />} /></WorkbookBoundary>)
    renderCurrent()
  } catch (error) {
    if (signal.aborted || id !== generation) return
    const key = error instanceof Error && error.message === 'tooLarge' ? 'tooLarge' : 'invalid'
    renderCurrent = () => root.render(<div className="status" role="alert">{t(key)}</div>)
    renderCurrent()
  }
}

function setOptions(options: { dark: boolean; locale: string; tokens: Record<string, string> }): void {
  dictionary = options.locale === 'en' ? en : zh
  document.documentElement.lang = options.locale === 'en' ? 'en' : 'zh-CN'
  document.documentElement.style.colorScheme = options.dark ? 'dark' : 'light'
  for (const [name, color] of Object.entries(options.tokens)) {
    if (/^--dsw-[a-z-]+$/.test(name) && /^#[0-9a-f]{6,8}$/i.test(color)) document.documentElement.style.setProperty(name, color)
  }
  renderCurrent?.()
}
function dispose(): void {
  lifetime.abort()
  generation++
  root.unmount()
  renderCurrent = undefined
}
Object.assign(window, { workbookPreview: { setDocument, setOptions, dispose } })
window.addEventListener('pagehide', dispose, { once: true })
