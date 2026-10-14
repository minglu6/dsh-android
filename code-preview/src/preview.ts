// DeepSeek highlighter/theme/table are vendored unchanged under vendor/LICENSE.
import { StreamingHighlightSession, subscribeGrammarLoaded } from '../vendor/highlight'
import { languageForPath } from '../vendor/code-language'

type Options = { wrap: boolean; plain: boolean; dark: boolean; fontSize: number; fontFamily: string; foreground: string; muted: string; background: string }
type SourceLine = { text: string; separator: string; end: number }

export function sourceLines(text: string): SourceLine[] {
  const lines: SourceLine[] = []
  const separators = /\r\n|\n/g
  let start = 0
  for (const match of text.matchAll(separators)) {
    const end = match.index! + match[0].length
    lines.push({ text: text.slice(start, match.index), separator: match[0], end })
    start = end
  }
  lines.push({ text: text.slice(start), separator: '', end: text.length })
  return lines
}

export function installPreview(document: Document, viewport: Pick<Window, 'scrollX' | 'scrollY' | 'scrollTo'>) {
  const code = document.getElementById('code')!
  const body = document.body
  let text = ''
  let path = ''
  let hasDocument = false
  let options: Options | undefined
  let generation = 0
  let pending: number | undefined

  function restore(x: number, y: number) { viewport.scrollTo(x, y) }
  function render() {
    const ownGeneration = ++generation
    clearTimeout(pending)
    const x = viewport.scrollX, y = viewport.scrollY
    body.dataset.plain = String(options?.plain ?? true)
    if (options?.plain || languageForPath(path) === undefined) {
      code.textContent = text
      body.dataset.plain = 'true'
      restore(x, y)
      return
    }
    const lines = sourceLines(text)
    body.style.setProperty('--number-width', `${String(lines.length).length}ch`)
    const nodes: HTMLSpanElement[] = []
    const fragment = document.createDocumentFragment()
    for (const line of lines) {
      const node = document.createElement('span')
      node.className = 'line'
      node.textContent = line.text
      nodes.push(node)
      fragment.append(node, document.createTextNode(line.separator))
    }
    code.replaceChildren(fragment)
    restore(x, y)
    const session = new StreamingHighlightSession()
    let endLine = 0
    let completed = 0
    function paint(index: number, spans: readonly { text: string; style: object }[]) {
      // TextMate normalizes newlines; never let tokenization rewrite the source.
      if (!nodes[index] || spans.map(span => span.text).join('') !== lines[index].text) return
      const fragment = document.createDocumentFragment()
      for (const span of spans) {
        const node = document.createElement('span')
        node.textContent = span.text
        Object.assign(node.style, span.style)
        fragment.append(node)
      }
      nodes[index].replaceChildren(fragment)
    }
    function batch() {
      if (ownGeneration !== generation) return
      // Match the official incremental grammar state, yielding between small batches.
      // The initial uncolored DOM remains selectable/scrollable while a large file colors.
      endLine = Math.min(lines.length, endLine + 16)
      const frame = session.updateFrame(text.slice(0, lines[endLine - 1].end), languageForPath(path))
      if (frame) {
        for (const spans of frame.appended) paint(completed++, spans)
        frame.tail.forEach((spans, index) => paint(completed + index, spans))
      }
      if (endLine < lines.length) pending = setTimeout(batch, 0)
    }
    pending = setTimeout(batch, 0)
  }
  const unsubscribe = subscribeGrammarLoaded(render)
  // Pseudo-element counters never enter the DOM. cloneContents preserves actual
  // separators instead of the browser's extra newlines for block-level line boxes.
  function copy(event: ClipboardEvent) {
    const selection = document.getSelection()
    if (!selection?.rangeCount || !event.clipboardData) return
    const range = selection.getRangeAt(0)
    if (!range.intersectsNode(code)) return
    event.clipboardData.setData('text/plain', range.cloneContents().textContent ?? '')
    event.preventDefault()
  }
  document.addEventListener('copy', copy)
  return {
    setDocument(next: { path: string; text: string }) {
      if (hasDocument && next.path === path && next.text === text) return
      const changedPath = next.path !== path
      path = next.path; text = next.text; hasDocument = true
      render()
      if (changedPath) viewport.scrollTo(0, 0)
    },
    setOptions(next: Options) {
      const previous = options
      options = next
      const x = viewport.scrollX, y = viewport.scrollY
      body.toggleAttribute('data-ds-dark-theme', next.dark)
      body.dataset.wrap = String(next.wrap)
      body.style.setProperty('--code-font-size', `${next.fontSize}px`)
      body.style.setProperty('--code-font-family', next.fontFamily)
      body.style.setProperty('--dsw-alias-label-primary', next.foreground)
      body.style.setProperty('--dsw-alias-label-tertiary', next.muted)
      body.style.setProperty('--dsw-alias-markdown-code-block', next.background)
      if (hasDocument && previous?.plain !== next.plain) render()
      restore(x, y)
    },
    destroy() {
      generation++
      clearTimeout(pending)
      unsubscribe()
      document.removeEventListener('copy', copy)
    },
  }
}

if (typeof window !== 'undefined') {
  Object.defineProperty(window, 'codePreview', { value: installPreview(document, window), writable: false })
}
