#!/usr/bin/env bash
# 下载 ASR 候选模型（FireRedASR2 CTC/AED、Qwen3-ASR、ct-punc 标点），分片并发 + 字节数校验。
#
# 用法：
#   bash scripts/fetch_asr_models.sh            # 下载全部
#   bash scripts/fetch_asr_models.sh --check    # 只校验已有文件
#   bash scripts/fetch_asr_models.sh firered_ctc qwen3   # 只下指定项
#
# 输出目录：dist/models/<name>/...
# 说明：hf-mirror 单连接约 400KB/s 被限速，故 12 路 range 并发；校验按官方字节数，
#      镜像截断是真实发生过的故障（曾导致 native SIGABRT），必须逐字节比对。

set -u
BASE="https://hf-mirror.com"
OUT="$(cd "$(dirname "$0")/.." && pwd)/dist/models"
WORKERS=12
CHUNK=$((8 * 1024 * 1024))
CHECK_ONLY=0

mkdir -p "$OUT"

# ---- 清单：name|repo|remote_path|size ----
MANIFEST="
firered_ctc|csukuangfj2/sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25|model.int8.onnx|775861420
firered_ctc|csukuangfj2/sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25|tokens.txt|79172
firered_aed|csukuangfj2/sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26|encoder.int8.onnx|817286833
firered_aed|csukuangfj2/sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26|decoder.int8.onnx|417291928
firered_aed|csukuangfj2/sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26|tokens.txt|79172
qwen3|csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25|conv_frontend.onnx|44148281
qwen3|csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25|encoder.int8.onnx|182491662
qwen3|csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25|decoder.int8.onnx|755914231
qwen3|csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25|tokenizer/vocab.json|2776833
qwen3|csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25|tokenizer/merges.txt|1671853
qwen3|csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25|tokenizer/tokenizer_config.json|12487
"

want_filter=""
for a in "$@"; do
  case "$a" in
    --check) CHECK_ONLY=1 ;;
    *) want_filter="$want_filter $a" ;;
  esac
done

# 单文件：分片并发下载 + 按序拼接
fetch_one() {
  local name="$1" repo="$2" path="$3" size="$4"
  local dir="$OUT/$name"
  local dest="$dir/$(basename "$path")"
  mkdir -p "$(dirname "$dest")"

  if [ -f "$dest" ]; then
    local cur
    cur=$(stat -c %s "$dest")
    if [ "$size" -gt 0 ] && [ "$cur" = "$size" ]; then
      echo "[ok] $(basename "$dest") 已存在且大小一致"
      return 0
    fi
    [ "$CHECK_ONLY" = "1" ] && { echo "[bad] $dest 大小 $cur != $size"; return 1; }
    rm -f "$dest"
  fi
  [ "$CHECK_ONLY" = "1" ] && { echo "[miss] $dest"; return 1; }

  local url="$BASE/$repo/resolve/main/$path"
  local parts="$dir/.parts_$(basename "$path")"
  rm -rf "$parts"; mkdir -p "$parts"

  local n=$(( (size + CHUNK - 1) / CHUNK ))
  echo "[get] $name/$(basename "$path")  ${size}B  ${n} 片"

  # 逐个分片，最多 12 路并发
  local running=0
  local i
  for ((i = 0; i < n; i++)); do
    local start=$((i * CHUNK))
    local end=$((start + CHUNK - 1))
    [ "$end" -ge "$size" ] && end=$((size - 1))
    local expect=$((end - start + 1))
    (
      local f="$parts/$(printf '%05d' "$i")"
      for try in 1 2 3 4; do
        curl -sL -m 600 --noproxy '*' -r "$start-$end" -o "$f" "$url"
        if [ -f "$f" ] && [ "$(stat -c %s "$f")" = "$expect" ]; then exit 0; fi
        sleep $((try * 2))
      done
      echo "[err] chunk $i of $(basename "$path")" >&2
      exit 1
    ) &
    running=$((running + 1))
    if [ "$running" -ge "$WORKERS" ]; then wait -n 2>/dev/null || wait; running=$((running - 1)); fi
  done
  wait

  cat "$parts"/* > "$dest.tmp"
  local got
  got=$(stat -c %s "$dest.tmp")
  if [ "$got" != "$size" ]; then
    echo "[bad] $(basename "$dest") 拼接后 $got != $size，重试整文件"
    rm -f "$dest.tmp"
    return 1
  fi
  mv "$dest.tmp" "$dest"
  rm -rf "$parts"
  echo "[ok] $(basename "$dest")  ${size}B"
  return 0
}

echo "$MANIFEST" | while IFS='|' read -r name repo path size; do
  [ -z "${name:-}" ] && continue
  if [ -n "$want_filter" ] && ! echo "$want_filter" | grep -qw "$name"; then continue; fi
  fetch_one "$name" "$repo" "$path" "$size" || echo "[fail] $name/$path"
done

echo "---- 汇总 ----"
du -sh "$OUT" 2>/dev/null
find "$OUT" -type f -not -path "*/.parts_*" | sort | while read -r f; do
  printf '%12d  %s\n' "$(stat -c %s "$f")" "${f#$OUT/}"
done
