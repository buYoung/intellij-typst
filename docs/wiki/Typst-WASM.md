# Typst WASM

Typst 컴파일러를 브라우저와 JavaScript 환경에서 실행하는 배포 패키지다. Typst Rust 라이브러리를 `wasm32-unknown-unknown`으로 빌드하고 wasm-bindgen으로 ES module API를 제공한다. 별도의 Typst CLI 프로세스는 필요 없다.

**[버전별 다운로드와 체크섬](Typst-WASM-Versions)** · [GitHub Releases](https://github.com/buYoung/intellij-typst/releases)

## 지원 범위

Typst 0.13.0부터 최신 안정 버전까지 모든 안정 패치 버전을 관리한다. 정확한 게시 버전·파일 크기·SHA-256은 다운로드 페이지와 해당 릴리스의 `manifest.json`을 따른다. Typst 엔진 버전과 WASM 배포 묶음 버전은 별개다. IntelliJ 플러그인 0.2.0의 버전과도 독립적이다.

| 항목 | 제공 방식 |
| --- | --- |
| 컴파일 | PDF 바이트 또는 페이지별 SVG 문자열 |
| 프로젝트 파일 | 메모리 안의 가상 파일 시스템. 소스·이미지·문헌·데이터를 호출자가 등록 |
| Typst 패키지 | 호출자가 패키지 manifest와 모든 필요한 파일을 등록 |
| 폰트 | 해당 엔진의 `typst-assets` 기본 폰트 내장, 사용자 폰트 추가 가능 |
| 입력값 | 문자열 값으로 구성된 `sys.inputs` |
| 날짜 | 호출 시각과 UTC 오프셋 또는 명시한 재현 가능한 값 |
| 진단 | 오류·경고·힌트, 파일 경로, UTF-8 바이트 범위와 UTF-16 문자열 범위 |

초기 API에는 PNG·HTML 출력, OS 파일 접근, 시스템 폰트 탐색, 자동 패키지 다운로드, 미리보기에서 소스로 이동, 증분 편집 API가 없다. IntelliJ 플러그인의 기존 렌더러를 WASM으로 전환하는 작업도 이 배포와 별개다. Typst 문법·표준 라이브러리·문서 호환성은 선택한 엔진 버전을 따른다.

## 파일 구성과 로딩

엔진별 ZIP을 풀면 `index.js`, `index.d.ts`, wasm-bindgen이 생성한 `typst_wasm.js`·선언 파일·`typst_wasm_bg.wasm`, 사용법과 라이선스 고지가 나온다. 기본 폰트는 WASM에 포함된다. JS와 WASM은 반드시 같은 ZIP에서 사용한다.

브라우저에서는 압축을 푼 디렉터리를 HTTP로 제공한다. ES module과 WebAssembly를 지원하는 환경이 필요하며, WASM 응답의 권장 MIME 형식은 `application/wasm`이다. 교차 출처에서 불러올 때는 서버 CORS 정책이 허용해야 한다.

```js
import { createCompiler } from './index.js';

const compiler = await createCompiler();
try {
  compiler.setSource('/main.typ', '= Hello Typst\n$ integral_0^1 x^2 dif x $');
  const result = compiler.compile({ mainPath: '/main.typ', format: 'pdf' });
  if (result.isSuccess) {
    const pdf = new Blob([result.pdf], { type: 'application/pdf' });
    // 호출하는 앱에서 저장하거나 미리보기로 전달한다.
  } else {
    console.error(result.diagnostics);
  }
} finally {
  compiler.dispose();
}
```

브라우저 UI에서는 이 코드를 Web Worker에서 실행한다. `compile`은 동기 계산이므로 메인 스레드에서 큰 문서를 컴파일하면 화면이 멈출 수 있다. 진행 중인 계산을 취소하려면 Worker를 종료하고 새 Worker에서 컴파일러를 만든다.

Node.js에서는 WASM 바이트를 명시적으로 전달한다.

```js
import { readFile } from 'node:fs/promises';
import { createCompiler } from './index.js';

const compiler = await createCompiler({
  wasm: await readFile(new URL('./typst_wasm_bg.wasm', import.meta.url)),
});
```

## API

`createCompiler({ wasm? })`는 초기화가 끝난 컴파일러를 반환하는 비동기 함수다. 생략하면 같은 디렉터리의 WASM을 불러온다. `wasm`에는 바이트, `WebAssembly.Module`, URL 또는 fetch 입력/응답을 전달할 수 있다. 이후 메서드는 동기 방식이다.

| API | 계약 |
| --- | --- |
| `version` | 실제 포함된 Typst 엔진 버전 문자열 |
| `setSource(path, text)` | UTF-8 소스를 등록하거나 교체 |
| `setFile(path, data)` | `Uint8Array` 파일을 등록하거나 교체 |
| `setPackageFile(specification, path, data)` | 지정 패키지의 파일 등록. 예: `@preview/example:1.0.0`, `/typst.toml` |
| `removeFile(path)` | 프로젝트 파일 제거. 없는 파일은 무시 |
| `resetFiles()` | 프로젝트와 패키지 파일 전체 제거. 폰트와 입력값은 유지 |
| `addFont(data)` | 폰트 바이트에서 찾은 모든 face 추가. 기존 폰트는 유지 |
| `setInputs(inputs)` | `Record<string, string>`으로 전체 입력값 교체 |
| `compile(options?)` | 아래 옵션으로 컴파일하고 결과 반환 |
| `dispose()` | 컴파일러 해제. 여러 번 호출 가능. 이후 다른 메서드는 오류 |

가상 프로젝트 경로는 `/main.typ`처럼 루트 기준으로 지정한다. 등록 API는 역슬래시, NUL, 콜론, `..` 경로 요소를 거절한다. Typst 소스 안의 상대 import는 엔진이 가상 경로로 해석한다. 메모리에 등록하지 않은 파일과 패키지는 컴파일 오류가 된다.

패키지를 제공할 때는 `typst.toml`, 진입 소스, 필요한 하위 소스·이미지·폰트를 모두 `setPackageFile`로 등록한다. 다운로드·캐시·패키지 신뢰 판단은 호출하는 앱의 책임이다. 한국어 등 기본 폰트에 없는 글리프는 적절한 폰트를 `addFont`로 추가하고 Typst 문서에서 해당 폰트를 선택한다.

### 컴파일 옵션

| 옵션 | 기본값 | 의미 |
| --- | --- | --- |
| `mainPath` | `/main.typ` | 가상 진입 파일 |
| `format` | `pdf` | `pdf` 또는 `svg` |
| `timestampMillis` | `Date.now()` | `datetime.today` 계산의 Unix 시각, 밀리초 |
| `utcOffsetMinutes` | 호출 환경의 로컬 오프셋 | UTC보다 동쪽이면 양수. -1439~1439의 정수 |

재현 가능한 날짜에는 `timestampMillis: 0, utcOffsetMinutes: 0`처럼 두 값을 함께 지정한다. 처리 가능한 날짜 범위는 Rust `time` 라이브러리의 범위로 제한된다. Typst 문서에서 명시한 `datetime.today` 오프셋은 엔진이 우선 적용한다.

### 반환값과 실패

```ts
interface CompileOutput {
  typstVersion: string;
  isSuccess: boolean;
  pageCount: number;
  diagnostics: Diagnostic[];
  svgPages: string[];
  pdf: Uint8Array;
}
```

PDF 성공 시 `pdf`를, SVG 성공 시 `svgPages`를 사용한다. 사용하지 않는 출력은 빈 배열이다. 소스 컴파일이나 PDF 생성 실패는 `isSuccess: false`와 진단을 반환한다. 성공해도 경고가 있을 수 있다. 잘못된 API 입력, 해제된 객체 사용, 런타임 실패는 예외이므로 호출하는 앱에서 처리한다.

진단에는 `severity`(`error` 또는 `warning`), `message`, `hints`가 있다. `path`, `byteStart`, `byteEnd`, `utf16Start`, `utf16End`는 위치를 특정할 수 있을 때만 제공된다. 범위는 파일 시작 기준, 끝 위치 제외다. UTF-8 바이트 위치를 JavaScript 문자열 인덱스로 바로 사용하지 말고 UTF-16 필드를 사용한다. 패키지 파일의 경로에는 패키지 식별자가 포함된다.

`dispose`가 WASM 모듈과 전역 캐시의 메모리 전체를 운영체제에 즉시 반환하는 것은 아니다. 긴 수명·대용량 문서 처리는 Worker 수명과 메모리 한도를 호출하는 앱에서 관리한다.

## 빌드·검증·배포

소스는 현재 저장소의 `packages/typst-wasm`에 있으며, 공통 Rust 코드와 엔진별 Cargo manifest·잠금 파일을 사용한다. Typst 내부 구성요소도 엔진의 패치 버전으로 고정한다. Node 24, pnpm 10.29.2, Rust 1.98.1, Python 3, GitHub CLI가 필요하다.

저장소 루트에서 실행한다.

```sh
pnpm install --frozen-lockfile
pnpm wasm:setup
pnpm --filter @typstninja/wasm versions:check
pnpm wasm:build
pnpm release
```

`wasm:setup`은 WASM 대상과 고정 버전의 wasm-bindgen CLI·cargo-about를 설치한다. `wasm:build`는 전체 엔진을 빌드하고 저장소의 기존 마크업·수학 샘플로 PDF·SVG 출력을 확인한다. 기존 오류 샘플로 위치가 포함된 오류 진단도 확인한다. 검증 결과, 소스 지문, ZIP·WASM의 SHA-256을 `manifest.json`에 기록한다. 이는 배포 산출물 검증 범위이며 모든 문서와 브라우저의 동작을 보장하는 적합성 검사는 아니다.

`pnpm release`는 깨끗한 `main`에서 배포 대상과 배포 묶음 버전을 선택하게 한다. 모든 엔진 빌드 후 커밋·태그·푸시를 차례로 확인한다. Typst IntelliJ 플러그인 항목은 준비 중인 자리표시자다.

WASM 게시 단계는 GitHub CLI 로그인으로 draft 릴리스를 만들고, 올린 모든 파일을 다시 내려받아 체크섬을 대조한 후 공개한다. WASM 태그는 `typst-wasm-v<배포 버전>`이며 기존 플러그인 릴리스와 구분한다. 기존 공개 릴리스 자산은 덮어쓰지 않는다. 최초 게시와 중단된 업로드 복구는 현재 버전을 빌드하고 소스 커밋을 원격에 푸시한 뒤 `pnpm wasm:publish`로 실행한다.

**Typst WASM 업데이트 시 GitHub Wiki를 반드시 갱신한다.** 사용법 원본은 `docs/wiki/Typst-WASM.md`이고 버전별 다운로드 페이지는 공개된 릴리스 manifest에서 생성한다. 릴리스 게시 후 Wiki 단계만 실패하면 릴리스를 삭제하지 않고 다음 명령으로 재시도한다.

```sh
pnpm wiki:sync --tag typst-wasm-v0.1.0
```

각 ZIP의 `LICENSE`, `THIRD-PARTY-NOTICES.txt`, `FONT-NOTICES.txt`를 함께 배포해야 한다.

## 참고

- [Typst 공식 소스와 안정 릴리스](https://github.com/typst/typst/releases)
- [Typst 공식 Rust API](https://docs.rs/typst/latest/typst/)
- [wasm-bindgen의 웹 배포 방식](https://wasm-bindgen.github.io/wasm-bindgen/reference/deployment.html)
- [이 저장소의 WASM 구현](https://github.com/buYoung/intellij-typst/tree/typst-wasm-v0.1.0/packages/typst-wasm)
