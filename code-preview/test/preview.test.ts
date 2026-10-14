import assert from 'node:assert/strict'
import { test } from 'node:test'
import { JSDOM } from 'jsdom'
import { installPreview, sourceLines } from '../src/preview'

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
