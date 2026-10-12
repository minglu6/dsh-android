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
  return svg
    .replace(/width="[^"]+"/, `width="${size}"`)
    .replace(/height="[^"]+"/, `height="${size}"`)
}
