import { ExcelPreviewError } from '../vendor/excel/error.ts'

/** Reject ZIP resource bombs before fflate or ExcelJS allocate decompressed parts. */
export function checkArchiveBudget(bytes: Uint8Array, maxBytes: number): void {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let end = bytes.length - 22
  for (; end >= Math.max(0, bytes.length - 65557); end--) {
    if (view.getUint32(end, true) === 0x06054b50 && end + 22 + view.getUint16(end + 20, true) === bytes.length) break
  }
  if (end < 0 || end < bytes.length - 65557) throw new ExcelPreviewError('invalid')
  const count = view.getUint16(end + 10, true)
  const directorySize = view.getUint32(end + 12, true)
  let offset = view.getUint32(end + 16, true)
  if (view.getUint32(end + 4, true) !== 0 || view.getUint16(end + 8, true) !== count || count === 65535 || count > 4096) throw new ExcelPreviewError('tooLarge')
  if (offset + directorySize !== end) throw new ExcelPreviewError('invalid')
  let total = 0
  const names = new Set<string>()
  for (let index = 0; index < count; index++) {
    if (offset + 46 > end || view.getUint32(offset, true) !== 0x02014b50) throw new ExcelPreviewError('invalid')
    const flags = view.getUint16(offset + 8, true)
    const method = view.getUint16(offset + 10, true)
    const compressed = view.getUint32(offset + 20, true)
    const expanded = view.getUint32(offset + 24, true)
    const nameLength = view.getUint16(offset + 28, true)
    const extra = view.getUint16(offset + 30, true)
    const comment = view.getUint16(offset + 32, true)
    const local = view.getUint32(offset + 42, true)
    const next = offset + 46 + nameLength + extra + comment
    if (next > end || flags & 1 || ![0, 8].includes(method) || local + 30 > bytes.length) throw new ExcelPreviewError('invalid')
    const name = new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(offset + 46, offset + 46 + nameLength)).toLowerCase()
    if (names.has(name) || name.includes('\\') || name.startsWith('/') || name.split('/').includes('..')) throw new ExcelPreviewError('invalid')
    names.add(name)
    total += expanded
    if (expanded > maxBytes || total > maxBytes * 4 || (compressed > 0 && expanded > Math.max(1024 * 1024, compressed * 200))) throw new ExcelPreviewError('tooLarge')
    if (view.getUint32(local, true) !== 0x04034b50 || view.getUint16(local + 6, true) !== flags || view.getUint16(local + 8, true) !== method) throw new ExcelPreviewError('invalid')
    const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true)
    if (start + compressed > view.getUint32(end + 16, true)) throw new ExcelPreviewError('invalid')
    // Data descriptors permit zero sizes locally; otherwise both headers must agree.
    if (!(flags & 8) && (view.getUint32(local + 18, true) !== compressed || view.getUint32(local + 22, true) !== expanded)) throw new ExcelPreviewError('invalid')
    offset = next
  }
  if (offset !== end) throw new ExcelPreviewError('invalid')
}

/** Bound worksheet rows/merges before ExcelJS expands sparse and merged ranges. */
export function cellAreaOfReferences(references: Iterable<string>, maxCells: number): number {
  let rows = 1
  let columns = 1
  for (const reference of references) {
    for (const address of reference.split(':')) {
      const match = /^\$?([A-Z]{1,3})\$?([1-9][0-9]{0,6})$/i.exec(address)
      if (!match) throw new ExcelPreviewError('invalid')
      let column = 0
      for (const char of match[1].toUpperCase()) column = column * 26 + char.charCodeAt(0) - 64
      rows = Math.max(rows, Number(match[2]))
      columns = Math.max(columns, column)
      if (rows * columns > maxCells) throw new ExcelPreviewError('tooLarge')
    }
  }
  return rows * columns
}
