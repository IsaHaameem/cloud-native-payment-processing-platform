import { cn } from '@/lib/utils';

/**
 * A loading placeholder shaped like the thing it replaces (M23.1, redesigned).
 *
 * **A shimmer, not a pulse.** A sweep travelling across the placeholder reads as "this is
 * loading"; an opacity pulse reads as "this is broken" or, worse, as a disabled control. The
 * sweep is a `transform` on a pseudo-element, so it composites on the GPU and costs nothing per
 * frame — see `.shimmer` in globals.css.
 *
 * The rule this exists to make cheap: skeletons match the final layout's geometry, so nothing
 * moves when data arrives. A spinner over a full page is never the answer — it says "something
 * is happening" and nothing about what.
 */
export function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div aria-hidden className={cn('shimmer rounded-md', className)} {...props} />;
}
