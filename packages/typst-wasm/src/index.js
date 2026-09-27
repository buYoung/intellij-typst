import initialize, { Compiler, version } from './typst_wasm.js';

/** One module instance per engine version. Run compilation in a Worker for UI use. */
export async function createCompiler({ wasm } = {}) {
  await initialize({ module_or_path: wasm ?? new URL('./typst_wasm_bg.wasm', import.meta.url) });
  const compiler = new Compiler();
  let isDisposed = false;
  const use = operation => {
    if (isDisposed) throw new Error('The Typst compiler has been disposed');
    return operation();
  };
  return {
    version: version(),
    setSource: (path, text) => use(() => compiler.set_source(path, text)),
    setFile: (path, data) => use(() => compiler.set_file(path, data)),
    setPackageFile: (specification, path, data) => use(() => compiler.set_package_file(specification, path, data)),
    removeFile: path => use(() => compiler.remove_file(path)),
    resetFiles: () => use(() => compiler.reset_files()),
    addFont: data => use(() => compiler.add_font(data)),
    resetFonts: () => use(() => compiler.reset_fonts()),
    documentToSource: (page, x, y) => use(() => compiler.document_to_source(page, x, y)),
    sourceToDocument: (path, utf16Offset) => use(() => compiler.source_to_document(path, utf16Offset)),
    setInputs: inputs => use(() => compiler.set_inputs(inputs)),
    compile: ({ mainPath = '/main.typ', format = 'pdf', timestampMillis = Date.now(), utcOffsetMinutes = -new Date().getTimezoneOffset(), ...options } = {}) =>
      use(() => {
        if (!Number.isFinite(timestampMillis)) throw new TypeError('timestampMillis must be a finite number');
        if (!Number.isInteger(utcOffsetMinutes) || Math.abs(utcOffsetMinutes) >= 1440) {
          throw new RangeError('utcOffsetMinutes must be an integer between -1439 and 1439');
        }
        return compiler.compile_with_options(mainPath, timestampMillis, utcOffsetMinutes, { format, ...options });
      }),
    dispose() {
      if (isDisposed) return;
      isDisposed = true;
      compiler.free();
    },
  };
}
