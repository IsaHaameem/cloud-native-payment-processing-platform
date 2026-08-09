'use client';

import { usePathname } from 'next/navigation';
import * as React from 'react';

/**
 * Moving focus to the new page after a client-side navigation (M23.3).
 *
 * ── The problem this fixes is invisible to anyone using a mouse ───────────────────────
 *
 * A full page load resets focus to the document and announces the new title. A client-side
 * navigation does neither: React swaps the subtree, the URL changes, and as far as a screen
 * reader is concerned nothing happened. Focus stays wherever it was — usually on the sidebar link
 * that was just clicked — so the next Tab continues through the *navigation* rather than into
 * the content that was just requested, and nothing is announced at all.
 *
 * `page-header.tsx` was built for this in M23.1: it renders exactly one `<h1 tabIndex={-1}>` per
 * page, in a predictable place, and its own note says route-change focus management arrives here.
 *
 * ── Why the heading and not the `<main>` landmark ─────────────────────────────────────
 *
 * Focusing the region announces "main" and then reads the whole page. Focusing the heading
 * announces the page's name and leaves the user at the top of the content, which is where a full
 * page load would have left them. `tabIndex={-1}` makes it programmatically focusable without
 * adding it to the tab order.
 *
 * ── The first render is deliberately skipped ──────────────────────────────────────────
 *
 * On initial load the browser has already done the right thing, and stealing focus would move it
 * away from wherever the user actually is — including out of the address bar, mid-keystroke, for
 * someone who typed the URL. Only *changes* of pathname move focus.
 *
 * A query-string change is not a navigation for this purpose either: filters and cursor pages
 * change `?…` constantly (D191 keeps that state in the URL), and yanking focus to the heading
 * every time someone types in a search box would make the page unusable. `usePathname` excludes
 * the query string, which is exactly the behaviour wanted.
 *
 * ── It waits for the heading rather than guessing when it will exist ──────────────────
 *
 * The first version focused after one animation frame, on the reasoning that the swap and the
 * effect share a commit so the next paint would have the new page. Measured, that is false:
 * `main h1` was still absent a frame after the pathname changed, because `PageTransition`'s
 * `AnimatePresence` has removed the outgoing page and React has not yet committed the incoming
 * one. Focus never moved, silently, which is exactly how an accessibility feature ends up
 * shipped and broken.
 *
 * So it observes instead. Any heading that appears inside `main` is the new page's, and the
 * outgoing one — which may still be mounted when this runs — is remembered and skipped so focus
 * never lands on an element that is about to be removed. The wait is bounded: after a second,
 * whatever is happening is not a page arriving, and focus stays where the user left it.
 *
 * ── And it keeps watching, because one focus call is not enough ───────────────────────
 *
 * Also measured rather than assumed. `PageTransition` runs `AnimatePresence` in `popLayout`, so
 * during a navigation both pages are briefly mounted and the incoming subtree can be *replaced*
 * after it first appears. Focusing the first heading that shows up therefore worked for a
 * palette navigation and silently failed for a sidebar click: the element holding focus was
 * detached a moment later and focus fell back to `<body>`.
 *
 * Measured sequence for a sidebar click: the heading appears, is focused successfully, and is
 * then **detached** as the transition settles — leaving focus on `<body>`. So stopping at the
 * first successful `focus()` is not enough, and the version that did looked correct in the logs
 * while failing on screen.
 *
 * The observer therefore stays connected for the whole window and re-focuses whenever the element
 * it chose has been detached and focus has fallen back to the body. It stops early only when
 * focus is somewhere real that it did not put there — the user tabbing, or a dialog restoring
 * focus to its trigger — which must never be stolen back.
 *
 * That last rule has one exception, and leaving it out made the effect a no-op on the commonest
 * navigation of all: focus at the *start* of a navigation is normally still on the sidebar link
 * that was just clicked. That is the stale focus this component exists to move, not a choice to
 * respect, so the element focused when the pathname changed is remembered and exempted.
 */

/** How long to wait for the incoming page's heading before giving up. */
const HEADING_WINDOW_MS = 1000;

export function RouteFocus() {
  const pathname = usePathname();
  const previous = React.useRef<string | null>(null);

  React.useEffect(() => {
    if (previous.current === null) {
      previous.current = pathname;
      return;
    }
    if (previous.current === pathname) return;
    previous.current = pathname;

    // The outgoing page's heading, if it is still mounted. Focusing it would be worse than doing
    // nothing: it is on its way out, and focus would fall back to the body when it goes.
    const outgoing = document.querySelector<HTMLElement>('main h1');

    /*
     * Where focus was when the navigation started — usually the sidebar link that was just
     * clicked, or the command palette's input.
     *
     * Remembered because the "do not steal focus" rule below must not apply to it: that focus is
     * the *stale* focus this component exists to move, and treating it as the user's deliberate
     * choice made the whole effect a no-op on exactly the navigation it was written for.
     */
    const initial = document.activeElement;

    let settled = false;
    let focused: HTMLElement | undefined;
    const observer = new MutationObserver(() => attempt());
    const timer = setTimeout(() => stop(), HEADING_WINDOW_MS);

    const stop = () => {
      settled = true;
      observer.disconnect();
      clearTimeout(timer);
    };

    const attempt = (): void => {
      if (settled) return;
      const active = document.activeElement;

      /*
       * Focus is somewhere real that is not the heading this is trying to place it on — the user
       * tabbed, or a dialog restored focus to its trigger. That is theirs, and stealing it back
       * would be worse than never having moved it.
       */
      if (active !== null && active !== document.body && active !== focused && active !== initial) {
        stop();
        return;
      }

      // Already done, and still attached. Keep watching: the transition may yet replace it.
      if (focused !== undefined && active === focused && focused.isConnected) return;

      const heading = document.querySelector<HTMLElement>('main h1');
      if (!heading || heading === outgoing) return;

      focused = heading;
      heading.focus();
    };

    // Occasionally the new page is already committed — a cached route, or a navigation with no
    // exit animation — and the first attempt is the only one needed.
    attempt();
    observer.observe(document.body, { childList: true, subtree: true });

    return stop;
  }, [pathname]);

  return null;
}
