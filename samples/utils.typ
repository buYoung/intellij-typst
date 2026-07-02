// 크로스 파일 이동 테스트용 모듈. verify.typ 에서 #import 로 불러옵니다.

/// 배율을 적용해 크기를 계산합니다.
#let scale-size(base, factor) = base * factor

/// 문서 제목 상수.
#let project-title = "Typst 플러그인"

/// 강조 박스 헬퍼.
#let callout(body) = block(fill: luma(230), inset: 8pt, body)
