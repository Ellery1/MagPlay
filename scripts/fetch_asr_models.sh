#!/usr/bin/env bash
# 下载 AED 模型（唯一引擎）：分片并发 + 官方字节数校验。
# 用法：bash scripts/fetch_asr_models.sh [--check]
# 说明：镜像截断是真实发生过的故障（曾导致 native SIGABRT），必须逐字节比对。
set -u
BASE="https://hf-mirror.com"
OUT="$(cd "$(dirname "$0")/.." && pwd)/dist/models"
WORKERS=12
CHUNK=$((8 * 1024 * 1024))
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

MANIFEST="
firered_aed|csukuangfj2/sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26|encoder.int8.onnx|817286833
firered_aed|csukuangfj2/sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26|decoder.int8.onnx|417291928
firered_aed|csukuangfj2/sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26|tokens.txt|79172
"

fetch_one() {
  local name="$1" repo="$2" path="$3" size="$4"
  local dir="$OUT/$name" dest="$OUT/$name/$(basename "$path")"
  mkdir -p "$dir"
  if [ -f "$dest" ]; then
    local cur; cur=$(stat -c %s "$dest")
    if [ "$cur" = "$size" ]; then echo "[ok] $(basename "$dest") 已存在且大小一致"; return 0; fi
    [ "$CHECK_ONLY" = 1 ] && { echo "[bad] $dest 大小 $cur != $size"; return 1; }
    rm -f "$dest"
  fi
  [ "$CHECK_ONLY" = 1 ] && { echo "[miss] $dest"; return 1; }
  local url="$BASE/$repo/resolve/main/$path"
  local parts="$dir/.parts_$(basename "$path")"
  rm -rf "$parts"; mkdir -p "$parts"
  local n=$(( (size + CHUNK - 1) / CHUNK ))
  echo "[get] $(basename "$path") ${size}B ${n} 片"
  local running=0 i
  for ((i = 0; i < n; i++)); do
    local start=$((i * CHUNK)) end=$((start + CHUNK - 1)) expect
    [ "$end" -ge "$size" ] && end=$((size - 1))
    expect=$((end - start + 1))
    (
      local f="$parts/$(printf '%05d' "$i")"
      for try in 1 2 3 4; do
        curl -sL -m 600 --noproxy '*' -r "$start-$end" -o "$f" "$url"
        [ -f "$f" ] && [ "$(stat -c %s "$f")" = "$expect" ] && exit 0
        sleep $((try * 2))
      done
      echo "[err] chunk $i" >&2; exit 1
    ) &
    running=$((running + 1))
    if [ "$running" -ge "$WORKERS" ]; then wait -n 2>/dev/null || wait; running=$((running - 1)); fi
  done
  wait
  cat "$parts"/* > "$dest.tmp"
  if [ "$(stat -c %s "$dest.tmp")" != "$size" ]; then echo "[bad] 拼接后大小不符"; rm -f "$dest.tmp"; return 1; fi
  mv "$dest.tmp" "$dest"; rm -rf "$parts"
  echo "[ok] $(basename "$dest") ${size}B"
}

echo "$MANIFEST" | while IFS='|' read -r name repo path size; do
  [ -z "${name:-}" ] && continue
  fetch_one "$name" "$repo" "$path" "$size" || echo "[fail] $name/$path"
done
echo "---- 汇总 ----"
find "$OUT" -type f -not -path "*/.parts_*" | sort | while read -r f; do
  printf '%12d  %s\n' "$(stat -c %s "$f")" "${f#$OUT/}"
done
