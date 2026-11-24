#!/system/bin/sh
# MagPlay 迅雷视频搬迁（同盘 mv = 元数据重命名，秒级、不占额外空间）
# 只搬视频文件，保留子目录结构；绝不删除非视频文件；幂等（目标已存在且大小一致则跳过）
SRC="/sdcard/Android/data/com.xunlei.downloadprovider/files/ThunderDownload"
DST="/sdcard/Download/ThunderDownload"
MODE="${1:-run}"

[ -d "$SRC" ] || { echo "SOURCE_MISSING"; exit 1; }
mkdir -p "$DST"

LIST=/data/local/tmp/magplay_mv_list.txt
find "$SRC" -type f \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.rmvb' \
  -o -iname '*.avi' -o -iname '*.wmv' -o -iname '*.flv' -o -iname '*.ts' \
  -o -iname '*.mov' -o -iname '*.m4v' \) > "$LIST" 2>/dev/null

total=0; ok=0; skip=0; fail=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  rel="${f#$SRC/}"
  target="$DST/$rel"
  srcsize=$(stat -c %s "$f" 2>/dev/null)
  total=$((total + 1))

  if [ "$MODE" = "dry" ]; then
    echo "PLAN $rel $srcsize"
    continue
  fi

  if [ -f "$target" ]; then
    dsize=$(stat -c %s "$target" 2>/dev/null)
    if [ "$dsize" = "$srcsize" ]; then
      skip=$((skip + 1)); continue
    fi
  fi

  mkdir -p "$(dirname "$target")"
  if mv "$f" "$target" 2>/dev/null; then
    dsize=$(stat -c %s "$target" 2>/dev/null)
    if [ "$dsize" = "$srcsize" ]; then
      ok=$((ok + 1))
    else
      fail=$((fail + 1)); echo "FAIL size idx=$total src=$srcsize dst=$dsize"
    fi
  else
    fail=$((fail + 1)); echo "FAIL mv idx=$total"
  fi
done < "$LIST"

rm -f "$LIST"
echo "SUMMARY mode=$MODE total=$total moved=$ok skip=$skip fail=$fail"
