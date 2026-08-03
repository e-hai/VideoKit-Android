#!/usr/bin/env bash
# 探测 SampleMediaCatalog 中全部测试片源是否可 Range 拉取真实媒体。
# 用法：./scripts/check_sample_media.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CATALOG="$ROOT/sample/src/main/java/com/kit/video/sample/media/SampleMediaCatalog.kt"

if [[ ! -f "$CATALOG" ]]; then
  echo "Catalog not found: $CATALOG" >&2
  exit 1
fi

URLS=$(grep -Eo 'https://[^"]+' "$CATALOG" | sort -u)
COUNT=$(printf '%s\n' "$URLS" | grep -c . || true)

fail=0
ok=0
echo "Probing $COUNT unique URLs from SampleMediaCatalog..."
echo

printf '%s\n' "$URLS" | while IFS= read -r u; do
  [[ -z "$u" ]] && continue
  tmp="$(mktemp)"
  code=$(curl -sS -o "$tmp" -w "%{http_code}" -L --max-time 25 -A "Mozilla/5.0" \
    -H "Range: bytes=0-2047" "$u" || echo ERR)
  mime=$(file -b --mime-type "$tmp" 2>/dev/null || echo unknown)
  rm -f "$tmp"

  if [[ "$code" =~ ^(200|206)$ ]] && [[ "$mime" == video/* || "$mime" == application/octet-stream ]]; then
    printf "OK   %-4s %-28s %s\n" "$code" "$mime" "$u"
  else
    printf "FAIL %-4s %-28s %s\n" "$code" "$mime" "$u"
    # mark failure via temp flag file because while is subshell
    echo fail >>"${TMPDIR:-/tmp}/videokit_media_probe_fail.$$"
  fi
done

FAIL_FILE="${TMPDIR:-/tmp}/videokit_media_probe_fail.$$"
if [[ -f "$FAIL_FILE" ]]; then
  fail=$(wc -l <"$FAIL_FILE" | tr -d ' ')
  rm -f "$FAIL_FILE"
  echo
  echo "FAIL=$fail"
  exit 1
fi

echo
echo "All URLs OK"
