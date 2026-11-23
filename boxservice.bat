@echo off
rem ================= STB Viewer 盒子版 体检/修复工具 =================
rem 服务现在随盒子开机自动启动(由盒子 init 直接拉起),
rem 平时不需要运行本脚本;只有文件丢失、升级 dex/网页或迁移新盒子时才用。
set BOX=192.168.1.3:5555
cd /d "%~dp0"

adb connect %BOX%
set SVC=
for /f %%s in ('adb -s %BOX% shell getprop init.svc.stbhttp 2^>nul') do set SVC=%%s
if /i "%SVC%"=="running" (
    echo [OK] 服务正在盒子上自动运行: https://192.168.1.3:8443
    goto :eof
)

echo [..] 服务未运行,开始检查并修复...
adb -s %BOX% shell "mount -o remount,rw /; mkdir -p /system/stbviewer"
adb -s %BOX% push box\out\stbhttp.dex                /system/stbviewer/stbhttp.dex
adb -s %BOX% push box\certs\stb-key.der              /system/stbviewer/stb-key.der
adb -s %BOX% push box\certs\stb-cert.der             /system/stbviewer/stb-cert.der
adb -s %BOX% push public\index.html                  /system/stbviewer/index.html
adb -s %BOX% push "C:\ProgramData\chocolatey\lib\scrcpy\tools\scrcpy-server" /system/stbviewer/scrcpy-server.jar
adb -s %BOX% push box\boot\start.sh                  /system/stbviewer/start.sh
adb -s %BOX% shell "chmod 755 /system/stbviewer/start.sh"

rem 确保 /init.rc 里有自启动块(没有就补上,并先备份)
adb -s %BOX% shell "grep -q '^service stbhttp' /init.rc || (test -f /system/stbviewer/init.rc.bak || cp /init.rc /system/stbviewer/init.rc.bak; printf '\nservice stbhttp /system/bin/sh /system/stbviewer/start.sh\n    class main\n    user root\n    group root\n    seclabel u:r:shell:s0\n' >> /init.rc)"

echo [OK] 修复完成。重启盒子后即可开机自启;
echo      也可不重启、立即手动拉起一次(下次开机仍走自启):
echo      adb -s %BOX% shell "nohup sh /system/stbviewer/start.sh ^>/dev/null 2^>^&1 ^&"
pause
