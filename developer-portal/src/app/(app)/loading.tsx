import { Skeleton } from '@/components/ui/skeleton';

/**
 * The loading UI every page under `(app)` inherits (M23.1, redesigned).
 *
 * Shaped like a page — a heading block, then content — rather than a spinner, so nothing moves
 * when the real thing arrives. Individual routes override this with a skeleton matching their
 * own geometry; this is the floor, and the floor is still a layout rather than a wait.
 */
export default function AppLoading() {
  return (
    <div>
      <div className="space-y-2 pb-8">
        <Skeleton className="h-5 w-40" />
        <Skeleton className="h-4 w-72" />
      </div>
      <div className="grid gap-4 grid-cols-1 sm:grid-cols-2 lg:grid-cols-3">
        <Skeleton className="h-28 rounded-lg" />
        <Skeleton className="h-28 rounded-lg" />
        <Skeleton className="h-28 rounded-lg" />
      </div>
      <span className="sr-only" role="status">
        Loading
      </span>
    </div>
  );
}
