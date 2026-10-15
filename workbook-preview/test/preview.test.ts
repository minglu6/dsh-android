import test from 'node:test'
import assert from 'node:assert/strict'
import { strToU8, strFromU8, zipSync, unzipSync } from 'fflate'
import { convertExcel } from '../vendor/excel/convert.ts'
import { checkArchiveBudget, cellAreaOfReferences } from '../src/archive-guard.ts'
import { limits } from '../src/contract.ts'
import { excelFixture, excelHtmlFixture, excelHtmlText, meetingMinutesFixture } from './fixtures/excel-fixture.ts'
import { excelDrawingFixture } from './fixtures/excel-drawing-fixture.ts'
import { xlsFixture } from './fixtures/xls-fixture.ts'

test('real XLSX keeps sheets, merges, formatting, cached false/zero and missing results', async () => {
  const value = await convertExcel(await excelFixture(), 'xlsx', limits)
  assert.deepEqual(value.sheets.map(sheet => sheet.name), ['季度预算', '公式与格式', '隐藏页'])
  assert.equal(value.sheets[0].config?.merge?.['0_0'].cs, 4)
  const cells = value.sheets[1].celldata!
  assert.equal(cells.find(cell => cell.r === 0 && cell.c === 0)?.v?.v, 42)
  assert.equal(cells.find(cell => cell.r === 0 && cell.c === 5)?.v?.v, 0)
  assert.equal(cells.find(cell => cell.r === 1 && cell.c === 5)?.v?.v, false)
  assert.equal(cells.find(cell => cell.r === 1 && cell.c === 0)?.v?.v, undefined)
  assert.ok(value.missingResults > 0)
  assert.equal(value.sheets[2].hide, 1)
  assert.equal(value.sheets[0].celldata?.find(cell => cell.r === 2 && cell.c === 3)?.v?.m, '80.0%')
})

test('real legacy XLS preserves saved BIFF formulas and hidden sheets', async () => {
  const value = await convertExcel(xlsFixture(), 'xls', limits)
  assert.deepEqual(value.sheets.map(sheet => sheet.name), ['预算', '明细', '隐藏'])
  assert.equal(value.sheets[0].config?.merge?.['0_0'].cs, 3)
  const formula = value.sheets[0].celldata?.find(cell => cell.r === 3 && cell.c === 1)?.v
  assert.equal(formula?.v, 3)
  assert.equal(formula?.f, '=1+2')
  assert.equal(value.sheets[2].hide, 1)
})

test('unsupported content reports desktop categories without losing supported data', async () => {
  const value = await convertExcel(await excelDrawingFixture(), 'xlsx', limits)
  assert.deepEqual(value.unsupportedFeatures, ['charts', 'conditionalFormatting'])
  assert.equal(value.sheets[0].celldata?.find(cell => cell.r === 0 && cell.c === 0)?.v?.v, 'DeepSeek · 项目预算')
})

test('HTML-looking text stays literal and formulas are never evaluated', async () => {
  const value = await convertExcel(await excelHtmlFixture(), 'xlsx', limits)
  assert.equal(value.sheets[1].celldata?.[0].v?.v, excelHtmlText)
  assert.equal(value.sheets[2].celldata?.[0].v?.v, excelHtmlText)
  const meeting = await convertExcel(await meetingMinutesFixture(), 'xlsx', limits)
  assert.ok(meeting.missingResults > 20)
  for (const sheet of meeting.sheets) for (const cell of sheet.celldata ?? []) {
    if (cell.v?.f !== undefined) assert.equal(cell.v.v, undefined)
  }
})

test('source and combined cell budgets apply to both real formats', async () => {
  const xlsx = await excelFixture()
  await assert.rejects(convertExcel(xlsx, 'xlsx', { ...limits, maxBytes: 8 }), /tooLarge/)
  await assert.rejects(convertExcel(xlsx, 'xlsx', { ...limits, maxCells: 4 }), /tooLarge/)
  await assert.rejects(convertExcel(xlsFixture(), 'xls', { ...limits, maxCells: 4 }), /tooLarge/)
  await assert.rejects(convertExcel(strToU8('<html>not XLS</html>'), 'xls', limits), /invalid/)
})

test('ZIP budgets reject expansion bombs, aliases and traversal before decompression', () => {
  assert.throws(() => checkArchiveBudget(zipSync({ huge: new Uint8Array(2 * 1024 * 1024) }), limits.maxBytes), /tooLarge/)
  assert.throws(() => checkArchiveBudget(zipSync({ 'a.xml': strToU8('x'), 'A.xml': strToU8('y') }), limits.maxBytes), /invalid/)
  assert.throws(() => checkArchiveBudget(zipSync({ '../outside.xml': strToU8('x') }), limits.maxBytes), /invalid/)
  assert.throws(() => checkArchiveBudget(new Uint8Array(4), limits.maxBytes), /invalid/)
  assert.throws(() => cellAreaOfReferences(['A1:XFD1048576'], limits.maxCells), /tooLarge/)
})

test('hostile worksheet merges and entities fail before ExcelJS expansion', async () => {
  const files = unzipSync(await excelFixture())
  const original = strFromU8(files['xl/worksheets/sheet1.xml'])
  files['xl/worksheets/sheet1.xml'] = strToU8(original.replace('A1:D1', 'A1:XFD1048576'))
  await assert.rejects(convertExcel(zipSync(files), 'xlsx', limits), /tooLarge/)
  files['xl/worksheets/sheet1.xml'] = strToU8(original.replace('<worksheet', '<!DOCTYPE worksheet [<!ENTITY x "bad">]><worksheet'))
  await assert.rejects(convertExcel(zipSync(files), 'xlsx', limits), /invalid/)
})
