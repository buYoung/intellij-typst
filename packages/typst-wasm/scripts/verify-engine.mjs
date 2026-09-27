import { readFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { repoRoot } from './common.mjs';

// Reuse the repository's existing verification corpus; do not create new test cases.
const [directory, version] = process.argv.slice(2);
if (!directory || !/^0\.\d+\.\d+$/.test(version ?? '')) throw new Error('Expected an engine directory and version');
const { createCompiler } = await import(pathToFileURL(path.join(directory, 'index.js')).href);
const compiler = await createCompiler({ wasm: readFileSync(path.join(directory, 'typst_wasm_bg.wasm')) });
const compiled = [];
try {
  if (compiler.version !== version) throw new Error(`WASM ABI version mismatch: ${version}`);
  for (const file of ['samples/verify/00-markup.typ', 'samples/verify/04-math.typ']) {
    compiler.setSource('/main.typ', readFileSync(path.join(repoRoot, file), 'utf8'));
    for (const format of ['pdf', 'svg']) {
      const result = compiler.compile({ format, timestampMillis: 0, utcOffsetMinutes: 0 });
      if (!result.isSuccess || result.pageCount < 1 || result.typstVersion !== version) throw new Error(`${version} ${file} ${format}: ${JSON.stringify(result.diagnostics)}`);
      if (format === 'pdf') {
        const pdf = Buffer.from(result.pdf);
        if (!pdf.subarray(0, 5).equals(Buffer.from('%PDF-')) || !pdf.subarray(-20).includes('%%EOF')) throw new Error('Invalid PDF output');
      } else if (result.svgPages.length !== result.pageCount || result.svgPages.some(svg => !svg.includes('<svg'))) {
        throw new Error('Invalid SVG output');
      }
      compiled.push({ file, format, pageCount: result.pageCount });
    }
  }
  const invalid = readFileSync(path.join(repoRoot, 'samples/verify-invalid.typ'), 'utf8');
  compiler.setSource('/main.typ', invalid);
  const result = compiler.compile({ timestampMillis: 0, utcOffsetMinutes: 0 });
  if (result.isSuccess || !result.diagnostics.some(item => item.severity === 'error' && Number.isInteger(item.utf16Start))) {
    throw new Error('Expected structured diagnostics for the existing invalid corpus');
  }
  console.info(JSON.stringify({ isModuleLoaded: true, compiled, hasDiagnostics: true }));
} finally { compiler.dispose(); }
