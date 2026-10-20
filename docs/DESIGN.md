# Drecorder 开发方案（Android 内部音频录制）

> 目标：只录制「应用媒体音频」（internal audio / playback capture），不录麦克风、不录屏幕、无画面。
> 文档状态：v1 设计（待用户确认 3 个决策点后进入计划阶段）。
> 事实核查日期：2026-10-17。所有平台行为均附来源或标注「需真机验证」。

---

## 0. 结论先行

| 问题 | 结论 |
| --- | --- |
| 无 root 能否只录应用媒体音频 | **能**。Android 10 (API 29) 起 `AudioPlaybackCapture` + `MediaProjection` 就是官方唯一合法路径 |
| 是否需要录屏 | **不需要**。只要 MediaProjection 令牌，**不调用 `createVirtualDisplay()`**，完全不产生画面 |
| 最大痛点 1 | Android 15 QPR1+ **锁屏即终止投屏会话**，录音随之断流 → 标准模式下的补偿见 §3.2（录制页保持屏幕常亮 + 会话终止即收尾） |
| 最大痛点 2 | 目标应用可主动禁止被捕获（Spotify / Chrome / SoundCloud）→ 只能给**静音检测告警**，无法绕过 |
| 分发 | GitHub 开源、自用，不上架商店 → 无 Play 合规负担；但隐私说明仍写进 README |

---

## 1. 需求与边界

### 1.1 在范围内
- 采集**其他 App 正在播放的音频**（媒体 / 游戏），保存为文件。
- 可限定「只录某个 App 的声音」（按 UID 过滤）。
- 记录列表、播放、重命名、分享、删除。
- 采集失败/静音的错误可见化（不产出"30 分钟静音文件"）。

### 1.2 明确不做（v1）
- 麦克风录音、屏幕画面录制、摄像头。
- 通话录音（VOIP/通话走 `AudioSource.VOICE_*`，被系统禁止，与本方案无关）。
- 音频实时转写、音频编辑、云同步。

### 1.3 硬约束（不可协商）
1. **必须用户同意一次 MediaProjection**（无 Shizuku 时每次会话一次）。
2. **迷你 SDK = API 29**（Android 10）。低于 10 没有 playback capture，唯一替代是 root + `REMOTE_SUBMIX`。
3. 采集**必须**是前台服务（`foregroundServiceType="mediaProjection"`），Android 14+ 还需要 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限，且**必须先 `startForeground()` 再 `getMediaProjection()`**。
4. 捕获到的音频是**其他 App 混音后的成品**：无法分离多 App、无法单声道剔除、无法拿到 DRM 受保护音轨。

---

## 2. 平台事实（决定架构的部分）

来源：开发者文档 [Capture video and audio playback](https://developer.android.com/media/platform/av-capture)（2026-10-01 更新）、[Media projection](https://developer.android.com/media/grow/media-projection)、[Android 17 Background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)（2026-10-01 更新）。

### 2.1 采集方（我们的 App）需要满足
- `RECORD_AUDIO` 权限（即使是回放捕获也要）。
- 需要 `MediaProjectionManager.createScreenCaptureIntent()` 的弹窗 + 用户同意。
- 采集方与被采集方必须在**同一用户配置文件**（同一 userId；工作资料里的 App 录不到）。
- 用 `AudioRecord.Builder().setAudioPlaybackCaptureConfig(config)`；音频源不设。

### 2.2 被采集方（目标 App）必须满足
- 播放的 usage ∈ `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN`；
- 且有效 capture policy = `ALLOW_CAPTURE_BY_ALL`。生效策略取**最严格**者：

| `android:allowAudioPlaybackCapture` | `ALLOW_CAPTURE_BY_ALL` | `ALLOW_CAPTURE_BY_SYSTEM` | `ALLOW_CAPTURE_BY_NONE` |
| --- | --- | --- | --- |
| true | 任意 App | 仅系统 | 不可捕获 |
| false | 仅系统 | 仅系统 | 不可捕获 |

> 结论：**这是 App 主动选择的结果，不是 bug，也不是我们能修的**。已知拒绝被捕获：Spotify、Google Chrome、SoundCloud。YouTube / YouTube Music / Apple Music / Twitch / Poweramp 等可录（[RootlessJamesDSP README](https://github.com/timschneeb/RootlessJamesDSP)）。
> 后果：**必须做静音检测**——被拒绝时 API 不报错，只给全 0 采样。

### 2.3 过滤规则（决定「只录某个 App」如何实现）
- `addMatchingUsage()` / `excludeUsage()` **二选一**，不能混用。
- `addMatchingUid()` / `excludeUid()` **二选一**，不能混用；可多次 add 多个 UID。

| 模式 | 配置 | 说明 |
| --- | --- | --- |
| 全设备媒体音频 | `addMatchingUsage(USAGE_MEDIA) + (USAGE_GAME) + (USAGE_UNKNOWN)` | 所有可捕获 App 混音 |
| 仅指定应用 | `addMatchingUid(uid1) [+ addMatchingUid(uid2)]` | **精确命中本 App 的核心诉求** |

`uid` 获取：`packageManager.getApplicationInfo(pkg, 0).uid`；Android 11+ 列应用清单需在 manifest 声明 `<queries>` 的 MAIN/LAUNCHER intent（等效启动器，默认放行），**避免申请 `QUERY_ALL_PACKAGES`**（Play 审核极严）。
注意：一个包可能有多个 UID（隔离进程、WebView 沙箱、分屏多实例），只加主 UID 可能漏音。

### 2.4 会话生命周期（最容易写错的地方）
- Android 14+：`createScreenCaptureIntent()` 返回的 Intent **只能 `getMediaProjection()` 一次**；`createVirtualDisplay()` 对同一 token **只能调用一次**，否则 `SecurityException`。我们**不调用** `createVirtualDisplay()`，因此天然规避。
- 必须 `registerCallback()` 监听 `onStop()`：用户在快捷设置里点掉、锁屏、进程被杀、别的投屏会话抢占，都会触发。**收到 onStop 必须立即停止录音并正常收尾文件**，否则会静默录出无限静音。
- Android 15 QPR1+：状态栏出现「投屏/录制」胶囊；**锁屏会自动终止会话**（§3.2 解法）。
  > **2026-10-18 实测修正**：vivo V2196A（Android 15 / API 35，有锁屏密码）锁屏约 15 s 后会话**没有**被终止——`AudioRecord` session 369 从 10:17:22.450 持续到 10:17:45.130 无中断，logcat 无 keyguard 终止记录，产物 550,419 B（22.7 s）完整收尾。说明该撤销行为**依机型/是否含 QPR1 而非仅依 API 级别**。结论：保留 §3.2 的常亮与「终止即收尾」作为兜底，但不要在 UI 上把「锁屏必断」说成所有 Android 15 的事实；换个 QPR1 机型仍需复测（DESIGN §10 第 1 条）。
- Android 15 QPR1+：投屏保护会**隐藏通知**；RootlessJamesDSP 的应对是提示用户打开开发者选项里的 "Disable screen share protection"（[#252](https://github.com/timschneeb/RootlessJamesDSP/issues/252)）。

### 2.5 Android 17（API 37）对后台音频的收紧
[Background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)：后台音频播放/请求焦点/音量 API 需要「可见 Activity 或不含 SHORT_SERVICE 的前台服务」，targetSdk 37 时更要求 FGS 具备 **while-in-use (WIU)** 能力，否则**静默失败**。
表里列出的是 `AudioTrack.write()` / `requestAudioFocus()` / 音量 API —— **不包含 `AudioRecord` 读取**；但：
- 我们的 FGS 必须**由前台用户操作启动**（点录音按钮时启动），不要在后台/开机时启动，否则拿不到 WIU；
- 我们对音量的任何操作（例如「提高目标 App 音量」）会被静默忽略，不要依赖；
- 用 `adb shell cmd audio set-enable-hardening throw` 可在开发时把静默失败变成崩溃，便于测试。

---

### 2.6 Android 14+ 的「共享或录制的范围」选择器（必须显式要整屏）
Android 14（API 34）起无参 `createScreenCaptureIntent()` 会弹一个「共享或录制的范围」选择器，默认停在**单个应用**，对录音场景是错误默认值：用户要的是设备上所有声音。
修法：`createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())`（API 34+），弹窗回到「整个屏幕」并由一条 `继续` 确认。授权范围只影响屏幕侧，**音频过滤仍由 `AudioPlaybackCaptureConfiguration` 的 UID/usage 决定**，两种来源模式都该用整屏令牌。
> 2026-10-18 真机验证：vivo V2196A / Android 15，弹窗显示「共享或录制的范围 → 整个屏幕」+「继续」，不再是单应用选择器。

### 2.7 Android 17 (API 37) 新增的「音频感知」授权 API（**必用**）
API 37 给 `MediaProjectionConfig` 增加了 `Builder.setAudioRequested(boolean)` / `isAudioRequested()`——文档原文含义：*若为 true，UI 组件应显示一个让用户启用音频共享的选项*。这是最接近「音频专用授权」的官方能力，**仍嵌在投屏对话框里，不是独立的录音授权**。
同时新增：`PROJECTION_SOURCE_DISPLAY=2` / `PROJECTION_SOURCE_APP=8` / `PROJECTION_SOURCE_APP_CONTENT=16`、`getProjectionSources()`、`getInitiallySelectedSource()`、`getRequesterHint()`、`isOwnAppContentProvided()`、`isSourceEnabled(int)`。
→ 设计动作：在 API ≥ 37 的机型上，用 `MediaProjectionConfig.Builder().setAudioRequested(true).build()` + `createScreenCaptureIntent(config)` 申请，让系统弹窗明确告知「本 App 要的是音频」；API 29–36 只能退回无参数的 `createScreenCaptureIntent()`，并在 App 内自行解释文案。
> 事实核查：截至 API 37，**仍然不存在「只授权音频、不授权屏幕」的授权流程**；`createConfigForDefaultDisplay()` / `createConfigForUserChoice()` 只改变**对话框里的可选项**（哪个显示器/哪个 App），不改变「是否弹窗」。
> 另：Android 15 起禁止从 `BOOT_COMPLETED` 启动 `mediaProjection` 类型的前台服务（targetSdk 15+），因此**不要做「开机自动开始录音」**。

---

## 3. 采集引擎（单引擎 + 已否决的备选）

### 3.1 引擎 A：标准模式（默认，零额外依赖）
`MediaProjection` + `AudioPlaybackCaptureConfiguration`。每次录音弹一次系统投屏授权。
- 优点：整机零额外依赖、无需 root（本版唯一实现的引擎）。
- 缺点：① 每次弹窗（文案是「开始录制或投射」而不是「录音」，用户会疑惑）；② **Android 15+ 有锁屏密码的设备，一锁屏录音就断**；③ 记不住授权。

### 3.2 标准模式下的锁屏补偿（**这是本项目的关键设计**）
用户已决定**不集成 Shizuku**，因此必须靠工程手段把「锁屏断录」的影响压到最低：

1. **录制页保持屏幕常亮**：`WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`（Compose 里 `window.addFlags`）。锁屏终止是由 **keyguard 被触发**引起的（AOSP 日志 `Stopped MediaProjection due to keyguard lock`），屏幕不灭 → keyguard 不介入 → 会话存活。**需真机验证**：无锁屏密码的机型、以及屏幕超时关闭后是否也终止。
2. **明确前置警告**：开始录制前，若设备设置了锁屏密码/指纹，弹一次性提示「本机未开启增强模式：**锁屏会结束录制**，建议录制期间保持屏幕点亮」。
3. **会话终止即收尾**：`onStop()` → 停止 `AudioRecord` → `MediaMuxer.stop()` → 落库 → 通知「录制已结束（系统终止）」。
4. **可选的屏幕超时兜底**：录制中把 `Settings.System.SCREEN_OFF_TIMEOUT` 临时调到最大需 `WRITE_SETTINGS` 特殊权限，**不做**（权限代价过大，收益被第 1 条覆盖）。

> 保留记录（未来可选，当前不实现）：一次性 `appops set <pkg> PROJECT_MEDIA allow` 同时解决「免弹窗」与「锁屏存活」——依据 AOSP `MediaProjectionManagerService` 的锁屏撤销逻辑，只有持有该 appop 的包被豁免（[RootlessJamesDSP #252](https://github.com/timschneeb/RootlessJamesDSP/issues/252)，用户实测有效；[AnyDesk 文档](https://support.anydesk.com/docs/anydesk-for-android)也让用户手敲这条命令）。它需要用户装 Shizuku 或连 adb，且 GrapheneOS 等加固 ROM 仍失败，因此本版放弃。

### 3.3 引擎 B：root 模式（**v1 不做**，列为 M5 可选）
`AudioRecord` + `MediaRecorder.AudioSource.REMOTE_SUBMIX`，需 `CAPTURE_AUDIO_OUTPUT`（signature|privileged，**Shizuku/adb 无法授予**，只有 root 能塞进 privapp 或直接 `su` 拉起进程）。
- 收益：不依赖 MediaProjection，不受锁屏策略影响；可录到部分拒绝捕获的 App。
- 代价：要 root；`REMOTE_SUBMIX` 路由行为各家 ROM 不一（可能抢走扬声器输出），需大量机型适配。
- **决策：v1 不实现**，但引擎接口预留（`CaptureEngine` 抽象），M5 视需求再加。

---

## 4. 数据通路

```
[目标 App] --播放--> AudioFlinger --(playback capture)--> AudioRecord(PCM16)
   → 环形缓冲 / 分块 20ms
   → 电平计 + 静音检测（滑动窗 RMS）
   → MediaCodec(AAC-LC 128~192kbps) → MediaMuxer(.m4a)
   → 可选：WAV 直写（无损调试用）/ FLAC
   → Room 记录元数据 + SAF/MediaStore 落盘
```

要点：
- 采样格式固定 **48000 Hz / 立体声 / ENCODING_PCM_16BIT**（44100 亦可）；非标准组合在部分 ROM 上直接拿不到数据 —— **需真机验证**。
- 写文件用 `MediaStore.Audio` 或 `ACTION_CREATE_DOCUMENT`(SAF)；上架 Play 走 MediaStore 更省事，API 29 起免存储权限。
- **静音检测（本方案的关键 UX）**：启动后前 ~8 秒若峰值 < 阈值（例如 -60 dBFS），弹「未检测到声音 · 可能该应用禁止被录制 · 请检查是否在播放」，并给出「继续录 / 停止」选项；不直接产出静音文件。
- **会话终止（onStop / 锁屏 / 用户撤销）**：立即 stop 编码器 → `MediaMuxer.stop()` → 落库 → 通知「录制已结束（系统终止）」。绝不能留着 `AudioRecord.read()` 空转。
- 编码失败降级：`MediaCodec` AAC 不可用（极少）→ 直接写 WAV。

### 4.1 输出格式

| 格式 | 容器 | 编码器 | 建议 |
| --- | --- | --- | --- |
| **AAC-LC** | M4A (`MUXER_OUTPUT_MPEG_4`) | 硬编，全机型 | **默认**，128/192/320 kbps 可选 |
| WAV | — | 无（PCM 直写） | 无损/调试，文件巨大 |
| FLAC / Opus | OGG (`MUXER_OUTPUT_OGG`, API 29+) | 编码器可用性依 ROM | v1.1 可选，需 `MediaCodecList` 运行时探测后再暴露给用户 |

---

## 5. 模块与代码骨架

### 5.1 仓库结构（保持小，别一上来搞多模块）
> 工具链已实测跑通，直接照用：**Gradle 9.6.1 + JDK 25.0.4（`D:\Android\Android Studio Preview\jbr`）+ AGP 9.4.0（内置 Kotlin，禁止 apply `org.jetbrains.kotlin.android`）+ `org.jetbrains.kotlin.plugin.compose` 2.2.10 + compileSdk 37 + buildToolsVersion 36.1.0 + minSdk 29 + targetSdk 36 + compose-bom 2026.09.00 + activity-compose 1.13.0 + core-ktx 1.19.1**；SDK 在 `D:\Android\Sdk`，`GRADLE_USER_HOME=D:\.gradle`；构建命令只能用 pwsh 通道。compileSdk 取 37 是有意的：只有 API 37 才有 `MediaProjectionConfig.Builder.setAudioRequested()`。
```
Drecorder/
├─ app/                          # 单 Activity + Compose
│  └─ src/main/java/.../
│     ├─ MainActivity.kt
│     ├─ ui/home/                # 功能选择页（见 §6）
│     ├─ ui/record/              # 录制页：电平、计时、暂停/停止
│     ├─ ui/library/             # 记录列表 / 播放（Media3）
│     ├─ ui/setup/               # 权限与 Shizuku 引导
│     └─ ui/settings/
├─ core-audio/                   # 采集引擎（无 Android UI 依赖，可单测）
│  ├─ CaptureEngine.kt           # interface: start/stop/level Flow/stopReason
│  ├─ ProjectionCaptureEngine.kt # 引擎 A / A+
│  ├─ RootCaptureEngine.kt       # 引擎 B（M5 占位）
│  ├─ encoders/{AacM4aWriter,WavWriter}.kt
│  └─ SilenceDetector.kt
└─ core-data/                    # Room + DataStore + MediaStore 落盘
```

### 5.2 引擎接口（先把契约钉死，三种引擎同形）
```kotlin
interface CaptureEngine {
    val levels: Flow<Float>            // 0f..1f 峰值，供 UI 波形
    val state: Flow<CaptureState>      // Idle/Preparing/Recording/Stopping/Error
    val stopReason: Flow<StopReason>   // UserStop / ProjectionRevoked / Locked / TargetSilent / Error
    suspend fun start(spec: CaptureSpec): Result<Unit>
    suspend fun stop(): RecordingHandle
}

data class CaptureSpec(
    val scope: CaptureScope,           // AllMedia | OnlyApps(uids: List<Int>)
    val format: AudioFormat,           // 48000 / stereo / PCM16
    val encoder: EncoderSpec,          // Aac(bitrate) | Wav | Flac
    val output: OutputTarget,          // MediaStore | SAF(uri)
)
```

### 5.3 采集核心片段（顺序即正确性）
```kotlin
// 1) 用户授权（Android 14+ 必须显式要整屏，否则弹窗默认停在「单个应用」，见 §2.6）
val intent = if (Build.VERSION.SDK_INT >= 34) {
    mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
} else {
    mpm.createScreenCaptureIntent()
}
launcher.launch(intent)

// 2) 先起前台服务（Android 14+ 必须在 getMediaProjection 之前）
ContextCompat.startForegroundService(ctx, Intent(ctx, CaptureService::class.java))
//   service: startForeground(id, notif, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

// 3) 拿 token（每个 Intent 只能用一次）
val projection = mpm.getMediaProjection(resultCode, data)
projection.registerCallback(object : MediaProjection.Callback() {
    override fun onStop() = engine.onProjectionRevoked()   // 必须处理
}, handler)

// 4) 回放捕获配置
val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
    .apply { when (spec.scope) {
        is AllMedia      -> { addMatchingUsage(USAGE_MEDIA); addMatchingUsage(USAGE_GAME); addMatchingUsage(USAGE_UNKNOWN) }
        is OnlyApps      -> spec.scope.uids.forEach { addMatchingUid(it) }
    } }.build()

// 5) AudioRecord —— 注意：不设置 audioSource
val record = AudioRecord.Builder()
    .setAudioFormat(AudioFormat.Builder()
        .setEncoding(ENCODING_PCM_16BIT)
        .setSampleRate(48_000)
        .setChannelMask(CHANNEL_IN_STEREO).build())
    .setBufferSizeInBytes(bufSize)
    .setAudioPlaybackCaptureConfig(cfg)
    .build()
// 6) 读 -> 电平/静音 -> MediaCodec(AAC) -> MediaMuxer(.m4a)
```

### 5.4 Manifest 关键声明
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- 后台悬浮窗：录制时长 + 停止；可选，拒绝不影响录制 -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<!-- 仅列出桌面应用，避免 QUERY_ALL_PACKAGES -->
<queries>
  <intent>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LAUNCHER" />
  </intent>
</queries>

<service android:name=".capture.CaptureService"
         android:exported="false"
         android:foregroundServiceType="mediaProjection" />
```
Shizuku（可选依赖，用 `rikka.shizuku:api` + `provider`，**编译期不强制**）：
打包时用 `shizuku-provider` AAR；`Shizuku.checkSelfPermission()` 通过后走 `Shizuku.newProcess(arrayOf("appops","set",pkg,"PROJECT_MEDIA","allow"),...)` 或 `ShizukuBinderWrapper` 直接调 `AppOpsManager`。

---

## 6. UI 方案（开源简约风，布局参考现有开源项目）

### 6.1 技术选型
Kotlin + **Jetpack Compose + Material 3**，单 Activity，无底部导航（功能只有 3 个，导航是噪音）。**固定 teal 配色**（2026-10-18 用户选定，放弃 Material You 动态取色：本 App 只有一个主操作，墙纸取的色可能让它失去对比度）+ 深色模式；无广告、无账号、不上传任何录音；**唯一网络用途是检查更新**（§11），且默认关闭。UI 基线取 Apache-2.0 的 `android/nowinandroid`，首页布局见 §6.2。
> 字体说明（2026-10-18）：不内置字体。设备把系统字体改成手写体/楷体时，App 内所有文字都会跟随——这是系统级设置，不是本 App 的排版问题；用户选择保持系统字体，避免 APK +15MB。

### 6.2 首页 = 录制台（2026-10-18 重做，替换第一版「卡片列表」）
第一版把来源做成两张高卡片、权限说明占首页最大一块、最近录制是纯文本列表，真机上一眼看不到能按的东西。现在的结构：

```
┌───────────────────────────────┐
│  Drecorder              ⚙️    │  TopAppBar
│  录制其他应用的媒体音频…        │  一句话说明
│   ┌─────────┬─────────────┐   │  ② 单选的来源分段控件
│   │ ✓ 全设备 │   指定应用   │   │     「指定应用」点了先开选择器
│   └─────────┴─────────────┘   │
│                               │
│          (  ●  )              │  ① 大圆按钮 = 唯一的录制入口
│          开始录制               │     录制中变红并显示 ■，点它回到录制页
│      ⓘ 为什么需要投屏授权？      │  ③ 一次性说明收进对话框
│  ┌──────────────┐ ┌─────────┐ │  ④ 最近录制 = 横向卡片（今天 10:17 · 00:25）
│  │ 今天 10:16 … │ │ …       │ │     点任一卡片进列表
└───────────────────────────────┘
```

- 来源二选一：**全设备**（默认）/ **指定应用**（打开选择器，可多选；选中后分段控件显示「N 个应用」）。
- 主按钮只有一个，颜色变化（teal → 红）与内部图形（● → ■）承担全部状态表达。
- 权限说明、锁屏提醒只出现在一次性对话框里，不占据首页。
- 列表页：扁平分隔行 + 行尾 ⋮（分享/重命名/删除），标题显示「今天 10:17」而不是文件名。
- 设置页：分区列表（格式/采样率/声道/码率/分段/保存位置/更新/关于），不是卡片堆。
- 系统返回（按钮或手势）在非首页时回到首页；录制中返回首页会留下「正在录制」横幅，避免回不去录制页。

### 6.3 参考项目清单（已逐个核对机制与许可，2026-10-17）
| 项目 | 机制 / 许可 | 借鉴点 |
| --- | --- | --- |
| [NoneBaiano/OpenRecorder](https://github.com/NoneBaiano/OpenRecorder) | (a) MediaProjection + AudioPlaybackCapture · GPL-3.0 · Android 10+ · F-Droid 在架 | **最接近的同类开源项目**：录屏+内部音频→MP4，可选 internal/mic/mixed；可排序的「录音列表 + 标签页」范式。其自述限制与我们一致：系统每次都要求确认、DRM/`FLAG_SECURE` 应用录出来是黑的/静音、内部音频只覆盖「允许被捕获」的应用 |
| [kuscher/studiosnap](https://github.com/kuscher/studiosnap) | (a) 同一个 MediaProjection 上做画面+系统音频 · MIT · 已 target Android 17 (API 37, JDK 21) · 无 INTERNET 权限 | **功能选择页的最佳参考**：浮动条 = 功能（截图/录制）× 来源；首页 = 状态 + 快捷操作 + 最近卡片。也是查 API 37 写法的最新样板 |
| [you-apps/RecordYou](https://github.com/you-apps/RecordYou) | 基座 App 无内部音频；内部音频要靠 Magisk 模块授予 `CAPTURE_AUDIO_OUTPUT` 走 `REMOTE_SUBMIX` · GPL-3.0 · 862★ · **已归档 (2024-08-07)** | Compose + MD3 的「录制器中心页 + 录音列表」UI 范本（代码仍可读，但已停更） |
| [timschneeb/RootlessJamesDSP](https://github.com/timschneeb/RootlessJamesDSP) | (c) Shizuku/adb 授予 `PROJECT_MEDIA` · GPL-3.0 · 1.7k★ · 上架 Play/F-Droid | 内部音频捕获的工程化边界、兼容性清单、`PROJECT_MEDIA` 与锁屏问题的完整排查记录（本版不集成，但**排错必读**） |
| [FossifyOrg/Voice-Recorder](https://github.com/FossifyOrg/Voice-Recorder) | **只录麦克风**（不是内部音频参考）· GPL-3.0 · 1020★ · 活跃 | 极简「列表 + FAB」布局与信息层级 |
| [zhanghai/MaterialFiles](https://github.com/zhanghai/materialfiles) | Views（非 Compose）· GPL-3.0 | 列表优先浏览 + 长按选择态 + 设置页即列表 |
| [android/nowinandroid](https://github.com/android/nowinandroid) | Kotlin + Compose · Apache-2.0 | Material 3 官方范式（**我们的 UI 基线**，许可也最干净） |
| [JunkFood02/Seal](https://github.com/junkfood02/Seal) | Compose + MD3 动态取色 · GPL-3.0 | 「单一任务 = 一张主卡片」的极简首页 |
| [kitsumed/ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) | 以 shell UID 运行 scrcpy-server · GPL-3.0 · 1767★ | 仅作「shell 路线」的存档参考（Android 17 上其自述状态为 unknown），本版不用 |

> **负面清单（别踩）**：`FossifyOrg/Voice-Recorder`、`axet/android-audio-recorder`（已迁 GitLab）、`Dimowner/AudioRecorder` 都**只录麦克风**，不含内部音频；`vijairaj/screencam` 仓库已 404。未找到任何基于 Xposed/VirtualXposed 的录音项目。
> **许可纪律（已定）**：本项目 **GPL-3.0**。理由：最值得抄的同类实现（OpenRecorder、RecordYou、JamesDSP、Fossify）全是 GPL-3.0，同许可下引用片段才合法；且本项目只自用+GitHub 开源，无商业约束。UI 基线取自 Apache-2.0 的 nowinandroid / Material3，可自由复用。**若日后想改成 Apache-2.0，必须在复制任何 GPL 代码之前决定。**

---

## 7. 里程碑

| 阶段 | 内容 | 验收（可观察） |
| --- | --- | --- |
| M0 骨架 | 工程 + Compose 首页 + 权限引导 + `ProjectionCaptureEngine` 最小闭环 | 录 YouTube 30s，产出可播放 m4a，电平图有反应 |
| M1 精确捕获 | App 选择器（`<queries>`）+ `addMatchingUid` + 静音检测告警 | 只录选中 App；未播放时 8s 内给出「未检测到声音」 |
| M2 增强模式 | Shizuku 集成 + `PROJECT_MEDIA` appop 授予 + 引导页 + 锁屏存活 | 有锁屏密码的 Android 15 机型：锁屏 10 分钟后仍有声；再次录音不弹窗 |
| M3 产品化 | 列表/播放(Media3)/重命名/分享/删除 + MediaStore 落盘 + 前台通知控制 + **检查更新与自更新（§11）** | 冷启动可管理 100 条录音无卡顿；从 v1.0 升到 v1.1 全流程走通 |
| M4 长时稳定 | 分段录制（默认 30 分钟一切片）、内存与句柄稳定性、异常恢复 | 连续录 2h，内存曲线平稳，进程被杀后文件可播 |
| M5 可选 | root 引擎（REMOTE_SUBMIX）、FLAC/Opus、定时录制 | 视需求决定是否做 |

---

## 8. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 目标 App `ALLOW_CAPTURE_BY_NONE`（Spotify/Chrome） | 全静音 | 静音检测 + 明确文案 + 兼容性清单（不做破解；ReVanced patch 属用户自担，不内置） |
| Android 15+ 锁屏杀会话 | 长录音中断 | 引导增强模式（appop）；未授权时 UI 明确警告「锁屏将结束录制」 |
| WebView 147 回归导致回放捕获静音 | 用户以为是我们的 bug | 已知 Google issue [#505414470](https://issuetracker.google.com/issues/505414470)/[#504175485](https://issuetracker.google.com/issues/504175485)（已 Won't Fix）：应用内文案提示「卸载 Android System WebView 更新」可规避 |
| Play 审核对 MediaProjection 的合规要求 | 下架 | 首次使用前**显著披露**「本 App 需要系统投屏授权以捕获媒体音频，不录制屏幕画面」；隐私政策说明只本地存储；不申请 `QUERY_ALL_PACKAGES` |
| Shizuku 在加固 ROM（GrapheneOS）仍失败 | 增强模式不可用 | 检测失败→自动退回引擎 A 并提示 |
| 只按 UID 过滤时漏音（隔离进程） | 录到部分声音 | 允许同时选多个 UID；文档说明 |
| `addMatchingUid` 与 usage 过滤不可混用 | 配置冲突 | 引擎层校验，UI 只暴露两种互斥模式 |

---

## 9. 已决策（2026-10-17）
| 决策点 | 结论 | 影响 |
| --- | --- | --- |
| 最低支持版本 | **minSdk 29（Android 10）** | 不做 root 引擎；API 29–36 用无参 `createScreenCaptureIntent()`，37+ 用 `setAudioRequested(true)` |
| 增强模式（Shizuku / `PROJECT_MEDIA`） | **不做** | 每个录音会话都要弹一次系统授权；有锁屏密码的 Android 15+ 机型，锁屏即结束录制 → 靠 §3.2 的「录制页保持屏幕常亮 + 前置警告 + onStop 收尾」补偿。`core-privileged` 模块取消 |
| 分发 | **GitHub 开源 + 自己用**，不上架 | 不做 Play 专项合规；README 里写清「需要投屏授权是因为 Android 未提供音频专用授权，本 App 不录制画面」与本地存储说明 |
| 许可 | **GPL-3.0** | 可直接参考/摘录 OpenRecorder、RecordYou、Fossify 等 GPL 实现；UI 基线用 Apache-2.0 的 nowinandroid |

残余风险（用户已接受）：无增强模式时「锁屏继续录音」在 Android 15+ 上无法保证，只能在 UI 上说清并让录制页保持常亮。

---

## 10. 需真机验证清单（写进 M0 的测试用例，勿凭文档假设）

1. 不调用 `createVirtualDisplay()` 时，状态栏是否仍显示投屏胶囊？Android 15+ 锁屏是否仍终止会话（无 appop）？
2. `appops set <pkg> PROJECT_MEDIA allow` 后：是否**同时**免弹窗 + 锁屏存活？覆盖 Android 14 / 15 / 16 / 17 各一台。
3. 48000/44100、立体声/单声道、PCM16 的哪些组合在所测 ROM 上真正可靠。
4. `MUXER_OUTPUT_OGG` + Opus / FLAC 编码器在目标机型上是否存在于 `MediaCodecList`。
5. `onStop()` 触发后 `MediaMuxer.stop()` 是否总能产出可播放文件（进程被杀除外）。
6. 工作资料（work profile）里的 App 是否确实录不到（文档称需同 userId）。

---

## 11. 检查更新与自更新（新增需求）

### 11.1 前提
已定「不上架商店、GitHub 开源、自己用」，因此**没有商店的自动更新**可依赖。可选三条路线：

| 方案 | 做法 | 代价 | 结论 |
| --- | --- | --- | --- |
| **A. 交给 Obtainium（零代码）** | 用户装 [Obtainium](https://github.com/ImranR98/Obtainium) 并添加本仓库 URL，它负责检测 Release → 下载 → 安装 | 本 App 零改动、零权限；但更新入口在另一个 App 里 | **v1 最省事的答案**，README 写明即可 |
| **B. 自建清单 + 应用内下载安装** | App 拉一个静态 `update.json` → 比 versionCode → `DownloadManager` 下载 → 校验 → 拉起系统安装器 | 需 `INTERNET` + `REQUEST_INSTALL_PACKAGES` + 约 300 行校验/安装代码 | **推荐实现**（M3），体验完整、完全可控 |
| C. GitHub API / Play In-App Updates | `GET /repos/:o/:r/releases/latest`，或 Play Core `AppUpdateManager` | 匿名 API 限 **60 次/小时/IP**、国内常不可达；无 Play 账号 → C 不可用 | 否 |

**为什么 B 用静态清单而不是 GitHub API**：不限流、可挂镜像、能表达「最低可升级版本」「渠道」「更新说明」，且清单本身可以放在 GitHub Pages 或任何静态托管上，换域名不用改 App。

### 11.2 `update.json` 契约（仓库内 `docs/update.json`，随 Releases 一起更新）
```json
{
  "versionCode": 12,
  "versionName": "1.2.3",
  "minSupportedVersionCode": 8,
  "apkUrl": "https://github.com/<owner>/Drecorder/releases/download/v1.2.3/drecorder-1.2.3.apk",
  "mirrors": ["https://<gh-proxy>/https://github.com/<owner>/Drecorder/releases/download/v1.2.3/drecorder-1.2.3.apk"],
  "sizeBytes": 3456789,
  "sha256": "<APK 的 sha256>",
  "signingCertSha256": "<签名证书 SHA-256，与已装包必须一致>",
  "publishedAt": "2026-10-17T00:00:00Z",
  "releaseNotes": "…",
  "force": false
}
```

### 11.3 Android 侧实现要点
- **权限**：`android.permission.INTERNET` + `android.permission.REQUEST_INSTALL_PACKAGES`（Android 8+ 安装 APK 必需；Play 禁止该权限，但我们不上架）。**这推翻了 §6.1 曾写的「不申请 INTERNET 权限」**；补偿：全 App 只有这一个网络调用，URL 可配置、开关可关闭，README 声明清楚。
- **检查时机**：① 设置页「检查更新」手动触发；② 冷启动静默检查，并用 DataStore 记 `lastCheckAt`，**限频 24 小时**（不要每次启动都请求）。
- **下载**：用 `DownloadManager`（自带断点续传、通知栏进度、省电策略友好），落到 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`。**不要自己写 HTTP 循环。**
- **校验三步，缺一不可**（否则等于把装机权交给 DNS/中间人）：
  1. 下载文件 `sha256` 必须等于清单值；
  2. `PackageManager.getPackageArchiveInfo(apkPath, GET_SIGNING_CERTIFICATES)` 取出的签名证书 SHA-256，必须等于 `packageManager.getPackageInfo(packageName, GET_SIGNING_CERTIFICATES)` 里**当前已安装包**的证书 SHA-256；
  3. `versionCode` 必须大于当前。
  三条全过才提示安装；任一条不过 → 删除文件并报错（不要「忽略继续」）。
- **安装**：`FileProvider` 暴露 URI → `Intent(ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(FLAG_GRANT_READ_URI_PERMISSION)`；首次会弹「允许安装未知应用」→ 引导到 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`。
- **签名密钥必须固定并异地备份**：自更新依赖「同签名才能覆盖安装」，keystore 丢失或更换 = 用户只能卸载重装（录音数据全丢）。README 里写明，keystore 不入库。
- **版本号纪律**：`versionCode` 单调递增整数，tag = `v<versionName>`；在 Gradle 里由 tag/versionName 自动推导 versionCode，禁止手工填。
- **发布流程**（五步）：`assembleRelease` → 算 sha256 → 重写 `docs/update.json` → `gh release create vX.Y.Z --notes-file …` → 提交 `update.json`。五步固化，避免漏发清单导致「有 Release 但 App 查不到更新」。发布脚本是本地工具，不随仓库分发。
- **手动兜底**：更新页永远提供「打开发布页」按钮（`ACTION_VIEW` 到 Releases），下载器或镜像失败时不会卡死。
- **UI**：设置页一行「版本 1.2.3（检查更新）」；检测到新版本时在首页顶部出现一张卡（版本号 + 更新说明 + 下载/安装 + 进度），**不用弹窗打断**。

### 11.4 验收（真机）
安装 v1.0 → 构造指向 v1.1 的 `update.json` → 冷启动检测到更新 → 下载 → sha256 通过 → **故意把 APK 改一个字节，必须报签名/校验失败而不是安装** → 还原后安装成功且已有录音数据保留 → 限频生效（24 小时内不重复请求，抓包或看日志确认）。

---

## 12. 录音文件编辑（2026-10-20 新增需求）

目标：录完之后能**删减**和**合并**，并且象普通录音机那样**看见波形**。

### 12.1 结论与硬约束

| 问题 | 结论 |
| --- | --- |
| 能不能不重编码就剪 | **能**。所有产物都是 AAC-LC in MP4，剪切 = 按样本表重排 `stts/stsz/stco`，**零音质损失** |
| 剪切精度 | AAC 帧 = 1024 samples，48 kHz 下 **±21 ms**。对节目剪辑足够；不做采样级精确剪切 |
| 合并的前提 | **同编码 / 同采样率 / 同声道数**。不一致时**明确拒绝并指出是哪几条**，不静默重编码 |
| 用什么实现 | `androidx.media3:media3-transformer:1.11.1`（与已有 `media3-exoplayer:1.11.1` 同版本线，Google Maven 存在），走 **transmux**（不转码） |
| 预览 | `CompositionPlayer`（media3 1.9.0+，支持单序列顺序预览），**与导出用同一个 `Composition` 对象** |
| 被否决 | 自己写 MP4 样本表编辑器（400–600 行 + `esds/stts` 边界，重复 Google 已踩过的坑）；解码→重编码（每编辑一次降一次音质，30 分钟音频要 1–3 分钟） |

### 12.2 删减模型：保留区间列表（用户 2026-10-20 选定方案 C）

```kotlin
data class KeepRange(val startMs: Long, val endMs: Long)   // [start, end)

data class SourceSpan(val file: File, val durationMs: Long, val keep: List<KeepRange>)

data class EditPlan(val sources: List<SourceSpan>)          // 合并 = 多个 source
```

一个 `EditPlan` 编译成 media3 的

```
EditedMediaItemSequence.withAudioFrom(
    sources.flatMap { src -> src.keep.map { clip(src.file, it) } }
)
```

其中 `clip()` = `EditedMediaItem.Builder(MediaItem.fromUri(...))`
`.setClippingConfiguration(ClippingConfiguration.Builder().setStartPositionMs(...).setEndPositionMs(...).build())`
`.setDurationUs(...)` ← **必须显式设置**，`CompositionPlayer` 文档要求 audio item 带 `duration`。

- **删除** = 从 `keep` 里挖掉 `[a,b)`，可能把一段裂成两段；段数不限，可反复删
- **恢复** = 把段加回 `keep`
- **合并** = 多条文件各自的 `keep`，继续往同一个 sequence 追加
- **全部删光** = 拒绝导出，提示「至少保留一段」
- **不支持**交叉淡入淡出（media3 明确列在 limitations），我们做硬切，不需要

> **必须调用 `Composition.Builder.setTransmuxAudio(true)`**。media3 的默认行为是：`Composition` 里有**多个 `MediaItem` 时，所有音轨默认被转码**（转码 = 音质降一级 + 30 分钟音频要 1–3 分钟）。只有显式开 transmux 才是「不转码、只重排样本」。这也是「零音质损失」这个卖点的技术开关所在。
> 代价：transmux 要求各 `MediaItem` 的音轨**采样格式一致**（同编码/同采样率/同声道），且不能带任何 audio effect——所以我们不提供淡入淡出。
> 参考：[Composition.Builder 文档](https://developer.android.com/reference/androidx/media3/transformer/Composition.Builder)

#### 纯函数边界（可单测，不需要设备）

`core-audio/src/main/java/dev/drecorder/audio/edit/EditPlanner.kt` 只做三件事，风格对齐现有 `RecordingDay` / `newestFirst()`：

- `normalize(keep, durationMs)`：排序 → 合并重叠/相邻 → 夹到 `[0, duration]` → 丢弃空区间
- `remove(keep, fromMs, toMs)`：挖洞，返回新列表
- `complement(keep, durationMs)`：反推被删区间，供波形灰显

**方案 C 的复杂度全部关在这三个纯函数里**，UI 只负责拖拽和画。

### 12.3 输出（派生文件，不覆盖原文件）

| 项 | 规则 |
| --- | --- |
| 命名 | `<原名>-edit-<n>.m4a`，`n` 从 1 递增，取第一个不存在的序号 |
| 原文件 | **永不修改、永不删除**。撤销 = 删掉派生文件，不需要 op log |
| 磁盘代价 | 30 分钟录音约 40 MB，可接受 |
| 原子性 | 先写 `<name>.tmp`，成功后才 rename；中断不留半截文件 |
| 静音标记 | 派生文件的 `.silent` 旁车**重新测定**（导出时本来就在解包），不继承源文件 |
| 库里的标识 | `RecordingLibrary` 读取派生标记，列表上显示「已编辑」 |

### 12.4 波形子系统

| 层 | 做法 |
| --- | --- |
| 解码 | `MediaExtractor` + `MediaCodec` 解成 PCM16，按桶取 min/max |
| 缓存 | `<baseName>.peaks` 存 `filesDir`（**不放录音目录**，避免被库扫成录音）；**100 桶/秒** |
| 体积 | 30 分钟 48 kHz 立体声 ≈ **352 KB**；首次解析 2–4 s（带进度），之后秒开 |
| 深度缩放 | 超出缓存分辨率时**只重新解码当前可见窗口**（几秒音频，约 50 ms），不存细粒度全量缓存 |
| 渲染 | Compose `Canvas`，每桶一根竖线，高度 = 峰值。**静音段自然成一条平线** |
| 时间轴 | **永远对应原始文件**；被删区间**变灰划掉留在原位**，可随时反悔恢复 |

#### 交互

- 横向拖动 = 平移；双指捏合 = 缩放（重新分桶）
- 在波形上拖出一段 = 选中，选中区反色
- 选中后底部出「删除此段 / 试听此段 / 恢复」
- 顶部时间刻度（`00:00 / 05:00 / …`）+ 跟随播放的播放头

#### 自动吸附（用户 2026-10-20 确认默认开启）

拖手柄时在 **±2 s** 内找能量最低点吸附，可开关。理由：用户不需要精确到毫秒，他要的是「去掉片头那段废话」，而静音段（切歌、暂停、换气）正好是天然切点。

### 12.5 预览

```kotlin
val player = CompositionPlayer.Builder(context).build()
player.setComposition(composition)   // 与导出完全相同的对象
player.prepare(); player.play()
```

- 新增 `app/.../playback/CompositionPreviewPlayer.kt`；现有 `RecordingPlayer.kt` **不动**（列表页普通播放保持原样）
- 编辑器里播放 = 直接听最终效果，接缝处即最终接缝
- `CompositionPlayer` 限制：`repeatMode` 只支持 `OFF` / `ALL`；只用单序列（纯音频），无需 `MultipleInputVideoGraph`

### 12.6 入口与并发约束

- `LibraryScreen` 行尾 ⋮ →「编辑」
- `LibraryScreen` **已有多选模式**（全选/批量删除）→ 直接在其中加「合并」，**不新造入口**
- 录制进行中（`CaptureService` 正在写同一目录）**禁止进编辑器**
- 编辑前释放 ExoPlayer 持有的文件句柄（部分 ROM 上不释放会导致 rename/delete 失败）

### 12.7 已知风险

1. **接缝处的编码器延迟**：AAC 有 priming samples，多段拼接理论上会引入 20–45 ms 间隙。真机上必须实际听接缝。
2. **media3 音频拼接有已知 issue**（[#1658](https://github.com/androidx/media/issues/1658)），纯音频场景是否受影响文档未说明，必须真机验证。
3. **APK 体积**：`media3-transformer` + 传递依赖，估计 +1~2 MB，未实测。
4. **风险 1/2 共同指向同一个动作**：先做技术探针（用一段真实录音跑通「裁剪 + 拼接 + 导出」），验完 §12.8 的 1/2/3 再动 UI。

### 12.8 验收（真机）

1. 30 分钟文件导入波形 **≤ 5 s**，主线程不卡顿
2. 三段保留（0–12 s / 35–48 s / 60–90 s）导出后：时长 **≈ 55 s ±0.1 s**，导出文件容器合法（duration / mp4a / 采样率 / 声道 / 1 track）
3. **导出耗时 < 10 s**（30 分钟源）。超过则说明 Transformer 悄悄转码了，必须查而不是接受
4. 导出文件与预览听感一致；接缝处无可闻断裂
5. 单测：`EditPlanner` 的 normalize / remove / complement 覆盖重叠、越界、全删、单点、逆序输入
6. 全部删光 → 拒绝导出且提示正确；格式不一致合并 → 报错并点名是哪几条

---

## 附：参考来源

- [Capture video and audio playback（AudioPlaybackCapture 全文）](https://developer.android.com/media/platform/av-capture)
- [Media projection（前台服务类型 / 锁屏自动停止 / 状态栏胶囊）](https://developer.android.com/media/grow/media-projection)
- [Android 17 Background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)
- [RootlessJamesDSP（Shizuku + 内部音频捕获的成熟实现）](https://github.com/timschneeb/RootlessJamesDSP) / [锁屏问题与 PROJECT_MEDIA 结论 #252](https://github.com/timschneeb/RootlessJamesDSP/issues/252)
- [AnyDesk：`adb shell appops set ... PROJECT_MEDIA allow`](https://support.anydesk.com/docs/anydesk-for-android)
- [回放捕获静音回归 #504175485](https://issuetracker.google.com/issues/504175485)
- [Restricted screen reading（Android 10 起故意取消特权 App 的静默屏幕捕获，并要求每次弹窗）](https://source.android.com/docs/core/permissions/restricted-screen-reading)
- [Obtainium（第三方 GitHub 更新器，§11 方案 A）](https://github.com/ImranR98/Obtainium)
