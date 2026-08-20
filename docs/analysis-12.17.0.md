# X 12.17.0-release.0 静态分析报告（对照 12.3.1-release.0 旧绑定）

> 分析对象：base.apk (12.17.0-release.0, versionCode 312170000, 17 dex / 235310 类)。
> 证据来源：JADX 反编译全量代码 + mt-mcp 原始 dex 方法级定位交叉验证。
> 证据等级标注：VERIFIED_LATEST = 在 12.17.0 中直接验证；INFERRED_CROSS_VERSION = 由两个版本对照推断；
> RUNTIME_SELF_ADAPTING = 由模块运行时机制保证，无需静态保证。

## 一、任务书五个问题的回答

### 1. 当前最新版的 URT 广告条目在进入 UI 前，最稳定的过滤点到底是哪一层？

**数据库 → SharedFlow 的 map 变换层，单一汇聚点。**

12.17.0 中所有时间线（首页 For You / Following / 帖子详情 / 回复、通知、搜索、
成员列表、video tab、paging top/bottom）都由 `DefaultURTTimelineRepository`
（混淆名 `com.x.repositories.urt.j`，@DebugMetadata 保留原名）驱动：

```
GraphQL 拉取 (j.b / fetchFromRemote)
其他仓库写入口 (x0.f0 / x0.i0，52 处调用方)
        │
        ▼
Room 数据库 (com.x.database.core.api.k) ──k.c(timelineKey)──→ DB Flow
        │
        ▼ onEach(h$b: 首屏条目计数)
com.x.repositories.urt.h$c  (Flow 包装) ──collect──→
com.x.repositories.urt.h$c$a.emit(Object /*List<UrtTimelineItem>*/, Continuation)   ★ Hook 点
        │  内部：谓词过滤 j.f → minimum_spacing / brand_safety 广告间距移除
        │  （应用自身的广告移除逻辑，含日志 "Ad removal: N ads removed"）
        ▼ this.a.emit(过滤后的 List)
SharedFlow j.C ──x0.v()──→ 全部 UI 消费者（com.x.home.n / com.x.urt.* / paging / k0 详情包装等 13 处）
```

该 emit 方法与 12.3.1 被Hook的 `com.x.repositories.urt.j$a.emit(Object, Continuation)`
完全同构（同样的 `(Object, Continuation) -> Object` CPS 形状、同样接收 List、同样
位于 DefaultURTTimelineRepository 内部）。区别只是内部类编号从 `$a` 漂移为
`$2$invokeSuspend$$inlined$map$1$2`（jadx 显示为 `h$c$a`）。

方法级原始 dex 验证（mt-mcp）：`Lcom/x/repositories/urt/h$c$a;->emit(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;`
使用字符串 `"Ad removal: "`，且该字符串在全部 17 个 dex 中唯一。

### 2. getPromotedMetadata / getEntryId 等访问器是否仍存在？

**全部存在，而且模型层整体未混淆（kotlinx.serialization 密封多态层级）**：

| 12.3.1 旧种子 | 12.17.0 等价物 | 访问器 |
|---|---|---|
| `com.twitter.model.timeline.o2.k` (timelineItem→tweet) | `UrtTimelinePost` | `getEntryId():String` `getPromotedMetadata():TimelinePromotedMetadata` `getPostResult()` |
| `com.twitter.model.core.e.b` (tweet→promoted) | `com.x.models.TimelinePromotedMetadata` | `getImpressionId()` 等（未混淆） |
| — | `UrtTimelineRtbImageAd` | `getPromotedMetadata()`（RTB 图片广告，独立条目类型） |
| — | `UrtTimelineJetfuel` | Jetfuel 广告条目（注册于多态 serializer） |
| getEventSummary 语义 | `UrtTimelineEventSummary` | `getEventSummary().getPromotedMetadata()` |
| getTimelineTrend 语义 | `UrtTimelineTrend` | `getTimelineTrend().getPromotedMetadata()` |
| getItems/getItem 语义 | `UrtTimelineModule` / `UrtTimelineModuleItem` | `getItems():List` `getItem():UrtTimelineItem` |

接口根：`com.x.models.timelines.items.UrtTimelineItem`
（`getEntryId/getSortIndex/getClientEventInfo`，子类型 15 个，全部带 `$$serializer`）。

**应用自带广告判定器**（未混淆包，单字母类名会漂移）：
`com.x.models.timelines.items.l`：
- `l.b(UrtTimelineItem): TimelinePromotedMetadata` —— Post/EventSummary/Trend/RtbImageAd 四分支
- `l.a(UrtTimelineItem): boolean` —— isAd

结论：旧 AdDetector 的语义 getter 链（getPromotedMetadata/getEventSummary/
getTimelineTrend/getItems/getItem/getEntryId）**原样保留可继续使用**，且比固定
类名更稳；`rtbimagead` 类名启发式现在直接对应未混淆类型名
`UrtTimelineRtbImageAd`。[VERIFIED_LATEST]

### 3. 旧 `j$a.emit(Object, Continuation)` 在最新版变成了什么调用链？有哪些稳定的 DexKit 锚点？

见问题 1 的链路。稳定锚点（均已在 12.17.0 原始 dex 验证）：

- **高熵业务字符串**（emit 方法体内，全 APK 唯一或近唯一）：
  `"Ad removal: "`（唯一）、`" ads removed (spacing="`、`", brand_safety="`、
  `"minimum_spacing"`、`"brand_safety"`、`"minimum_spacing_ad_removal"`、
  `"URTTimelineRepository"`
- **@DebugMetadata 注解**（Kotlin 协程元数据，包含原始未混淆类名字符串）：
  外层 lambda 类 `c="com.x.repositories.urt.DefaultURTTimelineRepository$2"`，
  emit 的续体类 `c="com.x.repositories.urt.DefaultURTTimelineRepository$2$invokeSuspend$$inlined$map$1$2", m="emit"`
- **方法形状**：`emit(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;`
  且声明类实现 `kotlinx.coroutines.flow.h`（FlowCollector）
- **调用关系**：invoke `getEntryId()`、`l.a/l.b`（UrtTimelineItem 广告判定），
  字段访问 `j.f`（Function1 谓词）、`j.a/j.b`（TimelineType/timeline key）

### 4. 旧 badge updater 是否还有存在价值，还是 URT + binder 已能完整覆盖？

**没有存在价值了，且旧结构已死**：
- 12.3.1 的 `com.twitter.app.settings.h1`（discriminator=2 的合成 updater）在
  12.17.0 无对应物（R8 合成壳已随版本消失）。
- badge 显示迁移到 weaver 体系：`com.twitter.tweetview.core.ui.badge.AdBadgeViewDelegateBinder`
  （未混淆），订阅 `TweetViewViewModel.e`（Rx Observable）驱动
  `AdBadgeViewDelegate`（`badge.b`）。badge 只是 promoted 元数据的显示结果，
  不是广告入口。
- 12.3.1 的 timeline binder `com.twitter.timeline.itembinder.b1` 在 12.17.0
  **是空壳**（仅 `extends com.twitter.weaver.adapters.b`，无任何方法）；
  首页 UI 已由 URT 数据管线 + weaver/Compose 渲染，legacy promoted row 折叠
  无处安放也无必要。
- 数据层（问题 1 的 emit 点）在 UI 之前删除全部 promoted 条目，badge/row
  根本不会被创建。[VERIFIED_LATEST]

因此本次改造：**URT 数据过滤是唯一安装的 Hook 层**；binder/badge 不再 Hook
（架构保留扩展点）。2.0.1 起原为未来 UI 层保留的 CollapsedViewRegistry 已删除
（死代码清理，P3-2）。

### 5. 哪些目标必须 runtime witness，哪些能仅靠静态 DexKit 唯一确认？

| 目标 | 静态可否唯一确认 | witness 策略 |
|---|---|---|
| urt_emit（强指纹：双字符串 + CPS 形状 + 接口 override 结构证明） | 可以（字符串唯一性极高） | 仍要求**安装后首次真实调用见证**：arg0 必须为 List 且元素呈 URT entry 形状（有 entryId/或实现 UrtTimelineItem）；连续 3 次形状不符 → **真实 unhook** 并失效该缓存条目（P1-3） |
| urt_emit（弱指纹兜底命中，或多候选且前两名分差 <10） | 不可以 | 临时 probe hook 前 N(≤5) 个候选；**单一弱样本不晋升**（P1-8）：需 ≥2 次非空调用且被检元素形状占比 ≥0.5 才 CONFIRMED；首个 CONFIRMED 者晋升，其余立即 unhook；30s 无见证 → fail-open 不 Hook |
| model.urtItemInterface | 可以（未混淆语义名 + 接口形状） | 免 witness，加载即验证 |
| model.adHelperIsAd | 形状可以（双静态方法形状组），**语义不可以** | 形状验证（缓存与 fresh 同一规则，P0-3）之后仅以**未验证权重（20，低于删除阈值 40）**参与评分；运行时语义见证（真实条目上 helper 判定与 promoted 元数据/entryId 证据相关性，≥12 样本且 ≥1 正例一致、0 矛盾 → 验证，权重升至 45；矛盾 >1 → 禁用并从缓存移除）。验证状态不跨进程持久化 |

## 二、Resolver discovery matrix（P1-1：discovery 与 scoring 对齐）

一个特征只有先被 discovery 找到，scoring 才有机会使用它。下表保证没有
"只能加分但永远不会被发现"的幽灵特征（DiscoveryAlignmentTest 静态校验）。
discovery 按选择度从上到下执行，出现"≥70 分且无歧义（分差≥10）"的领先者即
提前停止（保证 12.17.0 strong 路径冷启动不退化）。

### urt_emit

| # | discovery 入口 | 查询 | 对应 scoring 证据 |
|---|---|---|---|
| 1 | primary string | `usingStrings("Ad removal: ")` + CPS 形状 | strings（+45/+15） |
| 2 | secondary string | `usingStrings(" ads removed (spacing=")` | strings（+45/+15） |
| 3 | spacing metric | `usingStrings("minimum_spacing_ad_removal")` | strings:one（+15） |
| 4 | spacing logic | `usingStrings("minimum_spacing")` | spacingLogic（+10） |
| 5 | brand safety | `usingStrings("brand_safety")` | spacingLogic（+10） |
| 6 | structural | `com.x.repositories` 包内 name=emit + `(Object,Continuation)->Object` | cpsShape/nameSeed/urtPackage/emitOverride |
| 7 | legacy seed | 12.3.1 `com.x.repositories.urt.j$a` 反射 | nameSeed（兼容兜底） |

### 评分框架（实现于 CandidateScoring，2.0.1 修订）

```
+45 命中双高熵业务字符串（"Ad removal: " + " ads removed (spacing="）
+15 单高熵字符串（primary / secondary / minimum_spacing_ad_removal 之一）
+15 (Object, Continuation)->Object CPS 形状
+10 emitOverride：完整类型层级中存在同名 (Object,Continuation)->Object 接口声明
     （TriState.YES；UNKNOWN 不得分、NO 扣分 —— P1-2，不再硬编码 kotlinx.coroutines.flow.h）
+10 spacing/brand-safety 逻辑常量（minimum_spacing / brand_safety）
+10 包名位于 com.x.repositories.urt（仅加分）
+5  方法仍名为 emit（历史种子 token）
-15 emitOverride = NO（层级走完仍无接口 override）
-25 形状冲突（abstract/static、参数/返回类型不符）
```
阈值：≥70 直接接受；50~69 且唯一 → 接受但 NEEDS_RUNTIME_WITNESS；
否则进入 probe witness。Verifier 侧 `emitOverride=NO` 直接 INVALID。

### model.urtItemInterface / model.adHelperIsAd

| target | discovery | scoring | verifier | witness |
|---|---|---|---|---|
| model.urtItemInterface | 精确语义名 `com.x.models.timelines.items.UrtTimelineItem`（kotlinx.serialization 未混淆） | — | interface + getEntryId():String + getSortIndex() | 免（形状即语义） |
| model.adHelperIsAd | DexKit `paramTypes=UrtTimelineItem AND returnType=boolean`，同类须有 `(UrtTimelineItem)->TimelinePromotedMetadata` 伴随 | — | static + 形状 + modelInterface 可用；**缓存与 fresh 同规**（P0-3） | **运行时语义见证**（见问题 5）；未验证权重 20 < 阈值 40，单个错误 boolean 无法独立删除 |

## 三、引导状态机（2.0.1）

```
BOOTSTRAP → ATTACH_WAIT ──(Application.attach, 首次)──→ RESOLVING
                 │                                        │
                 │ 20s bootstrap deadline（仅覆盖 BOOTSTRAP/ATTACH_WAIT/RESOLVING，
                 │ probe 启动即取消 —— P0-2）              ├─ 唯一且 ≥70 分（strong direct）→ 装钩
                 │                                        │   → READY（runtimeSelfCheck=pending，
                 ▼                                        │       首个真实样本通过后落盘 witnessed）
            DEGRADED（fail-open，X 原样可用）              ├─ 歧义/弱 → WAITING_WITNESS（≤5 个只读探针，
                                                          │   30s witness deadline；P0-1：会话保持活跃，
                                                          │   完成仅由 promote/expire/cancel 事件驱动）
                                                          │     ├─ CONFIRMED（≥2 次调用+形状占比≥0.5）→ 晋升装钩 → READY
                                                          │     └─ 超时/全部被拒 → DEGRADED
                                                          └─ 无安全目标 → DEGRADED
```

- 所有状态转换只在单线程 worker 上发生（P1-4）；hook/witness 回调只投递事件。
- READY / DEGRADED 为终态冻结（P1-5）：陈旧定时器、迟到回调、旧会话事件一律忽略；
  唯一允许的终态后迁移是 **READY → DEGRADED 安全降级**（运行时见证解除最后一个钩子时）。
- 事件携带 sessionId；旧会话事件被丢弃。
- READY 语义 = "钩子已安装、引导完成"；内联见证的自校验可在 READY 之后继续
  （runtimeSelfCheck=pending → passed），README/日志与此一致（P3-2）。

## 四、跨版本结论标注

- 模型层未混淆（com.x.models.**）：VERIFIED_LATEST（12.17.0），且此类
  kotlinx.serialization 模型名自 12.x 重构引入后跨小版本保持（INFERRED_CROSS_VERSION）。
- emit 方法内业务字符串：VERIFIED_LATEST；跨版本漂移风险由 7 入口 discovery 梯子
  （5 个业务字符串 + 结构入口 + 历史种子）+ witness + 缓存失效自动重解析吸收
  （RUNTIME_SELF_ADAPTING）。
- 12.3.1 反射种子仅作 fallback 兼容（UNVERIFIED_HISTORICAL for future versions）。

## 五、"小版本通杀"的工程定义

> **普通 X 小版本升级不需要维护逐版本 Hook 表；当业务语义和调用结构仍可识别时，
> 通过运行时 DexKit + Verifier + Witness 自动重新定位目标。遇到大版本或业务架构
> 代际变化时，允许重新分析最新版并更新 Resolver/fingerprint。**

不承诺：永久全版本兼容、未来任意版本零维护、所有历史 X 全部支持。
versionCode 仅用于缓存失效与日志，不参与任何业务分支。
