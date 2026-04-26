# PyPackPack Spec Sheet

## 기능 개요

- 파이썬을 위한 멀티 플랫폼 빌드 시스템

#### 달성해야 하는 목표

운영체제 종속성이 없고 (Desktop, macOS, linux) by GraalVM Native Image
쉽게 설치하여 사용 가능해야 하고 (파이썬이나 jvm등의 경로 설정을 사용자한테 시키지 말자)
속도가 빨라야 함 (빌드 속도가 중요)

### <pypackpack> 본체

- (1). c, c++, rust로 작성된 파이썬 패키지 빌드 지원 (패치가 필요한 경우 제외)
- (2). 멀티플랫폼 코드를 위한 crossenv 지원
- (3). python 코드 컴파일/최적화/minification 지원 (nuitka, lpython, etcs)
- (4). code update fast track API 지원

### PyPackPack Companion (함께 사용하도록 디자인된 툴)

#### <toolchain>

- (1). pypackpack wrapping
- (2). python 코드를 포함하는 kotlin library 생성
- (3). final binary 생성 기능
- (4). pypackpack의 code update fast track을 확장하여 개발 단계에서도 hot-reload 가능하게 허용

#### <toolchain(python)>

- toolchin이 적용되어 있는 코틀린 멀티플랫폼 프로젝트를 통채로 whl 패키지로 압축한 것

#### <pip-jit>

- pypackpack은 패치가 필요하여 pypackpack에서 빌드를 실패하는 패키지들에 레시피를 삽입하여 빌드가 될 수 있도록 지원하는 프로젝트

#### <pip-central> (소스 비공개 프로젝트)

- pip-jit을 사용하여 패키지를 배포하는 서비스

#### <brainWave/intelliPush>

- pypackpack의 code update fast track API, brain wave API를 사용하는 관리 서버

## 디렉토리 구조

### pypackpack 코드 디렉토리 구조

```
- pypackpack
  - packpack
    - src/main/kotlin/org/thisisthepy/python/multiplatform/packpack
      - cli
        - Command.kt               # main entry point, root command, dynamic package command dispatch
        - CommandExtensions.kt     # CLI helper, validation, progress display
        - DependencyCommands.kt    # handles root-level 'add', 'remove', 'sync', 'tree'
        - DynamicPackageCommand.kt # handles '<package> add/remove/sync/tree'
        - PackageCommands.kt       # handles 'package add/remove/sync/tree'
        - ProjectCommands.kt       # handles 'version', 'init'
        - PythonCommands.kt        # handles 'python' related commands
        - TargetCommands.kt        # handles 'target' related commands
      - utils
        - Platforms.kt
        - Downloader.kt  # external tool downloader (URL downloader, pip downloader)
        - DownloadSpec.kt  # external tool download spec
      - dependency
        - frontend
          - BaseInterface.kt  # factory pattern
          - Cli.kt  # CLI interface
          - Gradle.kt  # Gradle interface
        - middleware
          - BaseInterface.kt  # factory pattern
          - DefaultInterface.kt  # strategy pattern
          - environment
            - DevEnv.kt  # dev/build environment venv 관리
            - CrossEnv.kt  # cross environment venv 관리
        - backend
          - external
            - UV.kt  # uv downloader
          - BaseInterface.kt  # factory pattern
          - UVInterface.kt
      - compile
        - frontend
          - BaseInterface.kt  # factory pattern
          - Cli.kt
          - Gradle.kt
        - middleware
          - external
            - Nuitka.kt  # nuitka downloader (c++ converter)
            - Cython.kt  # cython downloader (c converter)
            - Lpython.kt  # lpython downloader (llvm converter)
          - BaseInterface.kt  # factory pattern
          - DefaultInterface.kt  # decorator pattern
          - transcompile
            - BaseTransInterface.kt  # strategy pattern
            - NuitkaTransInterface.kt
            - CythonTransInterface.kt
          - minification
            - BaseMinifyInterface.kt  # strategy pattern
            - ...
        - backend
          - external
            - Clang.kt
            - MSVC.kt
            - NDK.kt  # adapter pattern (Clang.kt)
            - XCode.kt  # adapter pattern (Clang.kt)
            - Emscripten.kt  # adapter pattern (Clang.kt or MSVC.kt)
            - Cargo.kt  # adapter pattern (Clang.kt, MSVC.kt, NDK.kt, XCode.kt)
            - Meson.kt  # adapter pattern (Clang.kt, MSVC.kt, NDK.kt, XCode.kt)
          - BaseInterface.kt  # factory pattern
          - DefaultInterface.kt  # strategy pattern
      - bundle
        - BaseInterface.kt  # factory pattern
        - DefaultInterface.kt  # strategy pattern
        - binary (.exe, etcs)
        - fat (.whl)
        - single (.whl, just package except dependent libs)
        - patch (.whl.patch)
      - deploy
        - BaseInterface.kt  # factory pattern
        - DefaultInterface.kt  # decorator pattern
        - resource
          - BaseAPI.kt
          - ResourceHubAPI.kt  # deploy client
        - code
          - BaseAPI.kt
          - PyPIPublishAPI.kt  (uv publish)
          - FastTrackAPI.kt  # deploy client
        - weight
          - BaseAPI.kt  # factory pattern
          - BrainWaveAPI.kt  # deploy client

  - usage-example

```

### 사용자 디렉토리 구조

```
- <project>  # 멀티 패키지 프로젝트
  - .venv  # 프로젝트 빌드를 위한 dev 의존성이 관리되는 venv
  - <package1>  # 패키지 1 (toolchain에서는 하나의 gradle 모듈)
    - build  # 빌드 결과물 저장
      - crossenv
        - android_21_arm64   # 안드로이드용 venv
        - android_21_x86_64
        - windows_amd64      # windows용 venv
        - macos_arm64
      - packpack
        - binary
          - debug
            - <build level>  # instant(.py), bytecode(.py+.pyc), native, mixed
          - release
            - <build level>  # instant(.py), bytecode(.pyc), native, mixed
    - src  # 소스 코드 저장
      - android  # android 전용 코드
        - __init__.py
        - ...
      - main  # 플랫폼 common 코드
        - __init__.py
        - ...
      - windows  # windows 전용 코드
        - __init__.py
        - ...
      - test  # test 코드
        - test_*.py
    - pyproject.toml  # 패키지 설정 (dependency 관리 등)
  - <package2>
    - ...
  - .gitignore
  - LICENSE
  - README.md
  - uv.lock
  - pyproject.toml
```

## 기능 명세

### dependency 명세 해석 기준

- 이 문서의 dependency 관련 항목은 아래 세 가지 상태로 구분한다.
- `구현됨`: 현재 `packpack/src/main/kotlin/.../dependency` 및 연결된 CLI에서 동작이 확인되는 항목
- `부분 구현`: 핵심 흐름은 있으나 문서상 목표 대비 제약이나 누락이 있는 항목
- `미구현 목표`: 아직 코드로 반영되지 않았지만 유지하고 싶은 방향성
- dependency 관련 섹션에서는 `현재 구현`을 기준 사실로 서술하고, 미래 계획은 별도로 분리한다.

### 기본 기능

#### CLI Help (`구현됨`)

```bash
pypackpack help
pypackpack --help
```

- CLI 사용법을 출력

#### CLI Version (`구현됨`)

```bash
pypackpack version
pypackpack --version
pypackpack -v
```

- 현재 사용 중인 pypackpack 버전과 감지된 uv 버전을 출력

#### 프로젝트 생성 (`부분 구현`)

```bash
pypackpack init [<path>] [--python <python version>] [--name <project name>] [--package]
```

- 명세상 프로젝트 초기화는 `uv init --bare`를 기반으로 수행한다.
- `path`, `--python`, `--name`, `--package`를 `uv init` 옵션으로 전달하되, `uv`는 `pyproject.toml`만 생성하고 나머지 필요한 파일은 ppp가 생성한다.
- `targets`가 주어진 경우 루트 `pyproject.toml`의 `[tool.ppp.dependencies].platforms`를 갱신한다.
- 현재 구현은 `uv init --bare`로 초기화한 뒤, ppp가 `.gitignore`, `README.md`, `.python-version` 등을 추가 생성한다.

Limitation
- 기존 `pyproject.toml`의 ppp 여부 검사와 타 패키징 도구 충돌 판별은 아직 없다.
- `LICENSE` 생성과 `.venv` 자동 생성 보장은 아직 구현되어 있지 않다.

#### 프로젝트 파이썬 버전 변경 (`부분 구현`)

```bash
pypackpack python use <python version>
```

- 프로젝트 루트 기준 `.venv`를 삭제한 뒤 새 Python 버전으로 다시 생성하고 `uv sync`를 수행한다.

Limitation
- 하위 패키지 `build` 디렉토리 정리 로직은 아직 없다.

#### 프로젝트 파이썬 관련 추가 기능 (`구현됨`)

```bash
pypackpack python list
pypackpack python find <python version>
pypackpack python install <python version>
pypackpack python uninstall <python version>
```

- uv python ~~ 으로 명령어 포워딩

### 패키지 관리 기능

#### 패키지 추가/제거 (`부분 구현`)

```bash
pypackpack package add <package path> [--path <workspace root>]
pypackpack package remove <package path> [--path <workspace root>]
```

- `<package path>`는 워크스페이스 루트 기준 상대 경로를 허용한다. 예: `packages/core`
- 패키지 경로는 워크스페이스 바깥으로 벗어날 수 없다.
- `package add`의 명세상 초기화는 지정한 경로에 디렉토리를 만든 뒤, 해당 디렉토리에서 `uv init --bare --package --name <leaf dir name>`로 `pyproject.toml`만 생성하고 나머지 필요한 파일은 ppp가 생성하는 방식이다.
- `package remove`는 패키지 디렉토리를 재귀 삭제하고, 루트 `pyproject.toml`의 `[tool.uv.workspace].members`에서 해당 상대 경로를 제거한다.
- 패키지 식별은 경로 또는 패키지명 둘 다 허용하지만, 이름만으로 찾을 때 워크스페이스 멤버 중복이 있으면 에러를 낸다.
- 현재 구현은 `uv init --bare` 뒤에 패키지용 `README.md`, `src/main/<import>/__init__.py`, `src/test/test_import.py`, `build/crossenv`, `build/packpack` 스캐폴드를 생성하고 워크스페이스 멤버도 자동 등록한다.

Limitation
- 패키지명 예약어 검사와 remove 시 추가 정합성 검증은 아직 없다.

### 패키지 빌드 타겟 관리 기능

#### 빌드 타겟 플랫폼 조회/추가/제거 (`구현됨`)

```bash
pypackpack target list
pypackpack target add <target name>... [--path <package dir>]
pypackpack target remove <target name>... [--path <package dir>]
```

- `target list`는 `Platforms.SUPPORTED_TARGETS`를 별칭과 플랫폼 패밀리 단위로 묶어 출력한다.
- `target add/remove`는 지정한 패키지 디렉토리 또는 현재 디렉토리의 `pyproject.toml`에 있는 `[tool.ppp.dependencies].platforms` 배열만 수정한다.
- 타겟은 `Platforms.normalizeTargetsOrThrow`로 정규화한다.

Limitation
- 모든 패키지 일괄 수정 기능은 아직 없다.
- 잘못된 타겟 입력에 대한 유사 타겟 추천은 아직 없다.

### 의존성 관리 기능

#### 정책 메모

> - `pypackpack add/remove`는 DevEnv 전용이며 `packageName` 없이 동작
> - `pypackpack <package> add/remove/sync/tree`는 CrossEnv 전용이며 `packageName` 필수
> - CrossEnv의 타겟 의존성은 `Platforms.kt` 기반으로 정규화한 뒤 target별 marker를 사용해 `uv add` 또는 `pyproject.toml` 편집으로 관리
> - 의존성 문자열에 marker를 직접 포함하는 입력(예: `numpy; ...`)은 금지하고 `--target`만 허용
> - `remove --target`은 패키지 전체 제거가 아니라 지정된 타겟 범위만 제거
> - 현재 구현의 CLI `--target` 입력은 공백 구분 형식 사용(예: `--target windows linux`)
> - `tree`는 host target 또는 지정 target 기준의 해석 결과를 보여주는 검사용 동작이다
> - 타겟별 전용 venv 생성/설치는 아직 dependency 구현 범위에 없다

#### Dev 환경 의존성 관리 (`구현됨`)

```bash
pypackpack add <pypi name> [<etcs>]
```

- 프로젝트 루트를 찾은 뒤 루트 워킹 디렉토리에서 `uv add`를 실행한다.
- `--dev`, `--editable`, `--no-sync`, `--upgrade`, `--reinstall`, `--refresh`, `--frozen`, `--locked`, `--preview`, `--raw-sources`, `--quiet`, `--verbose`를 지원한다.


```bash
pypackpack remove <pypi name> [<etcs>]
```

- 루트 워킹 디렉토리에서 `uv remove`를 실행한다.
- marker가 포함된 의존성 문자열 입력은 허용하지 않는다.

```bash
pypackpack sync [<etcs>]
```

- 프로젝트 루트의 `.venv`를 대상으로 `uv sync`를 수행한다.

Limitation
- 실제 backend 호출은 워킹 디렉토리 기준으로 동작하며, `venvPath` 인자는 현재 `uv sync` 명령 인자로 전달되지 않는다.

```bash
pypackpack tree [--target <target1> <target2> ...] [<etcs>]
```

- 패키지명이 없을 때는 host target 또는 지정한 target 각각에 대해 `uv tree --python-platform <target>`를 호출한다.
- target 미지정 시 현재 호스트에 대응하는 target 하나를 기본값으로 사용한다.

#### 패키지별 타겟 의존성 관리 (`구현됨`)

```bash
pypackpack <package name> add <pypi name> [--target <target1> <target2> ...] [<etcs>]
```

```bash
# example
pypackpack mypackage add numpy --target windows linux
pypackpack mypackage add numpy --target windows linux --extra-index-url https://pypi.org/simple
```

- 동적 패키지 커맨드 `pypackpack <package> add/remove/sync/tree`가 지원된다.
- 대상 패키지는 워크스페이스 멤버에서 이름 또는 상대 경로로 해석한다.
- `add`는 target마다 marker를 계산해 `uv add --package <name> --marker <marker>`를 반복 호출한다.
- `--target`이 없으면 패키지 `pyproject.toml`의 `[tool.ppp.dependencies].platforms` 값을 기본 target으로 사용한다.
- 기본 target도 없고 `--target`도 비어 있으면 에러를 낸다.

```bash
pypackpack <package name> remove <pypi name> [--target <target1> <target2> ...] [<etcs>]
```

- `remove`는 패키지 `pyproject.toml`의 `project.dependencies`를 직접 편집해, 요청된 패키지명과 target marker가 모두 일치하는 항목만 제거한다.
- 제거 후 워크스페이스 루트에서 `uv lock`을 수행한다.

Limitation
- `uv remove --marker`를 호출하지 않고 TOML 편집 방식으로 처리한다.

```bash
pypackpack <package name> sync [--target <target1> <target2> ...] [<etcs>]
```

- `sync`는 먼저 `uv sync --package <name>`를 호출한 뒤, target마다 `uv tree --package <name> --python-platform <target>`를 호출해 해석 가능 여부를 확인한다.
- `--target`이 없으면 host target 하나를 기본값으로 사용한다.

Limitation
- target별 가상환경을 생성하지 않는다.

```bash
pypackpack <package name> tree [--target <target1> <target2> ...] [<etcs>]
```

- `tree`는 target마다 `uv tree --package <name> --python-platform <target>`를 호출하고 결과를 이어서 출력한다.
- `--target`이 없으면 host target 하나를 기본값으로 사용한다.

#### 미구현 목표

- CrossEnv 의존성을 위한 target별 전용 venv 생성 및 유지
- `sync` 단계에서 target별 설치 결과를 별도 환경으로 보존하는 기능
- `init` 시 기존 프로젝트 상태 검사와 ppp 프로젝트 판별 강화
- `python use` 시 하위 패키지 build 디렉토리 정리


### 빌드, 번들링, 배포

#### 패키지 빌드

```bash
pypackpack build <package name> source [<bundle type: default binary>] [--type <build type: default debug>] [--level <build level: default instant>] [--target <target name>] [<etcs>]
pypackpack build <package name> resource [--target <target name>] [<etcs>]
```

- 패키지의 pyproject.toml에 빌드해야 할 의존성 패키지의 범위 기술 필요 (기본적으로는 전부 다 같은 레벨로 빌드)
- 빌드 수준(instant, bytecode, native, mixed / debug, release)
- 단일 파일로 전부 다 묶는건지 따로 따로 빌드할건지 기술 필요 (메타 데이터)
- 번들 압축 (.whl) + 변경된 부분만 업데이트 가능하도록 (.whl.patch) 지원 필요
  - 어느 프라이메리 버전을 기준으로 업데이트 하는건지, 몇번째 패치인건지 고려 필요

#### 패키지 배포

```bash
pypackpack deploy <package name> source [<bundle type: default binary>] [--type <build type: default debug>] [--level <build level: default instant>] [--target <target name>] [<etcs>]
pypackpack deploy <package name> resource [--target <target name>] [<etcs>]
```

- 디플로이 서버 대상 지정 필요 (PyPI or FastTrack)
- 패치 기능 구현
  - 패치 업로드는 git 컨셉으로 가자 (문제는 속도, 병렬 처리)
  - 변경 사항 업로드, 서버 관리 페이지 필요, 클라이언트 코드
