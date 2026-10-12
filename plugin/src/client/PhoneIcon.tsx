import type { JSX } from 'react'

/** Matches dsh ui-primitives Medium outline icons: 16×16 viewBox, 1.3 stroke. */
export const ICON_MEDIUM_STROKE = 1.3

/**
 * Smartphone outline glyph for the Settings nav row.
 * dsh's icon set has no phone/smartphone export; this follows the same
 * Medium outline conventions (size/stroke/currentColor) used by
 * IconSettingsOutlineMedium and neighbors.
 */
export function PhoneIcon({
  size = 16,
  className,
  strokeWidth = ICON_MEDIUM_STROKE,
}: {
  size?: number
  className?: string
  strokeWidth?: number
}): JSX.Element {
  return (
    <svg
      width={size}
      height={size}
      className={className}
      viewBox="0 0 16 16"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
      strokeWidth={strokeWidth}
    >
      <rect x="4.75" y="1.75" width="6.5" height="12.5" rx="1.25" stroke="currentColor" />
      <path d="M7 12.25h2" stroke="currentColor" strokeLinecap="round" />
    </svg>
  )
}

/** Markup used when swapping the shell's default gear in the nav cell. */
export function phoneIconSvgHtml(className: string): string {
  return (
    `<svg width="16" height="16" class="${className}" viewBox="0 0 16 16" fill="none" ` +
    `xmlns="http://www.w3.org/2000/svg" aria-hidden="true" stroke-width="${ICON_MEDIUM_STROKE}" ` +
    `data-pocket-icon="1">` +
    `<rect x="4.75" y="1.75" width="6.5" height="12.5" rx="1.25" stroke="currentColor"></rect>` +
    `<path d="M7 12.25h2" stroke="currentColor" stroke-linecap="round"></path>` +
    `</svg>`
  )
}
