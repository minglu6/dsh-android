import { renderSVG } from 'uqr'

/** Render a pairing URI as an SVG QR string. */
export function qrSvg(text: string, size = 180): string {
  const svg = renderSVG(text, {
    ecc: 'M',
    pixelSize: 8,
    border: 2,
    blackColor: '#111',
    whiteColor: '#fff',
  })
  // uqr emits viewBox-only <svg>; pin display size on the root element (do not
  // rewrite the white <rect width/height>, which share the same attribute names).
  return svg.replace('<svg ', `<svg width="${size}" height="${size}" `)
}
