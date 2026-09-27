# Typstninja Marketplace 등록 정보와 소개문

JetBrains Marketplace 페이지를 준비할 때 참고할 **등록 정보 명세와 게시 문안 초안**이다. 기준일은 2026-09-26이며, 로컬 기능·설정을 기준으로 작성했고, 2026-09-27 출시 버전을 `0.2.0`으로 확정했다. 실제 상점 등록값이나 게시 완료 기록은 아니다.

로고·스크린샷 규격은 [시각 자산 규격](../visual-assets/stores/jetbrains-marketplace.md), T·청록색과 v3 스타일은 [디자인 기준](../visual-assets/index.md)을 따른다.

## 등록 정보 한눈에 보기

“확인”은 저장소나 명시한 외부 자료에서 읽은 값, “초안”은 이번에 작성한 게시 문구, “미확정”은 관리 화면이나 사용자 선택이 필요한 항목이다.

| 항목 | 값·상태 | 근거 또는 담당 위치 |
| --- | --- | --- |
| 제품명 | 확인: `Typstninja` | [plugin.xml](../../../src/main/resources/META-INF/plugin.xml)의 `<name>` |
| 플러그인 식별자 | 확인: `com.livteam.typninja` | `plugin.xml`의 `<id>`. Marketplace 페이지의 ID·주소는 별도 확인 필요 |
| 개발자 표시 | 확인: `livTeam` | `plugin.xml`의 `<vendor>`. 실제 Vendor 프로필 선택은 미확인 |
| 버전 | 확인: `0.2.0` | [gradle.properties](../../../gradle.properties) |
| 짧은 소개·상세 설명 | 초안: 아래 영문 HTML | [소개문](#소개문) |
| 시작 안내 | 초안: 설치 후 파일 열기·설정·미리보기·출력 | [시작 안내](#시작-안내) |
| 변경 내역 | 초안: `0.2.0` 기능 요약 | [변경 내역](#변경-내역) |
| 최소 IDE 버전 | 설정 확인: 2024.3, `sinceBuild = "243"` | [호환성](#호환성) |
| 소스 라이선스 | 확인: Apache License 2.0 | [LICENSE](../../../LICENSE). 실제 상점 License 입력값은 미확인 |
| 웹사이트·소스·지원 링크 | 확인값과 후보를 구분 | [연락처와 링크](#연락처와-링크) |
| 태그·분류 | 미확정: Typst 언어 지원에 해당하는 실제 태그 선택 | [태그와 가격](#태그와-가격) |
| 가격·결제 방식 | 미확정 | 소스 라이선스만으로 무료·유료를 결정하지 않음 |
| 릴리스 채널·숨김 설정 | 로컬 설정: `default`, `hidden = true` | [배포 상태](#배포-상태) |
| 공개 상점 주소·설치 링크 | 미확인 | 숫자 ID나 URL을 추정해서 만들지 않음 |
| 로고·미디어 | Quiver SVG 변환·패키지 적용과 정보형 이미지 4장 완료. 실제 제품 캡처·상점 게시 전 | [자산 목록](../visual-assets/asset-system.md) |

## 소개문

상점 설명은 영어를 기본 언어로 제공하고 번역을 함께 넣으면 영어를 먼저 둔다. [승인 기준 §1.4](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)

설명의 첫 40자는 영문 요약으로 사용되며, 카드 문구를 별도 필드처럼 독립 편집하는 방식은 아니다. 아래 첫 문단이 짧은 소개를 겸한다. [공식 소개문 안내](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html#plugin-description)

아래 HTML은 [현재 README](../../../README.md)와 [플러그인 설명](../../../src/main/resources/META-INF/plugin.xml)의 내용을 사용자 작업 중심으로 정리한 초안이다. `plugin.xml`이나 실제 상점에는 아직 반영하지 않았다.

```html
<p>Typst editing, navigation, and previews.</p>

<p>Write Typst documents with syntax highlighting, code completion,
navigation, and diagnostics. View source and rendered output side by side
in supported IntelliJ-based IDEs.</p>

<ul>
  <li><b>Edit Typst files:</b> syntax highlighting, code folding,
      line comments, and formatting for closed code groups while
      preserving surrounding markup.</li>
  <li><b>Navigate your document:</b> go to supported declarations, labels,
      and relative imports. Find usages of supported file-local
      <code>#let</code> declarations.</li>
  <li><b>Get help while writing:</b> contextual completion, quick
      documentation, and signature help for supported local and
      standard-library symbols.</li>
  <li><b>Inspect problems:</b> editor diagnostics and optional compiler
      diagnostics on save or after typing.</li>
  <li><b>Preview alongside source:</b> source, preview, and split views
      with document links and click-to-source navigation.</li>
  <li><b>Use packages and export:</b> download completed
      <code>@preview/name:version</code> imports and export to PDF, PNG,
      SVG, or HTML using the bundled Typst WASM engine.</li>
</ul>

<p><b>Requirements:</b> IntelliJ-based IDE 2024.3 or newer.
Document preview requires a JCEF-capable IDE runtime.
Compilation and exports run inside the IDE JVM without JCEF or Typst CLI.</p>

<p><b>Network access:</b> the plugin may download additional WASM engine versions from
GitHub Releases and Typst packages from packages.typst.org over HTTPS.
It does not collect telemetry or analytics.</p>

<p><a href="https://github.com/buYoung/intellij-typst">Source code and documentation</a></p>
```

기능을 “모든 문법 완벽 지원”, “설정 없이 모든 IDE에서 미리보기”, “모든 엔진의 HTML 기능 완성”으로 확대하지 않는다. 위 네트워크·수집 설명은 저장소의 현재 명시 내용이며, 이번 문서 작업에서 별도 네트워크 감사나 기능 실행을 수행한 것은 아니다.

## 시작 안내

Getting started는 페이지 관리 화면에서 제공할 수 있는 안내다. 다음 초안은 **플러그인을 설치한 뒤**의 흐름이며, 공개 설치 링크가 확인되면 그 앞에 연결한다. [공식 Getting started 안내](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html#getting-started-section)

```html
<ol>
  <li>Open a project containing a <code>.typ</code> file.</li>
  <li>Open <b>Settings → Tools → Typst</b> to review project,
      WASM engine, and compiler settings.</li>
  <li>Use the editor's preview or split view to see the rendered document
      when JCEF is available.</li>
  <li>For file export, configure the Typst executable and output settings,
      then choose <b>Tools → Export Typst</b>.</li>
</ol>
```

설정 경로와 동작은 [등록 정보](../../../src/main/resources/META-INF/plugin.xml), 설정 항목은 [TypstSettingsConfigurable](../../../src/main/kotlin/com/livteam/typninja/settings/TypstSettingsConfigurable.kt)에 근거한다. 실제 IDE에서 이 안내를 따라 실행한 결과는 아직 확인하지 않았다.

## 변경 내역

`0.2.0`용 문안 초안이다. [CHANGELOG](../../../CHANGELOG.md)에 기록된 날짜와 기능은 로컬 기록이며, 실제 상점 출시일을 입증하지 않는다.

```html
<p><b>0.2.0</b></p>
<ul>
  <li>Added Typst file recognition, syntax highlighting, code folding,
      line comments, and conservative code formatting.</li>
  <li>Added navigation, completion, documentation, diagnostics,
      and editor services for supported Typst constructs.</li>
  <li>Added optional compiler diagnostics and package downloads.</li>
  <li>Added editor-integrated document preview with split view and
      click-to-source navigation, plus WASM-based exports.</li>
</ul>
```

제품명·변경 내역은 배포 패키지의 `plugin.xml`과 연결된다. 상세 설명은 패키지 값 또는 페이지에서 별도 관리하는 값을 사용할 수 있으므로, 반영할 때 현재 설명의 관리 위치를 확인한다. [공식 페이지 정보 안내](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)

## 호환성

[build.gradle.kts](../../../build.gradle.kts)는 최소 빌드 `243`, 상한 미지정으로 구성되어 있다. 검증 대상 설정에는 IntelliJ IDEA Community `2024.3.7.1`, `2025.2.6.2`, IntelliJ IDEA `2026.2`가 있다. 이는 **검증 대상 설정**이며 이번 작업에서 검증을 실행하거나 통과 결과를 확인한 것은 아니다.

상점의 최종 지원 IDE 목록과 버전은 업로드된 패키지·검증 결과·관리 화면을 확인해야 한다. 상한이 없다는 이유로 모든 향후 IDE 버전의 동작을 보장하는 표현을 쓰지 않는다.

## 연락처와 링크

| 항목 | 값 또는 후보 | 확인 상태 |
| --- | --- | --- |
| Vendor website | [프로젝트 저장소](https://github.com/buYoung/intellij-typst) | 현재 `<vendor url>` 값이며 웹 도구에서 공개 저장소 페이지를 열었음 |
| Source code | [프로젝트 저장소](https://github.com/buYoung/intellij-typst) | README와 배포 설정에서 확인한 URL |
| Documentation | 위 저장소 README를 초기 연결 대상으로 사용 가능 | 이번 웹 조회가 반환한 공개 README와 로컬 최신 README 내용이 달라 게시 전 동기화 확인 필요 |
| Issue tracker | [GitHub Issues](https://github.com/buYoung/intellij-typst/issues) | 공개 저장소 페이지의 Issues 연결은 확인. 대상 페이지 조회는 `Cache miss`로 직접 확인하지 못함 |
| Vendor email | 미확정 | 로컬 `plugin.xml`에는 `email` 속성이 없음. 실제 Vendor 프로필 값은 미확인 |
| License | [로컬 Apache License 2.0 원문](../../../LICENSE) | 상점에 제출할 라이선스 내용·공개 URL은 반영 시 확인 |
| Privacy Policy | 미확정 | 별도 정책 URL을 이 조사에서 확보하지 못함. 수집 여부 설명과 정책 문서는 동일한 항목이 아님 |

오픈소스 라이선스로 등록할 때는 소스 링크가 필요하다. 연락처·정책·가격은 저장소에서 읽은 사실과 실제 등록 선택을 구분한다. [신규 업로드 안내](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)

공개 README의 차이는 조회 캐시의 영향일 수도 있으므로 원격 저장소가 반드시 오래되었다고 단정하지 않는다. 게시 시 배포할 패키지와 공개 설명의 기능 범위를 맞춘다.

## 태그와 가격

공식 안내는 업로드 때 최소 하나의 관련 태그를 선택하도록 권장한다. 제품의 실제 범위는 Typst 언어 지원과 문서 편집이며, 정확한 태그명은 관리 화면의 선택지에서 확인한다. 검색에 도움이 된다는 이유로 무관한 테마·AI·프레임워크 기능을 추가하지 않는다. [태그 안내](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html#tags)

가격·결제 방식은 현재 요청에서 정하지 않았다. Apache License 2.0이라는 소스 사실을 상점의 무료 등록 결정으로 바꾸지 않는다.

## 제품명

제품명은 기존 `Typstninja`를 유지한다. 공식 자료의 이름 길이는 소개 가이드의 최대 60자 설명과 승인 기준의 30자 제한이 다르다. 현재 이름은 양쪽 한도 안에 있으므로 충돌을 이유로 이름을 바꾸지 않으며, 향후 변경 시 최신 기준을 다시 확인한다. [소개 가이드](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html#plugin-name), [승인 기준 §1.2](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)

## 배포 상태

로컬 [publishing 설정](../../../build.gradle.kts)은 `channels = listOf("default")`, `hidden = true`다. 이 설정만으로 실제 업로드·승인·공개 여부를 알 수는 없다. 이번 검색으로 Typstninja의 정확한 Marketplace 페이지를 확보하지 못했으며, 검색 결과가 없다는 사실을 미등록의 증거로 사용하지 않는다.

공식 Hidden release 기능은 공개 검색에 노출하지 않은 채 페이지 정보를 준비하는 데 사용할 수 있다. 숨긴 플러그인도 직접 링크 등으로 접근할 수 있으므로 비밀 저장소로 취급하지 않는다. 최초 플러그인의 숨김 해제는 되돌릴 수 없고, 개별 버전의 숨김과도 구분된다. 이번 요청에서는 해당 상태를 변경하지 않는다. [Hidden release](https://plugins.jetbrains.com/docs/marketplace/hidden-plugin.html)

실제 등록 시 남은 값은 Marketplace ID·설치 주소, Vendor 연락처, 태그, 가격, 공개 범위다. 이 문서의 미확정 표시는 해당 값을 선택하거나 게시했다는 의미가 아니다.

## 확인 범위와 출처

로컬 제품명·기능·버전·호환성·라이선스를 대조하고, 위에 인용한 공식 등록·소개·승인·숨김 문서를 2026-09-26 온라인으로 열었다. 이 문서는 참조 명세 작성까지 완료한 상태이며, 상점 관리 화면·최종 패키지·공개 게시물 검증은 포함하지 않는다.
