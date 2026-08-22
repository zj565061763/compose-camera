# AGENTS.md

本文是本仓库唯一的代理开发规范，适用于仓库根目录及全部子目录。

## 语言偏好

- 默认使用简体中文回复。
- 代码、命令、API 名称、类名和原始错误信息保持原文，必要时补充中文解释。
- 除非用户明确要求使用其他语言，否则不要切换语言。

## 修改前必读

- 修改 `lib` 中的相机生命周期、设备枚举、错误转发、帧回调、预览尺寸、SurfaceRequest 发布或坐标转换前，必须完整阅读 `REVIEW_NOTES.md`。该文件是行为不变量、CameraX 兼容限制和回归测试清单的权威记录。
- `README.md` 描述公开 API 的使用契约。公开行为、示例或依赖发生变化时，必须同步检查并更新。
- CameraX 当前固定为 1.5.3。升级 CameraX 时必须重新验证 cameraId 枚举和预览坐标转换。
- 不要提前删除 `PreviewTransformSnapshot.correctMirrorAxis()`；移除条件和上游修复链接记录在 `REVIEW_NOTES.md`。

## 构建环境

- 使用仓库提交的 Gradle Wrapper 8.11.1 和 JDK 17。
- Android Gradle Plugin 为 8.7.3，Kotlin 为 1.9.25。
- `app` 与 `lib` 的 `compileSdk` 为 35，`app` 的 `targetSdk` 为 35，两个模块的 `minSdk` 均为 23。
- 插件和依赖版本集中在 `gradle/libs.versions.toml`；仓库解析依赖时会先检查 `mavenLocal()`。

## 常用命令

所有命令从仓库根目录运行。

```bash
# 构建两个模块的 Debug 产物
./gradlew assembleDebug

# 快速编译库及其仪器测试源码
./gradlew :lib:compileDebugKotlin :lib:compileDebugAndroidTestKotlin

# 验证待发布 AAR
./gradlew :lib:assembleRelease

# 安装示例应用
./gradlew :app:installDebug

# 运行全部本地 JVM 测试
./gradlew test

# 运行库的全部仪器测试，需要已连接设备或模拟器
./gradlew :lib:connectedDebugAndroidTest

# Android lint
./gradlew lint
./gradlew :lib:lintDebug

# 发布 release 产物到本机 Maven 仓库
./gradlew :lib:publishToMavenLocal

# 检查补丁空白错误
git diff --check
```

运行单个测试：

```bash
# 单个 JVM 测试方法
./gradlew :app:testDebugUnitTest \
  --tests 'com.sd.demo.compose.camera.ExampleUnitTest.addition_isCorrect'

# 单个仪器测试类
./gradlew :lib:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='com.sd.lib.compose.camera.CameraPreviewStateTest'

# 单个仪器测试方法
./gradlew :lib:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class='com.sd.lib.compose.camera.CameraPreviewStateTest#createTransformToPreview_fromAnalysisThread_usesPublishedTransforms'
```

`lib` 的测试主要位于 `src/androidTest`。其中许多状态和矩阵测试依赖 Android 类型，因此通过 `AndroidJUnit4` 运行；真实相机集成测试会自动授予 CAMERA 权限，并按设备实际 CameraX 能力跳过不满足前提的场景。

## 模块职责

- `lib/` 是发布到 Maven Central 的 Compose CameraX 库，公开包为 `com.sd.lib.compose.camera`。所有可复用行为放在此模块。
- `app/` 是仅通过 `lib` 公开 API 工作的示例应用，用于演示运行时权限、设备枚举、按 Camera2 cameraId 切换和真机验证；不要把库逻辑放入该模块。
- 库 Manifest 合并 CAMERA 权限，并把 camera 和 autofocus 硬件声明为可选。调用方仍必须先完成运行时授权，再组合 `CameraPreview`。

## 公开 API 与状态边界

- `CameraPreview` 是入口 Composable；通过 nullable cameraId、`CameraMirrorMode` 和 `CameraFrameFormat` 配置预览。
- `CameraPreviewState` 属于单个正在组合的预览，保存协商后的 `previewResolution`、retry generation 和帧到预览的变换快照，不能在多个同时存在的预览间共享。
- `CameraDevicesState` 表示 CameraX 最近一次枚举到的设备列表、加载或错误状态和主动刷新入口，可由设备选择 UI 与多个预览共享；共享设备状态不代表支持不同 cameraId 同时预览。
- `CameraFrame` 只在同步 `onFrame` 回调期间持有有效 `ImageProxy`；允许跨回调保留的是轻量 `CameraFrameTransformToken`，不是图像本身。

## 设备发现与手动刷新

- `rememberCameraDevicesState()` 初始化 `ProcessCameraProvider`，并通过 `availableCameraInfos` 发布 CameraX 当前能够识别的设备。
- 库不注册 Camera2 `AvailabilityCallback` 或 CameraX `CameraPresenceListener`，不监听运行时设备变化，也不提供自动热插拔恢复。
- 首次 Provider 初始化或枚举失败后不做后台重试；主动调用 `CameraDevicesState.refresh()` 必须重新尝试失败操作。
- `CameraDevicesState.refresh()` 只更新设备列表；需要重新创建当前相机会话时调用 `CameraPreviewState.retry()`，它会同时触发一次设备刷新。
- 单个设备读取 `lensFacing` 失败时必须保留其 cameraId，并把 lens 发布为 `null`，不能让异常厂商 HAL 阻断整批设备。
- 公开列表和最终绑定以 CameraX 能力为准，不读取 `CameraManager.cameraIdList` 推测可绑定设备。
- 设备列表保持 CameraX 的原始顺序。`CameraPreview.cameraId == null` 时使用第一个摄像头；非空时精确选择，目标不存在时报告 `CAMERA_NOT_FOUND`，禁止隐式回退。

## Compose 外壳与 CameraX 会话

- `CameraPreview` 负责 Compose 尺寸、显示旋转、设备快照、错误订阅和 retry generation。
- 只有获得非零布局尺寸且设备枚举完成后，才在 `DisposableEffect` 中创建 `CameraPreviewController`。
- 普通布局尺寸、`contentScale` 或镜像模式变化只更新显示和坐标状态，不得重复绑定相机。
- `CameraPreviewController` 始终创建 `Preview`；仅在 `onFrame != null` 时创建 `ImageAnalysis` 和专用单线程 executor。
- 直接绑定 `Preview` 和可选的 `ImageAnalysis`，不设置固定 `ViewPort`，并保存 `bindToLifecycle()` 返回相机的 `CameraState`。
- 库不创建 CameraX `ConcurrentCamera` 会话，不支持不同 cameraId 的多个预览同时工作。
- CameraState 使用显式移除的永久 Observer，确保 Lifecycle 停止期间的终止错误仍能到达；LifecycleOwner 销毁时必须主动释放 Controller 资源。
- 只解绑当前 Controller 创建的 UseCase，禁止调用 `unbindAll()` 影响进程内其他 CameraX 使用方。
- `SurfaceRequest` 到达时立即轮换 request identity；变换发布和取消通过 publication gate 保证原子顺序。旧请求、已关闭会话或过期回调不得覆盖或清空新请求状态。

## 预览与帧坐标转换

- `SurfaceRequest` 的首次 `TransformationInfo` 提供预览缓冲区的 sensor-to-buffer 变换；`CameraXViewfinder` 通过 `MutableCoordinateTransformer` 返回 viewfinder-to-buffer 变换。
- `SurfaceRequest` 的 transformation listener 是单监听器槽位；取得首次变换后必须先清除 listener，再把请求交给 `CameraXViewfinder`。
- `CameraPreviewState` 以不可变快照和 `AtomicReference` 跨线程发布变换，分析线程无锁读取。
- 变换链为 `analysis buffer -> sensor -> preview buffer -> Compose viewfinder -> optional mirror`。
- 稳定 request identity 用于拒绝旧 SurfaceRequest；独立 transform identity 用于帧 token。新请求、有效布局尺寸、`contentScale` 或额外镜像变化必须使旧 token 失效。
- 异步分析结果写回 UI 前必须使用 `CameraPreviewState.isFrameTransformCurrent()` 校验 token。
- `previewResolution` 来自 `SurfaceRequest.resolution`，表示真实预览缓冲区尺寸，不是 Compose 布局尺寸，也不保证等于分析帧尺寸。
- `CameraXViewfinder` 已处理 CameraX 默认镜像。额外 `graphicsLayer` 镜像只能补偿 `CameraMirrorMode` 与 `TransformationInfo.isMirroring` 的差异。
- 不得删除 CameraX 1.5.x 四分之一转镜像轴兼容逻辑，除非依赖已经包含 `REVIEW_NOTES.md` 指定的上游修复并完成真机验证。

## 线程、错误与清理

- `onFrame` 在名为 `CameraPreview-Analysis` 的专用线程同步执行，采用 `STRATEGY_KEEP_ONLY_LATEST`。
- 组件必须在 `finally` 中关闭每个 `ImageProxy`。释放会丢弃尚未开始的帧，但不能中断已经开始的回调。
- `onError` 在主线程收到初始化、枚举、选择、CameraState、帧回调和普通清理异常。已开始帧的异常可能晚于组件离开组合。
- CameraX `CRITICAL` 状态会终止并完整清理会话；外部条件恢复后调用方可使用 `CameraPreviewState.retry()`。
- 清理必须尝试全部步骤并汇总普通 `Exception`：停止帧分发、移除 Lifecycle 和 CameraState Observer、清除 Analyzer、解绑自身 UseCase、清空预览状态、关闭 executor。
- 普通清理异常不能跳过后续步骤；致命 `Error` 不得作为业务异常吞掉。
- 所有异步回调在写状态前必须确认当前 request、generation 或会话仍有效；状态销毁前已进入主线程队列的设备回调也必须被丢弃。

## 测试布局与改动映射

- `CameraPreviewStateTest.kt` 覆盖矩阵、镜像、旋转、request 和 transform identity、SurfaceRequest 发布竞态、设备状态、错误订阅、帧关闭、错误映射及异常安全清理。
- `CameraPreviewIntegrationTest.kt` 使用真实 CameraX 和 Compose test rule，覆盖 YUV/RGBA、无帧分析资源、精确 cameraId、retry、布局 token、设备错误转发、组合释放和 Lifecycle 销毁。
- `CameraManifestTest.kt` 验证库合并后的相机硬件特性仍为可选。
- `app/src/main/java/.../SampleActivity.kt` 是权限、设备切换和手动真机验证入口。
- 纯计算逻辑优先放入 JVM 测试；依赖 CameraX、Android 图形类型、Lifecycle、权限或 Compose 集成的行为放入仪器测试。
- 新增公开行为或修复回归时必须扩充对应测试。异步测试使用有超时的等待，禁止用固定 `sleep` 掩盖竞态。
- 测试优先使用轻量 Fake，不使用 Mockito；断言使用 Google Truth；测试类显式声明 `AndroidJUnit4` runner。

## Kotlin 与代码约定

- Kotlin 与 Gradle Kotlin DSL 使用两空格缩进；多行声明保留尾随逗号，优先使用不可变状态。
- 类型和 Composable 使用 `PascalCase`，函数与属性使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。
- 使用 Kotlin 作用域函数时，如果调用结果没有被使用，且代码块只基于接收者执行操作或副作用，优先使用 `also`，不要使用 `let`。仅在需要使用代码块返回值进行转换或继续计算时使用 `let`。
- 每个文件原则上只包含一个主要公开类型，文件名与类型一致。
- 公开 API 提供简洁 KDoc，注释尽量简短易读，优先使用单行注释；较长说明使用多行注释。如果整个注释只有一行并且整行中没有其他语义标点符号，那么末尾不需要加上句号。
- 多行注释需要换行时，优先在逗号、分号、句号等语义标点符号后换行，避免在连续语义中间断行。
- 包名保持在 `com.sd.lib.compose.camera` 或 `com.sd.demo.compose.camera` 下。
- 仓库没有独立 formatter 或 Detekt 任务，静态检查入口为 Android lint。

## 提交与本地配置

- 提交标题使用简短、单主题中文，例如 `修复相机错误上报与资源释放`。
- PR 说明应包含动机、行为变化和实际执行的验证命令；公开 API 或依赖变化要明确标注，可见预览变化附截图或录屏。
- `app/template.jks` 是示例签名材料，不要替换为生产密钥。
- 机器专属 Android SDK 路径保留在未跟踪的 `local.properties` 中。
## 官方参考项目

- CameraX 官方示例本地路径：`/Users/zhengjun/development/project/android/github/camera-samples`
- 修改预览绑定、`SurfaceRequest`、`ImageAnalysis` 或 CameraX 生命周期逻辑时，优先对照该项目的最新实现
- 官方示例面向单页面 Demo，不要直接照搬 `unbindAll()` 或页面级相机开关；本库必须保留多实例共存、定向解绑和错误传播语义
