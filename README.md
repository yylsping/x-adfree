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
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

产物位于 `app/build/outputs/apk/`。正式发布前应使用独立、妥善保管的发布密钥生成并签名 release APK。

## 相关项目

- [bili hook](https://github.com/yylsping/bili-hook)：面向哔哩哔哩 7.4.0 的画质解锁与去广告模块。
- [酷安净化](https://github.com/yylsping/coolapk-purifier)：面向酷安的去广告模块。

## 许可证

本项目采用 [MIT License](LICENSE)。

## 免责声明

本项目仅供学习、研究和个人设备使用，与 X Corp.、Twitter 或 LSPosed 项目无隶属或认可关系。使用前请确认符合当地法律及相关服务条款。
