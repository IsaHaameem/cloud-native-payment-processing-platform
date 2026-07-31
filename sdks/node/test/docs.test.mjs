/*
 * The README compiles (M22.4).
 *
 * Every ```ts block in README.md is extracted and type-checked against the **built
 * declarations** — the same `dist/esm/index.d.ts` an integrator installs. Documentation that
 * does not compile is documentation that is wrong, and it is wrong in the most expensive
 * possible place: a snippet is the first code a new integrator writes, and they will assume
 * the error is theirs.
 *
 * This also makes the README a real consumer of the public API. A method renamed, a type no
 * longer exported, a parameter that changed shape — all of them fail here, which is the only
 * thing that keeps a long README honest as the package moves under it.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const packageRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const workspace = join(packageRoot, 'build', 'readme-snippets');
const tscBin = join(packageRoot, 'node_modules', 'typescript', 'bin', 'tsc');

/** Every fenced block, with the language tag it declared and the line it started on. */
function fencedBlocks(markdown) {
  const blocks = [];
  const lines = markdown.split('\n');
  let language;
  let start = 0;
  let body = [];

  for (const [index, line] of lines.entries()) {
    const fence = /^```(\w*)\s*$/.exec(line);
    if (fence === null) {
      if (language !== undefined) body.push(line);
      continue;
    }
    if (language === undefined) {
      language = fence[1];
      start = index + 1;
      body = [];
    } else {
      blocks.push({ language, line: start, code: body.join('\n') });
      language = undefined;
    }
  }
  return blocks;
}

/**
 * Separates a snippet's `import` statements from the rest of it.
 *
 * The body is wrapped in an async function so a snippet may `await` at what looks like top
 * level, and so two snippets both declaring `client` do not collide — but an import cannot
 * live inside a function, so the two halves have to be emitted separately. Multi-line braced
 * imports are collected until the statement terminates.
 */
function splitImports(code) {
  const imports = [];
  const body = [];
  let pending = null;

  for (const line of code.split('\n')) {
    if (pending !== null) {
      pending.push(line);
      if (line.includes(';')) {
        imports.push(pending.join('\n'));
        pending = null;
      }
      continue;
    }
    if (/^\s*import\b/.test(line)) {
      if (line.includes(';')) imports.push(line);
      else pending = [line];
      continue;
    }
    body.push(line);
  }
  return { imports, body };
}

const readme = await readFile(join(packageRoot, 'README.md'), 'utf8');
const blocks = fencedBlocks(readme);
const typescript = blocks.filter((block) => block.language === 'ts');

test('the README has TypeScript examples to check', () => {
  // Asserted before anything below, so a README that lost its examples — or a fence parser
  // that silently matched nothing — fails here rather than passing over an empty list.
  assert.ok(typescript.length >= 15, `expected the README to carry real examples, found ${typescript.length}`);

  // And every fence declares a language, so a snippet cannot dodge this check by omitting one.
  const untagged = blocks.filter((block) => block.language === '');
  assert.deepEqual(untagged, [], 'every fenced block declares its language');
});

test('every TypeScript snippet in the README compiles against the built package', async () => {
  await rm(workspace, { recursive: true, force: true });
  await mkdir(workspace, { recursive: true });

  const names = [];
  for (const [index, block] of typescript.entries()) {
    // Wrapped in a module-scoped async function so `await` at the top level of a snippet is
    // legal, and so two snippets declaring `client` do not collide. `export {}` keeps each
    // file a module rather than a script sharing one global scope.
    const name = `snippet-${String(index + 1).padStart(2, '0')}-line-${block.line}.ts`;
    const { imports, body } = splitImports(block.code);
    const source = [
      `// README.md line ${block.line}`,
      ...imports,
      'export {};',
      'async function snippet(): Promise<void> {',
      ...body,
      '}',
      'void snippet;',
      '',
    ].join('\n');
    await writeFile(join(workspace, name), source, 'utf8');
    names.push(name);
  }

  await writeFile(
    join(workspace, 'tsconfig.json'),
    `${JSON.stringify(
      {
        extends: '../../tsconfig.json',
        compilerOptions: {
          rootDir: '.',
          baseUrl: '.',
          noEmit: true,
          // Resolved to the published declarations, not to ../src: a type that is correct in
          // the sources and missing from the build would pass a source-relative check and fail
          // for everyone who installed the package.
          paths: { paymentflow: ['../../dist/esm/index.d.ts'] },
          // A snippet illustrating a type by naming a variable is doing its job.
          noUnusedLocals: false,
          noUnusedParameters: false,
        },
        include: ['./*.ts'],
      },
      null,
      2,
    )}\n`,
    'utf8',
  );

  let output = '';
  let failed = false;
  try {
    // Run through Node against TypeScript's own entry script rather than the `.bin` shim with
    // `shell: true`. A shell re-splits this repository's absolute path on its spaces, so the
    // command became `'D:\Cloud-Native' is not recognized` — a failure that reads exactly like
    // a compilation failure and is not one.
    execFileSync(process.execPath, [tscBin, '-p', join(workspace, 'tsconfig.json')], {
      cwd: packageRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (error) {
    failed = true;
    output = `${error.stdout ?? ''}${error.stderr ?? ''}`;
  }

  assert.equal(failed, false, `the README does not compile:\n${output}`);
  assert.equal(names.length, typescript.length);
});

test('the README does not promise anything the package does not export', async () => {
  const esm = await import('../dist/esm/index.js');
  const exported = new Set(Object.keys(esm));

  // Names the README imports at runtime. A type-only import would compile against a name that
  // does not exist at runtime, so this checks the value imports specifically.
  const imported = new Set();
  for (const block of typescript) {
    for (const match of block.code.matchAll(/import\s*\{([^}]*)\}\s*from\s*'paymentflow'/g)) {
      for (const specifier of match[1].split(',')) {
        const name = specifier.trim();
        if (name === '' || name.startsWith('type ')) continue;
        imported.add(name);
      }
    }
  }

  assert.ok(imported.size > 0, 'the README imports something');
  for (const name of imported) {
    assert.ok(exported.has(name), `README imports \`${name}\`, which the package does not export`);
  }
});

test('the README documents every resource namespace the client actually has', async () => {
  const esm = await import('../dist/esm/index.js');
  const client = new esm.PaymentFlow({ apiKey: 'sk_test_docs' });

  // A namespace added without a line in the README is undiscoverable; a namespace documented
  // and then removed is worse. Derived from the client so neither can happen quietly.
  const namespaces = Object.keys(client).filter((key) => key !== 'config');
  for (const namespace of namespaces) {
    assert.match(readme, new RegExp(`\\b${namespace}\\b`), `the README mentions client.${namespace}`);
  }
  assert.equal(namespaces.length, 12, 'eleven resource namespaces plus webhooks');
});
