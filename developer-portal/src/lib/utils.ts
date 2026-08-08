import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Merges class names, with later Tailwind utilities beating earlier ones of the same kind.
 *
 * Plain `clsx` would leave `px-3 px-4` in the DOM and let CSS source order decide, which makes
 * a component's `className` prop unreliable as an override — the caller's intent wins only by
 * luck. Every primitive in `components/ui` takes `className` and merges it through here, so
 * "pass a class to adjust it" is a promise rather than a coincidence.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
