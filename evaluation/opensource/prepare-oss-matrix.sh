#!/usr/bin/env bash
# 准备 UnitMind 开源项目覆盖率闭环矩阵测试所需的本地仓库。
#
# 用法：
#   ./evaluation/opensource/prepare-oss-matrix.sh            # 准备全部项目
#   ./evaluation/opensource/prepare-oss-matrix.sh commons-io # 只准备指定项目
#
# 每个项目会：
#   1. 按固定 tag 浅克隆到 tmp/oss/<name>（已存在则跳过克隆）。
#   2. mvn -DskipTests compile 产出 target/classes。
#   3. dependency:build-classpath 按 test scope 导出依赖到 target/unitmind.classpath，
#      与 IDE 中模块 test classpath 的形态一致（包含项目自带的 JUnit 5 / Mockito），
#      专门用来暴露插件运行时与项目 JUnit 版本组合出的真实问题。
#
# 多模块项目（eclipse-collections）先根目录 install，再在子模块里导出 classpath；
# Gradle 多模块项目（mockito、resilience4j）与需要 Scala 代码生成的 vavr 暂不支持，见 opensource/README.md。
#
# 直连 GitHub 不通时（内网/大陆网络）可设置镜像前缀，例如：
#   UNITMIND_GIT_PREFIX="https://gh-proxy.com/" ./evaluation/opensource/prepare-oss-matrix.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OSS_DIR="$ROOT/tmp/oss"
mkdir -p "$OSS_DIR"

# 内网/受限网络下的 git 镜像前缀；为空表示直连 GitHub。
GIT_PREFIX="${UNITMIND_GIT_PREFIX:-}"

# name|git-url|tag|module|mode|jdk
# - module：目标子模块目录；单模块项目留空。
# - mode：root=根目录构建；standalone=模块自身可独立构建；reactor=先根 install 再进模块导 classpath。
# - jdk：指定 java_home 大版本；留空使用当前 JDK。source/target 8 的老项目必须显式给 17：
#   JDK 21 起 javac 已移除 release 8 支持，而 17 仍兼容。
PROJECTS=(
  "commons-codec|https://github.com/apache/commons-codec.git|rel/commons-codec-1.16.1||root|17"
  "commons-io|https://github.com/apache/commons-io.git|rel/commons-io-2.16.1||root|17"
  "commons-lang|https://github.com/apache/commons-lang.git|rel/commons-lang-3.14.0||root|17"
  "commons-text|https://github.com/apache/commons-text.git|rel/commons-text-1.11.0||root|17"
  "guava|https://github.com/google/guava.git|v33.0.0|guava|standalone|17"
  "jackson-databind|https://github.com/FasterXML/jackson-databind.git|jackson-databind-2.17.0||root|17"
  "jackson-core|https://github.com/FasterXML/jackson-core.git|jackson-core-2.17.0||root|17"
  "HikariCP|https://github.com/brettwooldridge/HikariCP.git|HikariCP-5.1.0||root|"
  "mybatis-3|https://github.com/mybatis/mybatis-3.git|mybatis-3.5.16||root|17"
  "spring-petclinic|https://github.com/spring-projects/spring-petclinic.git|main||root|"
)

run_mvn() { # run_mvn <dir> <args...>
  local dir="$1"; shift
  if [ -n "$PROJECT_JDK_HOME" ]; then
    (cd "$dir" && JAVA_HOME="$PROJECT_JDK_HOME" mvn -q "$@")
  else
    (cd "$dir" && mvn -q "$@")
  fi
}

WANTED="${1:-}"
for entry in "${PROJECTS[@]}"; do
  IFS='|' read -r name url tag module mode jdk <<<"$entry"
  if [ -n "$WANTED" ] && [ "$WANTED" != "$name" ]; then continue; fi
  repo="$OSS_DIR/$name"
  PROJECT_JDK_HOME=""
  if [ -n "$jdk" ]; then
    PROJECT_JDK_HOME="$(/usr/libexec/java_home -v "$jdk" 2>/dev/null || true)"
    [ -z "$PROJECT_JDK_HOME" ] && { echo "!! 未找到 JDK $jdk，跳过 $name"; continue; }
  fi
  if [ ! -d "$repo/.git" ]; then
    echo "== clone $name ($tag) prefix=${GIT_PREFIX:-<none>}"
    git clone --depth 1 --branch "$tag" "${GIT_PREFIX}${url}" "$repo"
  else
    echo "== reuse existing clone $name"
  fi
  echo "== build $name (mode=$mode jdk=${jdk:-default})"
  case "$mode" in
    root)
      run_mvn "$repo" -DskipTests compile dependency:build-classpath \
        -Dmdep.outputFile=target/unitmind.classpath -Dmdep.scope=test
      ;;
    standalone)
      run_mvn "$repo/$module" -DskipTests compile dependency:build-classpath \
        -Dmdep.outputFile=target/unitmind.classpath -Dmdep.scope=test
      ;;
    reactor)
      # 多模块：根 install 后在目标子模块导出 classpath（含兄弟模块产物）
      run_mvn "$repo" -DskipTests install
      run_mvn "$repo/$module" dependency:build-classpath \
        -Dmdep.outputFile=target/unitmind.classpath -Dmdep.scope=test
      ;;
  esac
  echo "== done $name -> $repo/${module:+$module/}target/classes"
done

echo "all projects ready under $OSS_DIR"
