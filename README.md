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
| 目标应用 | X 12.x 系列；已在 12.3.1 与 12.17.0-release.0 上验证。不绑定单一版本，普通小版本由运行时动态适配 |
| Android | 9.0（API 28）及以上 |
| 框架 | 支持 libxposed Modern API 102 的官方 LSPosed |
| 模块版本 | 2.0.2（versionCode 21） |

模块按"特征指纹 + 形状校验 + 运行时见证"解析目标，而非硬编码类名表。**小版本通杀的工程定义**：普通 X 小版本升级不需要维护逐版本 Hook 表；当业务语义和调用结构仍可识别时，通过运行时 DexKit + Verifier + Witness 自动重新定位目标；遇到大版本或业务架构代际变化时，允许重新分析最新版并更新 Resolver/指纹。不承诺对未来任意版本零维护。若 X 未来重构导致指纹全部失效，模块会以失效安全（fail-open）方式放弃过滤并保留 X 正常可用。

X 11.82.0 之后包含 `libpairipcore.so`。使用本模块前，需要在官方 LSPosed 的“设置 → 还原内联钩子”中勾选 X（`com.twitter.android`），否则 X 可能在冷启动时终止进程。

## 工作原理（简述）

1. X 主进程启动时，模块记录安装身份（包名、APK 及 split 尺寸、签名证书哈希、versionCode），形成稳定 token；versionCode 只用于缓存失效，不参与业务分支。
2. 首次运行：DexKit 按 7 个 discovery 入口（5 个高熵业务字符串、结构入口、历史种子）定位 URT emit 钩子点，候选按正交特征打分；分数不足或存在歧义时，最多 5 个候选先挂只读探针，观察到 ≥2 次真实调用且元素形状占比达标才晋升。时间线模型接口与应用广告判定方法并行解析。
3. 应用自身的广告判定（boolean helper）在运行时语义见证通过之前只以低于删除阈值的权重参与评分——单个被错误解析的 helper 不可能独立删除正常内容；见证发现矛盾时立即禁用该 helper。
4. 解析结果按身份写入缓存（原子替换、LRU 上限）；此后每次启动先逐目标验证缓存仍可加载且形状正确，才复用（缓存与全新解析使用同一套验证规则）。
5. 钩子安装后由运行时见证校验首个真实数据形状，不符时真实卸载钩子并使该缓存条目失效；广告判定三态评分，仅移除确定性广告，不确定内容一律放行。过滤只替换 ArrayList 输出，未知 List 实现直接放行（fail-open）。
6. 引导状态机单线程串行化，READY/DEGRADED 终态冻结；20 秒引导看门狗只覆盖解析阶段，探针观察期有独立的 30 秒期限，互不干扰。

详细分析（指纹矩阵、状态机、discovery 矩阵）见 [docs/analysis-12.17.0.md](docs/analysis-12.17.0.md)。

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
