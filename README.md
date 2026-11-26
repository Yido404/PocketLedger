# 随手账本

随手账本在 Android 手机上识别支付宝和微信付款，询问是否记入账本。确认后可查看月度支出，也可以手动补记或导入个人账单。

## 安装

从 [GitHub Releases](../../releases) 下载签名 APK。应用支持 Android 9 及以上，微信画面识别支持 Android 14 及以上。

在应用设置里按需开启「付款页面识别」和「付款通知识别」。可以开启「后台保持运行」，在系统通知里查看识别状态。账本保存在本机。

## 构建

在 Windows 上安装 JDK、Android SDK Platform 35 和 Android SDK Build-Tools 35.0.0。在项目目录运行下面的命令，将路径替换为本机 SDK 的安装位置。

```powershell
.\build.ps1 -AndroidJar "C:\Android\Sdk\platforms\android-35\android.jar" -BuildTools "C:\Android\Sdk\build-tools\35.0.0"
```

输出 APK 位于 `deliverables/PocketLedger.apk`。脚本会在 `.tools` 下准备识别依赖并生成本地签名密钥。要覆盖安装现有版本，需要使用与已安装 APK 相同的签名。
