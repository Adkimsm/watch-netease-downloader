# 网易云音乐手表版

**本地优先的网易云第三方播放器**,为手表而做:扫码登录后浏览并播放你的网易云歌单,
听着不好听的歌可以直接删掉 —— 一次同时干掉本地文件、对应歌单里的它,以及红心。
同时仍然可以把歌单批量下载成本地文件,离线也能听。

纯本地运行 —— 不依赖任何服务器,不依赖 GMS,不需要存储权限。主要面向 **OPPO Watch 3**(ColorOS Watch 2.1,类 Android 11);普通 APK 侧载,不是 Wear OS 应用,Android 10(`minSdk 29`)及以上设备均可安装,界面按手表小屏适配。

---

## 快速上手(5 步)

### 1. 拿到 APK

- **从 CI 取**:打开仓库的 **Actions** 页面 → 选最近一次成功的 `Android CI` 构建 → 在页面底部的 **Artifacts** 里下载 `watchmusic-<版本>-<构建号>.apk`(需要登录 GitHub;Actions 产物有保留期限,过期就重新触发一次构建)。
- **自己构建**:见下方[构建](#构建)。

### 2. 安装到手表

```bash
adb devices
adb install -r watchmusic-2.0-1.apk
```

也可以把 APK 传到手表上,用文件管理器点击安装(需允许「安装未知来源应用」)。

首次进入 App 会请求通知权限(Android 13+),**请允许** —— 否则同步进度通知不会显示。

### 3. 扫码登录

打开 App 即显示二维码,用手机上的网易云音乐 App 扫码:

- 等待扫码 → 已扫码,请在手机上确认 → 登录成功
- 二维码过期就点「刷新二维码」

登录凭据只保存在本机,重启后免扫码。

### 4. 选歌单

在「我的歌单」里勾选要同步的歌单(可多选),点「立即同步」。

首次会先拉取歌单曲目;歌单很大时(几千首)需要等一会儿,界面有加载反馈。

### 5. 预览并同步

「同步预览」会列出本次的**待下载 X 首 / 待删除 Y 首 / 跳过 Z 首**(无版权或 VIP 限制的歌曲),以及预计占用空间与剩余空间。确认后进入「同步进度」,可看总进度、当前曲目与剩余时间。

随时可点「停止」:已下载的文件保留,再次同步会从断点继续。

---

## 文件放在哪、怎么听

下载的音频保存到设备公共目录:

```
Music/WatchMusic/
```

- 文件名格式为 `歌名 - 歌手.ext`,例如 `晴天 - 周杰伦.mp3`
- mp3 与 flac 会写入歌名、歌手标签,系统播放器或其他播放器扫描本机音乐即可看到

### 播放

App 自带播放器,不依赖系统播放器:

- **本地优先**:已下载的歌直接播本地文件,飞行模式下也能听。
- **串流兜底**:没下载过的歌联网取地址边下边播,**不会写进本地库**(不占空间)。
- 播放页保持极简(只有歌名、歌手、进度与三键),播放模式、随机、队列、删除都在「⋮」的
  二级菜单里 —— 手表屏幕上每多一个控件,主控键就小一圈。
- 后台/息屏继续播放,系统媒体通知与蓝牙耳机按键均可控制。

### 删除一首歌

在播放页或曲目行点「⋮」→「删除这首歌」。它会同时:

1. 从你**拥有的**歌单里移除它(收藏来的他人歌单网易云不允许改,面板里会置灰);
2. **自动取消红心**;
3. 删除本地文件。

这首歌同时存在于多个歌单时,会先让你勾选删哪些(默认全选自己的)。
面板上还会提前告诉你:**删完以后还有哪些已勾选的歌单含这首歌** —— 那些歌下一轮同步
会被重新下载,别删完才发现。

设置里的「删除歌曲时」有三档:

| 档位 | 行为 |
|---|---|
| 每次都询问(默认) | 弹面板,自己勾要删哪些歌单 |
| 全部删除 | 从所有本人歌单移除 + 删本地,**点了就删不弹确认** |
| 只删本地 | 歌单不动、红心仍会取消,**点了就删不弹确认** |

后两档没有确认框,所以每次删除后会有 **3 秒的撤销条**:点「撤销」把歌单与红心恢复
(本地文件下次同步自然会下回来)。

---

## 界面说明

| 界面 | 能做什么 |
|---|---|
| 登录 | 扫码登录;二维码过期后刷新 |
| 我的歌单 | **点行进入歌单看曲目**;点勾选框决定这个歌单是否纳入同步;进设置;「立即同步」 |
| 歌单详情 | 曲目列表(已下载 / 在线 / 无版权徽标);点行播放;行尾「⋮」进二级菜单 |
| 播放页 | 歌名、歌手、进度条、上一首 / 播放暂停 / 下一首;页头「⋮」进二级菜单 |
| 二级菜单 | 删除这首歌、查看队列、播放模式(顺序 / 列表循环 / 单曲循环)、随机播放 |
| 播放队列 | 当前队列、当前曲高亮、单曲移除、点击跳播 |
| 删除面板 | 勾选要从哪些歌单移除、是否只删本地 / 保留本地,并显示实时后果 |
| 同步预览 | 查看本次新增 / 删除 / 跳过的歌曲与存储占用,确认或放弃 |
| 同步进度 | 进度、当前曲目、剩余时间;停止同步(可续传) |
| 设置 | 下载音质、在线播放音质、删除歌曲时的行为、退出登录、诊断日志 |
| 诊断日志 | 查看运行日志并一键复制(反馈问题时附上) |

界面按窗口**短边**分三档自适应:Compact(< 300dp)/ Medium(300–360dp)/ Expanded(≥ 360dp),窄屏上会自动收掉非必要信息,把空间让给列表和按钮。

---

## 使用提示

- **音质与空间**:设置里可选 标准(128 kbps,默认)/ 较高(192)/ 极高(320)/ 无损(FLAC)。标准音质每首约 3.8 MB,三千多首约十几 GB;手表空间有限,选高挡位前先确认。同步开始前会做**存储预检**,空间不够会直接拦截并提示。
- **耗时**:首次全量同步可能长达数小时。同步跑在前台服务里,息屏也会继续,但系统的省电策略仍可能把它杀掉 —— 建议把本 App 加入电池白名单,长时间同步时保持充电。
- **跳过**:无版权或需要 VIP 才能取到地址的歌曲会计入「跳过」,不会中断同步。
- **在线播放音质与下载音质分开设**:磁盘上可以存无损,但手表串流无损基本必卡,所以串流默认「极高(320 kbps)」。
- **串流不落盘**:在线播放的歌不会写进 `Music/WatchMusic/`,也不占本地索引。
- **删除**:从歌单里移除、或取消勾选歌单后不再需要的歌曲,会在同步时**删除本地文件**(严格差量);多个歌单共用的歌曲,只在最后一个歌单也移除后才删。
- **卸载**:卸载 App 不会删除已下载的音频文件(仍留在 `Music/WatchMusic/`),但会清除登录状态与本地索引,重装后需要重新扫码。

---

## 常见问题

**二维码扫不上 / 一直没反应**
确认手表网络可用;二维码过期就点「刷新二维码」重新生成。

**卡在「正在拉取歌单」**
歌单很大时解析需要时间,等待即可;网络较差时更久。

**有些歌一直没下载下来**
看预览里的「跳过」计数,多为无版权或 VIP 限制的歌曲。

**提示空间不足**
清理空间,或在设置里降低音质档位后重试。

**提示未登录 / 同步中途失效**
回到登录页重新扫码。

**同步忽然中断**
系统可能回收了后台服务。加入电池白名单后,再点「立即同步」即可从未完成的歌曲继续。

---

## 免责声明

本项目是非官方的第三方工具,与网易云音乐官方无关。它直接调用网易云音乐的接口,用来下载**你自己账号**的歌单内容,仅供个人备份与离线收听。相关接口并非公开 API,随时可能变更导致功能失效;请遵守网易云音乐的服务条款,不要用于商业或侵权用途。

---

## 构建

环境要求:

- **JDK 25**
- **Android SDK**(compileSdk 36)
- 让 Gradle 找到 SDK:设置 `ANDROID_HOME` 环境变量,或在项目根目录新建 `local.properties` 写入 `sdk.dir=/path/to/android-sdk`(`local.properties` 已被 gitignore)

```bash
./gradlew :app:compileDebugKotlin     # 只编译 Kotlin,最快的语法检查
./gradlew :app:testDebugUnitTest      # 单元测试
./gradlew :app:assembleDebug          # 调试包 → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # 发布包 → app/build/outputs/apk/release/app-release.apk
```

Windows 下把 `./gradlew` 换成 `gradlew.bat`。

安装到设备与查看日志使用标准 adb:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat
```

**Release 签名**:在项目根目录创建 `keystore.properties`(已被 gitignore):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=你的store密码
keyAlias=你的alias
keyPassword=你的key密码
```

也可以改用环境变量 `KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。只构建 debug 包不需要签名配置。

---

## 技术栈

- **语言 / UI**:Kotlin 2.3.20 + Jetpack Compose(Material 3,Compose BOM 2025.06.00)
- **构建**:Gradle 9.5.0(wrapper)+ AGP 9.2.0,JDK 25
- **网络 / 序列化 / 协程**:OkHttp 4.12.0、kotlinx.serialization 1.8.1、kotlinx.coroutines 1.10.2
- **本地存储**:DataStore 1.1.7(设置与登录凭据)+ framework SQLite 手写三表索引(不引入 Room)
- **播放**:Media3 1.11.1(`media3-exoplayer` + `media3-session`,不引 `media3-ui`)
- **其他**:zxing 3.5.3(本地生成登录二维码)、Coil 2.7.0(歌单封面)
- **依赖注入**:`App.kt` 中手写 ServiceLocator(不引入 Hilt)
- **无服务器、无 GMS**:接口加密(weapi / eapi)在 Kotlin 侧实现,直连 `music.163.com`

---

## 工程结构

```
app/src/main/java/io/github/adkimsm/neteasedownloader/
├── crypto/   NcmCrypto.kt                     weapi(AES-CBC + RSA)/ eapi(AES-ECB)
├── net/      NcmApi.kt, NcmModels.kt,         接口与响应模型、下载器、cookie 提供者
│             Downloader.kt, CookieProvider.kt
├── data/     AppDatabase.kt, Daos.kt,         手写 SQLite 四表(playlist / song /
│             Entities.kt, MediaStoreWriter.kt, playlist_song / liked_song)、
│             CookieStore.kt, SettingsStore.kt MediaStore 写入、登录凭据与设置
│             PlaylistCache.kt                 远端↔本地歌单/曲目缓存(同步与浏览共用)
├── sync/     SyncEngine.kt, SyncService.kt    差量同步引擎 + 前台服务
│             DiffPlanner.kt                   待删集合的纯决策
│             FileNamePolicy.kt                文件名规范化
│             AudioTagWriter.kt                mp3 / flac 标签写入
├── player/   PlaybackService.kt               MediaSessionService + ExoPlayer
│             PlaybackRepository.kt            UI 侧控制入口(MediaController)
│             LocalFirstResolver.kt            本地优先 / 串流兜底的唯一决策点
│             PlaybackQueue.kt                 手动切歌的纯语义
│             QueueStore.kt                    队列与位置快照
├── library/  SongPresence.kt / RemovePlan.kt  删除范围与后果预览(纯函数)
│             SongRemover.kt / RemoveReport.kt 远端删除 + 红心 + 本地删除 + 撤销
├── ui/       11 个界面 + ViewModel + theme/   尺寸令牌、颜色、字体
└── diag/     Diag.kt                          环形内存日志 + 文件日志(崩溃自动落盘)
```

数据流:

```
扫码登录 → CookieStore(仅持久化 MUSIC_U)
        → NcmApi(weapi / eapi 直连 music.163.com)
        → SyncEngine:diff → 文件名规范化 → 下载 → 写标签 → 删除
        → MediaStoreWriter(Music/WatchMusic/)+ SQLite 索引
```

差量以 SQLite 索引为基准:远端有、本地没有 → 下载;本地有、远端没有 → 删除(按 songId 全局引用计数,多歌单共用只删一次),同时清理应用创建的孤儿 MediaStore 条目。

---

## 接口

全部直连 `music.163.com`,均为**非官方接口**,随时可能变更:

| 用途 | 端点 | 说明 |
|---|---|---|
| 取登录 key | `POST /weapi/login/qrcode/unikey` | `{type:1}` |
| 轮询登录状态 | `POST /weapi/login/qrcode/client/login` | 800 过期 / 801 待扫 / 802 已扫待确认 / 803 成功 |
| 我的歌单 | `POST /api/user/playlist` | 带 cookie |
| 歌单详情 | `POST /api/v6/playlist/detail` | `{id, n:100000, s:8}`,一次取全量曲目 |
| 歌曲下载地址 / 串流地址 | `POST /api/song/enhance/player/url/v1` | eapi,批量取;取不到地址记 `MISSING_URL` |
| 我喜欢(红心)id 列表 | `POST /weapi/song/like/get` | weapi |
| 红心 / 取消红心 | `POST /weapi/radio/like` | weapi |
| 歌单内加曲 / 删曲 | `POST /weapi/playlist/track/add` · `/track/delete` | weapi,`tracks` 是 JSON 字符串 |
| 新建 / 删除 / 重命名歌单 | `POST /weapi/playlist/create` · `/remove` · `/batch` | weapi |

写操作一律走 weapi(参考实现的选择):eapi 对部分写端点不可用,而且写失败常常是
**静默的**(返回 200 却不生效),所以每次删除后都会读回歌单确认。

---

## 测试与 CI

单元测试共 **212 个**,覆盖加密(weapi / eapi)、写请求体构造(含歌名里的引号 / 反斜杠 / emoji)、接口解析与下载地址规整、文件名策略、音频标签读写、ETA 与字节格式化、窗口尺寸档位、Cookie 持久化,以及 2.0 新增的播放传输策略、本地优先解析、播放快照恢复、路由与返回栈、同步差量、删除范围与撤销:

```bash
./gradlew :app:testDebugUnitTest
```

CI 配置在 `.github/workflows/android.yml`:推送到 `main`(改动 `app/**`、`gradle/**`、`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties` 或 workflow 自身)、提交 PR、或手动触发时运行。流程是先跑单元测试,再(非 PR)构建 Release 包,并把产物重命名为 `watchmusic-<版本>-<构建号>.apk` 上传为 artifact。

CI 需要的 secrets:`KEYSTORE_BASE64`(keystore 文件的 base64)、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

---

## 许可

本项目以 **GNU General Public License v3.0** 发布,全文见 [LICENSE](LICENSE)。

完整的实施计划、阶段进度与踩坑记录见 [PLAN.md](PLAN.md)。
