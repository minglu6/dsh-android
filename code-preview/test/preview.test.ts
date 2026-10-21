import assert from 'node:assert/strict'
import { test } from 'node:test'
import { JSDOM } from 'jsdom'
import { installPreview, sourceLines, MAX_COMPARISON_LINES, renderedHunks } from '../src/preview'
import type { Comparison } from '../src/preview'

const defaults = { wrap: true, plain: false, dark: false, fontSize: 11, fontFamily: 'monospace', foreground: '#111111', muted: '#888888', background: '#ffffff' }
function fixture() {
  const dom = new JSDOM('<!doctype html><body><pre><code id="code"></code></pre></body>')
  const viewport = { scrollX: 0, scrollY: 0, scrollTo(x: number, y: number) { this.scrollX = x; this.scrollY = y } }
  const renderer = installPreview(dom.window.document, viewport)
  renderer.setOptions(defaults)
  return { dom, viewport, renderer, code: dom.window.document.getElementById('code')! }
}
async function colored(code: HTMLElement) {
  if (code.querySelector('[style]')) return
  const { promise, resolve } = Promise.withResolvers<void>()
  const observer = new code.ownerDocument.defaultView!.MutationObserver(() => {
    if (!code.querySelector('[style]')) return
    observer.disconnect()
    resolve()
  })
  observer.observe(code, { childList: true, subtree: true })
  await promise
}

test('physical lines retain empty lines, CRLF, trailing newline and tabs verbatim', () => {
  for (const source of ['', '\n', 'a\n\n', 'a\r\nb\r\n', '\t x\r\nlast']) {
    assert.equal(sourceLines(source).map(line => line.text + line.separator).join(''), source)
    const { renderer, code } = fixture()
    renderer.setDocument({ path: 'source.ts', text: source })
    assert.equal(code.textContent, source)
    assert.equal(code.querySelectorAll('.line').length, source.split('\n').length)
    renderer.destroy()
  }
})

test('real grammar keeps multiline state, JS aliases and markup font styles', async () => {
  const { renderer, code } = fixture()
  renderer.setDocument({ path: 'source.js', text: '/* open\nstill comment */\nconst answer = "ok";\n' })
  await colored(code)
  const lines = code.querySelectorAll('.line')
  assert.match(lines[1].innerHTML, /--shiki-token-comment/)
  assert.match(lines[2].innerHTML, /--shiki-token-keyword/)
  assert.match(lines[2].innerHTML, /--shiki-token-string/)
  renderer.setDocument({ path: 'source.md', text: '**strong** and *emphasis*\n' })
  await colored(code)
  assert.match(code.innerHTML, /font-weight: bold/)
  assert.match(code.innerHTML, /font-style: italic/)
  renderer.destroy()
})

test('source HTML is inert and copy uses actual separators without gutter counters', async () => {
  const { dom, renderer, code } = fixture()
  const source = '</script><img src=x onerror="alert(1)">\r\n\tconst a = 1;\n'
  renderer.setDocument({ path: 'unsafe.js', text: source })
  await colored(code)
  assert.equal(code.textContent, source)
  assert.equal(code.querySelector('img,script,iframe,a'), null)
  const selection = dom.window.getSelection()!
  const range = dom.window.document.createRange()
  range.selectNodeContents(code)
  selection.addRange(range)
  let copied = ''
  const event = new dom.window.Event('copy', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'clipboardData', { value: { setData(type: string, value: string) { assert.equal(type, 'text/plain'); copied = value } } })
  code.dispatchEvent(event)
  assert.equal(copied, source)
  renderer.destroy()
})

test('wrap/theme/font updates preserve nodes, source and vertical position; plain removes gutters', async () => {
  const { renderer, code, viewport } = fixture()
  const source = 'const value = 1;\n'.repeat(40)
  renderer.setDocument({ path: 'source.ts', text: source })
  await colored(code)
  const firstLine = code.firstChild
  viewport.scrollY = 234
  renderer.setOptions({ ...defaults, wrap: false, dark: true, fontSize: 15 })
  assert.equal(code.firstChild, firstLine)
  assert.equal(code.textContent, source)
  assert.equal(viewport.scrollY, 234)
  renderer.setOptions({ ...defaults, plain: true })
  assert.equal(code.querySelector('.line'), null)
  assert.equal(code.querySelector('[style]'), null)
  assert.equal(code.textContent, source)
  assert.equal(viewport.scrollY, 234)
  renderer.destroy()
})

test('long lines follow the official 1000 UTF-16-unit cutoff and updates cancel pending work', async () => {
  const { renderer, code } = fixture()
  const long = 'const source = "' + 'x'.repeat(1000) + '";'
  renderer.setDocument({ path: 'source.ts', text: long + '\nconst next = 1;' })
  await colored(code)
  assert.doesNotMatch(code.querySelector('.line')!.innerHTML, /--shiki-token-keyword/)
  renderer.setDocument({ path: 'source.ts', text: 'const a = 1;\n'.repeat(1480) })
  assert.equal(code.querySelectorAll('[style]').length, 0, 'large source paints plain before asynchronous token batches')
  renderer.setDocument({ path: 'source.ts', text: 'const replacement = true;' })
  await colored(code)
  assert.equal(code.textContent, 'const replacement = true;')
  renderer.destroy()
})

function comparison(hunks: Comparison['hunks'], path = 'snapshot.ts'): Comparison {
  return { path, before: true, after: true, coarse: false, hunks }
}

async function settled(renderer: { status(): { state: string; error?: string } }, code: HTMLElement) {
  if (renderer.status().state === 'rendering') {
    const { promise, resolve } = Promise.withResolvers<void>()
    const observer = new code.ownerDocument.defaultView!.MutationObserver(() => {
      if (renderer.status().state === 'rendering') return
      observer.disconnect()
      resolve()
    })
    observer.observe(code.ownerDocument.body, { attributes: true, attributeFilter: ['data-render-state'] })
    await promise
  }
  assert.equal(renderer.status().error, undefined)
  assert.equal(renderer.status().state, 'ready')
}

test('comparison strips prefixes, retains independent coordinates and colors each old/new hunk side', async () => {
  const { renderer, code, dom } = fixture()
  renderer.setComparison(comparison([{ oldStart: 4, newStart: 8, lines: [
    ' const head = 1;', '-/* old comment', '-continued */',
    '+const replacement = `open', '+still template`;', ' const tail = 2;',
  ] }]))
  await settled(renderer, code)
  const rows = [...code.querySelectorAll<HTMLElement>('[data-diff-line]')]
  assert.deepEqual(rows.map(row => [row.dataset.diffLine, row.dataset.old, row.dataset.new]), [
    ['context', '4', '8'], ['del', '5', ''], ['del', '6', ''],
    ['add', '', '9'], ['add', '', '10'], ['context', '7', '11'],
  ])
  assert.equal(dom.window.document.body.dataset.oneSided, 'false')
  assert.match(rows[2].innerHTML, /--shiki-token-comment/)
  assert.match(rows[3].innerHTML, /--shiki-token-keyword/)
  assert.match(rows[4].innerHTML, /--shiki-token-string/)
  assert.match(rows[5].innerHTML, /--shiki-token-keyword/)
  assert.deepEqual(rows.map(row => row.textContent), [
    'const head = 1;', '/* old comment', 'continued */', 'const replacement = `open', 'still template`;', 'const tail = 2;',
  ])
  renderer.destroy()
})

test('one-sided rows use one gutter, retain terminal empty lines and restart grammar for each hunk', async () => {
  for (const sign of ['+', '-']) {
    const { renderer, code, dom } = fixture()
    renderer.setComparison(comparison([
      { oldStart: 3, newStart: 7, lines: [sign + '/* open'] },
      { oldStart: 20, newStart: 40, lines: [sign + 'const independent = 3;', sign, sign] },
    ]))
    await settled(renderer, code)
    const rows = [...code.querySelectorAll<HTMLElement>('[data-diff-line]')]
    assert.equal(dom.window.document.body.dataset.oneSided, 'true')
    assert.deepEqual(rows.map(row => row.dataset.numbers), sign === '+' ? ['7', '40', '41', '42'] : ['3', '20', '21', '22'])
    assert.match(rows[1].innerHTML, /--shiki-token-keyword/)
    assert.equal(rows[2].textContent, '')
    assert.equal(rows[3].textContent, '')
    assert.equal(code.querySelectorAll('.comparison-hunk').length, 2)
    renderer.destroy()
  }
})

test('complete created and deleted snapshots read as source, while mixed changes retain patch context', async () => {
  const { renderer, code } = fixture()
  const lines = ['{', '  "count": 42', '}']
  for (const created of [true, false]) {
    renderer.setComparison({
      ...comparison([{ oldStart: created ? 0 : 1, newStart: created ? 1 : 0,
        lines: lines.map(line => (created ? '+' : '-') + line) }], 'contract.json'),
      before: !created, after: created,
    })
    await settled(renderer, code)
    const rows = [...code.querySelectorAll<HTMLElement>('[data-diff-line]')]
    assert.deepEqual(rows.map(row => row.textContent), lines)
    assert.deepEqual(rows.map(row => row.dataset.numbers), ['1', '2', '3'])
    assert.doesNotMatch(code.textContent!, /@@ -/)
  }
  renderer.setComparison(comparison([{ oldStart: 2, newStart: 2, lines: ['-  "count": 41', '+  "count": 42'] }], 'contract.json'))
  await settled(renderer, code)
  assert.match(code.textContent!, /@@ -2,1 \+2,1 @@/)
  assert.deepEqual([...code.querySelectorAll<HTMLElement>('[data-diff-line]')].map(row => [row.dataset.old, row.dataset.new]), [['2', ''], ['', '2']])
  renderer.setDocument({ path: 'contract.json', text: lines.join('\n') })
  await settled(renderer, code)
  assert.equal(code.textContent, lines.join('\n'))
  assert.equal(code.querySelector('[data-diff-line]'), null)
  renderer.destroy()
})

test('comparison cap is exactly 5000 across hunks, and truncation is explicit rather than a fake complete source', { timeout: 30_000 }, async () => {
  const lines = Array.from({ length: MAX_COMPARISON_LINES }, (_, at) => `+const line${at} = ${at};`)
  assert.equal(renderedHunks([{ oldStart: 0, newStart: 1, lines }]).truncated, false)
  const diff = comparison([
    { oldStart: 0, newStart: 1, lines: lines.slice(0, 2) },
    { oldStart: 0, newStart: 3, lines: [...lines.slice(2), '+const excluded = true;'] },
  ])
  const { renderer, code } = fixture()
  renderer.setComparison(diff)
  await settled(renderer, code)
  const rows = code.querySelectorAll<HTMLElement>('[data-diff-line]')
  assert.equal(rows.length, MAX_COMPARISON_LINES)
  assert.equal(rows[MAX_COMPARISON_LINES - 1].dataset.new, '5000')
  assert.match(rows[MAX_COMPARISON_LINES - 1].innerHTML, /--shiki-token-keyword/)
  assert.match(code.querySelector('[data-diff-note=\"truncated\"]')!.textContent!, /5000/)
  assert.doesNotMatch(code.textContent!, /excluded/)
  renderer.destroy()
})

test('comparison theme, wrap and font updates reuse tokens; plain/source switches cancel pending comparison work', async () => {
  const { renderer, code, dom, viewport } = fixture()
  const diff = comparison([{ oldStart: 1, newStart: 1, lines: ['-const before = 1;', '+const after = 2;'] }], 'state.js')
  renderer.setComparison(diff)
  await settled(renderer, code)
  const token = code.querySelector('[style]')
  const generation = renderer.status().generation
  viewport.scrollY = 93
  renderer.setOptions({ ...defaults, dark: true, wrap: false, fontSize: 14, fontFamily: 'DSH Meslo' })
  assert.equal(renderer.status().generation, generation)
  assert.equal(code.querySelector('[style]'), token)
  assert.equal(viewport.scrollY, 93)
  assert.equal(dom.window.document.body.hasAttribute('data-ds-dark-theme'), true)
  renderer.setOptions({ ...defaults, plain: true })
  assert.equal(code.querySelector('[style]'), null)
  assert.equal(code.querySelectorAll('[data-diff-line]').length, 2)
  renderer.setOptions(defaults)
  renderer.setComparison(comparison([{ oldStart: 0, newStart: 1, lines: Array(900).fill('+const obsolete = 1;') }]))
  renderer.setDocument({ path: 'next.json', text: '{\"replacement\": 42}' })
  await settled(renderer, code)
  assert.equal(code.querySelector('[data-diff-line]'), null)
  assert.equal(code.textContent, '{\"replacement\": 42}')
  assert.match(code.innerHTML, /--shiki-token-constant/)
  assert.equal(dom.window.document.body.dataset.mode, 'source')
  renderer.setComparison(diff)
  await settled(renderer, code)
  assert.equal(code.querySelectorAll('[data-diff-line]').length, 2)
  assert.equal(dom.window.document.body.dataset.mode, 'comparison')
  renderer.destroy()
})

test('line batches preserve real grammar state across batch boundaries and terminal blank token rows', async () => {
  const { renderer, code } = fixture()
  const source = [...Array(15).fill('const prefix = 1;'), '/* open', 'comment continues */', '', '', 'const last = 42;', ''].join('\n')
  renderer.setDocument({ path: 'batched.ts', text: source })
  await settled(renderer, code)
  const rows = code.querySelectorAll('.line')
  assert.equal(code.textContent, source)
  assert.match(rows[16].innerHTML, /--shiki-token-comment/)
  assert.equal(rows[17].textContent, '')
  assert.equal(rows[18].textContent, '')
  assert.match(rows[19].innerHTML, /--shiki-token-constant/)
  assert.equal(rows[20].textContent, '')
  renderer.destroy()
})
