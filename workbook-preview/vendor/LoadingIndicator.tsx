// DeepSeek Harness LoadingIndicator + ongoing StateDot branch (MIT).
import css from './LoadingIndicator.module.css'
import dot from './primitives/StateDot.module.css'

function syncSpinner(element: SVGSVGElement | null): void {
  if (element === null) return
  const pin = () => {
    for (const animation of element.getAnimations?.({ subtree: true }) ?? []) animation.startTime = 0
  }
  pin()
  element.addEventListener('animationstart', pin)
}

export function LoadingIndicator({ label }: { label: string }) {
  return <span className={css.loading} role="status" aria-label={label} data-document-loading>
    <svg ref={syncSpinner} className={dot.spinner} data-state="ongoing" width={28} height={28} viewBox="0 0 24 24" aria-hidden="true">
      <g className={dot.spinnerMotion}>
        <circle className={dot.spinnerTrack} cx="12" cy="12" r="9.5" />
        <circle className={dot.spinnerArc} cx="12" cy="12" r="9.5" />
      </g>
    </svg>
    <span>{label}</span>
  </span>
}
