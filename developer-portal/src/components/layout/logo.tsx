import { cn } from '@/lib/utils';

/**
 * The mark (M23.1, redesigned).
 *
 * Drawn rather than imported: an inline SVG costs no request, inherits `currentColor` so it
 * follows the theme without a second asset, and stays crisp at the 20px the collapsed rail
 * renders it at. Two overlapping rounded strokes suggesting a transfer — geometric, not
 * illustrative, which is the register the reference sits in.
 *
 * The mark takes the brand indigo; the wordmark does not. The reference's rule is "use the
 * primary color only for the single most important action per screen", and a logo that competes
 * with the page's one CTA is exactly what that rule exists to prevent — so the colour is
 * confined to the glyph and the text stays foreground.
 */
export function LogoMark({ className }: { className?: string | undefined }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden
      className={cn('size-[18px] text-accent', className)}
    >
      <path
        d="M4 8.5h11a3.5 3.5 0 0 1 0 7H9"
        stroke="currentColor"
        strokeWidth="2.2"
        strokeLinecap="round"
      />
      <path
        d="m12 5 3.2 3.5L12 12"
        stroke="currentColor"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
        opacity="0.5"
      />
      <path
        d="M20 15.5H9l3.2 3.5"
        stroke="currentColor"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
        opacity="0.5"
      />
    </svg>
  );
}

export function Wordmark({ className }: { className?: string | undefined }) {
  return (
    <span className={cn('flex items-center gap-2', className)}>
      <LogoMark />
      <span className="text-label font-[510] tracking-[-0.13px] text-fg">PaymentFlow</span>
    </span>
  );
}
