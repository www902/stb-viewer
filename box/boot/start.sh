#!/system/bin/sh
# STB Viewer boot service
DEX=/system/stbviewer/stbhttp.dex
test -f "$DEX" || DEX=/data/local/tmp/stbhttp.dex
pkill -9 -f '[S]tbHttp' 2>/dev/null
sleep 25
while true; do
  CLASSPATH=$DEX app_process / StbHttp
  sleep 5
done
