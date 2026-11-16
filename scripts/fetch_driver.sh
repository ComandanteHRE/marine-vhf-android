#!/usr/bin/env bash
# Кладёт APK «Rtl-sdr driver» (marto.rtl_tcp_andro) в assets, чтобы он ставился вместе с приложением.
# Источник — индекс F-Droid; контрольная сумма проверяется. Если не вышло — сборка идёт без вшитого
# драйвера, и приложение само загрузит его с F-Droid при первом запуске.
set -u
PKG=marto.rtl_tcp_andro
DST="$(dirname "$0")/../app/src/main/assets/rtl_tcp_andro.apk"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if curl -fsSL --retry 3 -o "$TMP/index.json" https://f-droid.org/repo/index-v1.json \
   && read -r NAME HASH < <(jq -r --arg p "$PKG" '.packages[$p][0] | "\(.apkName) \(.hash)"' "$TMP/index.json") \
   && [ -n "${NAME:-}" ] && [ "$NAME" != "null" ] \
   && curl -fsSL --retry 3 -o "$TMP/d.apk" "https://f-droid.org/repo/$NAME" \
   && [ "$(sha256sum "$TMP/d.apk" | cut -d' ' -f1)" = "$HASH" ]; then
    mkdir -p "$(dirname "$DST")"
    cp "$TMP/d.apk" "$DST"
    echo "Драйвер вшит: $NAME (sha256 $HASH)"
else
    echo "::warning::Не удалось получить драйвер с F-Droid — он будет загружаться приложением при первом запуске"
fi
exit 0
