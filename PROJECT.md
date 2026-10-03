# PROJECT: pypackpack

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

**구현 (테스트 있음)**
- `python list` / `find` / `uninstall` (`~/.pypackpack/python/registry.properties` 우선, 설치 루트 스캔)
- `python install`: 3.14.7 (python-build-standalone 20260807 / python.org Android / BeeWare 3.14-b11 iOS)
  와 3.13.0 (python-multiplatform `release` 브랜치 `binary/`, 데스크톱만). 모든 아카이브를
  `PythonDistributions.kt` 에 고정한 SHA-256 으로 압축 해제 전에 검증하고, 불일치나 고정값이 없는 쌍은
  실패한다. 스테이징 디렉터리에 받고 풀어서 옮기므로 실패해도 아무것도 남지 않는다. 가짜 아카이브로
  테스트 (`DefaultBackendTest`, `PythonDistributionsTest`); 실제 다운로드 테스트는 없음
- `installPython(version, target, installDir)` (라이브러리 API, #37): 같은 고정·검증 후 주어진 디렉터리를
  통째로 교체한다(옆 스테이징에서 이름 바꾸기). `projectRoot()`(`user.dir`)·`.venv`·레지스트리는 건드리지
  않는다. 가짜 아카이브 테스트 4개 (`DefaultBackendTest.installPython_explicitInstallDir*`)
- `target add` / `remove` (워크스페이스 멤버까지 전파, `src/<family>/__init__.py` 생성)
- `<package> remove --target`: `uv` 가 다시 쓴 `sys_platform` 마커도 매칭 (예전 결함 해결)
- `<package> sync`: 타깃별 `build/crossenv/<target>` 에 `uv pip install --target` 로 설치.
  `aarch64-linux-android` / `arm64-apple-ios` 에 실제 휠(`six`, `markupsafe`)을 설치하는 테스트
  (`UVBackendRealInstallTest`, 네트워크와 `uv` 필요) 있음
- `installDependenciesToTarget(..., requirements: List<String>? = null)` (라이브러리 API, #36); 목록을 주면
  `-r pyproject.toml` 대신 요구사항을 `uv pip install` 위치 인자로 그대로 전달(`pyproject.toml` 불필요, 빈 목록은
  성공한 no-op, `null` 은 기존 동작). 가짜 러너 테스트 4개 + 네트워크 테스트 1개 (`UVBackendRealInstallTest`)
- 번들 `resource` (`instant`, `bytecode`), `single`, `fat`, `patch` (`instant` 만)
- CLI 도움말, 통과 플래그(`--dev`, `--extra-index-url …`) 파싱
- `meson.build` 자동 생성, `meson`/`ninja` 자동 설치

**부분**
- `init`, `python use`, `package add/remove`, `target list`
- `version`: `pypackpack version` 만 동작. `--version` / `-v` / `v` 는 실패한다
- 루트 `add/remove/sync/tree`, `<package> add/tree`: CLI 전달만 테스트, `uv` 호출은 테스트 없음
- `build`: Meson 으로 C/C++ 만. `--level` / `--target` 은 디렉터리 이름만 바꾸고 교차 컴파일하지 않음
- 번들은 라이브러리 API 뿐, CLI 명령 없음. `build` 가 번들을 호출하지 않음
- GraalVM 네이티브 이미지: 설정은 있으나 이를 빌드하는 테스트나 CI 가 없음

**계획 (빈 파일이거나 없음)**
- `native` / `mixed` 빌드 레벨, `binary` 번들
- Clang/MSVC/NDK/XCode/Emscripten/Cargo 백엔드, Nuitka/Cython/Lpython, 최소화(minification)
- `deploy`: 명령은 등록되어 있지만 모든 타입이 "not implemented" 로 실패
- 패치 버전 추적, 코드 업데이트 fast track API

## 구조

```
packpack/        라이브러리 (dependency/, compile/, bundle/, deploy/, utils/): Maven 게시
cli/             pypackpack / ppp CLI (Clikt, application, GraalVM native image)
usage-example/   :packpack 에 의존하는 빌드 파일 (소스 없음)
docs/            INTENT.md, SPEC.md, issues/, locale/
.github/scripts/release/   main 용 release 브랜치 생성 스크립트와 테스트
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
bash .github/scripts/release/test-sync-release.sh   # release 동기화 스크립트 테스트
```

결과는 `<module>/build/test-results/test/*.xml` 에서 센다. 2026-10-03 기준 `:packpack` 135개,
`:cli` 26개, 실패 0. Python 테스트는 없다.

## 결정 사항

- **라이브러리와 CLI 분리**: `:cli` 가 `:packpack` 에 의존하고 반대는 없다. 라이브러리 소비자
  (`toolchain`)가 Clikt 를 받지 않게 하기 위함.
- **백엔드 계층은 `workingDir` 를 명시적으로 받는다**: `toolchain` 은 Gradle 데몬 안에서 호출하므로
  JVM 전역 `user.dir` 에 의존하면 안 된다. 예외(`compile` 의 `DefaultBackend.compile`,
  `installDir` 없이 호출한 `installPython`)는 결함으로 기록. `installPython(version, target, installDir)` 은
  주어진 디렉터리에만 설치하고 `projectRoot()`·`.venv`·레지스트리를 건드리지 않는다(#37, `toolchain` 의
  `build/pythonRuntime/<트리플>/<버전>/` 용).
- **미구현은 실패한 `Result`**: `UnimplementedBundler`, `UnimplementedDeployer`; 빈 결과로 성공하지 않는다.
- **타깃 이름은 정규 트리플**: 별칭(`windows` 등)은 `Platforms.normalizeTarget` 으로 정규화하고,
  `build/crossenv/<트리플>` 같은 디렉터리 이름에도 트리플을 쓴다.
- **마커는 (system, machine) 쌍으로 비교**: `uv` 가 마커 문자열을 다시 쓰기 때문.
- **`resource` 번들은 디렉터리**: 압축은 `toolchain` 의 일.

## 열린 질문

1. ~~3.13.0 모바일 배포판의 SHA-256 고정~~ **필요 없음 (2026-10-04):** 지원 범위가 CPython 3.15 이상,
   free-threaded 전용이다 (python-multiplatform #158, 기준 3.15t, PEP 803 `abi3t`). 3.13.0·3.14.7 항목과 `cp313` 태그는 정리 대상이다 (#70).
2. ~~WASM 을 첫 릴리스 범위에 넣을 것인가~~ **결정 (2026-10-04):** 넣는다.
3. ~~`build` 가 번들까지 할 것인가~~ **결정 (메인테이너 사양서 `spec.md`, 686b1ad):** `build` 가 번들을
   인자로 받는다 (`build <package> source [<bundle type>]`, `build <package> resource`). `bundle` 명령은 없다.
4. ~~`build --target` 별칭·생략 시 경로 불일치~~ **질문이 아니라 결함:** 위 결정 사항 "타깃 이름은 정규 트리플"
   대로 `build` 가 `Platforms.normalizeTarget` 으로 정규화해야 한다 (SPEC *Package build* Limitation).
5. ~~`deploy` 대상 서버~~ **고를 문제가 아니었다 (2026-10-04):** 라이브러리는 PyPI, 앱은 FastTrack.
6. ~~루트 `pyproject.toml` 의 Python 래퍼로 PyPI 배포를 할 것인가.~~ **결정 (2026-10-03):** 한다. 플랫폼마다 네이티브 바이너리를 담은 휠을 `publish-pypi.yml` 이 올린다 (#52).
7. ~~`README.md` 의 배지와 `scripts/build-native.sh` 가 없는 것을 가리킨다.~~ **해결 (2026-10-03, #58):**
   README 를 SPEC 과 가이드 기준으로 다시 썼다 (PyPI 페이지가 된다).
