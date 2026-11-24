#!/system/bin/sh
# MagPlay 迅雷存量视频迁移脚本（设备端复制，绝不删除源文件）
# 用法: sh migrate_xunlei.sh [dry|run|verify]

SRC="/sdcard/Android/data/com.xunlei.downloadprovider/files/ThunderDownload"
DST="/sdcard/Download/ThunderDownload"
LOG="$DST/_migration.log"
LIST="/data/local/tmp/magplay_video_list.txt"
MODE="${1:-run}"

if [ "$MODE" != "dry" ] && [ "$MODE" != "run" ] && [ "$MODE" != "verify" ]; then
  echo "usage: sh migrate_xunlei.sh [dry|run|verify]"
  exit 1
fi

if [ ! -d "$SRC" ]; then
  echo "SOURCE_MISSING $SRC"
  exit 1
fi
mkdir -p "$DST"

total=0
ok=0
skip=0
fail=0
mismatch=0

find "$SRC" -type f \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.rmvb' \) > "$LIST"

while IFS= read -r f; do
  [ -z "$f" ] && continue
  rel="${f#$SRC/}"
  target="$DST/$rel"
  size=$(stat -c %s "$f" 2>/dev/null)
  total=$((total + 1))

  if [ "$MODE" = "dry" ]; then
    echo "PLAN $rel $size"
    continue
  fi

  if [ "$MODE" = "verify" ]; then
    if [ -f "$target" ]; then
      tsize=$(stat -c %s "$target" 2>/dev/null)
      if [ "$tsize" = "$size" ]; then
        echo "MATCH $rel"
      else
        mismatch=$((mismatch + 1))
        echo "MISMATCH $rel src=$size dst=$tsize"
      fi
    else
      mismatch=$((mismatch + 1))
      echo "MISMATCH $rel missing"
    fi
    continue
  fi

  # run 模式：目标已存在且大小一致则跳过（断点续传）
  if [ -f "$target" ]; then
    tsize=$(stat -c %s "$target" 2>/dev/null)
    if [ "$tsize" = "$size" ]; then
      skip=$((skip + 1))
      echo "SKIP $rel"
      continue
    fi
  fi

  mkdir -p "$(dirname "$target")"
  if cp -p "$f" "$target" 2>/dev/null; then
    tsize=$(stat -c %s "$target" 2>/dev/null)
    if [ "$tsize" = "$size" ]; then
      ok=$((ok + 1))
      echo "OK $rel" >> "$LOG"
      echo "OK $rel"
    else
      fail=$((fail + 1))
      echo "FAIL $rel size=$size dst=$tsize" >> "$LOG"
      echo "FAIL $rel size=$size dst=$tsize"
    fi
  else
    fail=$((fail + 1))
    echo "FAIL $rel cp_error" >> "$LOG"
    echo "FAIL $rel cp_error"
  fi
done < "$LIST"

rm -f "$LIST"
echo "SUMMARY mode=$MODE total=$total ok=$ok skip=$skip fail=$fail mismatch=$mismatch"
