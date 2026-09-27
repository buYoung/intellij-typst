# Typst WASM

Typst 0.13.0 이후의 안정 버전을 공통 JavaScript API로 제공하는 WebAssembly 배포 패키지다. 배포 묶음의 버전과 Typst 엔진 버전은 별개다. GitHub Releases의 엔진별 ZIP에는 WASM, ES module, TypeScript 선언, 기본 폰트, 라이선스 고지가 포함된다.

초기 제공 범위는 PDF·페이지별 SVG, 파일·패키지 파일의 메모리 입력, 추가 폰트, 문자열 `sys.inputs`, 오류·경고와 UTF-8/UTF-16 범위다. 기본 폰트는 각 Typst 버전의 `typst-assets`를 사용한다. OS 파일 시스템·시스템 폰트 탐색·자동 패키지 다운로드·PNG·HTML·소스 위치 이동·IDE 통합은 포함하지 않는다.

브라우저에서 ZIP을 풀어 HTTP로 제공한 뒤 사용한다. WASM과 JS 파일은 항상 같은 ZIP에서 가져온다.

```js
import { createCompiler } from './index.js';
const compiler = await createCompiler();
try {
  compiler.setSource('/main.typ', '= Hello Typst');
  const result = compiler.compile({ mainPath: '/main.typ', format: 'pdf' });
  if (!result.isSuccess) console.error(result.diagnostics);
  else console.log(result.pdf); // Uint8Array
} finally {
  compiler.dispose();
}
```

Node.js에서는 `createCompiler({ wasm: await readFile(new URL('./typst_wasm_bg.wasm', import.meta.url)) })`처럼 바이트를 전달한다. 브라우저 UI에서는 Web Worker 안에서 컴파일한다. `compile`은 동기 계산이며 계산 중지를 위해서는 Worker를 종료해야 한다. `dispose`는 컴파일러 객체를 해제하지만 WASM 모듈과 전역 캐시 전체의 메모리를 즉시 운영체제에 반환한다는 뜻은 아니다.

`setFile(path, Uint8Array)`는 이미지·문헌·데이터 파일을 입력한다. `setPackageFile('@preview/name:version', '/typst.toml', bytes)`와 패키지 내 파일들로 오프라인 패키지를 제공한다. 파일 경로는 가상 프로젝트 루트 기준이며 역슬래시·상위 경로 `..`·콜론을 허용하지 않는다. `setInputs({key: 'value'})`는 문자열 입력을, `addFont(bytes)`는 추가 폰트를 제공한다. 한국어 등 기본 폰트에 없는 글리프는 사용자가 적절한 폰트를 제공해야 한다.

진단의 `byteStart`·`byteEnd`는 UTF-8 바이트 위치이고 `utf16Start`·`utf16End`는 JavaScript 문자열 위치다. 위치를 특정할 수 없으면 필드가 `undefined`다. 성공·실패 모두 진단 배열을 반환하며 입력 형식 오류·해제된 컴파일러 사용은 예외를 던진다.

`timestampMillis`와 `utcOffsetMinutes`를 컴파일 옵션으로 전달하면 `datetime.today`를 재현 가능한 시각에 맞출 수 있다. 기본값은 호출 당시의 시각과 로컬 UTC 오프셋이다.

## 로컬 빌드

Node 24, pnpm 10.29.2, Rust 1.98.1, Python 3, GitHub CLI를 사용한다. 저장소 루트에서 `pnpm install --frozen-lockfile`, `pnpm wasm:setup`, `pnpm wasm:build` 순서로 실행한다. `wasm:setup`은 WASM 대상과 고정 버전의 wasm-bindgen CLI·cargo-about를 준비한다. Cargo 의존성은 엔진별 잠금 파일로 고정한다.

`pnpm --filter @typstninja/wasm versions:check`는 공식 안정 릴리스와 목록을 대조한다. 새 엔진은 `versions.json`, 정확한 버전의 Cargo manifest·lock, 호환 코드와 Wiki를 함께 갱신한 뒤 추가한다. 빌드 결과는 `dist/<배포 버전>/`에 생성된다.

## 배포

저장소 루트의 `pnpm release`에서 Typst WASM을 선택한다. 버전 선택 후 모든 엔진을 빌드하고 커밋·태그·푸시를 각각 확인한다. 이후 GitHub Releases에 엔진별 ZIP·SHA-256 목록·manifest를 게시하고 Wiki를 동기화한다. IntelliJ 플러그인 항목은 아직 자리표시자다.

GitHub CLI의 기존 로그인으로 게시하므로 npm 토큰이나 추가 CI 비밀 값은 필요 없다. 실행 중 거절·취소하면 다음 단계로 진행하지 않으며 이미 바뀐 버전·로컬 커밋은 남겨 점검할 수 있게 한다. 게시 이후 Wiki 동기화만 실패한 경우 `pnpm wiki:sync --tag <태그>`로 재시도한다. 기존 공개 릴리스의 파일을 덮어쓰지 않는다.

정상 배포는 변경 사항을 커밋한 `main`과 `origin/main`에서 시작한다. 최초 게시나 업로드 재시도는 현재 배포 버전을 빌드하고 소스 커밋을 원격에 푸시한 뒤 `pnpm wasm:publish`로 실행할 수 있다. 이 명령은 버전을 올리지 않는다. 빌드 소스와 `HEAD`, 전체 엔진 목록, 업로드 후 다운로드한 파일의 체크섬이 일치해야 게시한다. Wiki 사용법 원본은 `docs/wiki/Typst-WASM.md`, 다운로드 목록은 게시된 `manifest.json`이다.

자세한 지원 버전과 검증·다운로드 정보는 [GitHub Wiki](https://github.com/buYoung/intellij-typst/wiki/Typst-WASM)를 따른다.
