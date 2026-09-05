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
 * So it observes instead, and it asks the DOM which page it is looking at rather than inferring
 * it. `PageTransition` publishes its `key` as `data-pathname`, so the incoming heading is the one
 * inside the subtree belonging to the current route — true no matter how the two pages interleave
 * during a `popLayout` fade.
 *
 * The earlier version instead snapshotted `main h1` when the effect ran and treated that element
 * as the outgoing page's, skipping it forever after. That holds only if the DOM has not caught up
 * yet, and under load it has: the incoming subtree commits in the same tick as the pathname
 * change, the snapshot captures the *arriving* heading, and the effect then spends its whole
 * window refusing to focus the only correct target. Focus stayed on `<body>` — silently, and only
 * on a slow machine, which is the shape of bug that reaches a user and never a developer.
 *
 * The wait is bounded: if no heading for this route appears within a second, whatever is
 * happening is not a page arriving, and focus stays where the user left it.
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

/** How long to wait for the incoming page's heading to appear at all before giving up. */
const HEADING_WINDOW_MS = 1000;

/**
 * How much longer to keep watching after each heading this focuses.
 *
 * A separate number from the one above because they answer different questions. The first is
 * "has the page arrived?"; this one is "has the transition finished moving it?" — and the second
 * is not bounded by the first. `popLayout` re-mounts the incoming subtree after it first appears,
 * so the heading holding focus is detached and replaced *after* the arrival everyone was waiting
 * for. Measured under CPU throttling: the arrival is immediate and the replacement lands 450-630ms
 * later at 10x, and past a second when the machine is slower still — which is a CI runner sharing
 * two cores with the server and the browser.
 *
 * Treating one deadline as both is what let focus end up on `<body>`: the observer was told to
 * give up a second after the navigation, the replacement landed after that, and nothing was left
 * watching to put focus back. Each successful focus therefore extends the watch, so the window
 * measures quiet rather than elapsed time. It still always terminates: only a *new* heading
 * extends it, and a transition mounts finitely many.
 */
const SETTLE_WINDOW_MS = 1000;

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
    let timer = setTimeout(() => stop(), HEADING_WINDOW_MS);

    const stop = () => {
      settled = true;
      observer.disconnect();
      clearTimeout(timer);
    };

    /**
     * The incoming page's heading, or `null` while it is not mounted yet.
     *
     * Selected through the transition wrapper's `data-pathname` (page-transition.tsx) rather than
     * as "the first `main h1`". During a `popLayout` fade both pages are mounted, so document
     * order is not evidence of which is which — and under load the incoming subtree can commit in
     * the same tick as the pathname change, which made the previous "whatever `main h1` was when
     * the effect ran is the outgoing one" snapshot identify the *arriving* heading as the one to
     * avoid. Focus then never moved, and stayed on `<body>`. Asking which route a subtree belongs
     * to answers the question instead of guessing at it.
     */
    const incomingHeading = (): HTMLElement | null => {
      for (const page of document.querySelectorAll<HTMLElement>('main [data-pathname]')) {
        if (page.dataset.pathname === pathname) return page.querySelector<HTMLElement>('h1');
      }
      return null;
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

      const heading = incomingHeading();
      if (!heading || heading === focused) return;

      focused = heading;
      heading.focus();

      // The page is still moving, so keep watching for the replacement rather than counting down
      // to a deadline set before any of this happened. See SETTLE_WINDOW_MS.
      clearTimeout(timer);
      timer = setTimeout(() => stop(), SETTLE_WINDOW_MS);
    };

    // Occasionally the new page is already committed — a cached route, or a navigation with no
    // exit animation — and the first attempt is the only one needed.
    attempt();
    observer.observe(document.body, { childList: true, subtree: true });

    return stop;
  }, [pathname]);

  return null;
}
