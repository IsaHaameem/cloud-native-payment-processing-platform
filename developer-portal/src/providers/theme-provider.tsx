'use client';

import { ThemeProvider as NextThemeProvider } from 'next-themes';
import type * as React from 'react';

/**
 * Theme (M23.1; locked to dark in Project 3).
 *
 * PaymentFlow is **dark only**. The token file still carries a derived light ramp, but no
 * surface may render it: `forcedTheme="dark"` pins the `dark` class on `<html>` and makes
 * `enableSystem` and any stored preference inert, so a light OS setting or a stale
 * `localStorage` value can never flip the product. The theme picker has been removed from the
 * UI to match — there is nothing to pick.
 *
 * `disableTransitionOnChange` is kept: it costs nothing and guards against a flash if the
 * forced value is ever relaxed.
 */
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  return (
    <NextThemeProvider
      attribute="class"
      defaultTheme="dark"
      forcedTheme="dark"
      disableTransitionOnChange
      value={{ light: 'light', dark: 'dark' }}
    >
      {children}
    </NextThemeProvider>
  );
}
