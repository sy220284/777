// Regression checks for scoped Apple exceptions. Run with node.
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const dir = mkdtempSync(join(tmpdir(), 'finesse-apple-'));
const detector = fileURLToPath(new URL('./detect.mjs', import.meta.url));
function scan(name, content, strict = false) {
  const file = join(dir, name); writeFileSync(file, content);
  const run = spawnSync(process.execPath, [detector, '--json', ...(strict ? ['--strict'] : []), file], { encoding: 'utf8' });
  assert.equal(run.error, undefined);
  return { report: JSON.parse(run.stdout), status: run.status };
}
const ids = result => result.report.files[0].findings.map(f => f.id);
try {
  assert(ids(scan('ordinary.css', ':root{--page:#fff;--ink:#000}')).includes('pure-bw'));
  assert(ids(scan('mention.css', '/* Apple-inspired */ :root{--page:#fff}')).includes('pure-bw'));
  assert(!ids(scan('apple.css', '/* finesse · design-language=apple · apple-mode=editorial */\n:root{--page:#fff;--ink:#000}')).includes('pure-bw'));
  assert(!ids(scan('inline.html', '<style>/* finesse · design-language=apple */ :root{--page:#fff}</style>')).includes('pure-bw'));
  assert(ids(scan('near-match.css', '/* finesse · design-language=appleish */ :root{--page:#fff}')).includes('pure-bw'));
  assert(ids(scan('later.css', '/* other */ /* finesse · design-language=apple */ :root{--page:#fff}')).includes('pure-bw'));
  const broken = scan('broken.html', '<style>/* finesse · design-language=apple */ :root{--page:#fff}</style><img src="missing-image.png">', true);
  assert(ids(broken).includes('dead-local-ref')); assert(broken.report.p0 > 0); assert.equal(broken.status, 1);
  // One stamped file never changes the treatment of a neighboring file.
  assert(ids(scan('neighbor.css', ':root{--page:#fff}')).includes('pure-bw'));
  assert(!ids(scan('fragment.js', 'const html = `<img src="document-relative.png">`;')).includes('dead-local-ref'));
  assert(ids(scan('broken.css', '.a{background:url(missing.png)}')).includes('dead-local-ref'));
  console.log('Apple detector regression checks passed (10 cases).');
} finally { rmSync(dir, { recursive: true, force: true }); }
