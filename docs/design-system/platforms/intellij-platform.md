# IntelliJ Platform 적용 조건

대상은 현재 저장소의 IntelliJ Platform 플러그인이다. [README](../../../README.md)의 지원 범위는 2024.3 이상이며, 이 문서는 아이콘의 플랫폼 조건만 다룬다.

## 사용 위치별 규격

2026-09-26 연 공식 [Working with Icons](https://plugins.jetbrains.com/docs/intellij/icons.html)에 따른 구분이다.

| 사용처 | 크기·조건 |
| --- | --- |
| 파일 형식·노드·일반 동작 | 16×16 |
| New UI 작업 창 | 기본 20×20, Compact Mode 16×16 |
| 기존 UI 작업 창 | 13×13 |
| 편집기 여백 | 기존 UI 12×12, New UI 14×14 |

이 표는 사용 위치가 생겼을 때 적용한다. 참조 제품의 `classic/v3` 폴더에 있는 20 크기 파일을 기존 UI의 작업 창 규격으로 해석하지 않는다. 현재 플랫폼 제공 아이콘은 해당 플랫폼 리소스를 재사용한다.

## 테마와 파일 선택

플랫폼은 기본 파일과 같은 위치의 `_dark` 변형을 환경에 따라 선택한다. New UI 작업 창에서는 [공통 값](../tokens.md)의 두 회색이 활성 상태의 대비 보정에도 관계된다. 브랜드 청록색을 해당 회색의 대체값으로 일괄 사용하지 않는다.

공식 New UI 작업 창 파일 구분은 기본 16 크기 파일과 `@20x20` 변형이며, 두 크기에 각각 `_dark`를 둘 수 있다. 별도 New UI 아이콘을 추가하는 경우에는 `iconMapper` 연결까지 확인한다. 현재 Typst에 그 연결이 이미 있다는 뜻은 아니다. [공식 연결 규칙](https://plugins.jetbrains.com/docs/intellij/icons.html#new-ui-icons)

## 지침 간 적용 범위

일반 [Icons 디자인 가이드](https://plugins.jetbrains.com/docs/intellij/icons-style.html)는 각진 형태와 2px 선을 설명하지만, 사용자가 지정한 v3 원본은 둥근 끝과 더 얇은 선을 사용한다. 이번 디자인 방향은 원본 v3를 따른다. 최신 New UI 전용 규정과 일반 가이드의 수치를 섞어 새 스타일을 만들지 않는다.

실제 IDE에서 선명도·선택 상태·테마 전환을 확인한 결과는 아직 없다. [검증 기록](../governance.md)에 이를 별도로 남긴다. 제품 로고의 배포 규격은 [상점 규격](../visual-assets/stores/jetbrains-marketplace.md)이 담당한다.
