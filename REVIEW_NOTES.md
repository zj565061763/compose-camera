# Camera Library Review Notes

本文记录截至 2026-08-30 已确认的实现约束。修改 `lib` 前应先阅读本文件，避免重新引入生命周期、坐标、帧缓冲区和资源释放问题。

## 产品边界

- 当前内部使用 Android 平台相机 API，公开 API 不暴露具体相机实现类型。
- 库不监听运行时设备变化，也不提供自动热插拔恢复。
- 库不协调并发相机会话，不支持不同 `cameraId` 同时预览。
- `cameraId` 是不透明的 `String`。当前实现由平台设备序号生成，但调用方不得解析、排序或持久依赖其格式。
- cameraId 是否能跨重启保持稳定由设备实现决定。

## 公开行为

- `CameraPreview.cameraId == null` 时使用设备列表的第一项；非空时精确选择，目标不存在时报告 `CAMERA_NOT_FOUND`，禁止隐式回退。
- `CameraPreviewState` 属于单个正在组合的预览，不能在多个同时存在的预览间共享。
- `CameraDevicesState` 可以由设备选择 UI 和多个预览共享；共享设备状态不代表支持多个相机会话同时运行。
- `CameraPreviewState.previewResolution` 表示当前会话使用的原始预览帧分辨率，不是 Compose 布局尺寸；会话未运行时为 `IntSize.Zero`。
- `CameraPreviewState.failure` 只保存需要重新枚举设备或重建相机会话的当前故障；其他普通异常只通过 `CameraPreview.onError` 报告。
- `frameProcessor` 默认为 `FrameProcessor.None`。`Preview` 输出 NV21 数据，`PreviewSampled` 按间隔输出预览区域截图。
- `CameraFrame.Preview.data` 和 `CameraFrame.PreviewSampled.data` 只保证在同步回调期间有效。允许跨回调保留的是数据副本、独立 `Bitmap` 或轻量 `CameraFrameTransformToken`。
- `CameraFrame.Preview.toBitmap()` 返回未旋转的独立图片，转换失败时返回 `null`，不抛出普通转换异常。
- `CameraFrame.PreviewSampled.data` 已应用显示旋转和 `ContentScale`，未由相机内容覆盖的区域透明；不包含平台镜像、目标镜像或预览上层内容，`rotationDegrees` 固定为 `0`。
- `CameraPreview.mirrorMode` 只影响预览和坐标矩阵，不修改帧数据；`CameraPreviewState.takeScreenshot(mirrorMode)` 的参数只决定返回 Bitmap 的镜像状态。
- `CameraPreviewState.takeScreenshot()` 必须在主线程调用；返回图片已应用显示旋转、`ContentScale` 和指定镜像模式，不包含预览上层内容。预览未产生有效帧、已离开组合或发生普通截图异常时返回 `null`，截图异常同时通过 `CameraPreview.onError` 报告；成功返回的独立 Bitmap 由调用方负责回收。
- `CameraPreviewState.requestFocus()` 必须在主线程调用；仅当前会话采用单次自动对焦时触发请求，连续对焦、设备不支持单次自动对焦、预览未运行或已离开组合时安全忽略。
- `displayRotation == null` 时监听当前 View 所在显示器的旋转；显式值必须是 `Surface.ROTATION_*`。
- 库 Manifest 声明 `CAMERA` 权限和可选相机硬件；应用仍负责运行时授权，并且只能在授权后组合 `CameraPreview`。

## 设备枚举与手动恢复

- `rememberCameraDevicesState()` 首次组合时同步枚举一次设备。之后只有调用 `CameraDevicesState.refresh()`，或通过关联预览的 `CameraPreviewState.retry()` 触发刷新时才重新枚举。
- 枚举失败后结束 loading 并发布错误，不做后台重试。
- 每次枚举失败都必须通知错误监听器，即使连续失败使用同一个 `Throwable` 实例，避免 `retry()` 清除当前故障后无法重新发布。
- 单个设备读取镜头方向失败时必须保留其 cameraId，并把 lens 发布为 `null`，不能让异常厂商 HAL 阻断整批设备。
- 设备列表保持平台返回顺序，不按 cameraId 重新排序。
- 成功枚举必须清除活动错误，避免后来组合的预览收到陈旧错误。
- `CameraDevicesState.refresh()` 只更新共享设备列表；`CameraPreviewState.retry()` 还会重新创建当前预览会话。
- `CameraPreviewState.retry()` 必须立即清除并使当前故障发布身份失效，避免旧 Controller 迟到的错误重新覆盖新尝试。
- loader 关闭后不得继续发布设备或错误状态。

## Compose 外壳与相机会话

- `CameraPreview` 只有获得非零 Compose 布局宽高且设备首次枚举完成后，才创建 `CameraPreviewController`。零尺寸是正常的隐藏或布局过渡，不报告错误。
- 会话由 `LifecycleOwner` 和 `TextureView.SurfaceTexture` 共同控制；Lifecycle 进入 `STARTED` 且 Surface 可用时打开，进入 `STOPPED`、Surface 销毁或组件释放时关闭。
- Surface 销毁回调必须转移释放责任，先在相机线程停止会话，再释放 `SurfaceTexture`。
- 显示旋转、cameraId、帧处理模式、retry generation 或相关设备快照变化会重建会话。
- 普通布局尺寸、`contentScale`、镜像模式、处理间隔、用户 lambda 或 `onError` lambda 实例变化不得重复打开相机。
- 会话重建时保留上一帧已经应用的显示矩阵；启动新预览前必须在主线程消费旧生产者尚未处理的 TextureView 更新，再启用新会话首帧门控。新会话的内容矩阵和额外镜像只能在首个属于当前 Surface 和会话的 `onSurfaceTextureUpdated` 回调中同步切换，禁止遗漏首帧、误认旧缓冲、把旧帧短暂恢复为 identity 或提前套用新会话矩阵。
- `CameraPreviewState.takeScreenshot()` 的截图入口只在对应 `CameraPreview` 组合期间有效，退出组合后必须解除，不能继续访问已经释放的 TextureView。
- `CameraPreviewState.requestFocus()` 的对焦入口只在对应 Controller 组合期间有效，Controller 替换或退出组合后必须按入口身份解除，旧 Controller 不得清除或接收新会话的请求。
- `CameraPreviewState.reset()` 必须同时清零 retry generation，避免同一状态实例再次组合时重放已消费的 `retry()`。
- Controller 在 `CameraPreview-Camera` 专用线程执行打开、配置、预览、对焦、回调缓冲区归还和释放操作。
- Controller 只释放自己打开的相机和创建的工作线程，不得影响进程内其他相机使用方。
- 打开、配置或运行错误会停止当前会话；外部条件恢复后可以调用 `CameraPreviewState.retry()`。

## 预览尺寸、旋转与镜像

- Controller 优先从不超过 `1280 × 960` 像素且面积不低于最大候选四分之一的预览尺寸中，选择旋转后最接近 Compose 区域比例的尺寸；若设备没有符合上限的尺寸，则选择像素面积最小的尺寸，避免为比例匹配分配过大的相机和 NV21 缓冲区。
- 普通布局变化只更新最新有效尺寸，不立即重建当前会话；Lifecycle 或 Surface 后续自然重开时必须按最新尺寸重新选择预览分辨率。
- 设置参数后必须重新读取设备实际采用的 preview format 和 preview size；启用任一帧处理模式且格式不是 NV21 时停止创建会话，尺寸用于发布 `previewResolution` 和创建回调缓冲区。
- `TextureView` 保持 Compose 预览区域尺寸，并通过内容变换矩阵应用旋转后的原始帧比例和 `ContentScale`，避免 AndroidView 互操作层把相机缓冲区直接拉伸到预览区域。
- 前置摄像头必须分别计算平台预览显示方向和原始帧旋转角度；`setDisplayOrientation()` 的镜像补偿结果不能用于 `CameraFrame.rotationDegrees`。
- 平台默认镜像前置预览。额外水平翻转合并到 `TextureView` 内容矩阵，只用于补偿平台默认状态与 `CameraMirrorMode` 目标状态的差异。
- 优先使用连续对焦模式；设备只支持 `FOCUS_MODE_AUTO` 时，在当前会话首个有效预览帧触发一次单次对焦，之后仅响应 `CameraPreviewState.requestFocus()`。进行中的请求只合并保留一次，回调超时后允许后续请求继续执行；会话停止时丢弃尚未执行或迟到的回调。
- `CameraPreviewState` 以不可变快照和 `AtomicReference` 跨线程发布变换，分析线程无锁读取。
- 变换链为 `raw frame -> display rotation -> ContentScale -> target mirror`。
- 新相机会话、有效布局尺寸、`contentScale` 或目标镜像变化必须使旧 transform token 失效。
- 异步分析结果写回 UI 前必须使用 `CameraPreviewState.isFrameTransformCurrent()` 校验 token。
- `createTransformToPreview()` 对 `Preview` 输入原始缓冲区坐标；若检测器输出已经按 `rotationDegrees` 旋转，调用方需先转回原始缓冲区坐标。对 `PreviewSampled` 输入 Bitmap 坐标，只补充目标镜像。

## 帧线程与缓冲区

- 只有 `frameProcessor` 不是 `None` 时才创建名为 `CameraPreview-Analysis` 的专用单线程 executor 和三个 NV21 回调缓冲区。
- `Preview` 保留正在处理的帧和最新一帧；新帧会替换尚未开始的旧帧。
- `PreviewSampled` 使用相机帧回调作为采样节拍，NV21 缓冲区立即归还；达到间隔后在主线程截取 `TextureView`，分析尚未结束时只保留最新待采样请求。
- `Camera.PreviewCallback` 返回 `null` 缓冲区时必须报告 `CAMERA_RUNTIME_ERROR` 并停止当前会话，禁止让空值异常逃逸相机线程。
- `TextureView.getBitmap()` 不会把 `setTransform()` 的内容矩阵烘入 Bitmap。`PreviewSampled` 和 `takeScreenshot()` 必须按当前 geometry 的内容比例截图，再显式绘制到浮点 content bounds 完成偏移和裁剪，禁止依赖平台 readback 应用显示矩阵。
- 截图源按统一比例限制在旋转后的相机缓冲区尺寸以内，避免为已经被相机缓冲区限制的内容创建超大 Bitmap；geometry 为 identity 且不需要移除平台镜像时直接转交截图 Bitmap，禁止无条件复制第二张预览尺寸 Bitmap。
- 采样请求、截图尺寸、content bounds 和 transform token 必须来自同一份状态快照；布局或会话在截图期间变化时丢弃结果。
- `PreviewSampled` 始终移除平台镜像；`takeScreenshot()` 则比较平台镜像和参数要求的目标镜像，只在两者不同时翻转返回像素。
- NV21 帧宽高必须为正偶数，所需字节数必须能安全表示为 `Int`，实际数据长度不得小于 `width × height × 3 / 2`。不符合条件的回调缓冲区直接归还，不创建 `CameraFrame`。
- 每个相机回调缓冲区都必须在处理完成、被替换、被丢弃或 dispatcher 关闭时归还。
- 帧回调异常和缓冲区归还异常必须保留正确的主异常及 suppressed 异常；致命 `Error` 不得作为业务异常吞掉。
- 释放会丢弃尚未开始的帧，但不能中断已经开始的同步回调。

## 错误与清理

- `onError` 通过主线程消息队列调用，确保用户回调异常位于库内部 `try/catch` 之外。
- 设备枚举以及相机选择、打开、配置或运行故障必须同时写入 `CameraPreviewState.failure`；新会话首帧确认后清除。故障发布受当前预览尝试身份约束，旧 Controller 的迟到故障不得覆盖新尝试，首帧前发生的新故障也不得被迟到帧清除。
- 自动对焦、帧处理、采样、截图、缓冲区归还、Surface 释放和普通清理异常不得写入 `CameraPreviewState.failure`，只通过 `onError` 作为诊断事件报告。
- 设备错误订阅在退出组合后失效，已经排队但尚未执行的设备错误不得送达已释放的预览。
- `CameraPreviewException` 区分无设备、目标不存在、打开或配置失败以及运行错误，并保留平台错误码或 cause。
- 清理必须尝试全部普通步骤：停止帧回调、清除相机错误回调、停止预览、释放相机、清空会话状态和关闭分析 executor。
- 一个普通 `Exception` 不能跳过后续清理；多个普通异常汇总到首个异常的 suppressed 中。致命 `Error` 不得被吞掉。
- 所有异步帧在写结果前必须确认 transform token 仍属于当前会话和布局。

## 测试与验证

- `CameraPreviewStateTest.kt` 覆盖缩放矩阵、前后摄像头旋转、镜像、截图像素与所有权、token 失效、尺寸选择、首帧与 session 对焦协调、单次对焦调度、NV21 校验、Bitmap 转换、原始帧与采样帧分发和异常安全清理。
- `CameraDevicesStateTest.kt` 覆盖设备枚举失败和单个镜头方向读取失败。
- `CameraPreviewIntegrationTest.kt` 使用真实相机和 Compose test rule，覆盖 NV21、Bitmap 转换、旋转、镜像、布局变换、retry、cameraId 选择与错误、Controller 显式对焦绑定、Lifecycle 清理和工作线程。
- `CameraManifestTest.kt` 验证库合并后的相机硬件特性仍为可选。
- 异步测试使用有超时的等待，禁止固定 `sleep`；优先使用轻量 Fake、Google Truth 和显式 `AndroidJUnit4`。
- 真实相机测试按设备能力跳过没有相机的场景。

```bash
./gradlew :lib:compileDebugKotlin :lib:compileDebugAndroidTestKotlin
./gradlew :lib:lintDebug :lib:assembleRelease
./gradlew :lib:connectedDebugAndroidTest
./gradlew :lib:publishToMavenLocal
git diff --check
```

硬件差异仍需真机验证，重点包括前置镜像、四个显示方向、预览与稳定帧是否变形、无相机、相机占用和运行错误恢复。自动热插拔不属于支持范围。
