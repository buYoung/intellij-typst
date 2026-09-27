# AGENTS.md

<!-- agents-md-generator: v1; doc-type: monorepo_root -->

## 1. 개요

Typstninja는 IntelliJ의 Typst 편집 기능과 JavaScript와 JVM에서 실행하는 Typst WASM 컴파일러를 함께 관리한다.

## 2. 책임 경계

- 루트 Gradle 프로젝트와 `src/`가 IntelliJ 플러그인을 관리한다. 플러그인 버전의 원본은 `gradle.properties`다. 컴파일·진단·내보내기는 `runtime/`의 Chicory WASM 호스트를 사용하고, JCEF는 미리보기 표시에만 사용한다.
- pnpm 모노레포의 `packages/typst-wasm`은 WASM 컴파일러와 엔진별 배포를 관리한다. `packages/typst-plugin`은 비활성 배포 자리표시자이며 별도 플러그인 버전을 갖지 않는다.
- WASM 배포 묶음 버전은 `packages/typst-wasm/package.json`, Typst 엔진 목록은 `versions.json`을 따른다. 두 버전과 플러그인 버전을 혼동하지 않는다.

### WASM 변경과 검증

- Typst 0.13.0부터 최신까지 모든 안정 패치 버전을 유지한다. 각 엔진의 Typst 내부 구성요소도 같은 패치 버전으로 고정하고 Cargo 잠금 파일을 커밋한다. 공유 Cargo 캐시에서 출력이 섞이지 않도록 엔진별 라이브러리 이름을 구분한다.
- 변경 흐름은 엔진 manifest·잠금 파일 → 공통 Rust `World` → JavaScript·raw ABI → JVM 호스트 → PDF·PNG·SVG·HTML·진단·위치 이동 소비자까지 확인한다. API 옵션과 진단 위치의 단위·구조를 보존한다. 중단되거나 트랩이 발생한 WASM 인스턴스는 재사용하지 않는다. `plugin-manifest.json`은 전체 WASM 빌드가 갱신하며 플러그인에 포함할 엔진의 체크섬을 고정한다.
- 필요한 기존 검증 명령을 작업 디렉터리와 함께 먼저 알린 뒤 실행한다. `pnpm wasm:build`는 기존 샘플을 이용해 실제 WASM 산출물을 검증한다. 명시적 요청 없이 새 테스트 사례나 린트·서식 도구를 추가하지 않는다.

## 3. 작업 규칙

- 응답과 문서 제목은 사용자가 다른 언어를 요청하지 않으면 한국어로 작성한다.
- **Typst WASM 업데이트 시 GitHub Wiki를 반드시 갱신한다.** API·제약·빌드 사용법은 `docs/wiki/Typst-WASM.md`를 수정하고, 게시된 엔진별 다운로드와 체크섬은 릴리스 manifest를 기준으로 갱신한다.
- `pnpm release`는 대화형 진입점이다. Typst WASM의 버전 선택·빌드·커밋·태그·푸시 후 GitHub Releases와 Wiki를 게시한다. 거절·취소하면 이후 단계로 진행하지 않는다.
- 정상 배포 전에 저장소 전체의 추적 파일과 인덱스가 깨끗해야 한다. 전체 엔진 목록, 빌드 소스와 원격에 존재하는 커밋의 일치, 업로드 자산을 다시 다운로드한 체크섬 검증을 통과해야 공개한다.
- `typst-wasm-v*` 태그로 플러그인 릴리스 작업을 실행하지 않는다. 공개된 릴리스 파일을 덮어쓰거나 실패 복구를 위해 무관한 변경을 되돌리지 않는다.
- Wiki 갱신은 이 기능이 관리하는 페이지만 수정하고 일반 푸시로 동시 수정 충돌을 드러낸다. 게시 후 Wiki 갱신이 실패하면 릴리스는 게시된 상태임을 정확히 보고하고 `pnpm wiki:sync --tag <태그>`로 복구한다.
