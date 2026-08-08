import type { Transition, Variants } from 'framer-motion';

/**
 * The portal's motion vocabulary (M23.1 redesign).
 *
 * ── Why this file exists ──────────────────────────────────────────────────────────────
 *
 * Motion is the part of a design system that decays fastest, because every component can
 * invent its own duration and nothing fails when they disagree. The result is the thing that
 * makes an interface feel assembled rather than designed: a menu at 300ms next to a card at
 * 120ms next to a page transition at 500ms. So durations, easings and the handful of reusable
 * variants live here, mirror the CSS custom properties in `tokens.css`, and are imported
 * rather than retyped.
 *
 * ── The rules ─────────────────────────────────────────────────────────────────────────
 *
 * 1. **Fast and small.** The reference's interaction evidence is a 3px focus outline and a
 *    blur — not travel. Nothing here moves more than 8px or lasts longer than 260ms.
 * 2. **Opacity and transform only.** Both are composited on the GPU; animating `height`,
 *    `top` or `box-shadow` forces layout or paint on every frame and is what makes an
 *    otherwise-nice interface feel cheap on a laptop.
 * 3. **Motion is never the only signal.** Every animated state change is also a colour, a
 *    label, or a position change that survives `prefers-reduced-motion`.
 * 4. **Reduced motion is honoured centrally**, by `MotionConfig reducedMotion="user"` in the
 *    providers, so no component has to remember to check.
 */

/** Mirrors `--duration-*` in tokens.css. Seconds, because that is Framer Motion's unit. */
export const duration = {
  instant: 0.08,
  fast: 0.14,
  base: 0.22,
} as const;

/** Mirrors `--ease-*`. `outQuart` decelerates hard, which is what makes a short move read as crisp. */
export const ease = {
  outQuart: [0.16, 1, 0.3, 1],
  standard: [0.4, 0, 0.2, 1],
} as const;

/**
 * The default spring for anything that should feel physical rather than timed — the sidebar
 * width, a hover lift. Critically damped enough not to wobble, which is the difference between
 * "responsive" and "bouncy".
 */
export const spring: Transition = {
  type: 'spring',
  stiffness: 420,
  damping: 38,
  mass: 0.9,
};

/**
 * Page transitions.
 *
 * 4px of travel and nothing else. A page that slides further than this reads as a *navigation*
 * — a whole context replaced — which is wrong for a dashboard where the shell stays put and
 * only the panel changes.
 */
export const pageVariants: Variants = {
  hidden: { opacity: 0, y: 4 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: duration.base, ease: ease.outQuart },
  },
  exit: {
    opacity: 0,
    y: -2,
    transition: { duration: duration.fast, ease: ease.standard },
  },
};

/**
 * A list that reveals its children in sequence.
 *
 * `staggerChildren` is deliberately small: at 0.03s a twelve-row table finishes in under half a
 * second, which reads as one movement. Anything slower turns a table into a performance the
 * user has to wait through, every single time they navigate.
 */
export const listVariants: Variants = {
  hidden: {},
  visible: {
    transition: { staggerChildren: 0.03, delayChildren: 0.02 },
  },
};

export const listItemVariants: Variants = {
  hidden: { opacity: 0, y: 6 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: duration.base, ease: ease.outQuart },
  },
};

/** Cards and panels entering. Same shape as a list item, so a grid and a table agree. */
export const cardVariants: Variants = {
  hidden: { opacity: 0, y: 8 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: duration.base, ease: ease.outQuart },
  },
};

/**
 * Overlays: menus, tooltips, dialogs.
 *
 * Scales from 0.98 rather than 0.95. The larger number is the template default and it makes a
 * dropdown look like it is being thrown at the screen; 2% is enough to read as "arrived from
 * the trigger" without the text visibly resampling.
 */
export const overlayVariants: Variants = {
  hidden: { opacity: 0, scale: 0.98, y: -2 },
  visible: {
    opacity: 1,
    scale: 1,
    y: 0,
    transition: { duration: duration.fast, ease: ease.outQuart },
  },
  exit: {
    opacity: 0,
    scale: 0.98,
    transition: { duration: duration.instant, ease: ease.standard },
  },
};

/** The scrim behind a modal or drawer. Opacity only — a blurred backdrop that also moves is noise. */
export const scrimVariants: Variants = {
  hidden: { opacity: 0 },
  visible: { opacity: 1, transition: { duration: duration.fast } },
  exit: { opacity: 0, transition: { duration: duration.fast } },
};

/**
 * The hover response shared by every interactive surface.
 *
 * Not a scale. Scaling a card resamples its text and its 1px border, which on a hairline-based
 * system is exactly the detail that goes soft. The lift is 1px of `y` plus a border change,
 * which stays crisp because neither touches the glyph raster.
 */
export const hoverLift = {
  whileHover: { y: -1, transition: { duration: duration.fast, ease: ease.outQuart } },
  whileTap: { y: 0, transition: { duration: duration.instant } },
} as const;

/** The press response for buttons. Scale down slightly; buttons are small enough to take it. */
export const pressScale = {
  whileTap: { scale: 0.97, transition: { duration: duration.instant } },
} as const;

/**
 * A floating dialog — the command palette, a confirmation.
 *
 * Enters from 8px above rather than from centre. A dialog that grows out of nothing reads as a
 * popup; one that settles downward reads as something arriving, which is the difference between
 * an alert and a tool.
 */
export const dialogVariants: Variants = {
  hidden: { opacity: 0, scale: 0.97, y: -8 },
  visible: {
    opacity: 1,
    scale: 1,
    y: 0,
    transition: { duration: duration.base, ease: ease.outQuart },
  },
  exit: {
    opacity: 0,
    scale: 0.98,
    y: -4,
    transition: { duration: duration.fast, ease: ease.standard },
  },
};

/**
 * A row of results that re-filters as the user types.
 *
 * `staggerChildren` is deliberately **zero** here, unlike a table's. A search result list
 * re-renders on every keystroke, and a stagger would make each character typed feel like a
 * loading state — the single most common way a command palette is made to feel slow. Results
 * fade in together, instantly.
 */
export const resultsVariants: Variants = {
  hidden: { opacity: 0 },
  visible: { opacity: 1, transition: { duration: duration.fast } },
};

/**
 * A section that expands in place — a table row's detail, a filter panel.
 *
 * Height *is* animated here, which the rest of the system avoids. There is no way around it:
 * the point is for surrounding content to move, and `transform` cannot push siblings. It is
 * confined to this one variant so the exception stays visible, and Framer measures the height
 * itself rather than the browser recalculating layout every frame.
 */
export const expandVariants: Variants = {
  hidden: { height: 0, opacity: 0 },
  visible: {
    height: 'auto',
    opacity: 1,
    transition: {
      height: { duration: duration.base, ease: ease.outQuart },
      opacity: { duration: duration.fast, delay: 0.04 },
    },
  },
  exit: {
    height: 0,
    opacity: 0,
    transition: {
      height: { duration: duration.fast, ease: ease.standard },
      opacity: { duration: duration.instant },
    },
  },
};

/**
 * A number counting to its value.
 *
 * 600ms is far longer than anything else here, and it is the one place a slower move is right:
 * a metric that snaps into place is read as static, while one that counts is read as *measured*.
 * It runs once on mount, never on re-render, so it never delays a value the user is waiting for.
 */
export const countTransition: Transition = {
  duration: 0.6,
  ease: ease.outQuart,
};
