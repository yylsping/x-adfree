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
（架构保留扩展点），CollapsedViewRegistry 保留供未来 UI 层目标复用。

### 5. 哪些目标必须 runtime witness，哪些能仅靠静态 DexKit 唯一确认？

| 目标 | 静态可否唯一确认 | witness 策略 |
|---|---|---|
| urt_emit（强指纹：双字符串 + CPS 形状 + FlowCollector 接口） | 可以（字符串唯一性极高） | 仍要求**安装后首次真实调用见证**：arg0 必须为 List 且元素呈 URT entry 形状（有 entryId/或实现 UrtTimelineItem）；连续 3 次形状不符 → 自卸载并失效该缓存条目 |
| urt_emit（弱指纹兜底命中，或多候选且前两名分差 <10） | 不可以 | 临时 probe hook 前 N(≤5) 个候选，首个通过真实调用见证者晋升，其余立即 unhook；30s 无见证 → fail-open 不 Hook |
| model.urtItemInterface | 可以（未混淆语义名 + 接口形状） | 免 witness，加载即验证 |
| model.adHelperIsAd | 基本可以（双静态方法形状组 + 未混淆参数/返回类型） | 反射调用一次空值容错验证即可（不传业务对象） |

## 二、指纹矩阵

| target | strong fingerprint | weak fingerprint | runtime witness | fallback | ambiguity rule |
|---|---|---|---|---|---|
| urt_emit | 方法用字符串 `"Ad removal: "` **和** `" ads removed (spacing="`；参数 `(Object, kotlin.coroutines.Continuation)` 返回 Object；声明类实现 `kotlinx.coroutines.flow.h` | 任一字符串单独命中；或 @DebugMetadata 含 `DefaultURTTimelineRepository` + emit 形状；或包名 `com.x.repositories.urt` 加形状 | 首次调用 arg0∈List 且元素有 entryId（或 instanceof UrtTimelineItem）→ 晋升；3 次不符 → 自卸载 | 12.3.1 历史种子反射：`com.x.repositories.urt.j$a.emit(Object,Continuation)`（加载成功即用，仍走 witness） | 强指纹唯一命中 → 直接装 Hook（带 witness 自校验）；≥2 候选或分差 <10 → probe witness；无见证 → fail-open |
| model.urtItemInterface | 类名 `com.x.models.timelines.items.UrtTimelineItem`（未混淆语义名）+ 是接口 + 有 `getEntryId()String/getSortIndex()J` | 仅类名 + 是接口 | 不需要 | witness 改用 entryId 形状检查（不依赖该类） | 唯一命中才持久化 |
| model.adHelperIsAd | 静态方法参数 `UrtTimelineItem` 返回 boolean，同类存在参数相同返回 `TimelinePromotedMetadata` 的伴随方法 | 包名 `com.x.models.timelines.items`；方法名历史种子 `a`/`b` | 不需要（null 入参不抛错验证） | AdDetector 语义 getter 链（永不失效，不依赖本 target） | 形状对不唯一即放弃，仅作加分证据 |

### 评分框架（实现于 CandidateScoring）

```
+45 命中双高熵业务字符串（"Ad removal: " + spacing 字符串）
+15 单高熵字符串
+15 (Object, Continuation)->Object CPS 形状
+10 声明类实现 FlowCollector
+10 调用 getEntryId / 广告判定静态方法
+10 包名位于 com.x.repositories.urt（仅加分）
+5  历史类名 token 相似（j$a → h$c$a 同包 lambda 家族）
-25 形状冲突（abstract/static/bridge、参数不是 Object+Continuation）
```
阈值：≥70 直接接受；50~69 且唯一 → 接受但 NEEDS_RUNTIME_WITNESS；
否则进入 probe witness。

## 三、跨版本结论标注

- 模型层未混淆（com.x.models.**）：VERIFIED_LATEST（12.17.0），且此类
  kotlinx.serialization 模型名自 12.x 重构引入后跨小版本保持（INFERRED_CROSS_VERSION）。
- emit 方法内业务字符串：VERIFIED_LATEST；跨版本漂移风险由强/弱/回退三层 +
  witness + 缓存失效自动重解析吸收（RUNTIME_SELF_ADAPTING）。
- 12.3.1 反射种子仅作 fallback 兼容（UNVERIFIED_HISTORICAL for future versions）。
