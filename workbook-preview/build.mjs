// npm ci && npm run build. Generated APK assets are checked in; Gradle is Node-free.
import './patch.mjs'
import { build } from 'esbuild'
import { mkdir, readFile, writeFile, readdir, rm } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
const root = fileURLToPath(new URL('.', import.meta.url))
const assets = resolve(root, '../app/app/src/main/assets/workbook-preview')
await mkdir(assets, { recursive: true })
for (const name of await readdir(assets)) await rm(resolve(assets, name), { recursive: true })
const common = { absWorkingDir: root, bundle: true, platform: 'browser', target: 'chrome109', minify: true, legalComments: 'eof', metafile: true }
const worker = await build({ ...common, entryPoints: ['vendor/excel/worker.ts'], format: 'iife', write: false })
const bundle = await build({
  ...common, entryPoints: ['src/preview.tsx'], outdir: assets, entryNames: 'preview', format: 'esm', jsx: 'automatic',
  define: { 'process.env.NODE_ENV': '"production"' },
  loader: { '.png': 'dataurl', '.svg': 'dataurl', '.woff': 'dataurl', '.woff2': 'dataurl', '.ttf': 'dataurl' },
  plugins: [{ name: 'desktop-inline-assets', setup(builder) {
    builder.onResolve({ filter: /worker\.ts\?raw$/ }, () => ({ path: 'workbook-worker', namespace: 'inline-worker' }))
    builder.onLoad({ filter: /.*/, namespace: 'inline-worker' }, () => ({ contents: `export default ${JSON.stringify(worker.outputFiles[0].text)}`, loader: 'js' }))
    builder.onResolve({ filter: /\.css\?inline$/ }, args => ({ path: resolve(root, 'node_modules', args.path.replace('?inline', '')), namespace: 'inline-css' }))
    builder.onLoad({ filter: /.*/, namespace: 'inline-css' }, async args => ({ contents: `export default ${JSON.stringify(await readFile(args.path, 'utf8'))}`, loader: 'js' }))
  } }],
})
await writeFile(resolve(assets, 'index.html'), await readFile(resolve(root, 'src/index.html')))
await writeFile(resolve(assets, 'LICENSE'), await readFile(resolve(root, 'vendor/LICENSE')))
// Include the actual browser graph, including libraries embedded inside ExcelJS.
// Node-only ExcelJS helpers are neither executable nor redistributed in this APK.
const packageName = source => source.match(/(?:^|\/)node_modules\/((?:@[^/]+\/)?[^/]+)/)?.[1]
const excelMap = JSON.parse(await readFile(resolve(root, 'node_modules/exceljs/dist/exceljs.min.js.map'), 'utf8'))
const bundledNames = new Set([...Object.keys(worker.metafile.inputs), ...Object.keys(bundle.metafile.inputs), ...excelMap.sources]
  .map(packageName).filter(Boolean))
const lock = JSON.parse(await readFile(resolve(root, 'package-lock.json'), 'utf8'))
const directories = new Set(Object.entries(lock.packages)
  .filter(([name, value]) => name.startsWith('node_modules/') && !value.dev)
  .map(([name]) => name))
const notices = []
for (const directory of [...directories].sort()) {
  const absolute = resolve(root, directory)
  const info = JSON.parse(await readFile(resolve(absolute, 'package.json'), 'utf8'))
  if (!bundledNames.has(info.name)) continue
  if (info.name === '@fortune-sheet/core' || info.name === '@fortune-sheet/react') {
    notices.push(`${info.name}@${info.version}\n${await readFile(resolve(root, 'vendor/FortuneSheet-LICENSE'), 'utf8')}`)
    continue
  }
  if (info.name === '@nodable/entities') {
    notices.push(`${info.name}@${info.version}\n${await readFile(resolve(root, 'vendor/Nodable-LICENSE'), 'utf8')}`)
    continue
  }
  if (info.name === 'saxes') {
    notices.push(`${info.name}@${info.version}\n${await readFile(resolve(root, 'vendor/Saxes-LICENSE'), 'utf8')}`)
    continue
  }
  if (info.name === 'isarray' && info.version === '1.0.0') {
    const readme = await readFile(resolve(absolute, 'README.md'), 'utf8')
    const start = readme.indexOf('## License')
    if (start < 0) throw new Error('Missing embedded isarray license')
    notices.push(`${info.name}@${info.version}\n${readme.slice(start)}`)
    continue
  }
  const licenses = (await readdir(absolute)).filter(name => /^(?:licen[cs]e|notice|copying)(?:[.-]|$)/i.test(name))
  if (licenses.length === 0) throw new Error(`Missing bundled license: ${info.name}`)
  for (const license of licenses) notices.push(`${info.name}@${info.version}\n${await readFile(resolve(absolute, license), 'utf8')}`)
}
// Retain copyright/license notices of the browser bundle's embedded polyfills,
// including development dependencies not installed in ExcelJS's runtime tree.
for (let index = 0; index < excelMap.sources.length; index++) {
  const source = excelMap.sources[index]
  if (!packageName(source)) continue
  const comments = excelMap.sourcesContent[index]?.match(/\/\*[\s\S]*?\*\/|(?:\/\/[^\n]*(?:\n|$))+/g) ?? []
  const legal = comments.filter(comment => /copyright|@license|@preserve|licensed under/i.test(comment))
  if (legal.length) notices.push(`${source}\n${legal.join('\n')}`)
}
await writeFile(resolve(assets, 'THIRD_PARTY_NOTICES'), notices.join('\n\n---\n\n'))
const names = (await readdir(assets)).filter(name => /\.(?:js|css|html)$/.test(name)).sort()
await writeFile(resolve(assets, 'manifest.json'), JSON.stringify(names) + '\n')
