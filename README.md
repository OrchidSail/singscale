# SingScale

> 基于 `sing-box` + `Tailscale (tsnet)` 的低功耗、高稳定 Android 原生直连优先 VPN 客户端。

**SingScale** 结合定制版 [sing-box](https://github.com/SagerNet/sing-box)（`v1.14.4`）Go 网络内核、内嵌 [Tailscale](https://github.com/tailscale/tailscale)（`tsnet`）用户态组网协议栈与原生 Kotlin / Jetpack Compose 界面，面向移动网络日常常驻场景设计：无需手写 JSON 配置，默认采用**直连优先动态分流**、**三态低功耗电源调度**以及面向蜂窝切网/弱网环境的**防崩溃稳定性加固**。

---

## 核心特性

### 1. 直连优先动态分流（Direct-First Routing）
- **实测驱动，无需依赖地区静态名单**：在默认「智能质量动态策略（`SMART_QUALITY`）」下，通过本地运营商 DNS（`dns-local`）解析获取最近边缘 CDN，由 Go 内核（`core/sing-box/route/singscale_direct_first.go`）实测目标域名的本地物理直连 TCP + TLS RTT。
- **健康直连，受阻自动切入中继/出口**：直连 RTT 低于阈值（默认 `220 ms`）的域名直接走本地物理网络（缓存 30 分钟）；直连超时、重置或延迟过高时自动回退至 Tailnet 最优出口/中继节点（缓存 5 分钟），并自动将代理目标改写为 FQDN 交由远端节点解析。
- **实时 UDP 低抖动优先**：非 QUIC 原始 UDP（语音、游戏、STUN/WebRTC 等非 443/8443 端口流量）默认直连以降低抖动；直连探测内置并发信号量（上限 8）与同目标去重（single-flight），避免突发流量导致线程暴涨。
- **四种路由模式**：支持 **智能质量动态策略（直连优先）**、**全局出口代理**、**自定义规则分流**、**仅 Tailnet 组网互联**，并可配置自定义直连与强制中继域名列表。

### 2. Go 核心（`sing-box` + `tsnet` + `gVisor`）+ 原生 Kotlin / Jetpack Compose 客户端
- **开箱即用的 Tailscale 登录与 Headscale 支持**：默认支持官方 Tailscale 交互式 OAuth 登录（Chrome Custom Tabs 一键网页授权 + 二维码扫码授权），内置持久化凭证保护避免重连时误重置 `NodeKey`；同时支持 Pre-Auth Key 与自建 Headscale 控制面（`control_url`）。
- **任意组网节点作出口（Exit Node）或中继（Relay Node）**：
  - **原生 WireGuard 热切换**：通过定制 Go RPC 接口（`MaskedPrefs`）毫秒级切换原生 Exit Node、Allow LAN、Accept Subnet Routes 与 Peer Relay，无需重启 `tsnet`。
  - **Proxy-over-Tailscale 多跳串联**：自动探测组网节点开放的 `SOCKS5` / `HTTP CONNECT` 代理端口，按需构建 `Android TUN -> Tailscale (ts-ep) -> [Relay 节点] -> [Exit 节点] -> Internet` 多跳链路。
- **主动链路质量评估引擎（`QualityEngine`）**：并发执行 Tailscale Disco Ping（识别 `DIRECT` P2P 直连 / `PEER_RELAY` / `DERP` 路径、RTT、抖动与丢包）并结合 HTTPS 204 端到端测速，给出 `0–100` 评分与 `S / A / B / C` 等级；支持滞回防抖自动优选、DERP 高延迟降级保护与出口离线故障安全直连回退（Fail-Safe）。
- **现代原生 UI 与三路分流透视**：采用 Kotlin + Jetpack Compose + Material 3 打造「连接」「分流」「节点」「设置」四大页面，提供三路互斥（本地直连 / 组网直连 / 中继）实时速率折线图、按域名/连接粒度的流量透视与命中规则说明。

### 3. 三路互斥流量统计与节点实时链路区分（v1.0.5）
- **「本地直连 / 组网直连 / 中继」三路互斥统计展示**：
  - **专用 `ts-mesh` 路由解耦**：`ConfigBuilder` 为 Tailscale CGNAT（`100.64.0.0/10`）、ULA（`fd7a:115c:a1e0::/48`）及已接受的 Tailnet 子网路由引入独立 `ts-mesh`（selector → `ts-ep`）出站，与经 `egress-select` 的公网出口/代理中继流量彻底分离，不再将组网内网互访混入中继统计。
  - **内核级驱逐守恒与跨热重载对账**：Go 内核（`core/sing-box/common/trafficcontrol/singscale_split.go`）在活跃连接、已关闭连接与超过上限被驱逐/清理的历史连接间原子维护三路分桶统计（严格满足 `本地直连 + 组网直连 + 中继 == 总流量`），经 `SingScaleGetTrafficSplitJSON()` 与 Clash API `singscaleSplit` 透传至客户端；`TrafficSplitTracker` 结合内核对账与实例 epoch 归档，保证前后台切换与 TUN 热重载期间不回退、不重算。
  - **三路可视化与明细筛选**：首页实时速率折线图、流量分布卡片与「分流」透视页全面展示 **本地直连**、**组网直连**、**中继** 三路实时速率、累计流量、严格守恒 `100.0%` 占比以及按路径分类的域名/连接明细。
- **节点实时链路区分与双向组网流量**：
  - Go 侧 `classifyPeerTransportPath` 输出各节点实时链路类型（`DIRECT` P2P 直连 / `PEER_RELAY` 节点中继 / `DERP` 中转 / `OFFLINE`），客户端优先依据活跃连接直连端点（`curAddr`）与中继路径（`peerRelay`）实时更新节点链路状态。
  - 「节点」页顶部汇总在线节点的 **P2P 直连 / 节点中继 / DERP 中转** 分布，节点卡片角标实时展示链路类型与延迟，展开详情可查看真实端点/中转区域及该节点累计双向组网流量（`↓ rx / ↑ tx`）；`4×2` 桌面小组件同步采用中文链路标签。

### 4. 动态 `100.x/32` TUN 绑定与被动事件驱动本机端口透传（v1.0.4）
- **动态 `100.x/32` TUN 地址绑定（无线调试直显 Tailscale 地址）**：
  - 登录后自动将 Android `tun0` 接口地址平滑绑定为本机真实 Tailscale CGNAT 地址（`100.x.y.z/32`，开启 IPv6 时双栈绑定 `fd7a:...:/128`）并自动关联 MagicDNS（`100.100.100.100` / `fd7a:115c:a1e0::53`），让 Android 系统「无线调试（Wireless Debugging）」等系统组件直接显示本机 Tailscale 地址。
  - 登录前及登出后自动安全回退至 `172.19.0.1/30`；由 `TunAddressRebindGuard` 状态机保证一次性平滑切换，内置防重入、冷却时间与滑动窗口频次上限，杜绝地址抖动循环；Go 侧 `ensureHostPrefixDNSFallback` 为 `/32`、`/128` 单主机前缀提供 MagicDNS 兜底保护。
- **被动事件驱动 Tailnet 入站本机端口透传与 UI 实时开关**：
  - 通过 `tsnet` 用户态 netstack 回调被动接管发往本机 Tailscale IP 的 TCP 入站流（如无线调试 ADB、Termux SSH 等本机服务），严格核验源 IP 属于当前 Tailnet 已认证且未过期节点后，直接透明转发至 Android OS 回环（`127.0.0.1`，失败自动回退 `[::1]`），不经公网也不回环重入 `tun0`。
  - 纯事件驱动、零后台 goroutine、零定时器、息屏零唤醒；内置全局与单节点并发上限及完整 TCP 半关闭（`CloseWrite`）回收。
  - 在「分流」页提供 **「允许组网设备直连本机端口」** 实时开关，通过 RPC 毫秒级热切换（无需重载 TUN），关闭时立即断开存量转发并拒绝新入站连接。

### 5. Quick Settings 快捷磁贴与双尺寸桌面小组件
- **下拉状态栏快捷磁贴（`SingScaleTileService`）**：随时一键启停 VPN，实时展示当前活跃节点与延迟。
- **双尺寸桌面小组件**：
  - `1×1` 极简快捷开关（`SingScaleMiniWidgetProvider`）：Canvas 抗锯齿绘制，单指一键启停。
  - `4×2` 状态卡片（`SingScaleWidgetProvider`）：显示节点、链路类型与延迟，并支持在小组件上直接循环切换路由策略。
- **零周期轮询开销**：小组件配置 `updatePeriodMillis="0"` 纯事件驱动，结合息屏静默与延迟分桶，避免无效 SystemUI / Launcher IPC 唤醒。

### 6. 切网 / 弱网防崩溃、Go 1.27 兼容与内存预算解耦
- **非法蜂窝/CLAT 前缀防崩溃**：Go 端 `parseInterfacePrefixes` 与 Kotlin 端 `formatValidatedCidrPrefix` 双重校验，安全过滤 Android 蜂窝切网瞬态上报的非法前缀（如 `-1`、IPv4 `/128`、带 `%iface` 作用域的 IPv6），避免触发 `netip.MustParsePrefix` panic。
- **接口变更签名去重**：`DefaultNetworkMonitor` 按网络句柄、接口名、计费状态、DNS 与地址列表计算拓扑签名，过滤 RSRP 信号格抖动引发的无效回调，同时在 Wi-Fi / 5G 真实切换时及时同步 `setUnderlyingNetworks`。
- **本地 DNS 并发闸门**：`LocalResolver` 通过并发信号量（上限 `16`）与 4 秒超时约束 Go→Java JNI 解析调用，防止弱网/断网期间线程数失控。
- **Go 1.27 兼容与进程 RSS / Go 堆内存预算解耦**：
  - `oomprofile` 按 `go1.27` / `!go1.27` 构建标签自动适配 Go 1.27 内存剖析记录 ABI 布局，并为 OOM 快照生成补充 `recover()` 兜底保护，防止异常诊断过程引发二次崩溃。
  - 将 Android 单进程总 VmRSS 守护上限（`oomMemoryLimit` 按设备内存分级为 `384 / 512 / 640 MiB`，覆盖 ART、Jetpack Compose、HWUI 与 `libbox.so`）与 Go 自身堆软预算（`GOMEMLIMIT` 独立约束于 `96–160 MiB`、`GOGC=50`）解耦，在保持后台 VPN 紧凑内存的同时避免前台 UI 交互触发误报 OOM 重置。
  - Tailscale 端点关闭期采用 `safeLocalBackend()` 与 `recover()` 保护；启用局域网访问时通过 CIDR 精确扣减，保留局域网直通与已接受的 Tailnet 子网路由。

### 7. 三态低功耗调度（息屏极致省电）
通过 `AppPowerStateMonitor` 感知屏幕交互状态、应用前后台与系统省电模式（不持有任何 `WakeLock`），联动 Go 内核实现三态电源调度：

| 运行状态 | 实时 UI 遥测流 | Tailnet 状态轮询 | 主动链路探测（Disco / HTTP） | 内核与射频优化 |
| --- | --- | --- | --- | --- |
| **前台交互态** (`FOREGROUND_INTERACTIVE`) | 开启（1.5s） | 6s（省电模式 10s） | 45s（省电模式 90s） | 正常响应并实时刷新分流与拓扑 |
| **后台亮屏态** (`BACKGROUND_SCREEN_ON`) | **关闭** | 30s（省电模式 60s） | 180s（仅探候选出口/中继单样本；仅组网模式暂停） | 断开 gRPC 遥测流，降低 CPU/网络唤醒 |
| **息屏休眠态** (`DOZE_SCREEN_OFF`) | **关闭** | **挂起（0 ms）** | **挂起（0 s）** | 调用 `CommandServer.pause()` 与 `SingScaleSetPowerMode(sleeping=true)` 开启 `SilentDisco`、停止 UDP 生命周期探测，抑制通知与小组件刷新，让蜂窝射频顺畅进入 `RRC_IDLE` |

此外，在无物理网络连接时自动挂起轮询与探测；`libbox.so` 采用按需延迟加载，空闲期小组件/磁贴广播唤醒保持轻量内存占用。

---

## 项目结构

```text
singscale/
├── app/                                      # Android 原生客户端 (Kotlin + Jetpack Compose + Material 3)
│   ├── build.gradle.kts                      # Android 构建与可选 Release 签名配置
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/dev/singscale/app/
│       │   │   ├── core/                     # 数据模型、sing-box 配置构建器、质量引擎、分流追踪、三态功耗监控、TUN 动态绑定状态机
│       │   │   ├── service/                  # VpnService、PlatformInterface、物理网络监听、并发受控本地 DNS
│       │   │   ├── widget/                   # 1×1 / 4×2 桌面小组件、Canvas 渲染与 Quick Settings 快捷磁贴
│       │   │   └── ui/                       # 主题、可视化组件与连接/分流/节点/设置页面
│       │   └── res/                          # 图标、小组件布局与主题资源
│       └── test/java/dev/singscale/app/      # 引擎、路由、功耗、TUN 绑定与切网防崩溃单元测试
├── core/sing-box/                            # 基于 sing-box v1.14.4 定制扩展的 Go 内核源码
│   ├── adapter/singscale.go                  # SingScale Tailscale 扩展接口契约
│   ├── common/trafficcontrol/singscale_split.go # 本地直连 / 组网直连 / 中继三路互斥原子分桶与驱逐守恒
│   ├── protocol/tailscale/singscale_ext.go   # Tailnet 快照、实时链路分类、MaskedPrefs 热更新、Disco 探测、网关扫描与休眠控制
│   ├── protocol/tailscale/singscale_inbound.go # 被动事件驱动 Tailnet 入站本机回环端口透传引擎
│   ├── protocol/tun/singscale_dns_fallback.go  # /32 与 /128 单主机前缀 MagicDNS 兜底保护
│   ├── route/singscale_direct_first.go       # 直连优先 TCP+TLS RTT 探测与结果缓存引擎
│   └── experimental/libbox/                  # Android gomobile 绑定、SingScale RPC、Go 1.27 OOM 剖析兼容与内存预算解耦
├── scripts/
│   ├── build_libbox.ps1                      # 从 core/sing-box 编译生成 app/libs/libbox.aar
│   └── build_apk.ps1                         # 自动检查/构建内核并打包 Debug/Release APK
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 构建与开发说明

### 环境要求
- **Go**：`1.24+`（已兼容 Go 1.27，需位于 `PATH` 中）
- **SagerNet gomobile**：用于将 `core/sing-box/experimental/libbox` 绑定为 Android AAR
  ```bash
  go install -v github.com/sagernet/gomobile/cmd/gomobile@latest
  gomobile init
  ```
- **JDK**：`JDK 17+`（配置 `JAVA_HOME` 或加入 `PATH`）
- **Android SDK & NDK**：Android SDK Platform `35`、Build-Tools `35.0.0` 及 Android NDK（推荐 `28.0.13004108`，配置 `ANDROID_HOME`）

### 1. 编译 Go 原生内核 (`app/libs/libbox.aar`)
源码仓库不包含体积较大的预编译 `.aar` 二进制文件，首次构建 Android 客户端前需先编译内核：

```powershell
# Windows (PowerShell)
powershell -ExecutionPolicy Bypass -File .\scripts\build_libbox.ps1
```

Linux / macOS 可在 `core/sing-box` 目录下使用相同的 `gomobile bind` 参数手动编译输出至 `../../app/libs/libbox.aar`（详见 `scripts/build_libbox.ps1`）。

### 2. 运行测试与构建 APK
```bash
# 运行 Go 扩展单元测试
cd core/sing-box
go test -ldflags="-checklinkname=0" -tags "with_gvisor,with_tailscale,badlinkname,tfogo_checklinkname0" ./common/trafficcontrol ./route ./protocol/tailscale ./protocol/tun ./experimental/libbox ./experimental/libbox/internal/oomprofile ./experimental/libbox/internal/runtimeinfo ./service/oomkiller

# 返回仓库根目录运行 Android 单元测试与 Debug 包构建
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

或使用脚本打包（Windows PowerShell）：
```powershell
# 构建 Debug APK
powershell -ExecutionPolicy Bypass -File .\scripts\build_apk.ps1 -BuildType debug

# 构建 Release APK
powershell -ExecutionPolicy Bypass -File .\scripts\build_apk.ps1 -BuildType release
```

### 3. Release 签名配置（本地准备，切勿提交）
`debug` 构建默认使用 Android 标准调试签名，可直接本地开发测试。如需签发已签名的 `release` APK，请在仓库根目录（或 `app/` 目录）自行创建本地 `keystore.properties`（已被 `.gitignore` 忽略）：

```properties
storeFile=path/to/your-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

也可以通过环境变量提供签名参数：`SINGSCALE_STORE_FILE`、`SINGSCALE_STORE_PASS`、`SINGSCALE_KEY_ALIAS`、`SINGSCALE_KEY_PASS`。未配置签名时，`:app:assembleRelease` 将生成未签名 Release APK。

---

## 开源协议与声明

- **SingScale Android 客户端与构建脚本**（`app/`、`scripts/`）：基于 [MIT License](LICENSE) 开源，Copyright (c) 2026 OrchidSail。
- **定制内核与第三方组件**：
  - `core/sing-box/` 衍生自 [SagerNet/sing-box](https://github.com/SagerNet/sing-box)，保留并遵循其原有 **GNU General Public License v3.0 or later** 许可（详见 [`core/sing-box/LICENSE`](core/sing-box/LICENSE)）。
  - 组网协议栈依赖 [Tailscale](https://github.com/tailscale/tailscale)（`tsnet`），遵循其上游 BSD-3-Clause 许可。
  - 若分发链接了 `libbox.aar` 的完整 APK 二进制安装包，请遵守 GPLv3 的相关开源义务。
- **免责声明**：本项目仅用于合法合规的个人私有组网、远程设备互联与网络工程技术研究，使用者需自行遵守所在国家与地区的法律法规。
