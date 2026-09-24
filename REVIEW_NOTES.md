# Camera Library Review Notes

本文记录截至 2026-09-25 已确认的行为不变量、回归测试映射和验证清单，是 `lib` 行为约束的唯一权威记录。

- 修改 `lib` 前必须完整阅读本文件，避免重新引入生命周期、坐标、帧缓冲区和资源释放问题。
- 修改行为时同步更新本文件和对应测试。

## 产品边界

- 内部使用 CameraX 1.5.3（`camera-core`、`camera-camera2`、`camera-lifecycle`、`camera-view`），依赖声明为 `implementation`，公开 API 不暴露任何 CameraX 类型。
- 替换相机后端时必须重新验证 cameraId、帧数据、旋转、镜像和预览坐标转换。
- 库不监听运行时设备变化，也不提供自动热插拔恢复。
- 库不协调并发相机会话，不支持不同 `cameraId` 同时预览。
- `cameraId` 是不透明的 `String`，公开 API 和文档不得承诺数字 ID 语义或列表排序契约。
- 当前实现使用 Camera2 cameraId，调用方不得解析、排序或持久依赖其格式。
- `cameraId` 是否能跨重启保持稳定由设备实现决定。
- 库 Manifest 声明 `CAMERA` 权限，并把 camera 和 autofocus 硬件声明为可选。
- 应用负责运行时授权，只能在授权后组合 `CameraPreview`。

## 公开 API

| API | 职责 |
|---|---|
| `CameraPreview` | 入口 Composable，接收 `state`、`devicesState`、`cameraId`、`CameraMirrorMode`、`ContentScale`、`displayRotation`、`onError` 和 `frameProcessor` |
| `CameraPreviewState` | 单个预览独占的状态，提供 `previewResolution`、`failure`、`takeScreenshot()`、`requestFocus()`、`retry()` 和帧坐标变换 |
| `CameraDevicesState` | 最近一次枚举的设备列表、加载或错误状态和 `refresh()`，可由设备选择 UI 与多个预览共享 |
| `FrameProcessor` | `None`（默认）、`Preview`（原始 NV21 帧）、`PreviewSampled`（按间隔截取预览区域） |
| `CameraFrameTransformToken` | 可跨回调保留的轻量令牌，用于校验异步结果是否仍属于当前变换 |

### 预览与设备选择

- `CameraPreview.cameraId == null` 时使用设备列表第一项；非空时精确选择。
- 指定的 `cameraId` 不存在时报告 `CAMERA_NOT_FOUND`，禁止隐式回退。
- `CameraPreviewState` 不能在多个同时存在的预览间共享。
- 共享 `CameraDevicesState` 不代表支持多个相机会话同时运行。
- `displayRotation == null` 时监听当前 View 所在显示器的旋转；显式值必须是 `Surface.ROTATION_*`，同时作用于预览显示和帧旋转。
- `CameraPreview.mirrorMode` 只影响预览和坐标矩阵，不修改帧数据。

### 状态与错误

- 启用 `FrameProcessor.Preview` 时，`previewResolution` 是原始帧分辨率；否则是预览流分辨率。
- `previewResolution` 在会话首帧上屏时发布，会话停止后为 `IntSize.Zero`。
- `failure` 只保存需要重新枚举设备或重建相机会话的当前故障。
- 其他普通异常只通过 `CameraPreview.onError` 报告。

### 帧数据

- `Preview` 输出 NV21 原始帧，`PreviewSampled` 按间隔输出预览区域截图。
- `CameraFrame.Preview.data` 和 `CameraFrame.PreviewSampled.data` 只保证在同步回调期间有效。
- 允许跨回调保留的是数据副本、独立 `Bitmap` 或 `CameraFrameTransformToken`。
- `CameraFrame.Preview` 只在 NV21 数据和宽高有效时创建。
- `CameraFrame.Preview.toBitmap()` 返回未旋转的独立图片，转换失败时返回 `null`，不抛出普通转换异常。
- `PreviewSampled.data` 已应用显示旋转和 `ContentScale`，未由相机内容覆盖的区域透明。
- `PreviewSampled.data` 不包含平台镜像、目标镜像或预览上层内容，`rotationDegrees` 固定为 `0`。
- `PreviewSampled.data` 在回调结束后被回收；`createTransformToPreview()` 使用创建帧时记录的尺寸，不得再读取该 Bitmap。
- 无效 token 的 `isSameTransform()` 始终返回 `false`，包括与自身比较。

### 截图与对焦

- `takeScreenshot()` 必须在主线程调用。
- 截图已应用显示旋转、`ContentScale` 和参数指定的镜像模式，不包含预览上层内容。
- `takeScreenshot(mirrorMode)` 的参数只决定返回 Bitmap 的镜像状态。
- 预览未产生有效帧、已离开组合或发生普通截图异常时返回 `null`。
- 截图异常同时通过 `CameraPreview.onError` 报告。
- 成功返回的独立 Bitmap 由调用方负责回收。
- `requestFocus()` 必须在主线程调用。
- `requestFocus()` 仅在当前摄像头只支持单次自动对焦时触发请求。
- 连续对焦、设备不支持单次自动对焦、预览未运行或已离开组合时，`requestFocus()` 安全忽略。

## 设备枚举与手动恢复

- `rememberCameraDevicesState()` 首次组合时开始枚举，`ProcessCameraProvider` 就绪前 `isLoading` 为 `true`。
- 之后只有调用 `CameraDevicesState.refresh()`，或关联预览的 `CameraPreviewState.retry()` 触发刷新时才重新枚举。
- 设备列表只包含 CameraX 能打开的摄像头，并按 `CameraManager.cameraIdList` 的平台顺序排列。
- 设备选择与设备列表使用同一个排序函数，`cameraId == null` 时两者选中同一个摄像头。
- 镜头方向不是前置或后置时，lens 发布为 `null`。
- 枚举失败后结束 loading 并发布错误，不做后台重试。
- 每次枚举完成都递增内部版本号，连续失败使用同一个 `Throwable` 实例时预览也能再次收到错误。
- 同时存在多次刷新时只发布最近一次请求的结果。
- 成功枚举必须清除活动错误，避免后来组合的预览收到陈旧错误。
- `CameraDevicesState.refresh()` 只更新共享设备列表；`CameraPreviewState.retry()` 还会重新创建当前预览会话。
- `retry()` 必须立即清除当前故障，旧会话在 retry 之后报告的故障不得写入 `failure`。
- loader 关闭后不得继续发布设备或错误状态。

## Compose 外壳与相机会话

- `CameraPreview` 负责 Compose 尺寸、显示旋转、设备快照、错误订阅和 retry generation。
- `CameraSession` 表示一次 CameraX 绑定，除帧回调外全部在主线程执行，关闭后忽略所有迟到回调。
- 只有获得非零 Compose 布局宽高且设备首次枚举完成后，才创建 `CameraSession`。
- 零尺寸是正常的隐藏或布局过渡，不报告错误。
- 会话通过 `bindToLifecycle` 交给 CameraX 管理，Lifecycle 进入 `STARTED` 时打开相机，进入 `STOPPED` 时关闭。
- 绑定前 Lifecycle 已销毁时直接跳过，CameraX 会静默忽略这类绑定。
- 关闭会话只解绑本会话创建的用例，不得影响进程内其他 CameraX 使用方。
- `LifecycleOwner`、显示旋转、cameraId、帧处理模式、retry generation 或相关设备快照变化会重建会话。
- 普通布局尺寸、`contentScale`、镜像模式、处理间隔、用户 lambda 或 `onError` lambda 实例变化只更新显示、坐标或回调引用，不得重建会话。
- 可恢复的相机错误由 CameraX 自动重连；外部条件恢复后可以调用 `CameraPreviewState.retry()` 重新绑定。
- 截图入口只在对应 `CameraPreview` 组合期间有效，退出组合后必须解除。
- 对焦入口只在对应会话存在期间有效，会话替换或退出组合后必须按入口身份解除。
- `CameraPreviewState.reset()` 必须同时清零 retry generation，避免同一状态实例再次组合时重放已消费的 `retry()`。

### 首帧

- 以 `PreviewView.previewStreamState` 变为 `STREAMING` 作为会话首帧上屏。
- 重新绑定时 `previewStreamState` 可能仍保留上一个会话的 `STREAMING`，新会话必须先观察到非 `STREAMING` 状态，之后的 `STREAMING` 才算首帧。
- 首帧上屏前不得发布可用的 transform identity，首帧前创建的 token 在首帧后也保持无效。
- 首帧上屏时才读取 `ResolutionInfo`，发布会话布局、`previewResolution` 并清除会话故障。
- 预览流停止时清除当前会话，但保留最近显示的会话布局，使旧画面在新会话首帧前维持原位置和比例。

## 预览布局、尺寸与镜像

- `PreviewView` 使用 `COMPATIBLE`（TextureView）实现和 `FILL_CENTER`，以支持自定义旋转、截图和父布局裁剪。
- `PreviewView` 按 `ContentScale` 计算出的内容区域布局，并保持相机内容宽高比，因此 `PreviewView` 自身不再裁剪或留边。
- 内容区域在 Compose 预览区域中居中，超出部分由外层裁剪。
- 非等比缩放和额外镜像通过 Compose 图层完成，额外镜像围绕预览区域中心水平翻转。
- 布局位置在测量阶段直接计算，有效布局尺寸变化时同步更新坐标快照，禁止依赖下一次重组纠正当前帧。
- 预览和分析用例使用同一个宽高比，在 4:3 与 16:9 中选择旋转后最接近 Compose 区域的比例，比例相同时保留 4:3。
- 预览使用 CameraX 默认分辨率；原始帧需要逐帧转换 NV21，分辨率上限为 `1280 × 960`（4:3）或 `1280 × 720`（16:9）。
- 用例组使用 `ViewPort.FIT`，把预览和原始帧裁剪到两者共同的视野，设备回退到不同比例时坐标仍然对齐。
- 平台镜像以 CameraX 报告的前置镜头为准，额外镜像只用于补偿平台状态与 `CameraMirrorMode` 目标状态的差异。
- 前置摄像头的原始帧旋转角度与预览显示方向分别由 CameraX 计算，帧数据不包含平台镜像。

## 对焦

- 使用 CameraX 默认对焦策略，设备支持时为连续对焦。
- 设备只支持 `CONTROL_AF_MODE_AUTO` 时，在每个会话首帧对画面中心触发一次单次对焦，之后仅响应 `CameraPreviewState.requestFocus()`。
- 新的对焦请求会取消进行中的请求，被取消不视为错误；其他对焦失败通过 `onError` 报告。

## 坐标变换

- `CameraPreviewState` 以不可变快照和 `AtomicReference` 跨线程发布变换，分析线程无锁读取，只在主线程整体替换。
- 原始帧变换链为 `raw frame -> crop rect -> rotation -> ContentScale -> target mirror`。
- 会话、有效布局尺寸、`contentScale` 或目标镜像变化必须使旧 transform token 失效。
- 布局为零或无法生成 geometry 时不得发布 transform identity，`isFrameTransformCurrent()` 必须返回 `false`。
- 异步分析结果写回 UI 前必须使用 `CameraPreviewState.isFrameTransformCurrent()` 校验 token。
- `createTransformToPreview()` 对 `Preview` 输入原始缓冲区坐标；检测器输出已按 `rotationDegrees` 旋转时，调用方需先转回原始缓冲区坐标。
- `createTransformToPreview()` 对 `PreviewSampled` 输入 Bitmap 坐标，只补充目标镜像。

## 帧线程与缓冲区

- 只有使用原始 `Preview` 帧处理时才绑定 `ImageAnalysis`，背压策略为 `STRATEGY_KEEP_ONLY_LATEST`。
- `CameraPreview-Analysis` 单线程在首个任务到达时懒创建，由单个正在组合的 `CameraPreview` 在多次会话间复用，退出组合后关闭。
- 原始帧和采样帧回调都在 `CameraPreview-Analysis` 线程同步执行。
- 原始帧从 `YUV_420_888` 按行列步长转换为 NV21，写入会话复用的缓冲区；`ImageProxy` 在回调结束后关闭。
- NV21 帧宽高必须为正偶数，所需字节数必须能安全表示为 `Int`，不符合条件的帧直接丢弃。
- `PreviewSampled` 在主线程按间隔截取 `PreviewView`，上一帧仍在处理时跳过本次采样，只处理最新画面。
- 采样帧的 Bitmap 在回调结束后回收。
- 会话关闭后不再开始新的帧回调；已经开始的回调不会被等待或中断，可能在关闭后完成。
- 帧回调中的普通异常通过 `onError` 报告，致命 `Error` 不得作为业务异常吞掉。

## 截图实现

- 截图源为 `PreviewView.getBitmap()`，它是预览控件显示的画面，包含平台镜像。
- 截图源绘制到 Compose 预览区域尺寸的 Bitmap 中对应的内容区域，完成偏移、裁剪和非等比缩放，区域外透明。
- 截图源与预览区域完全一致且不需要翻转时直接转交，禁止无条件复制第二张 Bitmap。
- 截图请求和渲染在同一次主线程调用中完成，必须使用同一份状态快照。
- `PreviewSampled` 始终移除平台镜像。
- `takeScreenshot()` 比较平台镜像和参数要求的目标镜像，只在两者不同时翻转返回像素。

## 错误与清理

- `onError` 通过主线程消息队列调用，确保用户回调异常位于库内部 `try/catch` 之外。
- 退出组合后丢弃尚未送达的 `onError`。
- `onError` 接收设备枚举、选择、打开、配置、运行、帧回调、对焦和截图异常。
- 设备枚举、相机选择、`ProcessCameraProvider` 初始化、绑定和 CameraX 相机状态错误必须同时写入 `CameraPreviewState.failure`。
- 同时存在时，`failure` 优先返回会话故障，其次返回设备枚举故障。
- 会话故障在新会话首帧上屏或调用 `retry()` 后清除。
- 设备枚举故障在枚举成功或调用 `retry()` 后清除，新会话首帧不清除它。
- 对焦、帧处理和截图异常不得写入 `failure`，只通过 `onError` 作为诊断事件报告。
- `CameraPreviewException` 区分无设备、目标不存在、打开或配置失败以及运行错误。
- 绑定失败报告为 `CAMERA_OPEN_FAILED` 并保留 cause。
- CameraX 相机状态错误在 `OPEN` 或 `CLOSING` 状态报告为 `CAMERA_RUNTIME_ERROR`，其余状态报告为 `CAMERA_OPEN_FAILED`，`cameraErrorCode` 为 `CameraState` 错误码。
- 同一个相机状态错误只报告一次，错误消失后再次出现时重新报告。

## 测试与验证

测试均位于 `lib/src/androidTest`，通用测试规范见 `AGENTS.md`。

| 测试类 | 覆盖范围 |
|---|---|
| `CameraPreviewStateTest` | 原始帧与采样帧矩阵、裁剪区域、四个旋转方向、目标镜像、token 失效、会话替换、故障优先级、截图渲染、宽高比选择 |
| `Nv21Test` | YUV_420_888 行列步长转换、NV21 尺寸校验、`toBitmap()` |
| `CameraDevicesStateTest` | 设备发布、同一异常连续失败、过期与关闭后的结果 |
| `CameraPreviewIntegrationTest` | 真实相机下的 NV21 帧与旋转、采样帧、截图镜像、`Fit` 留边布局、平台设备顺序、cameraId 选择与错误、retry、布局与镜像变化不重建会话、帧处理模式变化重建会话、Lifecycle 停止与恢复、退出组合后关闭分析线程 |
| `CameraManifestTest` | 合并后的相机硬件特性仍为可选 |

- 仪器测试 APK 的 targetSdk 与 compileSdk 保持一致，避免只覆盖旧版兼容行为。
- 真实相机测试自动授予 CAMERA 权限，并按设备能力跳过没有相机的场景。
- 除 API 35 外，至少在 API 23 模拟器上运行一次完整仪器测试，覆盖 `minSdk`。

```bash
./gradlew :lib:compileDebugKotlin :lib:compileDebugAndroidTestKotlin
./gradlew :lib:lintDebug :lib:assembleRelease
./gradlew :lib:connectedDebugAndroidTest
./gradlew :lib:publishToMavenLocal
git diff --check
```

硬件差异仍需真机验证，重点包括：

- 前置镜像。
- 四个显示方向，以及 `displayRotation` 覆盖系统方向。
- 预览与原始帧是否变形，原始帧 `rotationDegrees` 是否与画面一致。
- 只支持单次自动对焦的设备。
- 无相机、相机占用和运行错误恢复。

自动热插拔不属于支持范围。
