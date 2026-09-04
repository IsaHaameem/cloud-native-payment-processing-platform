import Link from 'next/link';

import { Reveal, RevealItem } from '@/components/marketing/reveal';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

export interface HeroAction {
  readonly label: string;
  readonly href: string;
  readonly variant?: 'primary' | 'secondary';
}

/**
 * The opening block of a public page (marketing build).
 *
 * A single component so every marketing page opens on the same rhythm: mono eyebrow, a
 * `-0.03em` display headline that tops out at 60px, a `body-lg` lede, and one accented action
 * beside one quiet one. Pages that need a figure beside the copy pass it as `aside`.
 */
export function MarketingHero({
  eyebrow,
  title,
  lede,
  actions = [],
  aside,
  className,
}: {
  eyebrow: string;
  title: string;
  lede: string;
  actions?: readonly HeroAction[] | undefined;
  aside?: React.ReactNode | undefined;
  className?: string | undefined;
}) {
  return (
    <section className={cn('border-b border-border-subtle px-5 py-16 sm:px-8 sm:py-20', className)}>
      <div
        className={cn(
          'mx-auto flex w-full max-w-6xl flex-col gap-10',
          aside && 'lg:flex-row lg:items-center',
        )}
      >
        <Reveal className={cn('min-w-0', aside && 'lg:flex-1')} stagger>
          <RevealItem>
            <p className="font-mono text-caption tracking-[0.12em] text-fg-subtle uppercase">
              {eyebrow}
            </p>
          </RevealItem>
          <RevealItem>
            <h1 className="mt-3.5 max-w-[20ch] text-[2.125rem] leading-[1.04] font-[510] tracking-[-0.03em] text-balance text-fg sm:text-[2.75rem] lg:text-[3.5rem]">
              {title}
            </h1>
          </RevealItem>
          <RevealItem>
            <p className="mt-5 max-w-[56ch] text-body-lg text-pretty text-fg-subtle">{lede}</p>
          </RevealItem>
          {actions.length > 0 ? (
            <RevealItem>
              <div className="mt-7 flex flex-wrap gap-3">
                {actions.map((action) => (
                  <Button
                    key={action.href}
                    variant={action.variant ?? 'secondary'}
                    size="lg"
                    asChild
                  >
                    <Link href={action.href}>{action.label}</Link>
                  </Button>
                ))}
              </div>
            </RevealItem>
          ) : null}
        </Reveal>

        {aside ? (
          <Reveal className="min-w-0 lg:flex-1">
            <div>{aside}</div>
          </Reveal>
        ) : null}
      </div>
    </section>
  );
}
