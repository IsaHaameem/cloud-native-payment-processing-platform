import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

/**
 * Regression guard for: `Error: A "use server" file can only export async functions, found object.`
 *
 * A `'use server'` module may export **only** async functions (plus type-only exports, which are
 * erased). A client component that imports a non-function value — an `IDLE` state object, a
 * constant — from one crashes the route at request time, and `next build` does not always catch
 * it because the offending route is server-rendered on demand.
 *
 * This walks every `'use server'` file under `src/` and asserts none of them has a value
 * `export const|let|var` or an `export default` that is not an async function. State objects
 * belong in a sibling non-server module (`action-state.ts`).
 */
const SRC = join(__dirname, '..', 'src');

function walk(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry === 'node_modules' || entry === '.next') continue;
      out.push(...walk(full));
    } else if (/\.tsx?$/.test(entry)) {
      out.push(full);
    }
  }
  return out;
}

function isUseServerModule(source: string): boolean {
  // The directive must be the first statement of the file (comments/blank lines allowed before).
  const head = source.replace(/^\s*(\/\/[^\n]*\n|\/\*[\s\S]*?\*\/\s*)*/, '');
  return /^['"]use server['"];?/.test(head);
}

/** Value exports that are not `export (async) function …`. Type-only exports are erased, so ignored. */
function offendingExports(source: string): string[] {
  const offenders: string[] = [];
  const constExport = /^export\s+(?:const|let|var)\s+([A-Za-z0-9_$]+)/gm;
  let m: RegExpExecArray | null;
  while ((m = constExport.exec(source)) !== null) {
    // `export const foo = async () => {}` is a valid Server Action; flag everything else.
    const line = source.slice(m.index, source.indexOf('\n', m.index));
    if (!/=\s*async\s*(\(|function)/.test(line)) offenders.push(m[1]!);
  }
  if (/^export\s+default\s+(?!async\s+function)/m.test(source)) offenders.push('default');
  return offenders;
}

describe('"use server" modules export only async functions', () => {
  const serverModules = walk(SRC).filter((f) => {
    try {
      return isUseServerModule(readFileSync(f, 'utf8'));
    } catch {
      return false;
    }
  });

  it('finds the action modules it is meant to guard', () => {
    // Sanity: the suite is actually looking at the files. If this drops to 0 the guard is inert.
    expect(serverModules.length).toBeGreaterThanOrEqual(8);
  });

  it.each(serverModules.map((f) => [f.slice(SRC.length + 1), f] as const))(
    '%s exports no non-function value',
    (_label, file) => {
      const offenders = offendingExports(readFileSync(file, 'utf8'));
      expect(
        offenders,
        `${file} exports non-function value(s): ${offenders.join(', ')}. ` +
          `Move state objects/constants into a sibling non-"use server" module (e.g. action-state.ts).`,
      ).toEqual([]);
    },
  );
});
