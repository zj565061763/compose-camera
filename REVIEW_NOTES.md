# Camera Library Review Notes

本文记录截至 2026-08-22 已经过 review 和模拟器验证的实现约束。修改 `lib` 前应先阅读本文件，避免重新引入已修复的生命周期、坐标和资源问题。

## 产品边界

- 库不监听运行时摄像头设备变化，不支持自动热插拔恢复。
- 不注册 Camera2 `AvailabilityCallback` 或 CameraX `CameraPresenceListener`，不读取 `CameraManager.cameraIdList`，也不安排设备收敛轮询。
- 库不创建 CameraX `ConcurrentCamera` 会话，不支持不同 cameraId 的多个预览同时工作。
- `rememberCameraDevicesState()` 首次组合时初始化 `ProcessCameraProvider` 并枚举一次设备。之后只有直接调用 `CameraDevicesState.refresh()`，或通过关联预览的 `CameraPreviewState.retry()` 触发刷新时才重新枚举。
- `CameraDevicesState.refresh()` 只更新共享设备列表；`CameraPreviewState.retry()` 重新创建当前预览会话，并同时触发一次设备刷新。物理设备发生变化后，调用方按恢复目标选择对应的手动入口。
- 设备列表和最终绑定均以 CameraX 能力为准。cameraId 是否能跨重启保持稳定由 Camera HAL 决定。

## 已确认的公开行为

- `CameraPreview` 未指定预览分辨率或固定 `ViewPort`，由 CameraX 按设备能力和用例组合协商默认输出视野与分辨率。
- `CameraPreviewState.previewResolution` 来自 `SurfaceRequest.resolution`，表示真实预览缓冲区尺寸；它不等于 Compose 布局尺寸，也不保证等于 `onFrame` 的分析帧尺寸。
- `onFrame` 默认为 `null`。此时不得创建 `ImageAnalysis`、分析执行器或分析线程。非空时使用单线程和 `STRATEGY_KEEP_ONLY_LATEST`，默认输出 `YUV_420_888`；RGBA 需要 CameraX 逐帧转换。
- `onFrame` 在分析线程同步执行。组件通过 `finally` 在回调返回或抛出异常后关闭 `ImageProxy`；调用方若要异步处理，必须先复制数据。
- 释放会丢弃尚未开始的帧，但不能中断已经开始的回调。已开始帧的异常即使晚于组件离开组合，也必须最终送达 `onError`。
- `onError` 在主线程收到 Provider 初始化、设备枚举、摄像头选择、绑定后的 `CameraState`、帧回调和普通清理异常。用户 `onError` 抛出的异常必须位于库内部异常边界之外。
- 无相机和 CameraX 状态错误使用 `CameraPreviewException` 区分，并保留状态错误码与 cause。
- 每个正在组合的预览必须使用独立的 `CameraPreviewState`。预览尚未绑定或已释放时，`previewResolution` 为 `IntSize.Zero`。
- `CameraDevicesState` 可以在设备选择 UI 与一个或多个预览间共享，复用最近一次设备快照、加载状态、错误状态和手动刷新入口。
- 设备列表去重后保持 `availableCameraInfos` 的原始顺序，不按 cameraId 重新排序。
- `CameraPreview.cameraId == null` 时使用 CameraX 当前提供的第一个摄像头；非空时精确选择 Camera2 cameraId，目标不存在时报告 `CAMERA_NOT_FOUND`，禁止静默选择其他设备。
- 单个设备读取 `lensFacing` 失败时必须保留其 cameraId，并把 lens 发布为 `null`，不能让异常厂商 HAL 阻断整批设备。
- 库 Manifest 声明 `CAMERA` 权限和可选相机硬件；应用仍负责运行时授权，且只能在授权后组合 `CameraPreview`。

## 设备枚举与手动恢复

- 首次成功发布 CameraX 设备列表前不得创建预览会话，避免 Controller 启动第二条 Provider 初始化链。
- Provider 初始化或设备枚举失败后结束 loading 并发布错误，不做后台自动重试。
- 主动 `CameraDevicesState.refresh()` 必须在 Provider 为空时重新调用 `ProcessCameraProvider.getInstance()`；Provider 已存在时重新读取 `availableCameraInfos`。
- 同一时刻只允许一条 Provider 初始化链。初始化进行中再次刷新可以保持 loading，但不能创建重复 future listener。
- 异步 Provider 完成后必须检查 loader 是否已关闭，禁止销毁后的回调写入状态。
- 成功枚举必须清除活动错误事件，避免后来组合的预览收到陈旧错误。
- 错误订阅者异常必须在通知其他订阅者后原样传播，不能被 Provider 初始化或设备枚举的 `catch` 重新发布为设备错误。
- 运行期间设备被移除时，CameraX `CameraState` 的 `CRITICAL` 错误仍会终止并完整清理当前会话；库不会自动等待或重建，外部条件恢复后由调用方刷新并重试。

## Compose 外壳与 CameraX 会话

- `CameraPreview` 只有获得非零 Compose 布局宽高且设备首次枚举完成后，才在 `DisposableEffect` 中创建 `CameraPreviewController`。零尺寸是正常的隐藏或布局过渡，不会创建相机会话，也不会因尺寸本身报告错误。
- `DisposableEffect` 只在上下文、LifecycleOwner、状态实例、cameraId、显示旋转、是否启用帧回调、有效帧格式、retry generation、首次加载状态或相关设备快照变化时重建会话。
- `CameraPreviewState.reset()` 必须同时清零 retry generation，禁止同一状态实例再次组合时重放已消费的 `retry()`。
- 普通布局尺寸、`contentScale` 和镜像模式变化只更新显示和坐标状态，不得重复绑定相机。
- `cameraId == null` 时只有设备快照中的第一个 cameraId 参与重建 key；精确选择时只有目标 cameraId 是否存在参与重建 key。
- 绑定时必须以 `ProcessCameraProvider.hasCamera()` 的当前结果为准；设备快照只决定何时启动或重建会话，不能否决 CameraX 当前已识别的 cameraId。
- Controller 始终创建 `Preview`；仅在 `onFrame != null` 时创建 `ImageAnalysis` 和专用单线程 executor。
- 直接绑定 `Preview` 和可选的 `ImageAnalysis`，不设置固定 `ViewPort`，并保存 `bindToLifecycle()` 返回相机的 `CameraState`。
- 每个 Controller 独立执行单相机 `bindToLifecycle()`；不得把设备支持并发相机解释为本库支持不同 cameraId 同时预览。
- 只解绑当前 Controller 创建的 UseCase，禁止调用 `unbindAll()` 影响进程内其他 CameraX 使用方。
- CameraX `StateError.type == CRITICAL` 都是不可恢复的终止状态；必须上报错误并关闭当前会话，之后由调用方显式 retry。
- CameraState 使用显式移除的永久 Observer，确保 Lifecycle 暂停期间的终止错误仍能到达。
- CameraX 会静默忽略向 `DESTROYED` 的 `LifecycleOwner` 绑定 UseCase；会话启动以及异步 Provider 完成后都必须主动检查并走失败清理路径。
- 已绑定的 LifecycleOwner 后续进入 `DESTROYED` 时，即使 Compose 仍保持组合，也必须主动释放 Observer、Analyzer 和分析执行器。

## 线程、错误与清理

- `onFrame` 在名为 `CameraPreview-Analysis` 的专用线程同步执行，采用 `STRATEGY_KEEP_ONLY_LATEST`。
- 每个 `ImageProxy` 都必须在 `finally` 中关闭。帧回调异常和图像关闭异常需要保留正确的主异常及 suppressed 异常；致命 `Error` 不得作为业务异常吞掉。
- 内部错误先通过主线程消息队列调用用户 `onError`，确保用户异常不被 CameraX、Provider 或设备枚举的 `catch` 重新解释。
- 清理必须尝试全部普通步骤：停止帧分发、移除 Lifecycle 和 CameraState Observer、清除 Analyzer、解绑自身 UseCase、清空预览状态、关闭 executor。
- 一个普通 `Exception` 不能跳过后续清理；多个普通异常汇总到首个异常的 suppressed 中。致命 `Error` 不得被吞掉。
- 所有异步回调在写状态前必须确认当前 request、generation、会话或 loader 仍有效。

## 预览与坐标转换不变量

- `SurfaceRequest` 的 transformation listener 是单监听器槽位。取得首次 `TransformationInfo` 后必须先清除 listener，再把请求交给 `CameraXViewfinder`。
- 当前实现只消费每个 `SurfaceRequest` 的首次 `TransformationInfo`。显示旋转通过重建会话产生新请求；如果以后改为同一请求内动态更新 target rotation 或 ViewPort，必须重新设计单 listener 转发。
- 新 `SurfaceRequest` 到达时立即轮换 request identity，并同步把旧 `previewResolution` 清零。
- 变换发布和取消通过 publication gate 保证原子顺序。旧请求、已关闭会话或过期回调不得覆盖或清空新请求状态。
- CameraPreviewState 使用不可变快照和 `AtomicReference` 跨线程发布变换，分析线程无锁读取。
- 变换链为 `analysis buffer -> sensor -> preview buffer -> Compose viewfinder -> optional mirror`。
- 稳定 request identity 用于拒绝旧请求；独立 transform identity 用于帧 token。新请求、有效布局尺寸、`contentScale` 或额外镜像变化必须使旧 token 失效。
- 异步分析结果写回 UI 前必须使用 `CameraPreviewState.isFrameTransformCurrent()` 校验 token。
- `createTransformToPreview()` 输入是 `CameraFrame.image` 的原始缓冲区坐标。若检测器输出已经按 `rotationDegrees` 旋转后的坐标，调用方需先转回原始缓冲区坐标。
- `CameraXViewfinder` 已处理 CameraX 默认镜像。额外 `graphicsLayer` 镜像只能补偿 `CameraMirrorMode` 与 `TransformationInfo.isMirroring` 的差异。
- 不得提前删除 `PreviewTransformSnapshot.correctMirrorAxis()`；它修复 CameraX 1.5.x 四分之一转镜像轴问题。

## CameraX 升级待办

CameraX 上游镜像轴修复于 2026-06-10 合入：
<https://android.googlesource.com/platform/frameworks/support/+/806f6f794204a91d963126485c33421e77b659e8%5E2..806f6f794204a91d963126485c33421e77b659e8/>

当前稳定版 1.6.1 早于该提交，升级到 1.6.1 时不能移除兼容。将来升级到包含修复的稳定版本后：

1. 用 Gradle `dependencyInsight` 确认最终解析的 `viewfinder-core` 包含修复。
2. 删除 `correctMirrorAxis()`、`RectSnapshot` 以及只为兼容保存的 crop、rotation 和 mirroring 字段。
3. 删除旧矩阵纠正测试，保留正常旋转和前后镜头镜像测试。
4. 在真机前置摄像头验证竖屏、横屏以及 `AUTO`、`ON`、`OFF` 三种镜像模式。

## 测试与验证

- `CameraPreviewStateTest.kt` 覆盖矩阵、镜像、旋转、request/transform identity、publication gate、分辨率和布局 generation、设备状态、错误订阅、帧关闭、错误映射及异常安全清理。
- `CameraPreviewIntegrationTest.kt` 覆盖真实 CameraX 的 YUV/RGBA、无分析资源、精确 cameraId、retry、布局 token、设备枚举错误转发、组合释放和 Lifecycle 销毁。
- `CameraManifestTest.kt` 验证库合并后的相机硬件特性仍为可选。
- 异步测试使用有超时的等待，禁止固定 `sleep`；优先使用轻量 Fake、Google Truth 和显式 `AndroidJUnit4`。
- 真实相机测试按设备实际 CameraX 能力跳过不满足前提的场景。

```bash
./gradlew :lib:compileDebugKotlin :lib:compileDebugAndroidTestKotlin
./gradlew :lib:lintDebug :lib:assembleRelease
./gradlew :lib:connectedDebugAndroidTest
git diff --check
```

硬件差异仍需在真实设备验证，重点包括异常 `lensFacing`、前置镜像、旋转、无相机、相机占用和 CameraState 错误恢复。自动热插拔不属于支持范围。
