// DeepSeek highlighter/theme/table are adapted under vendor/LICENSE.
import { LineHighlightSession, subscribeGrammarLoaded } from '../vendor/highlight'
import type { HighlightSpan } from '../vendor/highlight'
import { languageForPath } from '../vendor/code-language'

type Options = { wrap: boolean; plain: boolean; dark: boolean; fontSize: number; fontFamily: string; foreground: string; muted: string; background: string; language?: string }
type SourceLine = { text: string; separator: string; end: number }
export type ComparisonHunk = { oldStart: number; newStart: number; lines: readonly string[] }
export type Comparison = { path: string; before: boolean; after: boolean; coarse: boolean; hunks: readonly ComparisonHunk[] }
type RenderedHunk = ComparisonHunk & { oldCount: number; newCount: number }
type Row = { kind: 'add' | 'del' | 'context'; old?: number; new?: number; text: string }
type PaintJob = { lines: readonly { text: string }[]; nodes: (HTMLElement | undefined)[]; session: LineHighlightSession; end: number }
export const MAX_COMPARISON_LINES = 5000
const comparisonNotes = {
  created: ['新建文件', 'File created'],
  deleted: ['已删除文件', 'File deleted'],
  unchanged: ['文件内容未变化', 'File content unchanged'],
  coarse: ['此比较为粗略差异。', 'This comparison uses coarse differences.'],
  truncated: [`仅显示前 ${MAX_COMPARISON_LINES} 行差异。`, `Only the first ${MAX_COMPARISON_LINES} comparison lines are shown.`],
}
type ComparisonNote = keyof typeof comparisonNotes

export function sourceLines(text: string): SourceLine[] {
  const lines: SourceLine[] = []
  let start = 0
  for (const match of text.matchAll(/\r\n|\n/g)) {
    const end = match.index! + match[0].length
    lines.push({ text: text.slice(start, match.index), separator: match[0], end })
    start = end
  }
  lines.push({ text: text.slice(start), separator: '', end: text.length })
  return lines
}

export function comparisonRows(hunk: ComparisonHunk): Row[] {
  let old = hunk.oldStart, next = hunk.newStart
  return hunk.lines.map(line => {
    const text = line.slice(1)
    if (line[0] === '+') return { kind: 'add', new: next++, text }
    if (line[0] === '-') return { kind: 'del', old: old++, text }
    return { kind: 'context', old: old++, new: next++, text }
  })
}

export function renderedHunks(hunks: readonly ComparisonHunk[]) {
  let remaining = MAX_COMPARISON_LINES
  let truncated = false
  const kept: RenderedHunk[] = []
  for (const hunk of hunks) {
    if (hunk.lines.length > remaining) truncated = true
    if (remaining === 0) continue
    kept.push({
      ...hunk,
      oldCount: hunk.lines.reduce((count, line) => count + Number(line[0] !== '+'), 0),
      newCount: hunk.lines.reduce((count, line) => count + Number(line[0] !== '-'), 0),
      lines: hunk.lines.length <= remaining ? hunk.lines : hunk.lines.slice(0, remaining),
    })
    remaining -= Math.min(remaining, hunk.lines.length)
  }
  return { hunks: kept, truncated }
}

export function installPreview(document: Document, viewport: Pick<Window, 'scrollX' | 'scrollY' | 'scrollTo'>) {
  const code = document.getElementById('code')!
  const body = document.body
  let content: { path: string; text: string } | Comparison | undefined
  let options: Options | undefined
  let generation = 0
  let pending: number | undefined
  let state: 'empty' | 'rendering' | 'ready' | 'error' = 'empty'
  let error: string | undefined

  function restore(x: number, y: number) { viewport.scrollTo(x, y) }
  function fail(reason: unknown) {
    state = 'error'
    error = reason instanceof Error ? reason.message : String(reason)
    body.dataset.renderState = state
  }
  function status(next: typeof state) { state = next; body.dataset.renderState = next }
  function paint(node: HTMLElement | undefined, line: { text: string }, spans: readonly HighlightSpan[]) {
    if (!node) return
    // TextMate normalizes newlines; never let tokenization rewrite source or shift a row.
    if (spans.map(span => span.text).join('') !== line.text) throw new Error('Syntax tokens do not match the source line.')
    const fragment = document.createDocumentFragment()
    for (const span of spans) {
      const token = document.createElement('span')
      token.textContent = span.text
      Object.assign(token.style, span.style)
      fragment.append(token)
    }
    node.replaceChildren(fragment)
  }
  function job(lines: readonly { text: string }[], nodes: (HTMLElement | undefined)[]): PaintJob {
    return { lines, nodes, session: new LineHighlightSession(), end: 0 }
  }
  function render() {
    const ownGeneration = ++generation
    clearTimeout(pending)
    if (!content) return
    error = undefined
    status('rendering')
    const x = viewport.scrollX, y = viewport.scrollY
    const plain = options?.plain ?? true
    const language = languageForPath(content.path)
    const jobs: PaintJob[] = []
    body.dataset.plain = String(plain)
    const fragment = document.createDocumentFragment()
    if ('text' in content) {
      body.dataset.mode = 'source'
      delete body.dataset.oneSided
      delete body.dataset.fullFile
      if (plain || language === undefined) {
        code.textContent = content.text
        body.dataset.plain = 'true'
        status('ready')
        restore(x, y)
        return
      }
      const lines = sourceLines(content.text)
      body.style.setProperty('--number-width', `${String(lines.length).length}ch`)
      const nodes = lines.map(line => {
        const node = document.createElement('span')
        node.className = 'line'
        node.textContent = line.text
        fragment.append(node, document.createTextNode(line.separator))
        return node
      })
      jobs.push(job(lines, nodes))
    } else {
      body.dataset.mode = 'comparison'
      const hasAdditions = content.hunks.some(hunk => hunk.lines.some(line => line[0] === '+'))
      const hasDeletions = content.hunks.some(hunk => hunk.lines.some(line => line[0] === '-'))
      const oneSided = hasAdditions !== hasDeletions
      const onlyHunk = content.hunks.length === 1 ? content.hunks[0] : undefined
      const fullFile = oneSided && onlyHunk !== undefined
        && ((!content.before && hasAdditions && onlyHunk.newStart === 1)
          || (!content.after && hasDeletions && onlyHunk.oldStart === 1))
      body.dataset.fullFile = String(fullFile)
      body.dataset.oneSided = String(oneSided)
      const { hunks, truncated } = renderedHunks(content.hunks)
      function note(kind: ComparisonNote) {
        const node = document.createElement('span')
        node.className = 'comparison-note'
        node.dataset.diffNote = kind
        node.textContent = comparisonNotes[kind][options?.language === 'en' ? 1 : 0]
        fragment.append(node)
      }
      if (!fullFile) {
        if (!content.before) note('created')
        else if (!content.after) note('deleted')
        else if (content.hunks.length === 0) note('unchanged')
      }
      if (content.coarse) note('coarse')
      if (truncated) note('truncated')
      let largestNumber = 1
      for (const hunk of hunks) {
        const rows = comparisonRows(hunk)
        const section = document.createElement('span')
        section.className = 'comparison-hunk'
        if (!fullFile) {
          const header = document.createElement('span')
          header.className = 'comparison-header'
          header.textContent = `@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@`
          section.append(header)
        }
        const nodes = rows.map(row => {
          largestNumber = Math.max(largestNumber, row.old ?? 0, row.new ?? 0)
          const line = document.createElement('span')
          line.className = 'comparison-line'
          line.dataset.diffLine = row.kind
          line.dataset.old = row.old?.toString() ?? ''
          line.dataset.new = row.new?.toString() ?? ''
          // Gutters and signs are pseudo-elements, so selection never copies them as source.
          line.dataset.numbers = oneSided ? String((hasAdditions ? row.new : row.old) ?? '') : ''
          const source = document.createElement('span')
          source.className = 'comparison-text'
          source.textContent = row.text
          line.append(source)
          section.append(line, document.createTextNode('\n'))
          return source
        })
        fragment.append(section)
        if (!plain && language !== undefined) {
          // Separate old/new grammar states within each hunk, exactly like desktop FileDiff.
          for (const side of ['old', 'new'] as const) {
            const included = rows.flatMap((row, index) => row[side] === undefined ? [] : [{ row, node: row.kind === 'context' && side === 'new' ? undefined : nodes[index] }])
            if (included.length) jobs.push(job(included.map(line => line.row), included.map(line => line.node)))
          }
        }
      }
      body.style.setProperty('--number-width', `${String(largestNumber).length}ch`)
    }
    code.replaceChildren(fragment)
    restore(x, y)
    if (!jobs.length) { status('ready'); return }
    let current = 0
    function batch() {
      if (ownGeneration !== generation) return
      try {
        const next = jobs[current]
        const start = next.end
        const end = Math.min(next.lines.length, start + 16)
        const spans = next.session.highlight(next.lines.slice(start, end).map(line => line.text), language)
        // A lazy grammar is still loading. Its completion subscriber restarts this render;
        // do not declare uncolored text a successful highlighted document.
        if (!spans) return
        spans.forEach((tokens, offset) => paint(next.nodes[start + offset], next.lines[start + offset], tokens))
        next.end = end
        if (end === next.lines.length) current++
        if (current < jobs.length) pending = setTimeout(batch, 0)
        else status('ready')
      } catch (reason) { fail(reason) }
    }
    pending = setTimeout(batch, 0)
  }
  const unsubscribe = subscribeGrammarLoaded(() => { if (state === 'rendering') render() })
  function copy(event: ClipboardEvent) {
    const selection = document.getSelection()
    if (!selection?.rangeCount || !event.clipboardData) return
    const range = selection.getRangeAt(0)
    if (!range.intersectsNode(code)) return
    event.clipboardData.setData('text/plain', range.cloneContents().textContent ?? '')
    event.preventDefault()
  }
  document.addEventListener('copy', copy)
  function setContent(next: NonNullable<typeof content>) {
    const changedPath = next.path !== content?.path
    content = next
    render()
    if (changedPath) viewport.scrollTo(0, 0)
  }
  return {
    setDocument(next: { path: string; text: string }) {
      if (content && 'text' in content && next.path === content.path && next.text === content.text) return
      setContent(next)
    },
    setComparison(next: Comparison) { setContent(next) },
    status() { return { state, error, generation, textLength: code.textContent?.length ?? 0 } },
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
      if (previous?.language !== next.language) {
        for (const note of code.querySelectorAll<HTMLElement>('[data-diff-note]')) {
          note.textContent = comparisonNotes[note.dataset.diffNote as ComparisonNote][next.language === 'en' ? 1 : 0]
        }
      }
      if (content && previous?.plain !== next.plain) render()
      restore(x, y)
    },
    destroy() { generation++; clearTimeout(pending); unsubscribe(); document.removeEventListener('copy', copy) },
  }
}

if (typeof window !== 'undefined') {
  Object.defineProperty(window, 'codePreview', { value: installPreview(document, window), writable: false })
}
