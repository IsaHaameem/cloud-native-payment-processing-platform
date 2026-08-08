'use client';

import * as DialogPrimitive from '@radix-ui/react-dialog';
import { VisuallyHidden } from '@radix-ui/react-visually-hidden';
import { AnimatePresence, motion } from 'framer-motion';
import { CornerDownLeft, Search } from 'lucide-react';
import * as React from 'react';

import { dialogVariants, duration, ease, resultsVariants, scrimVariants } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * The command palette (M23.1 redesign) — ⌘K.
 *
 * ── Why this is hand-built rather than a library ──────────────────────────────────────
 *
 * The behaviour is thirty lines and the dependency would be permanent. What it does need is
 * Radix's dialog underneath, for the focus trap and the scroll lock, because a palette that
 * lets `Tab` wander into the page behind it is worse than no palette.
 *
 * ── The interaction rules, each of which is a way palettes go wrong ───────────────────
 *
 * 1. **The list never staggers.** It re-renders on every keystroke, and a stagger turns each
 *    character typed into a little loading animation. Results fade in together, at once.
 * 2. **The highlight is a shared layout animation**, so it slides between rows instead of
 *    blinking. This is the motion that makes a palette feel like Raycast rather than a `<select>`.
 * 3. **Arrow keys wrap**, and the active row scrolls itself into view. A palette that dead-ends
 *    at the last result, or highlights something off-screen, is one a keyboard user abandons.
 * 4. **Selection is by index, not by hover.** Moving the mouse across the list while typing must
 *    not steal the highlight from the keyboard.
 * 5. **Opening resets the query.** A palette that reopens with the last search is a palette that
 *    shows stale results at the moment of highest expectation.
 *
 * ── Accessibility ─────────────────────────────────────────────────────────────────────
 *
 * The input owns `role="combobox"` with `aria-expanded`, `aria-controls` and
 * `aria-activedescendant`; the list is a `listbox` of `option`s. Focus never leaves the input —
 * which is what lets the user keep typing while the highlight moves, and is why the active
 * option is announced by id rather than by focus.
 */

export interface CommandItem {
  readonly id: string;
  readonly label: string;
  readonly group: string;
  readonly icon?: React.ComponentType<{ className?: string }> | undefined;
  /** Extra words the query should match — an alias, a route, an object prefix. */
  readonly keywords?: readonly string[] | undefined;
  readonly shortcut?: string | undefined;
  readonly onSelect: () => void;
}

export function CommandPalette({
  open,
  onOpenChange,
  items,
  placeholder = 'Search or jump to…',
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  items: readonly CommandItem[];
  placeholder?: string | undefined;
}) {
  const [query, setQuery] = React.useState('');
  const [activeIndex, setActiveIndex] = React.useState(0);
  const listRef = React.useRef<HTMLDivElement>(null);

  // Rule 5: a palette that reopens holding the last query shows stale results at exactly the
  // moment the user is most certain about what they want.
  React.useEffect(() => {
    if (open) {
      setQuery('');
      setActiveIndex(0);
    }
  }, [open]);

  const matches = React.useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return items;
    return items.filter((item) => {
      const haystack = [item.label, item.group, ...(item.keywords ?? [])].join(' ').toLowerCase();
      // Subsequence match, not `includes` — "pyk" should find "Payments · API keys", which is
      // the shorthand people actually type once they know a palette is there.
      let cursor = 0;
      for (const char of q) {
        cursor = haystack.indexOf(char, cursor);
        if (cursor === -1) return false;
        cursor += 1;
      }
      return true;
    });
  }, [items, query]);

  // Clamp rather than reset: as results shrink, the highlight should stay as close to where the
  // user left it as the new list allows.
  const active = matches.length === 0 ? -1 : Math.min(activeIndex, matches.length - 1);

  React.useEffect(() => {
    if (active < 0) return;
    listRef.current
      ?.querySelector(`[data-index="${active}"]`)
      ?.scrollIntoView({ block: 'nearest' });
  }, [active]);

  const grouped = React.useMemo(() => {
    const map = new Map<string, Array<{ item: CommandItem; index: number }>>();
    matches.forEach((item, index) => {
      const bucket = map.get(item.group) ?? [];
      bucket.push({ item, index });
      map.set(item.group, bucket);
    });
    return [...map.entries()];
  }, [matches]);

  function onKeyDown(event: React.KeyboardEvent) {
    // Escape is Radix's, through the dismissable layer. Only the keys the listbox owns are
    // handled here.
    if (event.key === 'ArrowDown' || (event.key === 'n' && event.ctrlKey)) {
      event.preventDefault();
      setActiveIndex((i) => (matches.length === 0 ? 0 : (i + 1) % matches.length));
    } else if (event.key === 'ArrowUp' || (event.key === 'p' && event.ctrlKey)) {
      event.preventDefault();
      setActiveIndex((i) => (matches.length === 0 ? 0 : (i - 1 + matches.length) % matches.length));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const chosen = matches[active];
      if (chosen) {
        onOpenChange(false);
        chosen.onSelect();
      }
    }
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <AnimatePresence>
        {open ? (
          <DialogPrimitive.Portal forceMount>
            <DialogPrimitive.Overlay asChild forceMount>
              <motion.div
                className="fixed inset-0 z-50 bg-black/40 backdrop-blur-[20px]"
                variants={scrimVariants}
                initial="hidden"
                animate="visible"
                exit="hidden"
              />
            </DialogPrimitive.Overlay>

            <DialogPrimitive.Content asChild forceMount>
              <motion.div
                className={cn(
                  'gpu fixed top-[18vh] left-1/2 z-50 w-full max-w-xl -translate-x-1/2 px-4',
                  'sm:px-0',
                )}
                variants={dialogVariants}
                initial="hidden"
                animate="visible"
                exit="hidden"
              >
                <VisuallyHidden>
                  <DialogPrimitive.Title>Command palette</DialogPrimitive.Title>
                  <DialogPrimitive.Description>
                    Search for a page or an object, then press Enter.
                  </DialogPrimitive.Description>
                </VisuallyHidden>

                <div className="overflow-hidden rounded-xl bg-surface-elevated shadow-(--shadow-overlay)">
                  <div className="flex items-center gap-2.5 border-b border-border-subtle px-4">
                    <Search aria-hidden className="size-4 shrink-0 text-fg-subtle" />
                    <input
                      autoFocus
                      role="combobox"
                      aria-expanded
                      aria-controls="command-palette-list"
                      aria-activedescendant={active >= 0 ? `command-option-${active}` : undefined}
                      aria-label="Search"
                      value={query}
                      placeholder={placeholder}
                      onChange={(e) => {
                        setQuery(e.target.value);
                        setActiveIndex(0);
                      }}
                      onKeyDown={onKeyDown}
                      className={cn(
                        'h-12 w-full bg-transparent text-body-lg text-fg outline-none',
                        'placeholder:text-fg-subtle',
                      )}
                    />
                    <kbd className="hidden shrink-0 rounded-sm bg-surface-active px-1.5 py-0.5 text-caption font-[510] text-fg-subtle sm:block">
                      ESC
                    </kbd>
                  </div>

                  <motion.div
                    id="command-palette-list"
                    role="listbox"
                    aria-label="Results"
                    ref={listRef}
                    variants={resultsVariants}
                    initial="hidden"
                    animate="visible"
                    key={query}
                    className="max-h-[min(24rem,50vh)] overflow-y-auto overscroll-contain p-1.5"
                  >
                    {matches.length === 0 ? (
                      <p className="px-3 py-8 text-center text-body text-fg-subtle">
                        No results for &ldquo;{query}&rdquo;
                      </p>
                    ) : (
                      grouped.map(([group, entries]) => (
                        <div key={group} className="mb-1 last:mb-0">
                          <p className="px-2.5 py-1.5 text-caption font-[510] tracking-[0.04em] text-fg-subtle uppercase">
                            {group}
                          </p>
                          {entries.map(({ item, index }) => {
                            const Icon = item.icon;
                            const isActive = index === active;
                            return (
                              <div
                                key={item.id}
                                id={`command-option-${index}`}
                                data-index={index}
                                role="option"
                                aria-selected={isActive}
                                onMouseMove={() => setActiveIndex(index)}
                                onClick={() => {
                                  onOpenChange(false);
                                  item.onSelect();
                                }}
                                className={cn(
                                  'relative flex cursor-pointer items-center gap-2.5 rounded-md px-2.5 py-2',
                                  'text-label text-fg-muted select-none',
                                  isActive && 'text-fg',
                                )}
                              >
                                {isActive ? (
                                  // Rule 2: the highlight travels between rows rather than
                                  // blinking off and on. One prop, entirely on the compositor.
                                  <motion.span
                                    aria-hidden
                                    layoutId="command-active"
                                    className="absolute inset-0 rounded-md bg-surface-hover"
                                    transition={{ duration: duration.fast, ease: ease.outQuart }}
                                  />
                                ) : null}
                                {Icon ? (
                                  <Icon
                                    className={cn(
                                      'relative size-4 shrink-0',
                                      isActive ? 'text-fg' : 'text-fg-subtle',
                                    )}
                                  />
                                ) : null}
                                <span className="relative flex-1 truncate">{item.label}</span>
                                {item.shortcut ? (
                                  <kbd className="relative rounded-sm bg-surface-active px-1.5 py-0.5 text-caption font-[510] text-fg-subtle">
                                    {item.shortcut}
                                  </kbd>
                                ) : null}
                                {isActive ? (
                                  <CornerDownLeft
                                    aria-hidden
                                    className="relative size-3.5 shrink-0 text-fg-subtle"
                                  />
                                ) : null}
                              </div>
                            );
                          })}
                        </div>
                      ))
                    )}
                  </motion.div>
                </div>
              </motion.div>
            </DialogPrimitive.Content>
          </DialogPrimitive.Portal>
        ) : null}
      </AnimatePresence>
    </DialogPrimitive.Root>
  );
}

/**
 * Binds ⌘K / Ctrl-K.
 *
 * Ignores the shortcut while the user is typing in a field — a palette that hijacks ⌘K inside a
 * search box is a palette that fights the page it is meant to serve.
 */
export function useCommandShortcut(onOpen: () => void) {
  React.useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key.toLowerCase() !== 'k' || !(event.metaKey || event.ctrlKey)) return;
      const target = event.target as HTMLElement | null;
      const tag = target?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || target?.isContentEditable) return;
      event.preventDefault();
      onOpen();
    }
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onOpen]);
}
