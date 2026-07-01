# intellij-typst 개발 가이드

이 문서는 개발 환경 설정, 코딩 컨벤션, 기능 구현, 빌드/배포, 문제 해결을 다룬다.
디렉터리/패키지 구조와 파일 배치 규칙은 [PROJECT_STRUCTURE.md](./PROJECT_STRUCTURE.md)를 참고한다.

## 1. 개발 환경 설정

### 1.1 필수 요구사항

- JDK 21
- IntelliJ IDEA
- Gradle 래퍼(`./gradlew`)
- IntelliJ Platform Gradle Plugin 2.x

현재 저장소의 주요 버전 기준은 다음과 같다.

| 항목 | 기준 |
| --- | --- |
| Kotlin Gradle plugin | `2.1.20` |
| IntelliJ Platform Gradle Plugin | `2.16.0` |
| Grammar-Kit 생성 플러그인 | `org.jetbrains.intellij.platform.grammarkit` (IPGP `2.16.0`에 포함, 버전 자동 정렬) |
| Gradle wrapper | `9.5.0` |
| 대상 IDE | `2025.2.6.2` |
| 플러그인 ID | `com.livteam.typninja` |
| 기준 패키지 | `com.livteam.typninja` |

> Kotlin과 Gradle은 별도 전역 설치를 전제로 하지 않는다. Gradle은 프로젝트에 포함된 래퍼를 사용하고, Kotlin 및 IntelliJ Platform 관련 버전은 `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`에서 확인한다.

### 1.2 프로젝트 설정

1. 프로젝트를 클론한다.

   ```bash
   git clone https://github.com/buYoung/intellij-typst.git
   ```

2. IntelliJ IDEA에서 프로젝트를 연다.
3. Gradle 동기화를 실행한다.
4. `.run` 디렉터리에 포함된 실행 구성을 사용하거나 Gradle 태스크를 직접 실행한다.

## 2. 코딩 컨벤션

### 2.1 Kotlin 코딩 스타일

- Kotlin 공식 코딩 컨벤션을 따른다.
- 들여쓰기는 4칸 공백을 사용한다.
- 한 줄은 가능한 한 120자 안쪽으로 유지한다.
- 사용자에게 노출되는 문자열은 코드에 직접 쓰지 않고 `src/main/resources/messages`의 리소스 번들에 둔다.

### 2.2 명명 규칙

- 클래스: `PascalCase`
- 함수/변수: `camelCase`
- 상수: `UPPER_SNAKE_CASE`
- 패키지: 소문자
- 서비스 클래스: `*Service`
- 액션 클래스: `*Action`
- 설정 클래스: `*Settings`, `*Configurable`, `*State`
- Typst 언어 모델 클래스: `Typst*`

같은 개념에는 같은 단어를 사용한다. 예를 들어 Typst 파일, Typst 토큰, Typst PSI처럼 기능 경계가 같은 항목은 `Typst` 접두어를 일관되게 사용한다.

### 2.3 IntelliJ Platform 기본 규칙

- `plugin.xml`은 IDE가 호출하는 확장 계약이다. 새 확장, 액션, 리스너, 설정 화면은 `src/main/resources/META-INF/plugin.xml`에 등록한다.
- 확장 구현체는 상태를 들고 있지 않게 유지하고, 수명주기가 필요한 상태는 서비스에 둔다.
- 프로젝트 단위 상태는 `@Service(Service.Level.PROJECT)` 서비스에 둔다.
- 장시간 작업은 EDT에서 실행하지 않는다.
- PSI, VFS, `Document`, 프로젝트 모델을 읽거나 쓸 때는 IntelliJ 읽기/쓰기 규칙을 따른다.
- 동적 플러그인 재적재를 방해하는 정적 캐시와 전역 싱글턴 상태를 피한다.

## 3. 기능 구현 가이드

이 저장소는 IntelliJ Platform Plugin Template에서 시작했지만, 장기 기준은 Typst 네이티브 언어 플러그인이다.
새 기능의 위치와 소유권은 [PROJECT_STRUCTURE.md](./PROJECT_STRUCTURE.md)를 우선한다.

### 3.1 현재 템플릿 코드

현재 다음 클래스는 템플릿에서 온 샘플 코드 성격이 강하다.

- `MyProjectService`
- `MyProjectActivity`
- `MyToolWindowFactory`
- `MyPluginTest`

Typst 기능을 실제로 구현할 때는 이 샘플 코드를 그대로 확장하기보다, 기능 소유 패키지로 이동하거나 제거하는 방향을 우선 검토한다. 예를 들어 시작 시점 동작은 `listeners`, 도구 창은 `ui/toolWindow`, 언어 확장은 `language` 또는 `extensions` 아래에 둔다.

### 3.2 Typst 언어 서비스

Typst 언어 지원은 외부 언어 서버가 아니라 IntelliJ 네이티브 언어 파이프라인 위에 구현한다.
기능의 제품 범위와 경계는 [Typst Native Language Services FDD](./FDD/typst-native-language-services.md)를 기준으로 한다.

주요 구현 범위는 다음과 같다.

- `.typ` 파일 타입과 Typst 언어 식별
- 렉서, 파서, PSI 파일, PSI 요소
- 참조 해석, 선언 이동, 이름 변경의 기반 모델
- 자동완성, 문서화, 구조 보기, 진단
- 구문 강조와 포매터가 공유할 토큰 및 구문 개념

언어 기능 코드는 `src/main/kotlin/com/livteam/typninja/language` 아래에 모은다. 여러 기능에서 공유하는 Typst 문맥 판정, 범위 계산, 이름 해석 보조 로직도 가능하면 `language` 아래의 명확한 하위 패키지에 둔다.

### 3.3 Typst 구문 강조

구문 강조의 범위와 표시 정책은 [Typst Syntax Highlighting FDD](./FDD/typst-syntax-highlighting.md)를 기준으로 한다.

구현 시 다음 원칙을 지킨다.

- 빠른 렉서 기반 강조를 기본으로 한다.
- 의미 해석이 필요한 무거운 작업을 강조 경로에 넣지 않는다.
- 색상 키는 IDE 테마와 사용자 색상 설정을 존중하도록 정의한다.
- 알 수 없는 문자나 복구 불가능한 토큰은 제한된 범위에서만 문제로 표시한다.

구문 강조 관련 코드는 `language/highlighting` 아래에 둔다.

### 3.4 Typst 포매터와 주석 토글

포매터의 보존 정책과 실패 정책은 [Typst Formatting FDD](./FDD/typst-formatting.md)를 기준으로 한다.

구현 시 다음 원칙을 지킨다.

- 구조가 명확한 코드 표현식, 함수 호출, 배열, 딕셔너리, 블록, 수식 구간만 적극적으로 정리한다.
- 마크업 본문의 줄바꿈과 공백은 사용자 의도를 우선해 보존한다.
- 선택 영역 포맷은 안전하게 해석 가능한 범위에서만 적용한다.
- 주석 토글은 v1에서 Typst 줄 주석 중심으로 제공한다.

포매터 관련 코드는 `language/formatting` 아래에 둔다.

### 3.5 설정과 사용자 인터페이스

설정 상태는 IntelliJ의 `PersistentStateComponent` 계열을 사용한다.
설정 화면은 Kotlin UI DSL 또는 IntelliJ 표준 Swing 컴포넌트를 사용하고, 설정 상태와 화면 조립 코드를 분리한다.

- 설정 상태: `settings`
- 설정 화면: `settings` 또는 `ui`
- 도구 창: `ui/toolWindow`
- 공용 UI 컴포넌트: `ui/component`

### 3.6 Typst 렉서/파서 소스 생성

Typst 렉서와 파서는 손으로 작성하지 않고 Grammar-Kit / JFlex 생성기로 만드는 것을 기준으로 한다. 별도의 standalone `org.jetbrains.grammarkit` 플러그인 대신, IntelliJ Platform Gradle Plugin `2.16.0`에 내장된 `org.jetbrains.intellij.platform.grammarkit` 플러그인을 사용한다. 이 네이티브 모듈은 Gradle `9.x`를 최소 기준으로 동작하도록 설계되어 현재 빌드 환경(Gradle 래퍼 `9.5.0`, JDK 21)과 맞고, 곧 지원이 종료되는 standalone 플러그인을 대체한다.

문법 입력과 생성 출력 경로는 다음과 같다.

| 항목 | 경로 |
| --- | --- |
| JFlex 렉서 입력 | `src/main/grammar/Typst.flex` |
| Grammar-Kit 파서/PSI 입력 | `src/main/grammar/Typst.bnf` |
| 생성된 렉서 출력 루트 | `build/generated/sources/grammarkit-lexer/java/main` |
| 생성된 파서/PSI 출력 루트 | `build/generated/sources/grammarkit-parser/java/main` |

생성 출력은 `build/` 아래에 있어 버전 관리에 포함하지 않으며, `src/main/kotlin`의 손으로 작성한 `com.livteam.typninja` 코드와 디렉터리가 절대 겹치지 않는다. 두 출력 루트는 `build.gradle.kts`에서 메인 소스 세트의 자바 소스 루트로 등록되어 있어 생성된 코드가 컴파일 대상에 포함된다.

재생성 명령은 다음과 같다.

```bash
./gradlew generateLexer generateParser
```

IntelliJ IDEA에서는 Gradle 도구 창에서 `generateLexer`, `generateParser` 태스크를 직접 실행할 수도 있다.

문법 파일이 추가되는 단계(렉서/파서/PSI 구현 단계)에서는 컴파일 전에 생성이 먼저 실행되도록 `build.gradle.kts`에 다음 의존성을 추가한다. 현재 단계에서는 문법 파일이 아직 없어 `./gradlew compileKotlin`을 깨뜨리지 않도록 이 연결을 의도적으로 추가하지 않았다.

```kotlin
tasks.named("compileKotlin") { dependsOn("generateLexer", "generateParser") }
tasks.named("compileJava") { dependsOn("generateLexer", "generateParser") }
```

생성기 실행에는 빌드 시점에 Grammar-Kit / JFlex 아티팩트가 필요하다. 이는 `build.gradle.kts`의 `grammarKit()` / `jflex()` 의존성 헬퍼로 선언되며, 플러그인 런타임 의존성이 아니라 빌드 도구 의존성이다. 최초 실행 시 다운로드를 위해 네트워크가 필요할 수 있고 이후에는 Gradle 캐시로 동작한다.

## 4. 테스트 작성 가이드

테스트를 추가해야 하는 변경에서는 IntelliJ Platform 테스트 프레임워크를 사용한다.

- 테스트 클래스는 필요한 경우 `BasePlatformTestCase`를 상속한다.
- 테스트 러너는 JUnit 4 기준이다.
- 테스트 코드는 `src/test/kotlin/com/livteam/typninja` 아래에 메인 패키지와 대응되는 구조로 둔다.
- 테스트 데이터는 `src/test/testData` 아래에 둔다.
- Typst 입력 예시는 `src/test/testData/typst/<feature>` 형태로 기능별로 나눈다.

테스트 생성은 변경의 위험도와 사용자 요청 범위를 기준으로 결정한다. 문서만 수정하는 변경에서는 별도 테스트 파일을 추가하지 않는다.

## 5. 빌드 및 배포

### 5.1 로컬 빌드

```bash
./gradlew build
```

### 5.2 플러그인 실행

```bash
./gradlew runIde
```

`runIde`는 `.intellijPlatform/sandbox` 아래의 격리된 샌드박스 IDE를 사용한다. 샌드박스의 설정, 플러그인, 캐시는 평소 사용하는 IDE와 분리된다.

### 5.3 테스트 실행

```bash
./gradlew check
```

IntelliJ IDEA에서는 `.run/Run Tests.run.xml` 실행 구성을 사용할 수 있다.

### 5.4 플러그인 검증

```bash
./gradlew verifyPlugin
```

IntelliJ IDEA에서는 `.run/Run Verifications.run.xml` 실행 구성을 사용할 수 있다.

### 5.5 배포용 빌드

```bash
./gradlew buildPlugin
```

생성된 플러그인 ZIP 파일은 `build/distributions/` 디렉터리에서 확인한다.

### 5.6 배포

GitHub Actions는 다음 흐름을 기준으로 구성되어 있다.

- `Build`: `buildPlugin`, `check`, `verifyPlugin` 실행 및 릴리스 초안 생성
- `Release`: 릴리스 이벤트에서 `publishPlugin` 실행

Marketplace 배포에는 다음 비밀 값이 필요하다.

- `PUBLISH_TOKEN`
- `CERTIFICATE_CHAIN`
- `PRIVATE_KEY`
- `PRIVATE_KEY_PASSWORD`

배포 전에 `CHANGELOG.md`, `gradle.properties`의 `version`, `plugin.xml`의 플러그인 설명, Marketplace 배지와 식별자를 함께 확인한다.

## 6. 문제 해결

### 6.1 Gradle 동기화 실패

- 사용하는 JDK가 21인지 확인한다.
- IntelliJ IDEA에서 Gradle JVM 설정을 확인한다.
- Gradle 캐시 문제로 보이면 다음 명령을 시도한다.

  ```bash
  ./gradlew --stop
  ./gradlew build --refresh-dependencies
  ```

### 6.2 샌드박스 IDE 실행 문제

- `./gradlew runIde`로 재현한다.
- 로그는 `.intellijPlatform/sandbox/*/*/log/idea.log`에서 확인한다.
- `.run/Run Plugin.run.xml` 실행 구성을 사용할 때도 같은 샌드박스 로그를 확인한다.

### 6.3 플러그인이 로드되지 않음

- `src/main/resources/META-INF/plugin.xml`이 올바르게 파싱되는지 확인한다.
- `<depends>com.intellij.modules.platform</depends>`가 유지되는지 확인한다.
- 새 확장 지점의 이름, `language` 값, 구현 클래스 패키지가 실제 코드와 일치하는지 확인한다.
- 샘플 코드 제거 또는 패키지 이동 후 `plugin.xml` 등록 항목이 남아 있지 않은지 확인한다.

### 6.4 테스트 실패

- 실패한 테스트의 샌드박스 로그는 `.intellijPlatform/sandbox/*/*/log-test/idea.log`에서 확인한다.
- 테스트 데이터 경로는 `src/test/testData` 기준으로 맞춘다.
- PSI 기반 테스트는 파일 타입과 언어 등록이 먼저 완료되어야 안정적으로 동작한다.

### 6.5 Plugin Verifier 실패

- `./gradlew verifyPlugin` 결과와 `build/reports/pluginVerifier` 아래 보고서를 확인한다.
- 내부 API 사용, 누락된 의존성, 지원 IDE 범위와 맞지 않는 API 호출을 우선 확인한다.
- 새 IntelliJ 확장을 추가했다면 `plugin.xml` 등록과 Gradle의 플랫폼 의존성을 함께 점검한다.
