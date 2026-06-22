#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_TARGET_ROOT="$(cd "${PROJECT_DIR}/.." && pwd)"
TARGET_ROOT="${1:-${DEFAULT_TARGET_ROOT}}"
TIMESTAMP="$(date '+%Y%m%d-%H%M%S')"
PACKAGE_DIR="${TARGET_ROOT}/UnitMind-offline-package-${TIMESTAMP}"
GRADLE_HOME_TO_PACK="${GRADLE_USER_HOME:-${HOME}/.gradle}"

log() {
  printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"
}

require_file() {
  if [[ ! -f "$1" ]]; then
    printf 'Missing required file: %s\n' "$1" >&2
    exit 1
  fi
}

require_dir() {
  if [[ ! -d "$1" ]]; then
    printf 'Missing required directory: %s\n' "$1" >&2
    exit 1
  fi
}

cd "${PROJECT_DIR}"

require_file "${PROJECT_DIR}/gradlew"
require_dir "${GRADLE_HOME_TO_PACK}"

mkdir -p "${PACKAGE_DIR}"

log "Project: ${PROJECT_DIR}"
log "Target package: ${PACKAGE_DIR}"
log "Gradle user home to pack: ${GRADLE_HOME_TO_PACK}"

log "Building plugin with Gradle offline mode..."
./gradlew --offline buildPlugin

mapfile -t PLUGIN_ZIPS < <(find "${PROJECT_DIR}/build/distributions" -maxdepth 1 -type f -name '*.zip' -printf '%T@ %p\n' | sort -nr | awk '{print $2}')
if [[ "${#PLUGIN_ZIPS[@]}" -eq 0 ]]; then
  printf 'No plugin zip found under build/distributions after buildPlugin.\n' >&2
  exit 1
fi

PLUGIN_ZIP="${PLUGIN_ZIPS[0]}"
PLUGIN_TARGET="${PACKAGE_DIR}/$(basename "${PLUGIN_ZIP%.zip}")-plugin.zip"

log "Copying plugin zip..."
cp -f "${PLUGIN_ZIP}" "${PLUGIN_TARGET}"

SOURCE_ARCHIVE="${PACKAGE_DIR}/Generate-source-${TIMESTAMP}.tar.gz"
log "Packing source archive..."
tar \
  --exclude='./.git' \
  --exclude='./build' \
  --exclude='./.gradle' \
  --exclude='./.gradle-home' \
  --exclude='./.idea' \
  --exclude='./*.iml' \
  --exclude='./UnitMind-offline-package-*' \
  -czf "${SOURCE_ARCHIVE}" .

log "Stopping Gradle daemons before packing cache..."
./gradlew --stop >/dev/null || true

GRADLE_ARCHIVE="${PACKAGE_DIR}/gradle-user-home-${TIMESTAMP}.tar.gz"
log "Packing Gradle user home. This can take several minutes..."
tar -C "$(dirname "${GRADLE_HOME_TO_PACK}")" -czf "${GRADLE_ARCHIVE}" "$(basename "${GRADLE_HOME_TO_PACK}")"

README_FILE="${PACKAGE_DIR}/README-offline.md"
log "Writing README..."
cat > "${README_FILE}" <<EOF_README
# UnitMind 离线包使用说明

本目录用于把当前可联网/已有依赖环境迁移到不联网机器。

## 文件说明

- \`$(basename "${PLUGIN_TARGET}")\`：已经构建好的 IntelliJ IDEA 插件安装包。
- \`$(basename "${SOURCE_ARCHIVE}")\`：当前源码压缩包，已排除 \`.git\`、\`build\`、\`.gradle\`、\`.gradle-home\`、\`.idea\` 等本机状态目录。
- \`$(basename "${GRADLE_ARCHIVE}")\`：完整 Gradle 用户目录归档，包含 wrapper、caches、IntelliJ/Gradle 插件相关缓存等离线构建所需内容。

## 内网机器使用

1. 解压源码包到目标目录。
2. 解压 Gradle 缓存包到 Gradle 用户目录所在位置，使归档内的 \`$(basename "${GRADLE_HOME_TO_PACK}")\` 目录恢复为实际 Gradle 用户目录。
   - Linux/macOS/WSL 默认通常是 \`~/.gradle\`。
   - Windows 默认通常是 \`C:\\Users\\<用户名>\\.gradle\`。
3. 进入源码目录后执行：

\`\`\`bash
./gradlew --offline buildPlugin
\`\`\`

Windows CMD/PowerShell 可执行：

\`\`\`bat
gradlew.bat --offline buildPlugin
\`\`\`

构建成功后，插件包位于 \`build/distributions/UnitMind-1.0.0.zip\`。

## 直接安装插件

在 IntelliJ IDEA 中选择 \`Settings/Preferences -> Plugins -> Install Plugin from Disk...\`，选择本目录中的插件 zip。

## 环境要求

- JDK 17。
- IntelliJ IDEA 插件目标平台由源码中的 \`build.gradle.kts\` 配置控制，当前为 IDEA Community \`2022.2.5\`。
EOF_README

log "Generating SHA-256 checksums..."
if command -v sha256sum >/dev/null 2>&1; then
  (cd "${PACKAGE_DIR}" && sha256sum ./* > SHA256SUMS.txt)
elif command -v shasum >/dev/null 2>&1; then
  (cd "${PACKAGE_DIR}" && shasum -a 256 ./* > SHA256SUMS.txt)
else
  log "sha256sum/shasum not found; skipped checksum file."
fi

log "Verifying tar.gz archives..."
gzip -t "${SOURCE_ARCHIVE}" "${GRADLE_ARCHIVE}"

log "Done."
ls -lh "${PACKAGE_DIR}"
