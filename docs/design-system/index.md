# Typstninja 디자인 시스템

Typstninja의 IDE 아이콘, 로고·상점 이미지, Marketplace 등록 정보를 함께 관리하는 문서 묶음이다. IDE 아이콘은 **JSONinja 아이콘 팩 VERSION_3**의 얇은 단색 윤곽선과 둥근 선 끝을 공유한다. 로고는 **청록색 T를 절제된 닌자 형태로 표현**하며 JSONinja 로고의 윤곽과 평면 채움을 공유한다. 상점 이미지는 두 제품의 작업 화면·캡션 구성에 일관성을 유지한다.

## 범위와 상태

- 대상 독자: 아이콘·시각 자산 제작자, 플러그인 개발자, 상점 페이지 편집자.
- 범위: 파일·기능 아이콘과 테마별 변형, 실제 사용처 연결, 로고·상점 이미지, 스토어 등록 정보와 소개문.
- 방향의 근거: 2026-09-26 사용자의 v3 아이콘 스타일 적용 요청과, T를 닌자화하되 너무 튀지 않고 JSONinja 로고와 일관되게 표현하라는 선택.
- 성숙도: **로고 SVG 변환·패키지 적용 완료 / 실제 IDE 표시 미확인**. 사용자가 선택한 02번 로고는 [자산 원본](visual-assets/asset-system.md#확정한-로고-원본)에 연결했다. Quiver 변환 결과를 40·80px의 밝은·어두운 배경에서 확인했다. 작은 IDE 아이콘 제작과 실제 IDE·상점 표시 검증은 아직 수행하지 않았다.
- “우선 문서먼저” 요청에 따라 제작 전 확인 사항까지 기록한다. 문서 작성 완료가 새 자산의 제작·승인·배포 완료를 뜻하지 않는다.

관련 문서는 모두 `docs/design-system/` 아래에서 관리한다. 제품 아이콘 기준은 이 폴더의 루트, 로고·상점 이미지 기준은 `visual-assets/`, 스토어 등록 정보는 `marketplace/`에 둔다. 각 문서의 역할과 개별 목차는 유지한다. IDE 화면 전체의 색상·배치·타이포그래피를 새로 설계하는 문서는 아니다.

## 문서 지도

| 찾는 내용 | 기준 문서 |
| --- | --- |
| 공유할 표현과 바꿀 수 있는 요소 | [시각 원칙](foundations.md) |
| 원본에서 확인한 선·색·크기 값 | [값과 연결](tokens.md) |
| UI 글자 위계와 역할별 간격 | [글자·간격 기준](tokens.md) |
| 작업·결과·설명 배치와 상태 | [공통 패턴](patterns.md) |
| 제품명·기능명·설명 말투 | [콘텐츠 기준](content.md) |
| 계산한 대비와 색 사용 제한 | [접근성](accessibility.md) |
| 아이콘 목록과 실제 사용처 | [구성 요소 목록](components/index.md), [아이콘](components/icons.md) |
| 실행 환경별 크기와 테마 조건 | [IntelliJ Platform 적용](platforms/intellij-platform.md) |
| 확인 결과와 제작 후 확인 사항 | [적용·검증 기록](governance.md) |
| 로고·상점 이미지의 제작 기준과 문서 지도 | [시각 자산 문서](visual-assets/index.md) |
| JetBrains Marketplace의 로고·미디어 규격 | [상점별 규격](visual-assets/stores/jetbrains-marketplace.md) |
| JetBrains Marketplace에 입력할 제품 정보·소개문 | [등록 정보와 소개문](marketplace/jetbrains-marketplace-listing.md) |

## 제작할 때 읽는 순서

| 하려는 일 | 먼저 확인 | 이어서 확인 |
| --- | --- | --- |
| Typst 파일 아이콘 작성 | [언어 틀·선·테마 값](tokens.md) | [아이콘의 제작·상태·소비자](components/icons.md) |
| 로고·제품명 조합 작성 | [절제된 닌자 T의 형태·색·판정 기준](visual-assets/visual-language.md) | [원본·영역·변형](visual-assets/asset-system.md), [납품 규격](visual-assets/stores/jetbrains-marketplace.md) |
| 상점 이미지 제작 | [글자·캡션·화면 구성](visual-assets/composition-and-copy.md) | [장면 세트·촬영 조건](visual-assets/screenshots-and-previews.md), [가독성](visual-assets/accessibility.md) |
| 기준이 맞게 적용됐는지 검토 | [원본 분석·검증 기록](governance.md) | 해당 자산의 금지 예·조건부 변형 |

수치가 있다는 이유만으로 모든 곳에 같은 값을 적용하지 않는다. 문서는 **원본 관측값**, **이 제품에 채택한 적용 규칙**, **플랫폼 조건**, **제작 결과에서 확인할 항목**을 구분한다. 관측한 과거 자산이나 분석용 비교판의 배치를 새로운 브랜드 원형으로 취급하지 않는다.

## 근거와 우선순위

사용자의 명시적 선택이 디자인 방향을 정한다. 파일 형식·소비 위치 같은 플랫폼 제약은 해당 [환경 문서](platforms/intellij-platform.md), 공통 표현은 이 문서 묶음이 담당한다. 구현과 문서가 다르면 현재 구현을 자동으로 새 기준으로 삼지 않고 차이를 기록한다.

| 근거 | 용도 |
| --- | --- |
| [JSONinja v3 기능 아이콘](/Users/buyong/workspace/private/json-helper2/src/main/resources/icons/expui/v3) 및 [언어 아이콘](/Users/buyong/workspace/private/json-helper2/src/main/resources/icons/languages/v3) | 사용자가 지정한 스타일 원본 |
| [JSONinja 패키지 로고](/Users/buyong/workspace/private/json-helper2/src/main/resources/META-INF/pluginIcon.svg) | 닌자 표현·윤곽과 평면 채움·글자 결합의 참조 원본 |
| [JSONinja 아이콘 선택 코드](/Users/buyong/workspace/private/json-helper2/src/main/kotlin/com/livteam/jsoninja/icons/JsoninjaIcons.kt) | VERSION_3와 리소스의 연결 근거 |
| [TypstFileType](../../src/main/kotlin/com/livteam/typninja/language/TypstFileType.kt), [plugin.xml](../../src/main/resources/META-INF/plugin.xml) | 현재 제품명·등록·파일 아이콘 사용처 |

참조 저장소는 로컬 조사 경로다. 다른 작업 환경에서는 같은 커밋의 저장소로 연결해야 한다. 원본 식별값과 확인 범위는 [적용·검증 기록](governance.md)에 남긴다.
