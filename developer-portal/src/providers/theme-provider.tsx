'use client';

import { ThemeProvider as NextThemeProvider } from 'next-themes';
import type * as React from 'react';

/**
 * Theme switching (M23.1).
 *
 * `defaultTheme="dark"` because the design language is dark-first, and `enableSystem` because
 * someone whose OS says light should not have to ask twice. `value` maps the two themes onto
 * the class names the tokens are written against — `light` is the variant class, since dark is
 * the base.
 *
 * `disableTransitionOnChange` suppresses the colour transition during the switch itself. Without
 * it every bordered surface on the page animates independently and the change reads as a slow
 * wash rather than an instant flip.
 */
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  return (
    <NextThemeProvider
      attribute="class"
      defaultTheme="dark"
      enableSystem
      disableTransitionOnChange
      value={{ light: 'light', dark: 'dark' }}
    >
      {children}
    </NextThemeProvider>
  );
}
