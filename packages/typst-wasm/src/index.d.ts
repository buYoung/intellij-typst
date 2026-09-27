export interface Diagnostic {
  severity: 'error' | 'warning';
  message: string;
  path?: string;
  byteStart?: number;
  byteEnd?: number;
  /** UTF-16 offsets from the start of the file, compatible with JavaScript strings. */
  utf16Start?: number;
  utf16End?: number;
  hints: string[];
}

export interface CompileOutput {
  typstVersion: string;
  isSuccess: boolean;
  pageCount: number;
  diagnostics: Diagnostic[];
  svgPages: string[];
  /** Empty for SVG output or compilation failure. */
  pdf: Uint8Array;
}

export interface TypstCompiler {
  readonly version: string;
  setSource(path: string, text: string): void;
  setFile(path: string, data: Uint8Array): void;
  setPackageFile(specification: string, path: string, data: Uint8Array): void;
  removeFile(path: string): void;
  resetFiles(): void;
  addFont(data: Uint8Array): void;
  setInputs(inputs: Record<string, string>): void;
  /** Synchronous CPU work: use a Web Worker to keep a browser UI responsive. */
  compile(options?: {
    mainPath?: string;
    format?: 'pdf' | 'svg';
    timestampMillis?: number;
    utcOffsetMinutes?: number;
  }): CompileOutput;
  dispose(): void;
}

export function createCompiler(options?: {
  wasm?: BufferSource | WebAssembly.Module | URL | Request | Response | string;
}): Promise<TypstCompiler>;
