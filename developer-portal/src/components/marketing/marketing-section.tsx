import { Reveal } from '@/components/marketing/reveal';
import { cn } from '@/lib/utils';

/**
 * One band of a public page (marketing build).
 *
 * The vertical rhythm lives here rather than at each call site, because a marketing page whose
 * sections are 96px, 112px and 80px apart is the single most reliable way to make an otherwise
 * careful design read as assembled by hand. Every public page composes these, so spacing,
 * max-width and the eyebrow/title/lede hierarchy are decided once.
 *
 * The eyebrow is the reference's mono `caption` role — 10px, wide tracking, `fg-subtle` — the
 * same label the dashboard uses above a metric. The title tops out at `text-title-1` (40px):
 * the large display sizes are the marketing surface's, but they still come from the token scale.
 */
export function MarketingSection({
  id,
  eyebrow,
  title,
  lede,
  children,
  className,
  center = false,
  bordered = true,
}: {
  id?: string | undefined;
  eyebrow?: string | undefined;
  title?: string | undefined;
  lede?: string | undefined;
  children?: React.ReactNode | undefined;
  className?: string | undefined;
  center?: boolean | undefined;
  bordered?: boolean | undefined;
}) {
  return (
    <section
      id={id}
      className={cn(
        'scroll-mt-20 px-5 py-16 sm:px-8 sm:py-20',
        bordered && 'border-b border-border-subtle',
        className,
      )}
    >
      <div className={cn('mx-auto w-full max-w-6xl', center && 'text-center')}>
        {eyebrow || title || lede ? (
          <Reveal className={cn('mb-10 max-w-2xl', center && 'mx-auto')}>
            {eyebrow ? (
              <p className="font-mono text-caption tracking-[0.12em] text-fg-subtle uppercase">
                {eyebrow}
              </p>
            ) : null}
            {title ? (
              <h2 className="mt-3 text-[1.75rem] leading-[1.15] font-[510] tracking-[-0.02em] text-balance text-fg sm:text-[2rem]">
                {title}
              </h2>
            ) : null}
            {lede ? <p className="mt-3 text-body-lg text-pretty text-fg-subtle">{lede}</p> : null}
          </Reveal>
        ) : null}
        {children}
      </div>
    </section>
  );
}
