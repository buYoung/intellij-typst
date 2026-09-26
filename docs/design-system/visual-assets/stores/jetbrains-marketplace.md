# JetBrains Marketplace 시각 자산 규격

## 대상과 조사 범위

대상은 `Typstninja` IntelliJ Platform 언어 지원 플러그인이다. 색상 테마·교육 과정·독립 앱으로 분류하지 않는다. 공식 약관은 이 상점을 JetBrains 또는 그 계열사가 운영하는 플러그인 유통 플랫폼으로 정의한다. 이 문서는 로고·미디어 관련 요건만 다룬다.

실제 스토어에 입력할 제품명·소개문·시작 안내·변경 내역·링크와 등록 상태는 [Marketplace 등록 정보와 소개문](../../marketplace/jetbrains-marketplace-listing.md)에서 확인한다.

현재 공식 자료를 **2026-09-26 온라인으로 열어 확인**했다. 실제 플러그인 관리 화면과 Typstninja의 공개 상점 페이지는 확인하지 않았다.

| 출처 | 확인한 범위 |
| --- | --- |
| [Marketplace Agreement](https://www.jetbrains.com/legal/docs/plugins_site/plugin_marketplace/) | 공식 명칭과 운영 관계 |
| [Best practices for listing your plugin](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html) | Plugin logo, Media, Screenshots, Video |
| [Plugin Logo](https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html) | SVG·파일명·패키지 위치·표시 크기·여백 |
| [Approval Guidelines 1.3](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html) | 2026-03-31 발효 문서의 로고 필수 조건과 테마 전용 분기 |

## 자산별 조건

필수 규격, 공식 권장, 선택 기능을 구별한다. 공통 자산 역할은 [자산 체계](../asset-system.md)를 따른다.

| 공식 자산 | 위치·역할 | 규격과 강도 | 근거 |
| --- | --- | --- | --- |
| Plugin logo | 제품 식별 | 제공하는 로고는 **40×40 SVG**. 템플릿 기본 로고와 달라야 하며 JetBrains 제품 로고를 닮으면 안 된다. | Approval Guidelines §1.1 |
| Plugin logo 기본 파일 | 플러그인 패키지 | `pluginIcon.svg`를 플러그인 메인 JAR의 `META-INF`에 포함한다. | Plugin Logo |
| Plugin logo 어두운 테마 | 밝은·어두운 배경 대응 | 기본 로고가 어두운 배경에서 적절하지 않으면 선택 변형 `pluginIcon_dark.svg`를 사용한다. | Plugin Logo |
| Screenshots | Media의 기능 설명 | 추가 **권장**. 일반 안내의 최소 권장 크기는 **1200×760**이며 제출 필수 최소값이라고 단정하지 않는다. | Best practices → Screenshots |
| GIF·정보형 슬라이드 | 기능 동작·보충 설명 | 일반 스크린샷 외에 사용할 수 있다. | Best practices → Screenshots |
| Video | Media의 동영상 | YouTube URL 사용 가능. **5분 미만 권장**이며 영상 제작 자체는 선택 사항이다. | Best practices → Video |

## 로고의 표시와 여백

로고는 목록에서 40×40, 상세 화면과 상점 페이지에서 80×80으로 표시된다. 두 크기에서 확인한다. SVG가 권장하는 이상적인 파일 크기는 2–3kB 미만이며, 업로드 하드 한도로 바꾸어 기록하지 않는다. [Plugin Logo](https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html)

공식 가이드는 둘레에 최소 2px의 투명 여백을 **권장**한다. 기본 도형 예시는 정사각형 32×32, 원 지름 36, 가로 직사각형 36×26, 세로 직사각형 26×36이다. 이는 도형별 시각적 무게 조정 안내다. 기존 Typst 로고의 배경은 캔버스 끝까지 차 있으므로 새 시안에서 여백을 다시 검토한다. [Plugin Logo](https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html)

## 화면 이미지의 내용과 배치

공식 안내는 관련 IDE의 기본 테마, 읽을 수 있는 글자, 일관된 가로세로비를 사용하고 개인정보·바탕화면·브라우저 창·기능과 무관한 광고·오해를 일으키는 화면을 제외하도록 안내한다. 실제 캡처의 내용 검토에는 이 기준을 적용한다. [Best practices](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html#screenshots)

Media는 소개 페이지에서 먼저 보이는 영역이며 플러그인 업로드 후 관리 화면에서 스크린샷·영상을 추가한다. 이 조사에서는 자산들의 정확한 표시 순서나 정렬 조작을 검증하지 않았다. 제품 사용 화면을 앞세우는 [메시지 우선순위](../index.md)는 제품의 편집 기준이며 상점이 정한 고정 순서가 아니다. [Best practices](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html#media)

## 적용하지 않는 분기와 미확인 값

- Approval Guidelines §1.6의 스크린샷 필수와 1280×800·16:10 권장은 **Color scheme and Theme Plugins** 항목이다. 현재 제품을 테마 플러그인으로 바꾸어 적용하지 않는다. 일반 스크린샷 안내와 섞어 새로운 필수 해상도를 만들지 않는다.
- Best practices는 일반 안내에서 로고 생략을 허용한다. 이번 요청에는 로고 제작이 포함되므로, 제공할 로고의 규격은 위 조건을 따른다.
- 스크린샷의 허용 형식 전체, 파일당 용량, 최대 개수, 전용 배너 제출란, 언어별 미디어 슬롯, 기기별 별도 이미지 요구는 이번에 연 문서로 확정하지 못했다. 다른 상점의 규격을 가져오지 않는다.
- 설명 본문에 이미지를 넣을 때의 권장 너비는 Media 스크린샷의 제출 규격과 다른 조건이다. 이번 제작은 Media용 이미지를 기준으로 삼는다.

최종 납품 전에 패키지의 로고 경로·형식·크기, 테마별 가독성, 캡처의 실제 기능·비율·민감정보를 확인한다. 관리 화면에서 추가 제한이 확인되면 이 파일을 갱신하고 영향을 받는 결과물만 조정한다.

## JSONinja의 공개 이미지 관측

2026-09-26 [JSONinja 상점](https://plugins.jetbrains.com/plugin/26715-jsoninja)을 브라우저에서 열어 로고와 첫 세 장의 기능 이미지를 확인했다. JSONinja는 브랜드 비교 자료이며 별도의 새 상점 대상이 아니다. 아래 치수는 페이지 이미지의 `naturalWidth`·`naturalHeight` 관측값이고 제출 규격이 아니다.

| 관측 자산 | 실제 크기 | 확인한 구성 |
| --- | --- | --- |
| [정렬 이미지](https://plugins.jetbrains.com/files/26715/screenshot_193569fd-816e-4aa6-aa07-2a9703d8a9cb) | 1526×852 | 어두운 편집 화면, 정렬 결과, 검은 바탕·흰 굵은 고정폭 캡션 |
| [축약 이미지](https://plugins.jetbrains.com/files/26715/screenshot_9c2b8391-8b8b-42f0-bf85-c818db9709ca) | 1526×854 | 같은 폭의 작업 화면과 기능 설명 |
| [이스케이프 이미지](https://plugins.jetbrains.com/files/26715/screenshot_6f9f9877-5f10-4438-89a0-0e22443433b7) | 1526×854 | 같은 캡션 문법. 설명이 화면 일부를 덮는 배치 |

현재 공개 로고는 닌자 그림이며 로컬 `META-INF/pluginIcon.svg`의 표현과 대응했다. 페이지 제목의 서체·설치 버튼의 파랑·상점 헤더는 Marketplace가 제공하는 UI이므로 JSONinja 전용 브랜드 값으로 취급하지 않는다. 첫 세 장만 확인했으므로 전체 갤러리의 개수·순서·최신성을 검증했다고 주장하지 않는다.

## Typst 이미지 세트의 제작 기준

**초기 정적 이미지의 기준 캔버스는 1526×854로 채택한다.** JSONinja에서 직접 확인한 두 원본의 크기를 사용해 제품군의 표시 밀도를 맞추기 위한 제작 선택이며, Marketplace의 필수 수치가 아니다. 공식 일반 권장 크기보다 큰 캔버스다.

세트의 이미지는 이 비율로 통일한다. 정렬 이미지의 852 높이는 동일하게 따라 만들지 않는다. 원본 화면은 비율을 유지해 맞추고 필요한 빈 공간이나 별도 캡션 영역으로 조정하며, 코드나 결과 페이지를 잘라 맞추지 않는다. 글자가 읽히지 않으면 같은 캔버스 안에서 촬영 범위를 좁히거나 상세 장면을 분리한다.

추후 게시 화면이나 제작 요구로 다른 크기가 필요해지면 해당 세트 전체의 기준을 이 파일에서 갱신한다. 모바일 앱 스토어용 프레임·기기 모형·전용 배너 규격을 임의로 추가하지 않는다.
