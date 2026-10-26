# 构建与发布

## 源码结构

`android/` 是 Kotlin Android 应用；`server/roomscan/` 是 Python 服务与重建队列；
`viewer/` 是查看器源码与逻辑测试；`assets/` 是品牌资源；`scripts/` 是开发、构建与验证工具。
APK 内 `assets/viewer/vendor/` 是由 `viewer/vendor.mjs` 生成的离线资源，保留以支持直接构建。

## Windows x64

使用 Python 3.12、Node.js 22 或更高版本，在项目目录中创建隔离环境：

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.lock.txt -r requirements-desktop.txt
.\.venv\Scripts\python.exe -m pip install --no-deps -e .
.\.venv\Scripts\python.exe -m pytest -q
npm.cmd --prefix viewer ci
npm.cmd --prefix viewer test
npm.cmd --prefix viewer run vendor
```

从 [Brush 0.3.0 官方发布页](https://github.com/ArthurBrussee/brush/releases/tag/v0.3.0)
下载 Windows x64 包，校验发布者提供的摘要（如有），解压到 `.tools/brush/`，
使其包含 `brush_app.exe`、`LICENSE` 和 `README.md`。

```powershell
.\scripts\build-portable.ps1
```

输出 `dist/RoomScan-0.6.0-preview-windows-x64.zip`。必须分发整个 ZIP，
其中包含 `RoomScan.exe`、`_internal/`、`.tools/brush/` 和许可证。
服务与重建子进程复用冻结解释器，无需目标机器安装 Python。
PyCOLMAP 及其原生库由首次启动安装器从官方 PyPI 下载并校验，首次启动需要联网。
发布 ZIP 不包含这部分原生库，避免将其 GPL 组件混称为 MIT。

## Android

需要 JDK 17、Gradle 8.9、Android SDK 35 / Build Tools 35.0.0。
设置 `JAVA_HOME` 和 `ANDROID_HOME`，将 Gradle 的 `bin` 加入 PATH：

```powershell
.\scripts\build-android.ps1
```

也可通过 `-ToolchainRoot` 指定包含 `jdk/jdk-17.0.20.1+1`、`sdk/` 和
`gradle/gradle-8.9/` 的本地工具链目录。
调试包输出到 `android/app/build/outputs/apk/debug/app-debug.apk`。

签名发布包通过环境变量读取凭证：

```powershell
$env:ROOMSCAN_KEYSTORE = 'D:\private\roomscan-release.jks'
$env:ROOMSCAN_KEY_ALIAS = 'roomscan'
# 从本机安全存储设置 ROOMSCAN_STORE_PASSWORD 和 ROOMSCAN_KEY_PASSWORD。
.\scripts\build-android.ps1 -BuildType Release
```

输出 `android/app/build/outputs/apk/release/app-release.apk`。
未配置签名环境时 Gradle 生成 unsigned APK，不能作为可安装发布附件。
密钥和密码不得提交；更换签名的 APK 通常需要卸载旧版后安装，请先备份采集数据。

## 发布检查

1. 运行 Python 与查看器测试，重新生成离线资源。
2. 构建便携 ZIP 和签名 APK，运行 `scripts/smoke-portable.py` 验证冻结服务及重建入口。
3. 使用 Android SDK 的 `apksigner verify --verbose --print-certs` 验证 APK。
4. 检查暂存区中没有 `.runtime`、密钥、录制、日志、个人配置或大文件。
5. 创建预发布标签，上传 ZIP、APK、SHA256SUMS.txt 及必要的依赖源码附件。

GitHub 自动提供对应标签的项目源码 ZIP/TAR.GZ。
CI 检查服务和查看器测试及 Android 调试构建；正式发布密钥保留在维护者本机。

更新 Android 依赖声明时，先在 `android/` 使用 Gradle 参数
`-I ../scripts/android-dependencies.init.gradle :app:exportRuntimeArtifacts`，
再在项目根目录执行 `.\.venv\Scripts\python.exe scripts/prepare-release-notices.py`。
它会保留上游 POM、嵌入声明和许可证，并下载验证 Zeroconf 的 LGPL 对应源码附件。
