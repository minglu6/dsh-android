import type { ReactNode } from 'react'
import type { ExcelFormat } from '../vendor/excel/format.ts'
import type { ExcelLimits } from '../vendor/excel/model.ts'
import type { ExcelPreviewKey } from '../vendor/excel/locales.ts'
export const limits: ExcelLimits = Object.freeze({ maxBytes: 16 * 1024 * 1024, maxCells: 250_000, timeoutMs: 15_000 })
export interface LoadedExcelBodyProps {
  content: { kind: 'bytes'; data: Uint8Array<ArrayBuffer> }
  format: ExcelFormat
  limits: ExcelLimits
  t: (key: ExcelPreviewKey, args?: { features: string }) => string
  loading: ReactNode
}
