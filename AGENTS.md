# AGENTS.md

本文是本仓库唯一的代理开发规范，适用于仓库根目录及全部子目录。`lib` 的行为约束、测试映射和验证清单统一记录在 `REVIEW_NOTES.md`。

## 语言偏好

- 默认使用简体中文回复，除非用户明确要求其他语言。
- 代码、命令、API 名称、类名和原始错误信息保持原文，必要时补充中文解释。

## 修改前必读

- 修改 `lib` 前必须完整阅读 `REVIEW_NOTES.md`，涉及相机生命周期、设备枚举、错误转发、帧回调、预览尺寸、旋转、镜像或坐标转换时尤其如此。
- `REVIEW_NOTES.md` 是行为不变量和回归测试清单的权威记录，修改行为时同步更新。
- 未经用户明确要求，不得修改 `README.md`；公开行为、示例或依赖发生变化时也只检查影响，不主动更新该文件。
- 当前内部使用 CameraX，公开 API 不得暴露具体后端类型。

## 构建环境

| 项目 | 版本 |
|---|---|
| Gradle Wrapper | 8.11.1 |
| JDK | 17 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 1.9.25 |
| CameraX | 1.5.3 |
| `compileSdk`（`app` 与 `lib`） | 35 |
| `targetSdk`（`app`） | 35 |
| `minSdk`（`app` 与 `lib`） | 23 |

- 插件和依赖版本集中在 `gradle/libs.versions.toml`。
- 保持 Kotlin 1.9，不升级到 Kotlin 2.x；CameraX 固定为 1.5.3，不升级到要求 Kotlin 2.x 的版本。
- 仓库解析依赖时会先检查 `mavenLocal()`。

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

## 模块职责

| 模块 | 职责 |
|---|---|
| `lib/` | 发布到 Maven Central 的 Compose 相机库，公开包为 `com.sd.lib.compose.camera`，所有可复用行为放在此模块 |
| `app/` | 只通过 `lib` 公开 API 工作，演示运行时权限、设备枚举、cameraId 切换、镜像切换和真机验证；不要把库逻辑放入该模块 |

- `app/src/main/java/.../SampleActivity.kt` 是权限、设备切换、镜像切换和手动真机验证入口。
- 库 Manifest 合并 CAMERA 权限，并把 camera 和 autofocus 硬件声明为可选。
- 调用方仍必须先完成运行时授权，再组合 `CameraPreview`。

## 代码审查约定

- 本库只面向已经配置 Compose 的项目，调用方应自行提供 Compose 相关依赖。
- `lib` 将 Compose 依赖声明为 `implementation` 是有意设计，审查时不得建议改为 `api`。
- 不得把「公开 API 使用 Compose 类型，但发布的 API variant 不传递 Compose 依赖」列为 Bug 或风险。

## 测试

- `lib` 的测试全部位于 `src/androidTest`，测试类与覆盖范围见 `REVIEW_NOTES.md`。
- 状态和矩阵测试依赖 Android 类型，因此通过 `AndroidJUnit4` 运行。
- 纯计算逻辑优先放入 JVM 测试；`lib` 目前没有 `src/test` 和 `testImplementation` 依赖，新增时需先补齐。
- 依赖 Android 图形类型、Lifecycle、权限或 Compose 集成的行为放入仪器测试。
- 新增公开行为或修复回归时必须扩充对应测试。
- 异步测试使用有超时的等待，禁止用固定 `sleep` 掩盖竞态。
- 测试优先使用轻量 Fake，不使用 Mockito。
- 断言使用 Google Truth，测试类显式声明 `AndroidJUnit4` runner。

## Kotlin 与代码约定

- Kotlin 与 Gradle Kotlin DSL 使用两空格缩进，多行声明保留尾随逗号，优先使用不可变状态。
- 类型和 Composable 使用 `PascalCase`，函数与属性使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。
- 私有属性命名：
  - 类或普通 `object` 类体中的 `private val/var` 用 `_camelCase`。
  - `companion object` 中的私有属性用 `sCamelCase`。
  - 构造参数中声明的私有属性用普通 `camelCase`。
- 作用域函数的结果不被使用、只对接收者执行操作或副作用时用 `also`，不用 `let`；只有需要返回值继续计算时才用 `let`。
- 每个文件原则上只包含一个主要公开类型，文件名与类型一致。
- 按语义和可读性换行，不因长度机械换行；一行更清晰就保持单行，并保留用户主动调整过的排版，除非格式检查或项目规范要求修改。
- 包名保持在 `com.sd.lib.compose.camera` 或 `com.sd.demo.compose.camera` 下。
- 仓库没有独立 formatter 或 Detekt 任务，静态检查入口为 Android lint。

## 注释与文档

- 公开 API 提供 KDoc。
- 注释简洁易懂：优先单行，单行正文的 KDoc 写成 `/** ... */`，一句只表达一层意思。
- Markdown 文档用列表代替长段落，一条只说一件事；并列的对应关系用表格。
- 整理文档时保留原有的约束、禁止项和豁免，不改变规则含义。

注释句末标点按段落判断，段落以空行分隔：

| 情况 | 段落末尾 |
|---|---|
| 正文只有一段，且只有一行 | 不加句号，即使含逗号、分号等 |
| 正文有多段，某段只有一行，且没有逗号、分号、冒号等句内标点（标题式） | 不加句号 |
| 正文有多段，某段只有一行，但含逗号、分号、冒号等句内标点 | 加句号 |
| 某段跨多行 | 按完整句子补齐句末标点，不要求每个物理行末尾都有 |

- `@param`、`@return` 等标签行不算段落，末尾不加句号。
- 已有问号或感叹号时保留，不再追加句号。
- 跨多行的段落优先在逗号、分号、句号等标点后换行，不在连续语义中间断行。

## 提交与本地配置

- 提交标题保留英文类型和可选作用域，冒号后用简体中文，例如 `fix(lib): 保留分析期间已完成的帧结果`。
- 提交标题保持简短，只写一个主题。
- PR 说明应包含动机、行为变化和实际执行的验证命令。
- 公开 API 或依赖变化要在 PR 中明确标注，可见预览变化附截图或录屏。
- `app/template.jks` 是示例签名材料，不要替换为生产密钥。
- 机器专属 Android SDK 路径保留在未跟踪的 `local.properties` 中。
