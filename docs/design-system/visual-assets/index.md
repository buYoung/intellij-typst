# Typstninja 로고·상점 시각 자산 기준

**청록색 `T`에 가까이 모인 점눈과 하단 두 곡선을 넣은 닌자 로고**를 기준으로 삼는다. 로고에서 알아본 제품이 IDE 화면과 상점 이미지에서도 같은 제품으로 읽히도록 하는 제작 기준이다. JSONinja 로고의 윤곽·평면 채움 표현을 공유하고, 작은 IDE 아이콘은 JSONinja v3 체계를 따른다.

## 방향과 문서 상태

2026-09-26 사용자는 JSON Helper v3 스타일을 Typst에 적용하고, 상점 페이지와 로고를 만들기 전에 문서를 작성하도록 요청했다. 로고는 기존 T·청록색을 유지하면서 T를 닌자화하되, 너무 튀지 않고 JSONinja 로고와 일관되게 표현하도록 선택했다. 이 요청이 현재 브랜드 방향의 근거다.

현재 상태는 **로고 PNG 선택 완료 / SVG·제품 적용·상점 게시 전**이다. 사용자는 하찮고 멀뚱한 느낌의 10개 시안 중 02번 ‘모여 있는 눈’을 선택했다. [선택한 원본과 상태](asset-system.md#확정한-로고-원본)를 기준으로 후속 자산을 만든다. 실제 제품 캡처와 선택 로고를 적용한 게시용 이미지는 아직 없다.

## 범위와 전달할 내용

대상 제품명은 [plugin.xml](../../../src/main/resources/META-INF/plugin.xml)에 등록된 `Typstninja`다. 독자는 자산 제작자와 상점 페이지 편집자다. 대상 상점은 저장소의 배포 대상에 맞춘 [JetBrains Marketplace](stores/jetbrains-marketplace.md)다.

방문자가 먼저 이해할 내용은 “IDE에서 Typst 소스와 결과를 함께 다룬다”이다. 언어 지원과 문서 출력은 이를 뒷받침하는 장면으로 연결한다. 장면 선정은 [스크린샷 기준](screenshots-and-previews.md)에, 실제 기능 범위는 [README](../../../README.md)에 근거한다.

이 문서 묶음은 로고와 이미지에 들어가는 문구·화면 구성을 다룬다. 제품명·소개문·시작 안내·변경 내역·링크·태그·가격·배포 상태는 연결된 [Marketplace 등록 정보와 소개문](../marketplace/jetbrains-marketplace-listing.md)이 담당한다. 새 제품 기능이나 홈페이지 구현은 이 문서의 범위에 포함하지 않는다.

## 문서 지도

| 찾는 내용 | 기준 문서 |
| --- | --- |
| 닌자화한 T의 형태·색·JSONinja와의 일관성 | [시각 언어](visual-language.md) |
| 로고·파일 아이콘·화면 이미지의 역할과 원본 | [자산 체계](asset-system.md) |
| 보여줄 제품 장면과 사실성 | [스크린샷과 미리보기](screenshots-and-previews.md) |
| 이미지의 정보 순서와 문구 | [구성과 문구](composition-and-copy.md) |
| 언어별 이미지의 처리 | [현지화](localization.md) |
| 가독성과 색상 의존 방지 | [접근성](accessibility.md) |
| 결과물 연결·확인 기록·미확정 제작 사항 | [납품과 버전 관리](delivery-and-versioning.md) |
| JetBrains Marketplace의 로고·미디어 규격 | [상점별 규격](stores/jetbrains-marketplace.md) |
| JetBrains Marketplace에 입력할 제품 정보·소개문 | [등록 정보와 소개문](../marketplace/jetbrains-marketplace-listing.md) |
| IDE 내부 아이콘의 값과 소비자, 전체 문서 지도 | [디자인 시스템 전체 목차](../index.md) |
| 실제 색 대비·제품명·문구의 공통 기준 | [대비](../accessibility.md), [콘텐츠](../content.md) |

## 이 가이드로 바로 결정할 수 있는 것

로고는 선택한 02번 원본의 가까운 점눈·긴 T 비율·하단 두 곡선을 유지한다. 작은 파일 기호는 v3 언어 틀 안의 단순한 T와 회색 테마 쌍을 사용한다. 상점 이미지는 실제 작업 화면·검은 캡션·흰 고정폭 문구를 기본으로 하며, 기본 영문 장면과 일관된 캔버스를 사용한다. 구체적인 수치와 예외는 각 담당 문서에서 확인한다.

분석 원본의 [밝은 비교판](../references/jsoninja-reference-light.png), [어두운 비교판](../references/jsoninja-reference-dark.png), [UI 대표 프레임](../references/jsoninja-onboarding-reference.png)은 기준을 이해하는 자료다. 새 Typst 결과물로 상점에 업로드하는 파일이 아니다.

## 근거의 우선순위

브랜드 방향은 사용자의 선택, 제품 동작은 저장소와 실제 실행 결과, 제출 제약은 해당 상점의 현재 공식 문서를 따른다. [기존 로고](../../../src/main/resources/META-INF/pluginIcon.svg)는 `T`와 청록색의 출처지만, 여백·선 처리까지 새 로고의 완성 기준으로 채택한 것은 아니다.

시각 자산에 관한 상점의 가변 수치와 외부 규격 출처는 상점 문서가 단독으로 소유한다. 공통 문서는 이를 반복하지 않고 링크한다. 확인 결과와 미검증 범위는 [납품 기록](delivery-and-versioning.md)에 남긴다.
