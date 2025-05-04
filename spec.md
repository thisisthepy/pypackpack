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
      - util
        - CommandLine.kt  # CLI endpoint (Main 함수)
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
    - pyproject.lock  # 환경 별 세부 패키지 설정
    - pyproject.toml  # 패키지 설정 (dependency 관리 등)
  - <package2>
    - ...
  - .gitignore
  - LICENSE
  - README.md
  - pyproject.lock
  - pyproject.toml
```


## 기능 명세 
### 기본 기능
#### CLI Help (DevEnv.kt)
```bash
pypackpack help
pypackpack --help
```
- CLI 사용법을 출력

#### CLI Version (DevEnv.kt)
```bash
pypackpack version
pypackpack --version
pypackpack -v
```
- 현재 사용 중인 pypackpack 버전과 감지된 uv 버전을 출력

#### 프로젝트 생성 (DevEnv.kt)
```bash
pypackpack init [<project name>] [<python version>]
```
- 프로젝트 폴더를 하위에 새로 생성(project name 인자가 들어온 경우)하거나 현재 폴더 경로를 프로젝트 루트로(project name이 비어있는 경우) 설정
- pyproject.toml이 이미 있으면 ppp를 사용 중인지 확인하고, 만약 ppp를 사용중인 것이 아니라면 다른 패키징 도구를 사용중이라고 오류를 띄우고, 사용중이라면 이미 이니셜라이즈 되었다고 오류를 띄움
- 프로젝트 루트에 기본적인 파일들을 생성 (pyproject.toml, LICENSE, README.md, .gitignore)
- 시스템에 uv가 설치되어있는지 확인 후 만약 설치되어 있지 않다면 최신버전의 uv를 다운받을 수 있는 명령을 출력
- 지정된 파이썬 버전을 uv를 통해 다운로드 받고 .venv 생성
#### 프로젝트 파이썬 버전 변경 (DevEnv.kt)
```bash
pypaackpack python use <python version>
```
- 지정된 파이썬 버전을 uv를 통해 다운로드 받고 .venv와 프로젝트 전체 빌드 내역 초기화(하위 패키지들의 build 폴더 삭제)
#### 프로젝트 파이썬 관련 추가 기능 (DevEnv.kt)
```bash
pypackpack python list
pypackpack python find <python version>
pypackpack python install <python version>
pypackpack python uninstall <python version> 
```
- nv python ~~ 으로 명령어 포워딩

### 패키지 관리 기능
#### 패키지 추가 (CrossEnv.kt)
```bash
pypackpack package add <package name>
pypackpack package remove <package name>
```
- 지정된 이름으로 새로운 패키지를 프로젝트 루트에 생성 (init, python, package, target, add, remove, sync, tree, build, bundle, deploy는 패키지 이름으로 사용 불가능 -- 검사 필요)
- 새 패키지를 프로젝트 루트 pyproject.toml에 기록하고, 패키지 내부 pyproject.toml을 생성
- * 프로젝트 루트 pyproject.toml에서 pip install git+https://github.com/?/??.git['package name'] 형태로 설치될 수 있도록 빌드 설정
- 패키지 내부에 src 폴더를 생성하고, src 폴더에 main 폴더를 생성, main 폴더에 __init__.py 파일을 생성
- 패키지 내부에 src 폴더 안에 test 폴더를 생성하고, 그 내부에 .gitkeep 파일을 생성
- 패키지 내부에 build 폴더를 생성하고, build 폴더에 crossenv 폴더를 생성, crossenv 폴더에 현재 실행 중인 host platform에 맞는 venv를 생성

### 패키지 빌드 타겟 관리 기능
#### 빌드 타겟 플랫폼 추가/제거 (CrossEnv.kt)
```bash
pypackpack target add <target name> [<package name>]
pypackpack target remove <target name> [<package name>]
```
- 해당 패키지에 대해 타겟을 venv를 생성하고, 타겟에 대한 소스 디렉토리 추가
- 의존성 패키지를 새 타겟에 대해 다시 전부 설치 후 lock 파일 sync 진행
- lock file 동기화는 각 플랫폼의 lock으로 부터 uv export를 진행 후 플랫폼별 pylock.toml을 생성하여 uv pip sync pylock.toml --python-platform <플랫폼 이름>을 각 플랫폼별 venv에 대상으로 진행 

### 의존성 관리 기능
#### Dev 환경 의존성 관리 (DevEnv.kt)
```bash
pypackpack add <pypi name> [<etcs>]
```
- 사용자의 요청에 따라 package root pyproject.toml에 의존성을 추가한 후 해당 의존성을 venv에 설치
- uv를 통해 pip install을 실행하되, uv는 .venv 안에서 실행되어 uv.lock를 생성하고, uv 실행이 끝나면 그 파일을 복사하여 프로젝트 루트 pyproject.lock에 붙여넣기
- * uv 실행 전에도 pyproject.lock을 .venv 안의 pyproject.lock에 붙여넣기
```bash
pypackpack remove <pypi name> [<etcs>]
```
- add와 반대로 동작
```bash
pypackpack sync [<etcs>]
```
```bash
pypackpack tree [<etcs>]
```

#### 패키지별, 타켓 빌드 플랫폼 별 의존성 관리 (CrossEnv.kt)
```bash
pypackpack <package name> add <pypi name> [--target <target name>] [<etcs>]
```
```bash
# example
pypackpack mypackage add numpy --target windows linux
pypackpack mypackage add numpy --target windows linux --extra-index-url https://pypi.org/simple
```
- 지정된 패키지에 의존성을 추가
- target name에 인자가 들어오면 해당 타겟에 추가하고 인자가 비어있으면 모든 타겟에 추가
- 패키지 내부 pyproject.toml에 의존성을 기록하고, 패키지 내부 build 폴더에 있는 crossenv 폴더에 해당 타겟의 venv에 의존성을 설치
```bash
pypackpack <package name> remove <pypi name> [--target <target name>] [<etcs>]
```
```bash
```bash
pypackpack <package name> sync [--target <target name>] [<etcs>]
```
```bash
```bash
pypackpack <package name> tree [--target <target name>] [<etcs>]
```

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
