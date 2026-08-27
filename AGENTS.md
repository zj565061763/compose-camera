# AGENTS.md

本文是本仓库唯一的代理开发规范，适用于仓库根目录及全部子目录。

## 语言偏好

- 默认使用简体中文回复。
- 代码、命令、API 名称、类名和原始错误信息保持原文，必要时补充中文解释。
- 除非用户明确要求使用其他语言，否则不要切换语言。

## 修改前必读

- 修改 `lib` 中的相机生命周期、设备枚举、错误转发、帧回调、预览尺寸、旋转、镜像或坐标转换前，必须完整阅读 `REVIEW_NOTES.md`。该文件是行为不变量和回归测试清单的权威记录。
- `README.md` 描述公开 API 的使用契约。公开行为、示例或依赖发生变化时，必须同步检查并更新。
- 当前内部使用 Android 平台相机 API，公开 API 不得暴露具体后端类型；替换相机后端时必须重新验证 cameraId、帧数据、旋转、镜像和预览坐标转换。

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
  -Pandroid.testInstrumentationRunnerArguments.class='com.sd.lib.compose.camera.CameraPreviewStateTest#createTransformToPreview_quarterTurnMapsRawCoordinates'
```

`lib` 的测试主要位于 `src/androidTest`。状态和矩阵测试依赖 Android 类型，因此通过 `AndroidJUnit4` 运行；真实相机集成测试会自动授予 CAMERA 权限，并按设备能力跳过没有相机的场景。

## 模块职责

- `lib/` 是发布到 Maven Central 的 Compose 相机库，公开包为 `com.sd.lib.compose.camera`。所有可复用行为放在此模块。
- `app/` 只通过 `lib` 公开 API 工作，用于演示运行时权限、设备枚举、cameraId 切换、镜像切换和真机验证；不要把库逻辑放入该模块。
- 库 Manifest 合并 CAMERA 权限，并把 camera 和 autofocus 硬件声明为可选。调用方仍必须先完成运行时授权，再组合 `CameraPreview`。

## 公开 API 与状态边界

- `CameraPreview` 是入口 Composable；通过 nullable cameraId、`CameraMirrorMode`、`ContentScale` 和显示旋转配置预览。
- `cameraId` 对调用方是不透明的 `String`，不得公开当前后端的数字 ID 语义或列表排序契约。
- `CameraPreviewState` 属于单个正在组合的预览，保存 `previewResolution`、retry generation 和帧到预览的变换快照，不能在多个同时存在的预览间共享。
- `CameraDevicesState` 表示最近一次枚举到的设备列表、加载或错误状态和主动刷新入口，可由设备选择 UI 与多个预览共享；共享设备状态不代表支持不同 cameraId 同时预览。
- `CameraFrame.data` 只保证在同步 `onFrame` 回调期间有效；允许跨回调保留的是数据副本、独立 `Bitmap` 或轻量 `CameraFrameTransformToken`。
- `CameraFrame` 只能在 NV21 数据、宽高和旋转都有效时创建；公开的 `toBitmap()` 转换失败时返回 `null`。

## 设备发现与手动刷新

- `rememberCameraDevicesState()` 首次组合时枚举一次设备，不监听运行时设备变化，也不提供自动热插拔恢复。
- 首次枚举失败后不做后台重试；主动调用 `CameraDevicesState.refresh()` 必须重新尝试枚举。
- `CameraDevicesState.refresh()` 只更新设备列表；需要重新创建当前相机会话时调用 `CameraPreviewState.retry()`，它会同时触发一次设备刷新。
- 单个设备读取镜头方向失败时必须保留其 cameraId，并把 lens 发布为 `null`，不能让异常厂商 HAL 阻断整批设备。
- 设备列表保持平台原始顺序。`CameraPreview.cameraId == null` 时使用第一个摄像头；非空时精确选择，目标不存在时报告 `CAMERA_NOT_FOUND`，禁止隐式回退。

## Compose 外壳与相机会话

- `CameraPreview` 负责 Compose 尺寸、显示旋转、设备快照、错误订阅和 retry generation。
- 只有获得非零布局尺寸且设备枚举完成后，才创建 `CameraPreviewController`。
- 会话必须同时受 Lifecycle 和 `TextureView.SurfaceTexture` 生命周期约束；Lifecycle 停止、Surface 销毁或组件释放时关闭相机。
- Surface 销毁时必须先在相机线程停止会话，再释放 `SurfaceTexture`。
- 普通布局尺寸、`contentScale`、镜像模式和用户 lambda 变化只更新显示、坐标或回调引用，不得重复打开相机。
- displayRotation、cameraId、帧回调启用状态和 retry generation 变化需要重建会话。
- 只有 `onFrame != null` 时才创建回调缓冲区和专用单线程 executor。
- Controller 在 `CameraPreview-Camera` 专用线程执行全部相机会话操作，只释放自己打开的相机和创建的线程，禁止影响进程内其他相机使用方。
- 库不协调并发相机会话，不支持不同 cameraId 同时预览。

## 预览与帧坐标转换

- 参数设置后必须读取设备实际采用的 preview format 和 preview size；启用帧回调时格式必须是 NV21，实际尺寸用于发布 `previewResolution`，它表示原始帧缓冲区尺寸而不是 Compose 布局尺寸。
- `TextureView` 必须按旋转后的缓冲区比例和 `ContentScale` 布局，禁止直接拉伸到 Compose 区域。
- 前置摄像头的平台预览显示方向和原始帧旋转角度必须分别计算，禁止把镜像补偿后的显示方向用于帧数据或坐标变换。
- 平台默认镜像前置预览；额外 `graphicsLayer` 镜像只用于达到 `CameraMirrorMode` 指定的目标状态。
- `CameraPreviewState` 以不可变快照和 `AtomicReference` 跨线程发布变换，分析线程无锁读取。
- 变换链为 `raw frame -> display rotation -> ContentScale -> target mirror`。
- 新会话、有效布局尺寸、`contentScale` 或目标镜像变化必须使旧 transform token 失效。
- 异步分析结果写回 UI 前必须使用 `CameraPreviewState.isFrameTransformCurrent()` 校验 token。

## 线程、错误与清理

- `onFrame` 在名为 `CameraPreview-Analysis` 的专用线程同步执行，只保留正在处理的帧和最新待处理帧。
- 相机打开、配置、预览、对焦、回调缓冲区归还和释放统一在 `CameraPreview-Camera` 专用线程执行。
- 优先使用连续对焦模式；只支持 `FOCUS_MODE_AUTO` 时，每秒重新触发一次对焦，并在会话停止时取消任务。
- 每个相机回调缓冲区都必须在 `finally` 中归还。释放会丢弃尚未开始的帧，但不能中断已经开始的回调。
- NV21 缓冲区必须在创建 `CameraFrame` 前验证正偶数宽高、整数溢出和最小数据长度。
- `onError` 在主线程收到设备枚举、选择、打开、配置、运行、帧回调和普通清理异常。已开始帧的异常可能晚于组件离开组合。
- 清理必须尝试全部普通步骤并汇总普通 `Exception`：停止回调、清除错误监听、停止预览、释放相机、清空会话状态和关闭 executor。
- 普通清理异常不能跳过后续步骤；致命 `Error` 不得作为业务异常吞掉。
- 所有异步回调在写状态前必须确认当前 transform identity、会话或 loader 仍有效。

## 测试布局与改动映射

- `CameraPreviewStateTest.kt` 覆盖矩阵、前后摄像头旋转、镜像、transform identity、预览尺寸选择、对焦调度、NV21、帧缓冲区、错误聚合和异常安全清理。
- `CameraPreviewIntegrationTest.kt` 使用真实相机和 Compose test rule，覆盖 NV21、Bitmap 转换、旋转、镜像、布局变换、retry、cameraId 选择与错误、Lifecycle 清理和工作线程。
- `CameraManifestTest.kt` 验证库合并后的相机硬件特性仍为可选。
- `app/src/main/java/.../SampleActivity.kt` 是权限、设备切换、镜像切换和手动真机验证入口。
- 纯计算逻辑优先放入 JVM 测试；依赖 Android 图形类型、Lifecycle、权限或 Compose 集成的行为放入仪器测试。
- 新增公开行为或修复回归时必须扩充对应测试。异步测试使用有超时的等待，禁止用固定 `sleep` 掩盖竞态。
- 测试优先使用轻量 Fake，不使用 Mockito；断言使用 Google Truth；测试类显式声明 `AndroidJUnit4` runner。

## Kotlin 与代码约定

- Kotlin 与 Gradle Kotlin DSL 使用两空格缩进；多行声明保留尾随逗号，优先使用不可变状态。
- 类型和 Composable 使用 `PascalCase`，函数与属性使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。
- 使用 Kotlin 作用域函数时，如果调用结果没有被使用，且代码块只基于接收者执行操作或副作用，优先使用 `also`，不要使用 `let`。仅在需要使用代码块返回值进行转换或继续计算时使用 `let`。
- 每个文件原则上只包含一个主要公开类型，文件名与类型一致。
- 代码换行应以语义清晰和可读性为准，不要仅因代码长度机械换行；一行表达更清晰时保留单行写法，并优先保留用户主动调整过的排版，除非格式检查或项目规范明确要求修改。
- 公开 API 提供简洁 KDoc，注释尽量简短易读，优先使用单行注释；较长说明使用多行注释。如果整个注释只有一行并且整行中没有其他语义标点符号，那么末尾不需要加上句号。
- 多行注释需要换行时，优先在逗号、分号、句号等语义标点符号后换行，避免在连续语义中间断行。
- 包名保持在 `com.sd.lib.compose.camera` 或 `com.sd.demo.compose.camera` 下。
- 仓库没有独立 formatter 或 Detekt 任务，静态检查入口为 Android lint。

## 提交与本地配置

- 提交标题使用简短、单主题中文，例如 `重构相机预览与帧回调`。
- PR 说明应包含动机、行为变化和实际执行的验证命令；公开 API 或依赖变化要明确标注，可见预览变化附截图或录屏。
- `app/template.jks` 是示例签名材料，不要替换为生产密钥。
- 机器专属 Android SDK 路径保留在未跟踪的 `local.properties` 中。
