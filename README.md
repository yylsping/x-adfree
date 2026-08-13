# X AdFree

[English](README_EN.md)

面向 X Android 客户端的 LSPosed 去广告模块，基于 libxposed Modern API 102。

## 功能

- 去除首页时间线广告。
- 去除帖子详情页和评论区广告。
- 通过目标数据模型识别并去除广告。
- 不修改 X APK，不拦截网络请求，也不包含后台服务、轮询、WakeLock、native library 或 Frida 代码。

## 兼容性

| 项目 | 要求 |
| --- | --- |
| 目标应用 | X 12.3.1-release.0（versionCode 312031000） |
| Android | 9.0（API 28）及以上 |
| 框架 | 支持 libxposed Modern API 102 的官方 LSPosed |
| 模块版本 | 1.5.0（versionCode 17） |

模块依赖 X 的内部类名、方法签名和资源 ID，不保证兼容其他版本。

X 11.82.0 之后包含 `libpairipcore.so`。使用本模块前，需要在官方 LSPosed 的“设置 → 还原内联钩子”中勾选 X（`com.twitter.android`），否则 X 可能在冷启动时终止进程。

## 安装

1. 从 GitHub Releases 下载并安装 APK。
2. 在 LSPosed 的“还原内联钩子”设置中确认已勾选 X。
3. 在 LSPosed 中启用 X AdFree；静态作用域只包含 X。
4. 强制停止 X 后重新打开。

模块没有设置页面，开关、作用域和“还原内联钩子”均通过 LSPosed GUI 管理。

## 构建

需要 JDK 17、Android SDK 35，并可从 Maven Central 获取 `io.github.libxposed:api:102.0.0`。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

维护者本地的正式构建使用本项目专属发布密钥：

- 密钥：`signing-private/release.p12`
- 凭据：`signing-private/signing.properties`

这两个文件均已被 `.gitignore` 排除，不会提交到 GitHub。存在本地签名配置时，`assembleRelease` 会直接生成正式签名 APK；没有私钥的仓库克隆仍可使用 `assembleDebug` 生成测试包。

发布密钥决定 Android 能否覆盖升级。请加密备份整个 `signing-private/` 目录，切勿删除、重新生成或提交其中内容。由于本项目此前的 APK 使用测试签名，首次切换到该发布密钥时需要先卸载旧版；此后的正式版本可以直接覆盖升级。

## 许可证

本项目采用 [MIT License](LICENSE)。

## 免责声明

本项目仅供学习、研究和个人设备使用，与 X Corp.、Twitter 或 LSPosed 项目无隶属或认可关系。使用前请确认符合当地法律及相关服务条款。
