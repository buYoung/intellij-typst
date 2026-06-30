# intellij-typst 프로젝트 구조 규칙

이 문서는 `intellij-typst` 레포에서 새 파일과 새 패키지를 어디에 둘지 결정하는 기준이다. 현재 레포는 IntelliJ Platform Plugin Template에서 시작했지만, 앞으로의 기준은 Typst 언어 플러그인이다.

## 1. 기본 원칙

- 플러그인 런타임 코드는 `src/main` 아래에만 둔다.
- 프로젝트와 플러그인 구현의 기준 패키지는 `com.livteam.typninja`이다.
- 사용자에게 노출되는 문자열은 코드에 직접 쓰지 않고 `src/main/resources/messages`의 번들에 둔다.
- IntelliJ 확장 등록은 `src/main/resources/META-INF/plugin.xml`에 모은다.
- 기능 설계 문서는 `docs/FDD`에 두고, 레포 구조와 개발 규칙 문서는 `docs` 바로 아래에 둔다.
- 외부 참조 코드와 실험 코드는 플러그인 본체 패키지와 섞지 않는다.

## 2. 기준 디렉터리 구조

```text
.
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
├── docs/
│   ├── DEVELOPMENT_GUIDE.md
│   ├── PROJECT_STRUCTURE.md
│   └── FDD/
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── com/livteam/typninja/
│   │   │       ├── actions/
│   │   │       ├── extensions/
│   │   │       ├── icons/
│   │   │       ├── language/
│   │   │       ├── listeners/
│   │   │       ├── model/
│   │   │       ├── services/
│   │   │       ├── settings/
│   │   │       ├── ui/
│   │   │       └── utils/
│   │   └── resources/
│   │       ├── META-INF/
│   │       ├── icons/
│   │       ├── messages/
│   │       └── typst/
│   └── test/
│       ├── kotlin/
│       └── testData/
```

없는 디렉터리는 기능이 실제로 필요해질 때 만든다. 빈 패키지를 미리 만들지 않는다.

## 3. Kotlin 패키지 규칙

`src/main/kotlin/com/livteam/typninja` 아래는 역할 기준으로 나눈다.

- `actions`: 메뉴, 단축키, 에디터 명령처럼 `AnAction`으로 시작하는 사용자 명령.
- `extensions`: IntelliJ 확장 지점 구현체. 언어 파일 타입, 파서 정의, 강조 색상 제공자, 완성 제공자처럼 `plugin.xml`에 등록되는 진입점을 둔다.
- `icons`: 코드에서 참조하는 아이콘 상수와 아이콘 선택 로직.
- `language`: Typst 언어 모델의 중심 패키지. 파일 타입, 토큰, 어휘 분석기, 파서, PSI, 포맷터, 주석 처리기, 코드 통찰 기능의 공통 언어 계층을 둔다.
- `language/highlighting`: Typst 구문 강조와 색상 키, 속성 설명자.
- `language/psi`: Typst PSI 타입, PSI 파일, PSI 유틸리티.
- `language/formatting`: Typst 포맷터, 공백 규칙, 블록 규칙.
- `language/completion`: Typst 자동완성 제공자와 후보 모델.
- `language/references`: 참조, 선언 이동, 이름 변경에 필요한 참조 처리.
- `language/diagnostics`: 구문 오류, 약한 검증, 주석 기반 경고처럼 에디터에 표시되는 진단.
- `listeners`: 시작 활동, 프로젝트 열림, 애플리케이션 수명주기 리스너.
- `model`: 여러 계층에서 공유되는 값 객체, 열거형, 설정 모델.
- `services`: 프로젝트 또는 애플리케이션 서비스. 색인, 문서 상태, Typst 표준 라이브러리 메타데이터, 캐시처럼 수명주기가 있는 로직.
- `settings`: 설정 상태, 설정 화면, 설정 변경 알림.
- `ui`: Swing 또는 IntelliJ UI DSL 기반 화면.
- `ui/component`: 여러 화면에서 재사용되는 UI 컴포넌트.
- `ui/dialog`: 대화상자와 대화상자 발표자.
- `ui/toolWindow`: 도구 창이 생길 경우의 팩토리와 화면 조립 코드.
- `utils`: 특정 기능의 소유권이 없는 작은 유틸리티. 기능 전용 유틸리티는 해당 기능 패키지 안에 둔다.

현재 템플릿의 `startup`과 최상위 `toolWindow` 패키지는 장기 기준이 아니다. 해당 기능을 계속 유지한다면 각각 `listeners`, `ui/toolWindow`로 이동하는 방향을 따른다.

## 4. Typst 언어 기능 배치

Typst 언어 지원은 `language` 아래에 모은다. `actions`나 `services`에 언어 처리 핵심 로직을 흩어 놓지 않는다.

- 파일 타입, 언어 객체, 파서 정의, 토큰 타입은 `language` 또는 `language/psi`에 둔다.
- 어휘 분석과 파싱은 IntelliJ 사용자 지정 언어 파이프라인을 따른다.
- 구문 강조는 `language/highlighting`에 두고, 의미 분석이나 프로젝트 색인에 의존하지 않는 것을 기본으로 한다.
- 포맷팅은 `language/formatting`에 두고, 외부 포맷터 실행 없이 IntelliJ 포맷터 API 경계 안에서 처리한다.
- 완성, 참조, 선언 이동, 이름 변경, 진단은 각각 `language/completion`, `language/references`, `language/diagnostics`에 둔다.
- 여러 언어 기능이 공유하는 Typst 문맥 판정, 범위 계산, 이름 해석 보조 로직은 `language` 아래의 명확한 하위 패키지에 둔다.

사용자가 “lsp”라고 부르는 기능은 이 레포에서는 외부 언어 서버 프로토콜 서버가 아니라 IntelliJ 네이티브 코드 통찰 기능으로 구현한다.

## 5. 리소스 배치 규칙

`src/main/resources` 아래는 플러그인에 번들되는 파일만 둔다.

- `META-INF/plugin.xml`: 의존성, 확장, 리스너, 액션, 설정 화면, 도구 창 등록.
- `messages`: `MyBundle.properties`와 이후 지역화 번들. 번들 이름을 바꾸는 경우 코드와 `plugin.xml`을 함께 바꾼다.
- `icons`: 플러그인 아이콘, 액션 아이콘, 언어 아이콘.
- `typst`: Typst 표준 라이브러리 메타데이터, 문법 보조 데이터, 내장 스니펫처럼 플러그인 실행에 필요한 정적 자료.

`plugin.xml`은 기능별로 읽히도록 정리한다. 권장 순서는 의존성, 리소스 번들, 언어 등록, 에디터 기능 등록, 리스너, 액션, 설정 화면, 도구 창이다.

## 6. 테스트와 테스트 데이터

- 테스트 코드는 `src/test/kotlin/com/livteam/typninja` 아래에서 운영 코드 패키지 구조를 따라간다.
- IntelliJ fixture가 직접 읽는 파일은 `src/test/testData` 아래에 둔다.
- 테스트 리소스로 클래스패스 로딩이 필요한 파일만 `src/test/resources`에 둔다.
- Typst 입력 예시는 `src/test/testData/typst/<feature>` 형태로 기능별로 나눈다.

이 문서는 테스트 생성을 요구하지 않는다. 테스트 파일은 사용자가 명시적으로 요청했거나 변경 검증에 필요하다고 합의한 경우에만 추가한다.

## 7. 문서 배치 규칙

- `docs/FDD`: 기능 설계 문서. 구현 절차가 아니라 기능의 결정 사항과 경계를 기록한다.
- `docs/DEVELOPMENT_GUIDE.md`: 개발 환경, 코딩 규칙, 빌드/배포, 문제 해결 기준.
- `docs/PROJECT_STRUCTURE.md`: 폴더 구조와 파일 배치 규칙.

FDD, 개발 가이드, 구조 문서는 서로 역할이 다르다. 기능의 행동과 범위는 FDD에 쓰고, 개발 흐름과 명령은 개발 가이드에 쓰며, 파일 배치와 소유권은 이 문서에 쓴다.

## 8. 외부 코드와 생성물 경계

- 외부 참조 코드는 레포에 상시 보관하지 않는다.
- 외부 참조 코드에서 아이디어를 가져오더라도 문서와 구현은 이 플러그인의 구조와 IntelliJ API 기준으로 설명한다.
- 참고용으로 임시 복제한 외부 레포는 분석이 끝나면 제거하고, 플러그인 런타임 코드가 외부 복제본에 직접 의존하지 않게 한다.
- `build`, `.gradle`, `.idea`, `.intellijPlatform`, `.kotlin`, `.codemap`, `node_modules`는 생성물 또는 로컬 작업 상태로 취급한다.
- 인증서, 개인 키, 로컬 토큰, 개인 설정 파일은 레포 구조 규칙의 대상이 아니며 새로 추가하지 않는다.

## 9. 새 파일 위치 결정 절차

새 파일을 만들기 전에 다음 순서로 판단한다.

1. IntelliJ가 직접 호출하는 등록 진입점인가? 그렇다면 `actions`, `extensions`, `listeners`, `settings`, `ui/toolWindow` 중 하나에 둔다.
2. Typst 문법, PSI, 강조, 포맷, 완성, 참조, 진단을 다루는가? 그렇다면 `language` 아래에 둔다.
3. 수명주기와 캐시가 필요한 공유 로직인가? 그렇다면 `services`에 둔다.
4. 화면 조립이나 사용자 상호작용인가? 그렇다면 `ui` 아래에 둔다.
5. 여러 기능에서 공유되는 값 모델인가? 그렇다면 `model`에 둔다.
6. 위 어느 쪽도 아니고 매우 작은 보조 함수인가? 그렇다면 `utils`를 고려하되, 특정 기능 전용이면 해당 기능 패키지 안에 둔다.

판단이 애매하면 더 넓은 공용 패키지보다 기능 소유 패키지를 우선한다.
