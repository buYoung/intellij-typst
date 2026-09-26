# 선·색·글자·간격의 기준값

아래 값은 2026-09-26 읽은 JSONinja v3 SVG와 제품 UI 소스에서 확인했다. 별도 디자인 토큰 배포 계층은 확인되지 않았으므로, 새 API나 토큰 이름을 만들지 않고 실제 SVG 속성·UI 표현과 용도를 연결한다.

## 선과 색

| 속성 | 원본 값 | 의미와 적용 범위 |
| --- | --- | --- |
| `fill` | `none` | 루트의 기본값. 아래 축약 기호의 점은 명시적 예외 |
| `stroke-width` | `0.833333333333` | 원본 좌표계의 선 굵기. 16·20 크기 원본에서 동일 |
| `stroke-linecap` | `round` | 열린 선의 끝 |
| `stroke-linejoin` | `round` | 선의 연결부 |
| `stroke` — 기본 | `#6C707E` | 밝은 테마용 선 |
| `stroke` — `_dark` | `#CED0D6` | 어두운 테마용 선 |

근거: [기능 아이콘 기본](/Users/buyong/workspace/private/json-helper2/src/main/resources/icons/expui/v3/prettyIcon-v3-20.svg), [기능 아이콘 어두운 테마](/Users/buyong/workspace/private/json-helper2/src/main/resources/icons/expui/v3/prettyIcon-v3-20_dark.svg), [언어 아이콘](/Users/buyong/workspace/private/json-helper2/src/main/resources/icons/languages/v3/typescript.svg).

`uglyIcon-v3-20.svg`에는 `r="0.416666666667"`인 점 세 개가 있다. 점은 해당 테마의 선 색으로 채우고 `stroke="none"`을 사용한다. 중심은 `(6.875, 10)`, `(10, 10)`, `(13.125, 10)`이다. 점 지름은 기본 선 굵기와 같으며, 별도 굵은 테두리를 더하지 않는다. 따라서 “모든 하위 도형도 채움 금지”라는 규칙은 원본과 맞지 않는다.

## 언어 아이콘의 공통 틀

11개 언어의 밝은·어두운 SVG 모두 아래 사각 틀을 사용한다. Typst 파일 아이콘은 이 틀을 재사용하는 방향으로 작성한다.

| 항목 | 확인한 값 | 해석 |
| --- | --- | --- |
| 캔버스 | `16 × 16`, `viewBox="0 0 16 16"` | 파일·언어 기호의 원본 좌표계 |
| 사각 틀 시작 | `x="1.5"`, `y="1.5"` | 선의 중심 좌표 |
| 사각 틀 크기 | `width="13"`, `height="13"` | 선 중심 기준 범위는 1.5…14.5 |
| 모서리 | `rx="2"` | 이 틀의 값이며, 모든 제품 카드의 모서리 값은 아님 |
| 바깥 여백 | 선 중심까지 1.5 | 실제 선 바깥쪽까지는 약 1.0833. 선 두께의 절반을 빼서 계산 |

기존 Typst의 [파일 T 경로](../../src/main/resources/icons/typst.svg)는 `x=4…12`, `y=4…12` 안에 있다. 새 윤곽선형 T도 이 내부 범위를 배치의 출발점으로 삼는다. 기존 채움형 T를 그대로 축소해 넣는 방식과 새 선형 T를 만드는 방식은 구분한다. 외곽 틀과 글자의 간격은 실제 크기에서 확인한다.

## 크기별 해석

| 원본 묶음 | `width` × `height` | `viewBox` | 사용 시 주의점 |
| --- | --- | --- | --- |
| `classic/v3`, `expui/v3` | `20` × `20` | `0 0 20 20` | 참조 원본의 크기다. 모든 Typst 동작 아이콘의 납품 크기로 일반화하지 않는다. |
| `languages/v3` | `16` × `16` | `0 0 16 16` | 작은 언어·파일 기호의 비교 원본이다. |

20 크기의 SVG 전체를 80%로 축소하면 선도 약 `0.6667`로 줄어든다. 원본의 16 크기 아이콘은 같은 선 굵기를 유지하므로, 작은 파생본은 필요한 좌표계에서 형태를 조정한다. 사용처별 요구 크기는 [환경 문서](platforms/intellij-platform.md)가 정한다.

선 굵기는 20 좌표계 높이의 약 4.17%, 16 좌표계 높이의 약 5.21%다. 이는 원본 수치의 계산 결과이지, 모든 크기에 적용할 비례식은 아니다. 40 크기 제품 로고에는 이 비율을 자동 적용하지 않는다.

## 제품 UI의 글자 위계

JSONinja는 [OnboardingTutorialDialogView](/Users/buyong/workspace/private/json-helper2/src/main/kotlin/com/livteam/jsoninja/ui/onboarding/OnboardingTutorialDialogView.kt)에서 아래 역할을 사용한다. 별도의 브랜드 전용 글꼴을 확인하지 못했으므로, IDE 안에서는 이 역할과 플랫폼 글꼴을 기준으로 삼는다.

| 역할 | 확인한 구현 | 적용 의미 |
| --- | --- | --- |
| 대표 제목 | `JBFont.h2().asBold()` | 제품·주요 장면을 식별하는 제목 |
| 단계 제목 | `JBFont.h4().asBold()` | 현재 작업의 제목 |
| 본문 | `JBFont.regular()` | 설명과 작업 안내 |
| 소제목 | `JBFont.small().asBold()` | 전·후, 상세 설명 같은 짧은 표제 |
| 보조 설명·진행 상태 | `JBFont.small()`, `UIUtil.getContextHelpForeground()` | 본문보다 낮은 강조 |
| 대표 설명의 기존 예외 | `JBFont.regular().deriveFont(13f)` | 해당 온보딩 설명에 한정. 모든 본문을 13으로 고정하지 않음 |

기호 안의 `TS`, `Kt`, `Go` 등은 SVG 경로로 그려져 있다. 이를 상점용 글꼴의 이름이나 라이선스 근거로 사용하지 않는다. 상점 캡션의 글자 규칙은 [별도 구성 기준](visual-assets/composition-and-copy.md)이 담당한다.

## 간격은 역할에 따라 재사용한다

아래 값은 같은 온보딩 구현에서 읽은 값이다. `JBUI` 여백은 IDE 배율에 따라 조정되는 논리 단위이며, 상점 이미지의 픽셀 여백으로 그대로 복사하지 않는다.

| 관계 | 실제 값 | 재사용 범위 |
| --- | --- | --- |
| 중심 내용의 바깥 여백 | `empty(20, 24, 10, 24)` | 위·오른쪽·아래·왼쪽. 제목과 본문 영역의 좌우 정렬 |
| 하단 동작 영역 | `empty(0, 24, 16, 24)` | 중심 내용과 같은 좌우 기준선 |
| 전·후 비교 카드 사이 | `GridLayout(1, 2, 12, 0)` | 두 결과를 동등하게 보여줄 때 |
| 비교 카드 안쪽 | `empty(8)`, 경계선 `1` | 내용과 카드 경계를 구분 |
| 제목과 상세 내용 | `BorderLayout(0, 4)` | 밀접한 설명 묶음 |
| 이미지와 캡션·버튼 사이 | `8` | 인접한 요소 관계 |
| 대표 이미지와 제목 | `16` | 그림과 제목을 서로 다른 층위로 분리 |

4·8·12·16·24 등이 보이지만 6·10·20도 함께 사용된다. 이를 근거 없이 “모든 간격은 8의 배수”로 바꾸지 않는다. 같은 역할의 기존 간격을 먼저 재사용한다.

## 색의 역할 연결

기능 기호는 위의 회색 쌍, Typst 식별은 [청록색 쌍](visual-assets/visual-language.md), UI 본문·경계·선택 상태는 IDE의 의미별 색을 따른다. 과거 PNG 시안의 `#5C6166`, `#0066CC`, `#21A038`은 새 v3 토큰이 아니다. 브랜드 색의 실제 대비와 사용 제한은 [접근성 기준](accessibility.md)에 있다.

## 최종 소비자 연결

참조 제품은 [JsoninjaIcons](/Users/buyong/workspace/private/json-helper2/src/main/kotlin/com/livteam/jsoninja/icons/JsoninjaIcons.kt)의 `VERSION_3` 선택이 기능·언어 리소스로 연결된다. `classic/v3`와 `expui/v3`의 같은 이름 파일은 현재 바이트 단위로 동일했다.

Typst는 아직 위 속성을 적용하지 않았다. [아이콘 문서](components/icons.md)의 파일 아이콘 경로에 연결될 제작 기준이며, 새 자산의 도입과 화면 확인은 후속 작업이다. 로고 색상은 별도 [시각 언어](visual-assets/visual-language.md)를 따른다.
