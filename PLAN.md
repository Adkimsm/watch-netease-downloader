# 网易云音乐同步 — OPPO Watch 3 App 实施计划

> 本文件是项目的唯一计划源(single source of truth)。整个项目跨多个会话完成,
> 每次新会话先读本文件 + `git log` 了解进度,从下一个未完成 Phase 继续。
> 完成一个 Phase 后更新本文件的勾选状态。

## 0. 项目身份

| 项 | 值 |
|---|---|
| 应用名(显示) | 网易云音乐同步 |
| applicationId | `io.github.adkimsm.neteasedownloader` |
| 仓库 | `git@github.com:Adkimsm/watch-netease-downloader.git` |
| 工作目录(宿主机) | `~/code/watchmusic` → 容器内 `/workspace/watchmusic` |
| 目标设备 | OPPO Watch 3(国行,ColorOS Watch 2.1,类 Android 11 基线,1.75" 372×194 屏) |
| 安装方式 | `adb-docker install -r` 侧载普通 APK(非 Wear OS) |

## 1. 核心决策(已与用户确认,不再变动)

1. **纯本地零依赖**:App 内置 weapi/eapi 加密(Kotlin 实现),直接请求 `music.163.com`。
   不部署任何外部服务器(用户明确否决了 VPS 代理方案),不依赖 GMS。
2. **不做播放**:只负责同步,文件写入公共目录 `Music/WatchMusic/`(走 MediaStore,
   免存储权限),播放交给系统/第三方播放器。
3. **不做定时同步**:仅手动点「立即同步」触发。
4. **删除策略**:直接删文件 + 删 DB 行(严格差量),并清理孤儿 MediaStore 条目。
5. **音质**:设置项可选 standard(默认)/ higher / exhigh / lossless;非 VIP 歌曲
   自动降级或标记跳过;选高挡位时给出存储空间预警。
6. **不限网络**:不做 WiFi-only 限制,用户自行决定何时同步。
7. **大歌单**:用户歌单 3742 首,必须支持(见 §5)。

## 2. 技术栈

- Kotlin + Coroutines/Flow
- OkHttp(网络)
- kotlinx.serialization(含流式 JSON 解析,应对大歌单响应)
- Room(本地索引库)
- Jetpack Compose(UI,4 屏,适配 372×194 小屏;`LazyColumn(keyed)` 应对长列表)
- zxing(本地生成登录二维码)
- Coil(歌单封面缩略图)
- DI:手写 ServiceLocator,**不上 Hilt**
- 构建:严格走容器(`gradle-docker`),装机走 `adb-docker`,Git 操作在宿主机

## 3. 工程结构(单 module)

```
app/src/main/java/io/github/adkimsm/neteasedownloader/
├── crypto/      Weapi.kt, Eapi.kt         # weapi(AES-CBC + RSA)、eapi(AES-ECB)
├── net/         NcmApi.kt, Dtos.kt        # 端点定义 + 响应模型
├── data/        AppDatabase, entities, dao, MediaStoreWriter
├── sync/        SyncEngine, Diff, Downloader
├── ui/          4 屏 + ViewModel + 主题
└── service/     SyncForegroundService
```

res:Compose 布局、mipmap 图标、strings.xml。

## 4. 接口清单(全部直连 music.163.com)

| 功能 | 端点 | body/备注 |
|---|---|---|
| 取登录 key | `POST /weapi/login/qrcode/unikey` | `{type:1}` |
| 二维码内容 | `https://music.163.com/login?codekey=<key>` | 本地 zxing 渲染成位图 |
| 轮询登录 | `POST /weapi/login/qrcode/client/login` | 800 过期 / 801 待扫 / 802 已扫待确认 / 803 成功(得 MUSIC_U cookie) |
| 我的歌单 | `POST /api/user/playlist?uid=<uid>` | weapi,带 cookie |
| 歌单详情 | `POST /api/v6/playlist/detail` | `{id, n:100000, s:8}`(见 §5) |
| 歌曲下载地址 | `POST /api/song/enhance/player/url/v1` | eapi,`{ids:[], level:"standard"}`;带 `freeTrialInfo` 的记 MISSING_URL |

Cookie 只持久化 MUSIC_U(DataStore);接口返回未登录 → 引导重新扫码。

## 5. 大歌单专项(3742 首)

**拉取全量曲目(双保险)**
1. 主方案:`POST /api/v6/playlist/detail {id, n:100000, s:8}` —— 与 NeteaseCloudMusicApi
   的 `playlist/track/all` 同法,服务端一次性返回全部曲目(旧 `playlist/detail` 的
   1000 上限不适用于 v6 的 n 参数)。
2. 兜底:v6 响应的 `playlist.trackIds` 始终包含全部歌曲 ID;若主方案异常,改用
   「取 trackIds + 分批 `POST /api/v3/song/detail`」(每批 500,约 8 个请求)。

**手表端工程处理**
- 响应 JSON 可达十几 MB:OkHttp 流式读取 + kotlinx.serialization 流式解析,
  边解析边入库(每 200 首一个 Room 事务),解析进度显示在 UI;中断重试幂等
  (INSERT_OR_REPLACE)。
- 下载地址批量取:每批 50 个 id 调 `song/url/v1`,失败指数退避重试 2 次。

**量级本身的挑战与对策**
- 存储:128k 标准音质每首约 3.8MB → 3742 首 ≈ 14GB;手表 32GB,系统占用后剩余
  约 20GB。**同步前存储预检**:实时估算「将占用约 X GB,剩余 Y GB」,不足直接拦截
  并提示降档音质。
- 时间:首轮同步可能 2~4 小时 → **断点续传**(每首歌状态独立入库,杀进程/息屏/
  手动停止后从断点继续);前台通知显示总进度、当前曲目、ETA。
- 流量:不限网络(用户决定),但通知中显示本次同步量级,心里有数。

## 6. 数据模型(Room)

- `playlist(id, name, cover, trackCount, enabled, lastSyncAt)` — enabled 表示纳入同步
- `song(songId, name, artist, album, duration, md5, size, br, state, errorCode, updatedAt)`
  — state: `DOWNLOADING / OK / FAILED / MISSING_URL`
- `playlist_song(playlistId, songId, sortIndex)`

## 7. 同步引擎流程

1. 拉取所有 `enabled` 歌单的全量曲目(分页流式入库)
2. **diff**:远端集合 − 本地 = 待下载;本地 − 远端 = 待删除候选
3. 删除时按 songId **全局引用计数**:多歌单共用的歌曲,只在最后一个关联歌单移除时
   才删文件;删 MediaStore 行 + 文件 + DB 行;额外扫描应用创建的孤儿 MediaStore 条目
4. **下载**:前台服务 + 协程顺序下载,流式写 `ContentResolver.openOutputStream`
   (MediaStore insert),校验 size/md5,失败重试 2 次,仍失败置 FAILED
5. 进度通过 Flow + 前台通知实时反馈,ETA 基于已下载耗时滚动估算

## 8. UI 四屏(Compose)

1. **登录**:二维码大图 + 状态文案(等待扫码 / 已扫码请在手机确认 / 成功),
   过期可点刷新重新轮询
2. **歌单列表**:封面缩略图 + 名称 + 曲数 + 勾选框(多选),顶栏「立即同步」、
   「设置」(音质档位、退出登录)
3. **同步预览**:待新增 X 首 / 待删除 Y 首 / 跳过 Z 首(分组可展开列表)+
   存储占用估算,确认后执行
4. **同步进度**:总进度条 + 当前曲目 + ETA + 可暂停/停止(停止后再点是断点续传)

## 9. 权限清单

`INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、
`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`。
**不要**任何存储权限(MediaStore 自建行免权限)。

## 10. 构建与验证流程(严格遵守 AGENTS.md)

- 语法验证:`gradle-docker :app:compileDebugKotlin`
- 完整构建:`gradle-docker :app:assembleDebug`
- 装机:`adb-docker install -r`(先 `adb-docker devices`)
- 设备基线确认:`adb-docker shell getprop ro.build.version.sdk`、`wm size`、
  `adb-docker logcat -d` 抓日志
- 上表测试清单:
  - [ ] 二维码在 1.75" 屏幕上可被手机网易云 App 扫描(尺寸/对比度)
  - [ ] 扫码登录全流程打通,cookie 持久化、重启免扫码
  - [ ] WiFi 与 eSIM 下下载均正常
  - [ ] 前台服务息屏不被杀、断点续传可恢复
  - [ ] 系统播放器能扫到 `Music/WatchMusic/` 下文件
  - [ ] 增删幂等:连续两次同步,第二次无新增无删除
  - [ ] 大歌单(3742 首)首轮同步全流程跑通
  - [ ] 存储预检在空间不足时正确拦截

## 11. Git 规范

- 仓库已建:`git@github.com:Adkimsm/watch-netease-downloader.git`
- 宿主机 `git init` + `git remote add origin` 后直接推 main
- **每个可编译的 Phase 一个 commit**,不搞一个大 commit;commit 前必须
  `gradle-docker :app:compileDebugKotlin`(或 assembleDebug)通过
- **kernel-style commit message**:imperative 语气、`子系统: 动作` 前缀、
  首行 ≤50 字符、正文与首行空行隔开。示例:
  ```
  crypto: add weapi/eapi encryption for direct NCM access

  Implements AES-CBC/RSA weapi and AES-ECB eapi schemes required by
  qrcode login and song url endpoints.
  ```

## 12. 风险与对策

| 风险 | 对策 |
|---|---|
| 网易加密/接口变更 | crypto、net 层隔离,改一处即可;关注 `freeTrialInfo` 逻辑变化 |
| 非 VIP 取不到 url | 置 MISSING_URL,UI 汇总跳过,不阻塞同步 |
| 超大歌单响应解析 OOM/超时 | 流式解析 + 分批事务;兜底 trackIds 分批方案 |
| ColorOS 杀前台服务 | 前台通知保活;实测必要时引导用户加电池白名单 |
| 屏幕形态/方向 | 布局自适应,装机后按 `wm size` 实测调整字号与方向锁定 |
| minSdk 与实际基线不符 | 装机 `getprop` 核实后定 minSdk(暂设 29,预期 Android 11) |

## 13. 执行 Phase 清单

- [x] **Phase 0**:核对容器工具链(JDK 25 / Gradle 9.5.0 / AGP 9.2.0 / Kotlin 2.3.20);
  工程骨架搭建完成(Compose 空 Activity、自适应图标、version catalog),
  `compileDebugKotlin` 与 `assembleDebug` 均通过(下同)
- [x] **Phase 1**:crypto(weapi/eapi,向量化单测)+ net 层(eapi 直连 + cookie 持久化)
  + 扫码登录 UI(zxing 渲染 + 轮询);live 接口单测通过(unikey/801、歌单详情、歌曲详情、批量 url)
- [x] **Phase 2**:SQLite 三表(**改用手写 SQLite**:Room 2.8.5 的编译器与 KSP2
  在 Kotlin 2.3.20 工具链不兼容、KSP1 已移除)+ MediaStoreWriter(Music/WatchMusic/)
  + 歌单/歌曲批量接口
- [x] **Phase 3**:SyncEngine(diff 引用计数 / 断点续传 / 硬删除 + 孤儿清理 /
  存储预检 / md5+size 校验重试)
- [x] **Phase 4**:UI 四屏(登录/歌单/同步预览/同步进度)+ 设置页(音质档位、退出登录)
  + SyncService 前台服务(dataSync,进度通知,息屏保活)
- [x] **Phase 4.5**:UI/UX 统一设计系统 + 全程 loading 反馈(不新增依赖):
  theme 令牌(Color/Type/Spacing)+ components 复用组件(Skeleton 骨架屏、
  ScreenScaffold/PrimaryButton/ConfirmDialog 等)+ Format.kt、EtaEstimator.kt;
  6 屏全部重排,中文文案迁入 strings.xml;
  落实「任何等待都有 loading」:骨架屏(200ms 延迟防闪烁)、按钮内联 loading、
  行内 loading、确认对话框、失败错误条;补 ETA 与停止二次确认
- [ ] **Phase 5**:手表装机,按 §10 测试清单逐项实测并修问题

## 14. 实施纪要(避坑)

- Kotlin 固定 2.3.20;不可降到 2.3.12(该版本的 org.jetbrains.kotlin.android
  插件未发布)。KSP 版本号不等于 Kotlin 版本 —— KSP 2.3.12 的 POM 依赖的正是
  kotlin-stdlib 2.3.20。
- Room 2.8.5 + KSP2(KSP 2.3.12)报 “No property named value was found in
  annotation Query”,上游未适配 Kotlin 2.3.20;KSP1 已从 KSP 2.x 移除。
  数据层改用 framework SQLite 手写三表。
- Kotlin 2.3 里 context 是上下文接收者语法关键字,构造参数/变量避开此名。
- collectAsStateWithLifecycle() 对部分 StateFlow 在 lifecycle 2.9.0 下出现类型
  推断失败时,用 collectAsState() 替代。
- ViewModel 里属性不可与构造参数同名(private val app = app as App 会自引用
  失效),用 appRef。
- adb 在容器内路径为 /opt/android-sdk/platform-tools/adb,已修 ~/bin/adb-docker。
- **CookieStore 的 deviceId 竞态(真实缺陷,已修)**:init{} 里镜像磁盘的常驻
  收集器会在 init() 写盘落地前先收到一份空快照,把刚生成的 deviceId 抹回空串,
  导致 cookie 头缺 deviceId、鉴权偶发失败。改为「仅当磁盘确有值时才镜像」。
  该缺陷原先被 CookieStoreInitTest 偶发暴露,且**与测试类执行顺序相关** ——
  在纯净 HEAD 上新增任意一个无关测试类即可复现,故一度被误判为新代码引入。
- **CookieStoreInitTest.externalWrite_reflectsInCookieHeader 自身的竞态**:
  musicUState 与 init{} 的常驻收集器是 dataStore.data 上两个独立收集器,
  只等 musicUState 就绪不代表 csrf 已回填,断言会偶发失败。已在测试侧改为
  等待断言真正依赖的状态。
- **工具链注意**:写入含反引号的 Kotlin 行(如反引号测试名)时,编辑工具可能
  把该行拼坏。测试函数名改用普通 camelCase,或改用脚本写文件。
- 全量单测连续跑 10 次均通过(40 个测试,含 Phase 4.5 新增 21 个),已排除 flaky。

---

*本计划随推进持续更新;行为变更以本文件为准。*