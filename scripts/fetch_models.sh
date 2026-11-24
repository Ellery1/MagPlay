#!/bin/bash
# 拉取离线字幕模型（不入库，因为单文件 238MB 超过 GitHub 100MB 上限）
#
# 说明：
#   Paraformer 中文非流式 int8 模型是离线字幕的主模型，构建 APK 时必须存在于
#   app/src/main/assets/models/asr/paraformer/model.int8.onnx，否则打包出来的 APK
#   运行时会因缺模型而无法生成字幕（编译本身不会报错，容易漏）。
#
# 用法：
#   bash scripts/fetch_models.sh          # 下载并校验
#   bash scripts/fetch_models.sh --check  # 只校验已存在的文件
#
# 注意：
#   - GitHub 直连在国内常被限速，脚本默认走 gh-proxy 镜像；如镜像失效改成直连即可。
#   - 必须按**精确字节数**校验：镜像会静默截断（本项目 VAD 模型曾被截断 166KB，
#     导致 ONNX Runtime 解析残缺 protobuf 抛 C++ 异常、进程 SIGABRT，排查了很久）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT/app/src/main/assets/models/asr/paraformer"
MODEL="$DEST_DIR/model.int8.onnx"
EXPECT_BYTES=238429929

# sherpa-onnx 官方模型包（含 model.int8.onnx + tokens.txt）
PKG="sherpa-onnx-paraformer-zh-int8-2025-10-07"
GH_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$PKG.tar.bz2"
MIRROR_URL="https://gh-proxy.com/$GH_URL"

check() {
  if [ ! -f "$MODEL" ]; then
    echo "缺失：$MODEL"
    return 1
  fi
  local size
  size=$(stat -c %s "$MODEL" 2>/dev/null || stat -f %z "$MODEL")
  if [ "$size" != "$EXPECT_BYTES" ]; then
    echo "字节数不符：实际 $size / 期望 $EXPECT_BYTES（疑似截断，请重新下载）"
    return 1
  fi
  echo "OK：model.int8.onnx $size 字节"
  return 0
}

if [ "${1:-}" = "--check" ]; then
  check
  exit $?
fi

check && { echo "模型已就绪，无需下载"; exit 0; }

mkdir -p "$DEST_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "下载 $PKG ..."
if ! curl -fL --retry 3 -o "$TMP/pkg.tar.bz2" "$MIRROR_URL"; then
  echo "镜像失败，尝试直连 GitHub ..."
  curl -fL --retry 3 -o "$TMP/pkg.tar.bz2" "$GH_URL"
fi

echo "解压 ..."
tar -xjf "$TMP/pkg.tar.bz2" -C "$TMP"
cp "$TMP/$PKG/model.int8.onnx" "$MODEL"
cp "$TMP/$PKG/tokens.txt" "$DEST_DIR/tokens.txt" 2>/dev/null || true

check || { echo "校验失败，模型不可用"; exit 1; }
echo "完成：$MODEL"
