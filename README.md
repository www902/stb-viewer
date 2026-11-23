# STB Viewer — 机顶盒局域网观看 / 遥控

在安卓机顶盒(实测:创维 E900V20C,Android 9,Goke kunlun)上运行一个纯 Java 的
HTTPS 服务:手机/电脑浏览器直接访问 `https://<盒子IP>:8443`,即可实时观看盒子画面
(WebCodecs 解码 H.264)、收听盒子声音,并用网页虚拟遥控器操作盒子。
零安装、无需 root 应用、服务随盒子开机自启。

## 目录结构

```
box/src/            盒子端服务源码(StbHttp.java + LocalSocket/AudioRecord 桩类)
box/boot/           开机自启脚本 start.sh(服务块需写入盒子 /init.rc)
box/certs/          HTTPS 自签证书(私钥已 gitignore)
box/out/stbhttp.dex 编译产物,可直接推盒子使用
public/index.html   浏览器播放页(WebCodecs + Web Audio + 虚拟遥控)
audio/src/          盒子端音频采集(android.media 桩类)
server.js           PC 版(经 adb 中转的旧方案,备用)
boxservice.bat      PC 端体检/修复脚本(安装/升级/文件丢失时用)
```

## 快速使用(盒子已装好服务的场景)

1. 盒子开机联网,等待 1~2 分钟;
2. 同一局域网的设备浏览器打开 `https://<盒子IP>:8443`
   (自签证书,首次需"高级 → 继续前往");
3. 点一下画面,声音自动开启。

## 全新盒子安装

```bat
boxservice.bat
```

脚本会:adb 连接盒子 → 把 `stbhttp.dex / 证书 / 网页 / scrcpy-server`
推到 `/system/stbviewer/` → 在盒子 `/init.rc` 追加开机服务块 → 完成。
之后服务由盒子 init 自动拉起(注意:该固件要求服务声明 `seclabel`)。

## 编译

```bat
javac --release 8 -encoding UTF-8 -d box\out ^
  box\src\StbHttp.java box\src\android\net\LocalSocket.java box\src\android\net\LocalSocketAddress.java ^
  audio\src\android\media\AudioFormat.java audio\src\android\media\AudioRecord.java audio\src\android\media\MediaRecorder.java
java -jar <build-tools>\dx.jar --dex --min-sdk-version=21 --output=box\out\stbhttp.dex box\out
```

(不能用的特性:lambda、invokedynamic 相关语法 —— dx 只吃 Java 8 字节码的匿名内部类。)

## 证书重新生成

```bat
keytool -genkeypair -keystore box\certs\stb.jks -alias stb -keyalg RSA -keysize 2048 ^
  -validity 10000 -storepass stbpass -keypass stbpass -dname "CN=stb"
keytool -certopt no_subject_no_validity no_extensions -rfc -alias stb ^
  -keystore box\certs\stb.jks -storepass stbpass > box\certs\stb-cert.der
keytool -importkeystore -srckeystore box\certs\stb.jks -srcstorepass stbpass ^
  -destkeystore certs\stb.pfx -deststoretype PKCS12 ...
```

## 已知坑

- 该固件的 init 只解析显式 import 的 rc 文件,且**服务必须带 `seclabel`** 才会登记;
- 向 `/init.rc` 追加内容时必须带前导换行(原文件末行无换行符);
- 盒子固件会清理 `/data/local/tmp` 下的大文件,故全部文件放在 `/system/stbviewer/`;
- 浏览器 WebCodecs 需要 HTTPS(secure context);
- Android 浏览器"自动播放门禁":首次触摸画面才允许出声(页面已自动处理)。
