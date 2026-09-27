import { readFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { repoRoot } from './common.mjs';

// Reuse the existing corpus for both host interfaces; no new test documents.
const [directory, version] = process.argv.slice(2);
if (!directory || !/^0\.\d+\.\d+$/.test(version ?? '')) throw new Error('Expected an engine directory and version');
const { createCompiler } = await import(pathToFileURL(path.join(directory, 'index.js')).href);
const browser = await createCompiler({ wasm: readFileSync(path.join(directory, 'typst_wasm_bg.wasm')) });
const { instance } = await WebAssembly.instantiate(readFileSync(path.join(directory, 'typst_wasm_raw.wasm')), {
  typst_host: { read_font_bytes: () => -1 },
});
const raw = instance.exports;
function take(packed) {
  if (packed === 0n) return Buffer.alloc(0);
  const pointer = Number(packed >> 32n), length = Number(packed & 0xffffffffn);
  try { return Buffer.from(new Uint8Array(raw.memory.buffer, pointer, length)); }
  finally { raw.dealloc(pointer, length); }
}
function request(command, data = Buffer.alloc(0)) {
  const bytes = Buffer.from(JSON.stringify(command));
  const pointer = raw.alloc(bytes.length), dataPointer = raw.alloc(data.length);
  new Uint8Array(raw.memory.buffer).set(bytes, pointer);
  new Uint8Array(raw.memory.buffer).set(data, dataPointer);
  try {
    const response = JSON.parse(take(raw.request(pointer, bytes.length, dataPointer, data.length)));
    if (response.error) throw new Error(response.error);
    return response.ok;
  } finally { raw.dealloc(pointer, bytes.length); raw.dealloc(dataPointer, data.length); }
}
const identity = request({ method: 'version' });
if (identity.apiVersion !== 1) throw new Error('Raw ABI mismatch');
const host = {
  version: identity.typstVersion,
  setSource: (path, text) => request({ method: 'setFile', path }, Buffer.from(text)),
  compile({ format = 'pdf' } = {}) {
    const result = request({ method: 'compile', path: '/main.typ', timestampMillis: 0, utcOffsetMinutes: 0, options: { format } });
    if (format === 'pdf' && result.binaryOutputCount) result.pdf = take(raw.take_output(0));
    return result;
  },
};
const compiled = [];
try {
  for (const [backend, compiler] of [['browser', browser], ['raw', host]]) {
    if (compiler.version !== version) throw new Error(`WASM version mismatch: ${version}`);
    for (const file of ['samples/verify/00-markup.typ', 'samples/verify/04-math.typ']) {
      compiler.setSource('/main.typ', readFileSync(path.join(repoRoot, file), 'utf8'));
      for (const format of ['pdf', 'svg']) {
        const result = compiler.compile({ format, timestampMillis: 0, utcOffsetMinutes: 0 });
        if (!result.isSuccess || result.pageCount < 1 || result.typstVersion !== version) throw new Error(`${version} ${backend} ${file} ${format}: ${JSON.stringify(result.diagnostics)}`);
        if (format === 'pdf') {
          const pdf = Buffer.from(result.pdf);
          if (!pdf.subarray(0, 5).equals(Buffer.from('%PDF-')) || !pdf.subarray(-20).includes('%%EOF')) throw new Error('Invalid PDF output');
        } else if (result.svgPages.length !== result.pageCount || result.svgPages.some(svg => !svg.includes('<svg'))) {
          throw new Error('Invalid SVG output');
        }
        compiled.push({ backend, file, format, pageCount: result.pageCount });
      }
    }
    compiler.setSource('/main.typ', readFileSync(path.join(repoRoot, 'samples/verify-invalid.typ'), 'utf8'));
    const result = compiler.compile({ timestampMillis: 0, utcOffsetMinutes: 0 });
    if (result.isSuccess || !result.diagnostics.some(item => item.severity === 'error' && Number.isInteger(item.utf16Start))) {
      throw new Error(`Expected structured ${backend} diagnostics for the existing invalid corpus`);
    }
  }
  console.info(JSON.stringify({ isModuleLoaded: true, isRawModuleLoaded: true, compiled, hasDiagnostics: true }));
} finally { browser.dispose(); }
