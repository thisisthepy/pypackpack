[English](https://github.com/thisisthepy/pypackpack/blob/develop/README.md) | 한국어

<div align="center">

# pypackpack

**Python 프로젝트를 한 번 빌드하고, 모든 플랫폼으로.**

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-0f9d76.svg)](https://github.com/thisisthepy/pypackpack/blob/develop/LICENSE)
[![PyPI](https://img.shields.io/pypi/v/pypackpack.svg?color=0f9d76)](https://pypi.org/project/pypackpack/)
![Targets](https://img.shields.io/badge/targets-Android%20%7C%20iOS%20%7C%20Desktop-0f9d76.svg)
![Status](https://img.shields.io/badge/status-alpha-orange.svg)

[가이드](https://thisisthepy.github.io/pypackpack/) · [시작하기](https://thisisthepy.github.io/pypackpack/getting-started.html) · [상태](https://thisisthepy.github.io/pypackpack/status.html)

</div>

---

## 💡 왜 필요한가

데스크톱뿐 아니라 휴대폰에서도 돌아야 하는 Python 프로젝트에는 `pip install` 이상의 것이 필요합니다.
빌드하는 기기가 아닌 플랫폼을 위해 해석한 의존성, 그 플랫폼용으로 컴파일한 확장 모듈, 그리고 앱이
담을 수 있게 포장한 결과물. `pypackpack` (`ppp`) 이 그 일을 하므로, Python, JVM, 크로스 컴파일러를
손으로 설정할 일이 없습니다.

```
pypackpack = crossenv + 컴파일러 + 번들러 + 코드푸시
```

다시 만들지 않고 위임합니다. 의존성 해석과 설치는 [`uv`](https://github.com/astral-sh/uv) 가 하고,
pypackpack 은 그 위에 타깃, 타깃별 마커, 타깃별 설치 디렉터리를 더합니다.

## ✨ 기능

- 🎯 **트리플로 지정하는 타깃.** Android, iOS, macOS, Linux, Windows 를 정식 타깃 트리플
  (`aarch64-linux-android`, `arm64-apple-ios`, …) 이나 몇 가지 별칭으로.
- 📦 **타깃별 의존성.** `ppp <package> add numpy --target aarch64-linux-android` 는 uv 가 그
  타깃에서 평가하는 마커를 쓰고, `sync` 는 타깃마다 휠을 `build/crossenv/<triple>/` 에 설치합니다.
- 🐍 **고정된 인터프리터.** `ppp python install 3.14 <target>` 은 타깃용 CPython 빌드를 받아, 풀기
  전에 SHA-256 을 확인합니다.
- 🛠️ **C/C++ 확장.** `ppp build` 가 `meson.build` 를 생성해 컴파일합니다.
- 🧳 **번들.** [python-multiplatform](https://github.com/thisisthepy/python-multiplatform) 이
  실행하는 `resource` 페이로드, 그리고 single, fat, patch 휠.
- ☕ **하나의 코드, 두 개의 입구.** `pypackpack` 명령줄 도구, 그리고 같은 작업을
  [toolchain](https://github.com/thisisthepy/toolchain) (Gradle 플러그인) 이 호출하는 JVM 라이브러리로.

## 🚀 설치

```shell
pip install pypackpack        # 또는: uv tool install pypackpack
pypackpack --help             # 별칭: ppp
```

휠은 명령줄 도구를 네이티브 바이너리 (GraalVM Native Image) 로 담으며, 플랫폼마다 휠이 하나씩
있습니다: Apple silicon 의 macOS 11 이상, Linux x86_64 와 aarch64 (glibc), Windows x86_64. 그 밖의
플랫폼에서는 pip 가 맞는 배포본이 없다고 알립니다. 소스 배포본은 일부러 두지 않습니다. 빌드에 JDK 와
GraalVM 이 필요하므로, sdist 는 바이너리 없이 런처만 설치하게 됩니다.

실행에는 `uv` 가 필요합니다. `PATH` 에 없으면 pypackpack 이 `~/.pypackpack/uv` 에 내려받습니다.

<details>
<summary>체크아웃에서 직접 빌드하기</summary>

```shell
git clone -b develop https://github.com/thisisthepy/pypackpack
cd pypackpack
./gradlew :cli:installDist                 # JDK 21
alias ppp="$PWD/cli/build/install/cli/bin/cli"
./gradlew :cli:nativeCompile               # 네이티브 바이너리. JAVA_HOME 이 GraalVM 이어야 함
```

</details>

## ⚡ 빠른 시작

```shell
ppp init myapp --python 3.13
cd myapp
ppp package add packages/core
ppp target add aarch64-linux-android arm64-apple-ios

ppp core add requests                                     # 패키지의 모든 타깃
ppp core add six --target arm64-apple-ios                 # 한 타깃에만
ppp core sync --target aarch64-linux-android arm64-apple-ios \
    --python-version 3.14 --only-binary :all:          # → packages/core/build/crossenv/<triple>/

ppp build core --target aarch64-apple-darwin              # Meson. 지금은 호스트만
```

`--target` 이 없으면 `sync` 와 `tree` 는 호스트 타깃만 씁니다. 모듈은 `packages/core/src/main/core/` 같은 패키지 디렉터리에 두세요. 플랫폼 전용 코드는
`src/android/`, `src/ios/` 등에 둡니다.

## ☕ 라이브러리로

```kotlin
repositories { mavenLocal() }    // ./gradlew :packpack:publishToMavenLocal
dependencies { implementation("org.thisisthepy.python.multiplatform:packpack:0.1.0") }
```

```kotlin
BundlerInterface.create(BundleType.RESOURCE).bundle(
    BundleRequest(packageDir = packageDir, target = "aarch64-linux-android", buildLevel = "bytecode")
)
```

가이드의 [라이브러리 페이지](https://thisisthepy.github.io/pypackpack/guide-library.html)에 번들링,
원하는 디렉터리로의 Python 설치, 타깃별 의존성 설치가 있습니다.

## 🧭 어디에 놓이나

| 저장소 | 맡는 것 |
|---|---|
| **pypackpack** | 작업: Python 확보, 의존성 해석, 크로스 컴파일, 번들링 |
| [toolchain](https://github.com/thisisthepy/toolchain) | pypackpack 호출로 바뀌는 Gradle `python { }` 블록 |
| [python-multiplatform](https://github.com/thisisthepy/python-multiplatform) | 런타임: Kotlin Multiplatform 에 내장된 CPython 이며 `resource` 번들을 읽음 |

## 📊 현재 상태

솔직한 요약입니다. 항목별 계약은 [상태 페이지](https://thisisthepy.github.io/pypackpack/status.html)에
있습니다.

| 영역 | 상태 |
|---|---|
| Python 배포판 (install, list, find, uninstall, 고정 SHA-256) | ✅ 구현됨 |
| 타깃과 타깃별 의존성 (`add`, `remove`, `sync`, `tree`) | 🟡 동작함. 일부는 아직 테스트 없음 |
| Meson 을 통한 C/C++ 확장 | 🟡 호스트만, 크로스 컴파일은 아직 |
| 번들: `resource`, `single`, `fat`, `patch` | ✅ 구현됨 |
| 빌드 레벨: `instant` / `bytecode` | ✅ / 🟡 (`resource` 만) |
| 빌드 레벨: `native` / `mixed` | ⏳ 계획. 의미는 결정됨, 컴파일 슬롯은 초안 |
| `deploy`, 코드 푸시, `binary` 번들 | ⏳ 계획 |

## 📖 문서

- **[가이드](https://thisisthepy.github.io/pypackpack/)**: 시작하기, 타깃과 의존성, Python 배포판,
  빌드 레벨과 번들, 라이브러리 API. 영어와 한국어.
- **[English README](https://github.com/thisisthepy/pypackpack/blob/develop/README.md)**

## 🤝 기여

이 저장소의 작업은 의도 → 스펙 → 테스트 → 코드 순서입니다. 동작 변경은 스펙 변경에서 시작하고,
테스트를 먼저 써서 실패를 본 뒤, 코드로 통과시킵니다. 테스트 실행 방법은 가이드의
[기여 안내](https://thisisthepy.github.io/pypackpack/status.html#contributing)에 있습니다.

## 📄 라이선스

[Apache-2.0](https://github.com/thisisthepy/pypackpack/blob/develop/LICENSE) © thisisthepy
