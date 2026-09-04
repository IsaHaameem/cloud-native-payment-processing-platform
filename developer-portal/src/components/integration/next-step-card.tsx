import { ArrowRight } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import Link from 'next/link';
import * as React from 'react';

import { Card, CardContent } from '@/components/ui/card';
import { cn } from '@/lib/utils';

/**
 * "What should I do next?" — the single recommended action after a milestone.
 *
 * The whole card is the link. There is exactly one call to action; anything else that could be
 * done next goes elsewhere on the page, quieter. Server-rendered — it never needs state.
 */
export function NextStepCard({
  eyebrow = 'Next',
  title,
  body,
  href,
  cta,
  icon: Icon,
  className,
}: {
  eyebrow?: string;
  title: string;
  body: React.ReactNode;
  href: string;
  cta: string;
  icon?: LucideIcon;
  className?: string | undefined;
}) {
  return (
    <Link href={href} className={cn('block', className)}>
      <Card interactive className="h-full">
        <CardContent className="flex items-start gap-3.5 pt-5">
          {Icon ? (
            <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-md bg-surface-inset text-fg-subtle ring-hairline">
              <Icon aria-hidden className="size-4" />
            </span>
          ) : null}
          <div className="min-w-0 flex-1">
            <p className="text-caption font-[510] tracking-[0.08em] text-fg-faint uppercase">
              {eyebrow}
            </p>
            <p className="mt-1 text-label font-[510] text-fg">{title}</p>
            <p className="mt-1 text-label text-fg-subtle">{body}</p>
            <span className="mt-3 inline-flex items-center gap-1 text-label-sm font-[510] text-accent-text">
              {cta} <ArrowRight className="size-3.5" />
            </span>
          </div>
        </CardContent>
      </Card>
    </Link>
  );
}
