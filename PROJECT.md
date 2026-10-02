# PROJECT — pypackpack

Python 프로젝트를 여러 플랫폼(데스크톱, Android, iOS, WASM)으로 배포하기 위한 빌드 도구.
Python 확보, 의존성 해석(`uv`), 컴파일(Meson), 번들링을 맡는다. GraalVM 네이티브 CLI
`pypackpack`(`ppp`) 이면서, `toolchain` 이 호출하는 JVM 라이브러리
(`org.thisisthepy.python.multiplatform:packpack:0.1.0`) 이기도 하다.

## 사양의 출처

| 무엇 | 어디 |
|---|---|
| 의도 / 계약 | `docs/INTENT.md` / `docs/SPEC.md` (항목마다 `Status:`) |
| 생태계 안에서의 역할 | `python-multiplatform` 저장소의 `docs/design/ecosystem.md` §1, §5 |
| 알려진 결함 | `docs/issues/KNOWN_ISSUES.md` |
| 체크리스트 | GitHub 이슈 `thisisthepy/pypackpack#2` (초기 개발), `#1` (의존성 관리), `#5` (플랫폼 마커) |

## 현재 상태 (2026-10-03, 코드와 테스트를 읽고 판정)

**구현됨 (테스트 있음)**
- `python list` / `find` / `uninstall` (`~/.pypackpack/python/registry.properties` 우선, 설치 루트 스캔)
- `target add` / `remove` (워크스페이스 멤버까지 전파, `src/<family>/__init__.py` 생성)
- `<package> remove --target` — `uv` 가 다시 쓴 `sys_platform` 마커도 매칭 (예전 결함 해결)
- `<package> sync` — 타깃별 `build/crossenv/<target>` 에 `uv pip install --target` 로 설치.
  `aarch64-linux-android` / `arm64-apple-ios` 에 실제 휠(`six`, `markupsafe`)을 설치하는 테스트
  (`UVBackendRealInstallTest`, 네트워크와 `uv` 필요) 있음
- 번들 `resource` (`instant`, `bytecode`), `single`, `fat`, `patch` (`instant` 만)
- CLI 도움말, 통과 플래그(`--dev`, `--extra-index-url …`) 파싱
- `meson.build` 자동 생성, `meson`/`ninja` 자동 설치

**부분**
- `init`, `python use`, `python install` (3.13 만, 다운로드 테스트 없음), `package add/remove`, `target list`
- `version` — `pypackpack version` 만 동작. `--version` / `-v` / `v` 는 실패한다
- 루트 `add/remove/sync/tree`, `<package> add/tree` — CLI 전달만 테스트, `uv` 호출은 테스트 없음
- `build` — Meson 으로 C/C++ 만. `--level` / `--target` 은 디렉터리 이름만 바꾸고 교차 컴파일하지 않음
- 번들은 라이브러리 API 뿐, CLI 명령 없음. `build` 가 번들을 호출하지 않음
- GraalVM 네이티브 이미지 — 설정은 있으나 이를 빌드하는 테스트나 CI 가 없음

**계획 (빈 파일이거나 없음)**
- `native` / `mixed` 빌드 레벨, `binary` 번들
- Clang/MSVC/NDK/XCode/Emscripten/Cargo 백엔드, Nuitka/Cython/Lpython, 최소화(minification)
- `deploy` — 명령은 등록되어 있지만 모든 타입이 "not implemented" 로 실패
- 패치 버전 추적, 코드 업데이트 fast track API

## 구조

```
packpack/        라이브러리 (dependency/, compile/, bundle/, deploy/, utils/) — Maven 게시
cli/             pypackpack / ppp CLI (Clikt, application, GraalVM native image)
usage-example/   :packpack 에 의존하는 빌드 파일 (소스 없음)
docs/            INTENT.md, SPEC.md, issues/, locale/
tools/release/   main 용 release 브랜치 생성 스크립트와 테스트
```

## 빌드와 테스트

전제: JDK 21 (기본 JDK 25 에서는 Kotlin Gradle 플러그인이 깨진다), `PATH` 에 `python3`
(`ResourceBundlerTest` 의 `bytecode` 테스트). 네이티브 CLI 는 GraalVM `native-image` 필요.

```bash
export JAVA_HOME=/Users/ibrew/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home
rm -rf packpack/build/test-results
./gradlew :packpack:test --rerun --console=plain > .tmp/packpack-test.log 2>&1; echo "EXIT=$?"
rm -rf cli/build/test-results
./gradlew :cli:test --rerun --console=plain > .tmp/cli-test.log 2>&1; echo "EXIT=$?"
./gradlew :packpack:publishToMavenLocal   # toolchain 을 이 변경으로 빌드하기 전에 반드시
bash tools/release/test-sync-release.sh   # release 동기화 스크립트 테스트
```

결과는 `<module>/build/test-results/test/*.xml` 에서 센다. 2026-10-03 기준 `:packpack` 135개,
`:cli` 26개, 실패 0. Python 테스트는 없다.

## 결정 사항

- **라이브러리와 CLI 분리**: `:cli` 가 `:packpack` 에 의존하고 반대는 없다. 라이브러리 소비자
  (`toolchain`)가 Clikt 를 받지 않게 하기 위함.
- **백엔드 계층은 `workingDir` 를 명시적으로 받는다**: `toolchain` 은 Gradle 데몬 안에서 호출하므로
  JVM 전역 `user.dir` 에 의존하면 안 된다. 예외(`compile` 의 `DefaultBackend.compile`,
  `installPython`)는 결함으로 기록.
- **미구현은 실패한 `Result`**: `UnimplementedBundler`, `UnimplementedDeployer` — 빈 결과로 성공하지 않는다.
- **타깃 이름은 정규 트리플**: 별칭(`windows` 등)은 `Platforms.normalizeTarget` 으로 정규화하고,
  `build/crossenv/<트리플>` 같은 디렉터리 이름에도 트리플을 쓴다.
- **마커는 (system, machine) 쌍으로 비교**: `uv` 가 마커 문자열을 다시 쓰기 때문.
- **`resource` 번들은 디렉터리**: 압축은 `toolchain` 의 일.

## 열린 질문

1. 지원 Python 버전 — `python install` 은 3.13 만. python-multiplatform 을 따라갈 것인가.
2. WASM 을 첫 릴리스 범위에 넣을 것인가 (`wasm32-pyodide2024` 는 타깃 목록에만 있음).
3. `build` 가 번들까지 할 것인가, `bundle` 을 별도 명령으로 둘 것인가.
4. `build --target` 이 별칭이나 생략(`default`)일 때, 번들러가 찾는 정규 트리플 경로와 어긋난다.
   `build` 쪽에서 정규화할 것인가.
5. `deploy` 대상 서버 (PyPI 또는 FastTrack).
6. 루트 `pyproject.toml` 이 가리키는 Python 래퍼 패키지(빈 파일 두 개)로 PyPI 배포를 할 것인가.
7. `README.md` 의 배지(Build 워크플로, JetBrains Marketplace `MARKETPLACE_ID`)와
   `scripts/build-native.sh` 는 이 저장소에 없는 것을 가리킨다. 정리할 것인가.
