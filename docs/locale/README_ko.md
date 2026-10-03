[English](../../README.md) | 한국어

# pypackpack

![Build](https://github.com/thisisthepy/pypackpack/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

📖 **가이드:** [thisisthepy.github.io/pypackpack](https://thisisthepy.github.io/pypackpack/) — 시작하기, 타깃과 의존성, 빌드 레벨, 라이브러리 API, 현재 상태를 영어와 한국어로 ([소스](../guide/index.html)).

### 설명

Python 프로젝트를 배포하기 위한 멀티플랫폼 솔루션.

pypackpack = crossenv + 컴파일러(nuitka, pyinstaller) + 번들러(js webpack) + 코드푸시(js expo)

#### 지원하는 멀티플랫폼:

- Android (arm64, x86_64)
- iOS (arm64)
- macOS (universal)
- Linux (x86_64)
- Windows (x86_64)
- WASM -

> [!NOTE]  
> \*\* Xcode 는 macOS 에서만 실행되므로, 이 저장소를 iOS 용으로 빌드하려면 macOS 가 필요합니다.

## 직접 빌드하기 🛠️

### 사전 요구 사항

- Native Image 를 지원하는 **GraalVM 22+**
- **Gradle 8.5+** (wrapper 에 포함)

#### (1) 이 저장소 클론

- RC 버전

```bash
git clone https://github.com/thisisthepy/pypackpack PyPackPack
```

- 개발 버전

```bash
git clone https://github.com/thisisthepy/pypackpack@develop PyPackPack
```

#### (2) GraalVM 설정

[https://www.graalvm.org/](https://www.graalvm.org/) 에서 GraalVM 을 내려받아 설치합니다.

환경 변수를 설정합니다:

```bash
export GRAALVM_HOME=/path/to/graalvm
export PATH=$GRAALVM_HOME/bin:$PATH
```

Native Image 컴포넌트를 설치합니다:

```bash
gu install native-image
```

#### (3) 빌드 옵션

**표준 Gradle 빌드:**

```bash
./gradlew build
```

**네이티브 실행 파일 빌드:**

```bash
./gradlew buildNativeExecutable
```

**네이티브 배포본 패키징:**

```bash
./gradlew packageNative
```

**빠른 빌드 스크립트 (Unix):**

```bash
chmod +x scripts/build-native.sh
./scripts/build-native.sh
```

**빠른 빌드 스크립트 (Windows):**

```cmd
scripts\build-native.bat
```

#### (4) 사용 가능한 Gradle 태스크

- `build` - 테스트를 포함한 표준 빌드
- `nativeCompile` - 네이티브 실행 파일로 컴파일
- `buildNativeExecutable` - 네이티브 실행 파일을 빌드하고 복사
- `packageNative` - 배포 패키지 생성
- `buildAllPlatforms` - 크로스 플랫폼 빌드 (Docker 필요)

---

## 미리 빌드된 패키지 사용하기 🧰

#### (1) Maven 저장소 (릴리스 전용)

프로젝트의 build.gradle.kts 에

    implementation("io.github.thisisthepy:python-multiplatform:0.0.1")

#### (2) Jitpack (프리릴리스용)

프로젝트의 settings.gradle.kts 에

    pluginManagement {
        repositories {
            google {
                mavenContent {
                    includeGroupAndSubgroups("androidx")
                    includeGroupAndSubgroups("com.android")
                    includeGroupAndSubgroups("com.google")
                }
            }
            mavenCentral()
            gradlePluginPortal()

            maven {
                setUrl("https://jitpack.io")  // Add this line!
            }
        }
    }

프로젝트의 build.gradle.kts 에

    implementation("com.github.thisisthepy:python-multiplatform-mobile:0.0.1")

> [!TIP]
> 몇 가지 팁

---

## 사용법 📑

main 메서드에서,

```kotlin


```

> [!IMPORTANT]
> 중요한 내용

---

## 시간에 따른 Stargazers 🌟

[![Stargazers over time](https://starchart.cc/thisisthepy/pypackpack.svg?variant=adaptive)](https://starchart.cc/thisisthepy/python-multiplatform-mobile)
