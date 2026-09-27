export interface Diagnostic {
  severity: 'error' | 'warning';
  message: string;
  path?: string | null;
  byteStart?: number | null;
  byteEnd?: number | null;
  /** UTF-16 offsets from the start of the file. Lines and columns are zero-based. */
  utf16Start?: number | null;
  utf16End?: number | null;
  startLine?: number | null;
  startColumn?: number | null;
  endLine?: number | null;
  endColumn?: number | null;
  hints: string[];
}
export interface DocumentPosition { page: number; x: number; y: number }
export interface SourcePosition {
  path: string;
  byteStart: number;
  byteEnd: number;
  utf16Start: number;
  utf16End: number;
  line: number;
  column: number;
  endLine: number;
  endColumn: number;
}
export interface SemanticRegion {
  kind: 'text' | 'shape' | 'link';
  x: number; y: number; width: number; height: number;
  url?: string; page?: number; targetX?: number; targetY?: number;
}
export interface CompileOutput {
  typstVersion: string;
  isSuccess: boolean;
  pageCount: number;
  diagnostics: Diagnostic[];
  svgPages: string[];
  pngPages: Uint8Array[];
  html: string;
  pdf: Uint8Array;
  /** Original one-based page numbers and point dimensions after page selection. */
  pages: { number: number; width: number; height: number }[];
  regions: SemanticRegion[][];
  /** Supply these files, then retry compilation. No implicit network or disk access. */
  missingFiles: { path: string; package: string | null }[];
  hasSourceMap: boolean;
}
export interface CompileOptions {
  mainPath?: string;
  format?: 'pdf' | 'svg' | 'png' | 'html' | 'check';
  timestampMillis?: number;
  utcOffsetMinutes?: number;
  ppi?: number;
  pages?: string;
  pdfStandards?: string[];
  creationTimestampMillis?: number;
  features?: string[];
  shouldRetainSourceMap?: boolean;
  /** Requires Typst 0.14+. */
  shouldTagPdf?: boolean;
  /** Requires Typst 0.15+. */
  shouldPrettyPrint?: boolean;
  /** Requires Typst 0.15+. */
  shouldRenderBleed?: boolean;
}
export interface TypstCompiler {
  readonly version: string;
  setSource(path: string, text: string): void;
  setFile(path: string, data: Uint8Array): void;
  setPackageFile(specification: string, path: string, data: Uint8Array): void;
  removeFile(path: string): void;
  resetFiles(): void;
  addFont(data: Uint8Array): void;
  resetFonts(): void;
  setInputs(inputs: Record<string, string>): void;
  /** Coordinates use points; pages are one-based. */
  documentToSource(page: number, x: number, y: number): SourcePosition | undefined;
  sourceToDocument(path: string, utf16Offset: number): DocumentPosition[];
  /** Synchronous CPU work: use a Web Worker to keep a browser UI responsive. */
  compile(options?: CompileOptions): CompileOutput;
  dispose(): void;
}
export function createCompiler(options?: {
  wasm?: BufferSource | WebAssembly.Module | URL | Request | Response | string;
}): Promise<TypstCompiler>;
