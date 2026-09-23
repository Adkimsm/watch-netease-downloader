# 网易云音乐手表版 — 本地优先的网易云第三方播放器(OPPO Watch 3)

> 本文件是项目的唯一计划源(single source of truth)。整个项目跨多个会话完成,
> 每次新会话先读本文件 + `git log` 了解进度,从下一个未完成 Phase 继续。
> 完成一个 Phase 后更新本文件的勾选状态。

## 0. 项目身份

| 项 | 值 |
|---|---|
| 应用名(显示) | 网易云音乐手表版(2.0 起由「网易云音乐同步」更名) |
| 版本 | versionCode 2 / versionName 2.0 |
| applicationId | `io.github.adkimsm.neteasedownloader` |
| 仓库 | `git@github.com:Adkimsm/watch-netease-downloader.git` |
| 工作目录(宿主机) | `~/code/watchmusic` → 容器内 `/workspace/watchmusic` |
| 目标设备 | OPPO Watch 3(国行,ColorOS Watch 2.1,类 Android 11 基线,1.75" 372×194 屏) |
| 安装方式 | `adb-docker install -r` 侧载普通 APK(非 Wear OS) |
| 屏幕适配 | 按窗口短边分 Compact(<300dp)/ Medium(300~360dp)/ Expanded(≥360dp),见 Phase 4.6 |

## 1. 核心决策(已与用户确认,不再变动)

1. **纯本地零依赖**:App 内置 weapi/eapi 加密(Kotlin 实现),直接请求 `music.163.com`。
   不部署任何外部服务器(用户明确否决了 VPS 代理方案),不依赖 GMS。
2. **本地优先播放**(2.0 起):已下载的歌播本地文件,未下载的联网串流兜底
   (**串流不落盘**)。文件仍写入公共目录 `Music/WatchMusic/`(走 MediaStore,免存储权限)。
   见 §15 的 D1–D14。
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
├── sync/        SyncEngine, FileNamePolicy, AudioTagWriter
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
播放追加:`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`WAKE_LOCK`。
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
- [x] **Phase 4.6**:小屏自适应(为手表尺寸做真正的适配,而非只调固定值):
  新增 `theme/WindowSizing.kt` —— 按窗口**短边**划分 Compact(<300dp)/ Medium(300~360dp)/ Expanded(≥360dp),
  每档导出一整套尺寸令牌,经 `LocalWindowSizing` 由主题下发;各屏不再直接取 `Dimens` 固定值。
  **首页**:底栏按档位收缩(极窄屏省略分隔线与计数行,只留主按钮),把垂直空间还给列表;
  **下载页**:上半内容改为可滚动 + 停止按钮恒定可见(原先 `Spacer(weight(1f))` 在矮屏会把按钮顶出屏外且无法滚动到),
  极窄屏把总数并入百分比行并降一档字号。
  同步覆盖登录(二维码按档位收窄)、预览、设置页;补 11 个档位边界与单调性单测。
  验证:`compileDebugKotlin` / `testDebugUnitTest`(40→51 个测试全过)/ `assembleDebug` 均通过。
- [x] **Phase 4.7**:下载文件名规范化——新增 sync/FileNamePolicy.kt(纯函数 + 14 项单测),
  文件名由「{songId}_{歌手} - {歌名}」改为「歌名 - 歌手.ext」:多歌手分隔符「/」归一为「、」、
  非法字符与控制符替换、首尾空白/点清理、超长截断但保住扩展名(旧实现对整串 take 会把扩展名截掉);
  execute() 开头新增 NORMALIZING 阶段,批量把已下载的旧文件重命名成新格式(幂等,待删歌曲跳过);
  MediaStoreWriter 加 displayNameByUris/rename,UI/通知/路由同步新阶段。
- [x] **Phase 4.8**:下载/存量文件写入歌名与歌手标签——新增 `sync/AudioTagWriter.kt`(纯函数 + 26 项单测):
  mp3 写 ID3v2 文本帧 `TIT2`/`TPE1`(v2.3 帧长大端原值、v2.4 syncsafe,重建时主版本跟着原标签走;
  中文走 UTF-16 + BOM),flac 写 VORBIS_COMMENT 的 `TITLE=`/`ARTIST=`(块长大端、内部长度小端);
  **只替换这两个字段**,封面 APIC/PICTURE、专辑、音轨号等其它标签与音频正文逐字节保留;
  下载路径在 `markDone` 之前写标签(条目仍是 IS_PENDING,播放器读不到半成品),并按最终字节数落库;
  `execute()` 新增 **TAGGING 阶段**幂等补齐存量文件(内容探测判定,已是目标值则零写盘,待删文件跳过);
  `MediaStoreWriter` 加 `openRead`/`rewrite`(临时文件重写 + 双空间预检,失败绝不截断原文件);
  m4a/aac/ogg 不处理。单测 67→93 全过,并用 3 处变异确认新用例不是空转。
- [ ] **Phase 5**:手表装机,按 §10 测试清单逐项实测并修问题
- [x] **Phase 6**:播放内核 —— media3 1.11.1(`media3-exoplayer` + `media3-session`,
  不引 UI/datasource-okhttp);`PlaybackService`(MediaSessionService + ExoPlayer,
  音频焦点 / 拔耳机暂停 / WakeMode / 媒体通知);`PlaybackUri` 统一
  `watchmusic://song/<id>`,`LocalFirstResolver` 在打开前解析成 `content://` 或签名 https;
  `PlaybackQueue` 只保留 ExoPlayer 语义与手动切歌预期不一致的部分;
  `QueueStore` 快照恢复。新增 49 个单测(97→146)
- [x] **Phase 7**:导航栈 + 浏览 + 极简播放页 —— `Dest` 密封接口取代 Screen 枚举与
  settingsOpen/diagnosticsOpen 布尔量;`PlaylistCache` 从 SyncEngine 抽出,同步与浏览
  共用;**点行歌单进详情**(勾选框才管同步);曲目按需拉取并缓存;
  `planLocalDeletions` 精化差量(文件集合仍 = 已勾选歌单并集,浏览缓存不再每轮被清);
  极简无封面播放页 + 整屏二级菜单 + 队列 + mini 播放条;`SongDao.getByIds` 分批
  (3700 首会顶穿 SQLite 绑定变量上限)。新增 20 个单测(146→166)
- [x] **Phase 9**:weapi 通道 —— `NcmApi.weapiRaw`(params + encSecKey + csrf_token +
  Referer);`RemoteWritePayload` 纯函数构造写请求体(`tracks` 与 `/api/batch` 都是
  JSON-in-string,一律走 kotlinx 生成);`CookieProvider.csrfToken()` 带默认实现,
  不动 `fun interface` 的 SAM 用法。新增 15 个单测(166→181)
- [x] **Phase 10**:**删除这首歌** —— DB v2(`liked_song` 表 + `playlist.creatorId`/
  `specialType`);`SongPresence` / `RemovePlan` / `RemoveUndo` / `SongRemover`;
  设置项「删除歌曲时」三档(每次询问 / 全部删除 / 只删本地);多歌单选择面板(他人歌单
  置灰)+ **实时后果预览**(删完仍会被重新下载的歌单);**红心一律自动取消**;
  点了就删时配 3 秒撤销条;写后读回校验。新增 31 个单测(181→212)
- [x] **Phase 11**:我喜欢列表页 + 加入歌单 + 歌单级远端管理(新建/重命名/删除歌单)。新增 11 个单测(212→223)
- [x] **Phase 12**:文档与版本(本文件 §15、README 重写、2.0);装机实测项待有设备后执行(见 §10 清单)
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
- **尺寸令牌要逐项缩放,不能整体乘系数**:触控目标有硬下限(手表上 <36dp 很难点中),
  而封面/图标这类纯装饰项可以放心缩小。WindowSizing 因此逐字段声明而非用 scale 因子。
- **矮屏的“底栏”必须可滚动或固定,不能靠 `Spacer(weight(1f))` 顶**:
  进度页原写法在内容高于窗口时会把停止按钮顶出可视区,且没有滚动容器可拉回来 ——
  这类“按钮可达性”缺陷在真机上才暴露,静态看代码不报错。
- **档位阈值比的是 dp,不是物理像素**:`Configuration.screenWidthDp/HeightDp` 已是 dp,
  而 `wm size` 报的是 px。必须配合 `wm density` 换算(`dp = px / (density/160)`),
  直接把 px 当 dp 比会算错档位。例如 372×430 px @ density 320(2.0x)= 186×215 dp → **Compact**;
  同样是 372 px 宽,若 density 为 160 则是 372 dp → Expanded。
  **装机后先用 `adb-docker shell wm size; wm density` 实测再核对档位**,
  并注意 PLAN §0 记的「372×194」缺 density、且 194 对真实手表的高度明显偏小,疑似笔误。
  阈值 299/300/359/360 的边界已用单测钉住,改阈值前先看 `WindowSizingTest`。
- **文件名截断不能 take 整个串**:旧 buildFileName 对完整文件名 take(160),超长歌名会把
  扩展名一起截掉(如 .flac 变 .fla)。新策略先按总长算主体上限、截完再去掉残尾的「 -」「.」;
  旧文件由 NORMALIZING 阶段在下次同步时幂等重命名(用户要求:同步时把旧文件一起改名)。
- **ID3 的版本不能随手统一**:重建标签时若把 v2.4 文件改写成 v2.3 头,而保留的帧仍是 v2.4 的
  syncsafe 帧长编码,播放器就会把帧长读错、整个标签解析失败。`AudioTagWriter` 因此让主版本跟着原标签走
  (无标签才新建 v2.3)。帧长编码在 v2.3 是大端原值、v2.4 是 28-bit syncsafe —— 单测要用 >127 字节的
  载荷才能区分这两种编码,小于 128 时两者恰好相同,根本测不出来。
- **中文标签必须 UTF-16 + BOM**(编码字节 0x01),ISO-8859-1 会把中文写成乱码;显式写小端 BOM
  `FF FE`,比 Java `Charsets.UTF_16` 默认的大端 BOM 更被老播放器接受。
- **FLAC 块头长度是大端,VORBIS_COMMENT 内部长度是小端**,两处弄反都会读出天文数字的字段长度;
  另外 STREAMINFO 必须仍是第一块,last-metadata-block 标志要落在最后一块上。
- **补标签不能把整个标签重建一遍**:只摘掉 `TIT2`/`TPE1`(或 `TITLE=`/`ARTIST=`),
  否则会把封面(APIC/PICTURE)、专辑、音轨号一起丢掉,播放器里封面直接消失。
  带 unsynchronisation / extended header / 压缩帧标志或 v2.2 标签的文件一律整体放弃(不动文件),
  宁可漏填也不写坏。
- **就地重写必须走临时文件 + 空间预检**:`openOutputStream(uri,"wt")` 一调用原文件就被截断,
  此时若目标卷没空间,写一半失败就只剩个损坏文件。先写 cacheDir 临时文件、再查目标卷可用空间、
  最后才截断回写;失败时原文件完好,交给下次同步重试。
- **字节级代码的测试要拿变异验一遍**:刻意注入「v2.4 帧长用大端」等 3 处缺陷,确认真的打挂 7 个用例;
  否则很容易写出永远为真的断言,看着绿实际什么都没验。
- **「同步完成后不弹下载预览,点一下设置再返回就出现」是真缺陷,根因在 Flow 的接线**
  (Phase 4.5 后修复):`computeDiff()` 先发 `Stage.READY`,而差量是 `refreshAndDiff()`
  返回后才由服务赋值的 —— 两者不在同一个事件里。旧实现把 `lastDiff` 当普通 `@Volatile var`,
  在 `combine(loggedIn, progress, settingsOpen, diagnosticsOpen)` 的 transform 里临时读一次快照:
  READY 那一刻读到 null → 路由到歌单页,而且**之后再无任何事件触发重算**,就永久停在歌单页;
  用户点开设置 / 返回只是改了 `settingsOpen`,顺带把路由重算了一次,于是预览又“神奇地”出现。
  修法:差量改成 `StateFlow` 并作为 `combine` 的**输入之一**(`routeUiState`),
  迟到的差量自己会再驱动一次路由;同时页面与差量合并为一个 `UiState` 同源产出,
  预览页不会在差量未就绪时先渲染。教训:凡是 UI 依赖的状态,**只要它是普通 `var`/快照、
  又处在“先发事件、后写值”的顺序里,就一定会出现这一类竞态**;把它做成 Flow 输入,
  而不是在 combine 里临时读。路由已抽成纯函数 `routeScreen`/`routeUiState`,
  `ScreenRoutingTest` 用 4 个用例钉住时序(拿掉 combine 里的差量输入会实打挂 2 个用例)。

---
*本计划随推进持续更新;行为变更以本文件为准。*

---

## 15. 2.0 决策记录(D1–D14)

| # | 决策 | 取值 | 理由 |
|---|---|---|---|
| D1 | 播放音源 | 本地优先 + 在线串流兜底,**串流不落盘** | 已下载的完全离线可播;未下载的仍能听 |
| D2 | 远端管理范围 | 歌单内歌曲增删 + 新建/重命名/删除歌单 + 红心;**不做**收藏/取消收藏他人歌单 | 用户选定 |
| D3 | 播放内核 | Media3 ExoPlayer + MediaSessionService | 音频焦点/通知/蓝牙/唤醒全部现成 |
| D4 | 同步删除策略 | 严格差量**不变** | 语义可预测,README 已写明 |
| D5 | 多歌单删除 | 弹面板选删哪些;设置可改为「全部删除」「只删本地」 | 用户明确要求 |
| D6 | 本地屏蔽(不喜欢) | **不引入** | 用户否决:删除即删除 |
| D7 | 被屏蔽歌曲呈现 | 不适用 | D6 |
| D8 | 删除交互 | 「全部删除」「只删本地」**点了就删**,不弹确认 | 用户明确要求 |
| D9 | 删除入口 | 播放页二级菜单 + 曲目行 ⋮ | 用户选定 |
| D10 | UI 约束 | 所有新 UI 必须过手表小屏这一关(§16) | 用户明确要求 |
| D11 | 误触兜底 | 删除后 3 秒可撤销 | 对 D8 的补偿 |
| D12 | 播放页形态 | 极简播放页 + 整屏二级菜单 | 小屏上每多一个控件,主控键就小一圈 |
| D13 | 封面 | 播放页 / 曲目行 / 队列**都不显示** | 下载不回填专辑图,本地无图;为此逐首拉远端图不划算 |
| D14 | 红心 | 删除时**自动**取消,不是可选项 | 留着红心会让「我喜欢的音乐」把歌下回来 |

### 15.1 已知后果(用户已确认接受)

- 他人歌单(收藏来的)无法远端移除。只要那个歌单还勾着同步,删掉的歌**下一轮同步会被
  重新下载**。做法是删除前就在面板上写清楚,而不是删完才让用户发现。
- 「只删本地」本质上删不掉 —— 歌单仍勾选同步时下次会下回来。设置项说明里已前置写明。
- 「点了就删」没有确认框,靠 3 秒撤销条兜底;撤销只恢复歌单与红心,本地文件等下次同步。

## 16. 2.0 新增踩坑

- **`MediaSessionService` 里 `mediaSession?.run { player?.release() }` 会解析到
  `MediaSession.getPlayer()`**,不是自己那个字段。虽然此刻恰好是同一个实例,但
  语义含糊且编译器只给一句"Unnecessary safe call"。先把两个引用取成局部变量再释放。
- **media3 的 `minCompileSdk` 写在 AAR 元数据里**:1.11.1 要求 `minCompileSdk=36`。
  升级前先 `checkDebugAarMetadata`,不然会在编译期报一个和依赖版本毫无关系的错。
- **`REPEAT_MODE_ONE` 下 `seekToNextMediaItem()` 会重播当前曲**。手动"下一首"必须
  临时把 repeatMode 置 OFF、切完再恢复 —— 这是唯一需要自己实现(而非交给 ExoPlayer)
  的传输语义,所以它被抽成了带单测的纯函数。
- **`SongDao.getByIds` 用单条 `IN (?,?,…)` 会被大歌单顶穿**:安卓上 SQLite 的绑定变量
  上限是 999(旧版本),3700 首的歌单直接抛异常。已改为按 500 分批。
- **`joinToString` 默认分隔符是 `", "`**:拿来拼 JSON 数组会插进一个空格,与参考实现
  不一致。要么显式 `separator = ","`,要么用 kotlinx 生成。写请求体一律用后者。
- **`/api/batch` 与 `playlist/track/*` 的请求体是 JSON-in-string**:`tracks` 的值本身
  是一段 JSON 文本,`/api/batch` 的键就是端点路径、值又是一整段带歌名的 JSON。
  手工拼字符串遇到歌名里的引号/反斜杠必然写坏,而服务端多半只回 200。
- **写接口的 200 不能当作生效的证明**:`playlist/track/delete` 会返回 200 却不改动。
  删除后必须 `fetchPlaylistTrackIds` 读回确认,失败/不一致要单独报告。
- **weapi 与 eapi 的差别不只是加密**:weapi 要把 `csrf_token` 放进**请求体**、
  必须带 `Referer`、表单多一个 `encSecKey`,URL 走 `music.163.com` 而不是
  `interface.music.163.com`。少任何一项都可能拿到 401 或静默失败。
- **`ResolvingDataSource.Resolver.resolveDataSpec` 跑在 ExoPlayer 的加载线程上**,
  所以桥接挂起函数只能用 `runBlocking`。这可以接受的前提是**命中缓存时不产生挂起调用**;
  否则每次 seek 都在加载线程上等一次网络。
- **删除决策必须抽成纯函数**:`planLocalDeletions` 里"只看有本地文件的歌"是一条不变量。
  它一度由调用方先过滤,单测立刻就挂了 —— 不变量放在校验不到的地方迟早会被漏掉。
- **"点了就删"必须配撤销**:去掉确认框之后,误触的成本全压在撤销条上。撤销条只恢复
  **确实改动过**的部分(失败的歌单本来就没动,再 add 一遍会重复)。
- **weapi 通道 2026-09 起整体失效(空 200)**:`music.163.com/weapi/*` 对全部端点
  返回 HTTP 200 + 空 body —— 没有错误码、没有 JSON,`code` 字段回退成 httpCode 后
  看起来像"成功",实际什么都没发生。表现:**红心列表拉取抛 JsonDecodingException
  (JSON input 为空)、删除/加歌/红心/建删改名歌单全部静默不生效**(写后读回不一致)。
  实测 eapi 通道(`interface.music.163.com/eapi/*`)一切正常,于是远端写操作整体从
  weapi 迁到 eapi(参考实现 4.32.0 里红心列表 likelist.js、`/api/batch` 本就默认
  eapi;加/删曲参考 playlist_tracks.js 走 `/api/playlist/manipulate/tracks`)。
- **eapi 写端点要把设备 header 内嵌进 payload**:参考实现 `data.header = header`
  (与 Cookie 头同源,含 osver/deviceId/os/appver/__csrf/.../MUSIC_U)。读端点不要求,
  写端点照抄最稳。
- **`playlist/manipulate/tracks` 有 code=512 怪癖**:新歌单/操作频繁时服务端回 512,
  参考实现的重试是"trackIds 翻倍再发一次"(ids 原样重复一遍,服务端会去重)。
