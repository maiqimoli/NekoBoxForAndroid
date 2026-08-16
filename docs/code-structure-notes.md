# NekoBoxForAndroid 代码结构开发文档

> 更新：2026-08
> 目的：记录代码结构拆分工作（巨型文件拆分）的成果、决策与后续查阅指引。

---

## 一、拆分背景

原 `ConfigurationFragment.kt` 单文件 **1712 行**，承载主界面全部逻辑（生命周期、菜单、节点列表、测速、分组导航），维护困难。本项目对超过 500 行的源码文件进行拆分，目标是**每个源码文件控制在 300-500 行**。

**约定**：`SingBoxOptions.java`（libcore 自动生成代码，2467 行）与构建脚本 `buildSrc/Helpers.kt` 不计入拆分范围。

---

## 二、拆分成果

### 1. `ConfigurationFragment.kt`（1712 → 379 行）→ 6 个文件

| 文件 | 行数 | 职责 |
|---|---|---|
| `ui/ConfigurationFragment.kt` | 379 | 主类核心：生命周期、分组导航、配置导出、搜索 |
| `ui/ProfileGroupFragment.kt` | 478 | 节点列表 Fragment + `ConfigurationAdapter`（DiffUtil 增量刷新） |
| `ui/ConfigurationHolder.kt` | 285 | 节点卡片 ViewHolder（独立类，注入 `ProfileGroupFragment` 引用） |
| `ui/ConfigurationMenu.kt` | 319 | 添加/导入菜单 + 菜单动作处理（`ConfigurationFragment` 扩展函数） |
| `ui/ConnectionTest.kt` | 317 | 测速对话框（`TestDialog`）+ `pingTestImpl`/`urlTestImpl` |
| `ui/GroupPagerAdapter.kt` | 128 | 分组 ViewPager 适配器（独立类，注入 `ConfigurationFragment` 引用） |

**拆分要点**：
- 嵌套类 `GroupFragment` 改名为 **`ProfileGroupFragment`** 移出（避免与既有 `ui/GroupFragment.kt` 顶层类冲突）
- inner class（`ConfigurationHolder`/`ConfigurationAdapter`）→ 独立普通类，构造函数注入外部 Fragment 引用（`gf: ProfileGroupFragment`）
- 菜单方法 → `ConfigurationFragment` 扩展函数，`onMenuItemClick` 在主类保留转发
- `expandedChainIds`/`chainHopDetails`/`testChainHops`/`exportConfig` 等可见性从 `private` 放宽供跨文件访问

**提交**：`94f839d`（ProfileGroupFragment）、`aa2f555`（ConnectionTest + GroupPagerAdapter）、`adb2289`（ConfigurationMenu + ConfigurationHolder）

### 2. `V2RayFmt.kt`（602 → 522 行）→ 2 个文件

| 文件 | 行数 | 职责 |
|---|---|---|
| `fmt/v2ray/V2RayFmt.kt` | 522 | VMess/VLESS 解析与 URI 生成 |
| `fmt/v2ray/V2RaySingBox.kt` | 164 | sing-box 出站流设置 / TLS / 标准出站构建 |

**提交**：`872657d`

---

## 三、暂缓拆分（接受现状）的文件

以下文件经评估**暂不拆分**，原因记录如下：

| 文件 | 行数 | 结构 | 不拆分原因 |
|---|---|---|---|
| `group/RawUpdater.kt` | 690 | `object RawUpdater`，核心为 **490 行单函数** `parseRaw`（clash YAML / JSON / Base64 / 链接解析分发） | 非多类结构；按函数拆分需重构闭包状态（`proxies` 列表、全局变量），回归风险高 |
| `fmt/ConfigBuilder.kt` | 675 | 顶层 **612 行单函数** `buildConfig`（sing-box 配置生成，含大量嵌套函数引用闭包变量） | 同上；拆分为辅助函数属于架构重构，需充分测试 |
| `SingBoxOptions.java` | 2467 | libcore 自动生成代码 | 不应手拆 |

> 若后续决定拆分：`parseRaw` 可按协议（ss/vmess/vless/trojan/ws 等）提取辅助函数；`buildConfig` 可按模块（DNS / 路由 / 出站）拆分。预计各需 2-3 次迭代并充分验证代理核心流程。

---

## 四、相关工程改动速查

| 变更 | 提交 | 说明 |
|---|---|---|
| 依赖升级 | `8b082f1` | okhttp 5.4.0、snakeyaml 2.6、guava 33.6.0、Kotlin 2.2.21、KSP 2.2.21-2.0.5、Room 2.7.2、AGP 9.3.1 |
| 编译警告清理 | `541efe8` | 应用代码警告 ~32 → 0；`getParcelable`/`onBackPressed`/`launchWhenCreated` 等为有意弃用（Suppress 标注） |
| 字符串 i18n | `8b8730e` | `<?>` / `VLESS` 硬编码收进 strings.xml |
| Phase 1-4 UI 现代化 | `de1417a` | Material 3、Monet、卡片重构、DiffUtil、Spring 动效、Edge-to-Edge |
| 新手引导 | `3cabcc3` | 规则集缺失检测、IPv6 默认启用、空状态、延迟徽标 |

---

## 五、构建与验证

```bash
# 编译验证（快速，~1 分钟）
./gradlew :app:compileOssDebugKotlin

# 单元测试（13 个：SOCKS 解析 + 订阅导入）
./gradlew :app:testOssDebugUnitTest

# 打包
./gradlew :app:assembleOssDebug
```

**拆分验证标准**：每次拆分后执行编译 + 单元测试，确保行为无回归（拆分仅调整文件组织，不改动功能逻辑）。
