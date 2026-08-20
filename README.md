# X AdFree

[English](README_EN.md)

面向 X Android 客户端的 LSPosed 去广告模块，基于 libxposed Modern API 102 与 DexKit 运行时动态解析。

## 功能

- 去除首页时间线（For You / Following）广告。
- 去除帖子详情页和评论区广告。
- 通过目标数据模型（URT promoted metadata、entryId 前缀、应用自身广告判定）三态识别广告；不确定的内容一律放行，不会误删正常帖子。
- 在 URT 数据层单一汇聚点过滤，所有消费时间线的界面同时受益，不依赖具体界面布局。
- 运行时用 DexKit 按字符串指纹、方法签名、类型形状多特征定位目标方法，按目标应用安装身份缓存解析结果；下一次启动直接命中缓存，无需重新解析。
- 内置运行时见证（witness）校验：钩子安装后首次真实调用必须符合 URT 数据形状，否则自动卸载自己并使缓存失效，杜绝误钩非目标方法。
- 不修改 X APK，不拦截网络请求，也不包含后台服务、轮询、WakeLock 或 Frida 代码。唯一打包的 native 库是 DexKit 自带的 `libdexkit.so`，仅在解析阶段使用。

## 兼容性

| 项目 | 要求 |
| --- | --- |
| 目标应用 | X 12.x 系列；已在 12.3.1 与 12.17.0-release.0 上验证。不再绑定单一版本，新小版本由运行时动态适配 |
| Android | 9.0（API 28）及以上 |
| 框架 | 支持 libxposed Modern API 102 的官方 LSPosed |
| 模块版本 | 2.0.0（versionCode 19） |

模块按“特征指纹 + 形状校验 + 运行时见证”解析目标，而非硬编码类名表。若 X 未来重构导致指纹失效，模块会以失效安全（fail-open）方式放弃过滤并保留 X 正常可用，等待版本更新。

X 11.82.0 之后包含 `libpairipcore.so`。使用本模块前，需要在官方 LSPosed 的“设置 → 还原内联钩子”中勾选 X（`com.twitter.android`），否则 X 可能在冷启动时终止进程。

## 工作原理（简述）

1. X 主进程启动时，模块记录安装身份（包名、APK 及 split 尺寸、签名证书哈希），形成稳定 token。
2. 首次运行：DexKit 按“强/弱/名称/兜底”四级指纹解析 URT emit 钩子点、时间线模型接口与应用广告判定方法，候选按正交特征打分，分数不足时先以只读探针观察真实调用再晋升。
3. 解析结果按身份写入缓存（原子写入、上限 5 个目标、LRU 淘汰）；此后每次启动先验证缓存目标仍可加载、形状正确，才复用。
4. 钩子安装后由运行时见证校验首个真实数据形状；广告判定（promoted metadata / entryId 前缀 / 应用自身判定）三态评分，仅移除确定性广告。

详细分析见 [docs/analysis-12.17.0.md](docs/analysis-12.17.0.md)。

## 安装

1. 从 GitHub Releases 下载并安装 APK。
2. 在 LSPosed 的“还原内联钩子”设置中确认已勾选 X。
3. 在 LSPosed 中启用 X AdFree；静态作用域只包含 X。
4. 强制停止 X 后重新打开。首次启动会进行一次 DexKit 解析（约 1 秒），之后启动直接命中缓存（几十毫秒内生效）。

模块没有设置页面，开关、作用域和“还原内联钩子”均通过 LSPosed GUI 管理。

## 构建

需要 JDK 17、Android SDK 35，并可从 Maven Central 获取 `io.github.libxposed:api:102.0.0` 与 `org.luckypray:dexkit:2.0.6`。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

测试 APK 输出到 `app/build/outputs/apk/debug/`。面向普通用户的已签名版本请从 GitHub Releases 下载。

## 许可证

本项目采用 [MIT License](LICENSE)。

## 免责声明

本项目仅供学习、研究和个人设备使用，与 X Corp.、Twitter 或 LSPosed 项目无隶属或认可关系。使用前请确认符合当地法律及相关服务条款。
