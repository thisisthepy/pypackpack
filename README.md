# pypackpack

![Build](https://github.com/thisisthepy/pypackpack/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

### Description

A multiplatform solution to distribute python project.

pypackpack = crossenv + compiler(nuitka, pyinstaller) + bundler(js webpack) + codepush(js expo)

#### Supporting multiplatforms:

- Android (arm64, x86_64)
- iOS (arm64)
- masOS (universal)
- Linux (x86_64)
- Windows (x86_64)
- WASM -

> [!NOTE]  
> \*\* Since Xcode only runs on macOS, you need macOS to build this repo for iOS.

## Build Manually 🛠️

### Prerequisites

- **GraalVM 22+** with Native Image support
- **Gradle 8.5+** (included via wrapper)

#### (1) Clone this repo

- RC version

```bash
git clone https://github.com/thisisthepy/pypackpack PyPackPack
```

- dev version

```bash
git clone https://github.com/thisisthepy/pypackpack@develop PyPackPack
```

#### (2) Setup GraalVM

Download and install GraalVM from [https://www.graalvm.org/](https://www.graalvm.org/)

Set environment variables:

```bash
export GRAALVM_HOME=/path/to/graalvm
export PATH=$GRAALVM_HOME/bin:$PATH
```

Install Native Image component:

```bash
gu install native-image
```

#### (3) Build Options

**Standard Gradle Build:**

```bash
./gradlew build
```

**Build Native Executable:**

```bash
./gradlew buildNativeExecutable
```

**Package Native Distribution:**

```bash
./gradlew packageNative
```

**Quick Build Script (Unix):**

```bash
chmod +x scripts/build-native.sh
./scripts/build-native.sh
```

**Quick Build Script (Windows):**

```cmd
scripts\build-native.bat
```

#### (4) Available Gradle Tasks

- `build` - Standard build with tests
- `nativeCompile` - Compile to native executable
- `buildNativeExecutable` - Build and copy native executable
- `packageNative` - Create distribution package
- `buildAllPlatforms` - Cross-platform build (requires Docker)

---

## Use Pre-Built Package 🧰

#### (1) Maven Repo (Release only)

In your project build.gradle.kts

    implementation("io.github.thisisthepy:python-multiplatform:0.0.1")

#### (2) Jitpack (for Pre-release)

In your project settings.gradle.kts

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

In your project build.gradle.kts

    implementation("com.github.thisisthepy:python-multiplatform-mobile:0.0.1")

> [!TIP]
> Some tips

---

## Usage 📑

In your main method,

```kotlin


```

> [!IMPORTANT]
> Somethig important

---

## Stargazers over time 🌟

[![Stargazers over time](https://starchart.cc/thisisthepy/pypackpack.svg?variant=adaptive)](https://starchart.cc/thisisthepy/python-multiplatform-mobile)
