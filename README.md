# Codex 随行 0.1.3

中文 Android 多电脑 Codex 桌面控制台。Android 10 及以上；每台 Windows x64 电脑运行一个桥接程序，对接这台电脑原来的 Codex 桌面实例。

An unofficial Android companion for monitoring and controlling existing Codex desktop chats on multiple Windows PCs over a private Tailscale network. No central relay, cloud account transfer, or replacement model backend.

**实验性预览，不是 OpenAI 官方产品，也未经独立安全审计。** 当前仅适配下文注明的精确 Codex 桌面版本；不承诺任意新版兼容。两台物理 PC 联合验收及 Fork 真正创建端到端验收仍待完成。

## 下载

在本仓库的 [Releases](https://github.com/faithererer/codex-remote/releases) 下载 Android APK 和 Windows x64 便携包。每台电脑独立运行一个桥接并分别配对；不设置两台电脑的产品限制。

- Android：`codex-remote-0.1.3-android.apk`，Android 10 或更新版本。同一发行者的覆盖更新保留配对，不要先卸载。
- Windows：`codex-remote-0.1.3-windows-x64.zip`，自包含便携程序，无需另装 .NET Runtime。
- 发布页同时提供源码和 `SHA256SUMS.txt`。源码使用 MIT 许可证；第三方依赖遵守各自许可证。
- 自行构建使用自己的签名密钥；不同签名不能覆盖安装维护者的 APK。私钥不随源码或 Release 公开。

## 使用

1. 手机安装发布页中的 Android APK；覆盖更新保留原配对，不需要卸载。
2. 每台电脑解压 Windows 便携包，运行 `CodexRemote.exe`。电脑上照常启动原 Codex，手机、电脑都连接自己的 Tailscale。
3. 手机点“添加”，扫描电脑的配对二维码；再在电脑窗口确认。另一台电脑重复相同步骤。
4. “全部关注”同时显示各电脑任务。进入任务可查看历史、实时输出，发送文字指令、追加指示、中断、处理命令／文件审批及普通选择题。
5. 尚未加载到桌面的历史会话，点“在电脑打开这个现有会话并接入”。这只打开原会话，不创建新任务，但会切换电脑上的当前 Codex 页面。
6. 想在退出 App 后继续接收提醒，主动开启“离开应用后继续关注”，并允许系统通知。锁屏通知默认不显示对话正文。
7. 首页点“新建会话”，先选电脑，再选“Codex 默认目录”或该电脑已有的本地项目，填写首条指令，点“创建并发送”。默认目录不归属项目，由电脑创建独立工作目录。标题可选；点击后立即开始执行，不是仅创建一个空白占位。
8. 会话右上角点分叉图标“Fork 当前会话”：保留历史，在当前工作区（原目录）或新 Git 工作树中创建独立聊天。共用原目录会共用文件；新工作树从当前工作区状态创建隔离副本。Fork 不自动发送指令。原任务运行中或有待处理审批时需先等待结束，不会自动中断。

Android 通常只能启用一个 VPN。若当前使用 Clash 等其他 VPN，需由用户先切换到 Tailscale；仅安装 Tailscale 而未连接时不能访问电脑。配对页提供“打开 Tailscale”按钮，不会自动改动其他 VPN 的配置。

## 手机会话视图

- 同一轮的原始输入与 Codex 原生用户消息按客户端消息 ID 合并成一条，原生回显到达前后保持稳定显示 ID；不会按文本全局去重，真正重复发送、不同轮次及追加指示仍保留。
- 发送／追加／审批／新建／Fork 在点击时立即锁定；同一电脑已有操作提交中时拒绝第二次提交，不排队生成另一个操作。不同电脑互不阻塞。
- 进入会话直接定位到最新内容，在底部时跟随新输出。主动上翻历史时停止跟随，点“回到最新”恢复。
- 思考摘要不隐藏，只在输入框上方原位显示当前轮次的最新一条，新摘要替换旧摘要，不积累历史卡片。点摘要可看完整文本。
- 连续工具调用合并为紧凑的“执行记录”；展开可看到独立的命令、结果入口和退出码。长结果在单独滚动面板中查看，不挤占主对话。
- 文件修改单独显示文件名、类型和增删行数，不藏在折叠命令里。点卡片或输入框上方“本轮修改 → 查看 diff”，看完整路径、逐行红绿差异、行号和多个文件；支持左右滑动长行及复制。新增／删除文件的原始全文也会正确显示为增删行。
- AI 消息本地渲染 Markdown，支持标题、粗体、斜体、列表、引用、分隔线、代码块、行内代码、链接、删除线和表格。代码可复制，长代码默认折叠；不执行 HTML、不自动加载图片。
- 发送自己的新消息后回到最新；主动阅读历史时，新输出仍不会强行拉回。
- 顶部合并会话名与电脑名，减少重复信息、卡片留白及首页横幅占用。
- TLS 连接清理在后台线程执行，避免切换应用时在主线程关闭网络连接。

手机离线时只能保存草稿，不排队执行。发送结果不确定时不会重发，也不会把请求转到另一台电脑。先核对会话，再在“我的电脑 → 核对未确认操作”解除发送锁定。

## 与原计划不同的接入方式

最终使用当前 Codex 桌面已有的本地 IPC 路由与桌面会话所有者通信，而不是替换 CLI 的 stdio 启动入口。原因是该版本的 CLI 覆盖入口会改变桌面注入的 Windows 沙箱上下文。

本实现不更换 CLI、不另起模型后端、不修改安装包、不写会话数据库、不拷贝账号，也不修改沙箱／审批／模型设置；不需要重启正在工作的 Codex。所有任务仍在各自电脑的原桌面后端运行。

这是版本相关的非稳定桌面入口，不是官方保证的移动控制 API。控制仅允许当前已核对的桌面包：`26.1013.4894.0`，并匹配 `app.asar` 的完整 SHA-256。版本或文件不匹配时锁定控制，不降级到另一个后端。

| 组件 | 当前兼容范围 |
| --- | --- |
| Android | 10 / API 29 起，目标 API 35 |
| Windows 桥接 | Windows x64，.NET 10 自包含 |
| Codex 桌面包 | `OpenAI.Codex_26.1013.4894.0_x64__2p2nqsd0c76g0` |
| 桌面归档 SHA-256 | `BBEC1D64E135B718A154B31655BB8AD9A39472BAC1B2CABBA991A9D97A339DD8` |

版本检查不是可随意关闭的开关。适配新版需重新核对双向同步、桌面工具、审批、沙箱和错误处理，并增加回归测试。不要只改哈希就称为兼容。

## 首版限制

- 每台 PC 一个 Codex 桌面实例，不接管 CLI、IDE 或同一电脑的多个独立桌面实例。
- 列表显示桌面工具返回的最近 50 个非置顶会话及所有置顶会话。实时视图最多显示最近 600 项，普通输出最多 20,000 字符；单文件 diff 最多 96,000 字符、一次文件修改最多 320,000 字符及 100 个文件、轮次汇总 diff 最多 160,000 字符。截断明确标记，完整内容保留在电脑。
- 新建仅支持所选电脑的本地项目或默认独立工作目录。不会把 SSH／云项目当作本机目录，不创建 worktree、不切换分支，不提供任意路径输入。使用该电脑原桌面的默认模型、工具和权限。首次创建仍由用户自己点“创建并发送”，未擅自发送测试任务。
- Fork 使用原桌面的 fork_thread 入口，显式指定本机原会话和 same-directory/worktree，不传路径、分支或模型权限覆盖。新工作树仅在原目录属于已确认的 Git 仓库时可选，由 Codex 异步设置；clientThreadId 不是就绪会话。历史同步未完成或设置失败时，不自动进入子会话发送指令，也不自动再次 Fork。弹窗保留上次结果，需要用户在电脑核对后才可清除提示。Fork 的真实创建端到端验证仍需用户明确点击，未在开发时擅自复制现有聊天。
- 普通命令／文件审批只提供“允许本次”和“拒绝”，不提供永久批准或自动批准。权限扩展、未知工具和特殊问题由桌面处理。
- 不含独立终端、任意 RPC、文件直接写入或跨电脑转移任务功能。
- 后台服务为用户主动启动的 Android 前台服务；系统强杀、强行停止、VPN 断开、省电限制或电脑睡眠时，不承诺实时通知。重新打开会读取真实状态。
- 不能仅凭一台 PC 上的两个桥接测试进程声称“两台真实 PC 验收通过”。实际测试结果见交付的测试记录。

## 安全与回退

Windows 只绑定 Tailscale IPv4 地址的 `48761/TCP`，拒绝非 `100.64.0.0/10` 来源，不启用 Funnel。若 Windows 防火墙拦截，应只允许该程序在 Tailscale 私网上通信，不应关闭防火墙或开放公网端口。

配对码有效 5 分钟且一次性使用；电脑确认后才发设备凭证。Android 固定校验证书，使用 Android Keystore 加密本地数据；Windows 用当前用户 DPAPI 保护证书、设备记录和操作去重记录。凭证、证书私钥、APK 签名私钥不包含在交付包中。

退出桥接：托盘菜单选择“退出桥接”。不会关闭或中断 Codex。撤销手机：在该电脑桥接窗口撤销设备；不会影响其他电脑。手机移除电脑只删除手机侧信任，建议同时在电脑撤销。

完全卸载：退出桥接、删除便携程序、卸载 Android App；确认不再需要信任与去重记录后，可由用户删除 `%LOCALAPPDATA%\CodexRemote`。本程序没有安装系统服务、开机启动项、公共证书信任或系统工具链。

## 源码与构建

- `windows/`：.NET 10 Windows Forms 托盘、Kestrel HTTPS/WSS、DPAPI、配对、原桌面协议适配器与自测。
- `android/`：Kotlin + Jetpack Compose、证书固定、Keystore、多电脑连接、前台服务、通知及单元／真机测试。
- `docs/`：接口和验收说明。
- `tools/build.mjs`：独立工具链构建入口。

需要 .NET 10 SDK、Microsoft OpenJDK 17、Android SDK Platform / Build Tools 35、Gradle 8.13、Node.js。开发时全部工具位于工作目录 `work/toolchains`，没有安装到系统 PATH。下载的 .NET SDK、JDK、Gradle 已与其官方校验值比对。

将工具放在源码目录相邻的 `toolchains/`，或通过 `CODEX_DOTNET`、`CODEX_GRADLE`、`JAVA_HOME`、`ANDROID_HOME` 指定绝对路径。自行生成签名密钥后设置 `CODEX_SIGNING_STORE`、`CODEX_SIGNING_PASSWORD`，运行 `node tools/build.mjs`。构建脚本不附带也不会打印签名密码。

自测命令：`CodexRemote.exe --self-test result.json`、`--api-test api-result.json`、`--smoke smoke.json`。API 自测在一台 PC 的 Tailscale 私网临时使用 `48762/48763`，不等于两台物理 PC 测试。`--smoke` 只读原桌面，不发送任务指令。

详细构建步骤见 [docs/BUILDING.md](docs/BUILDING.md)，验证范围见 [docs/VERIFICATION.md](docs/VERIFICATION.md)，接口见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。CI 只构建和执行隔离测试，不连接维护者的电脑、不使用发行签名私钥，也不操作真实对话。

## 开源与反馈

- 许可证：[MIT](LICENSE)。第三方组件及许可证见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) 和 `licenses/`。
- 贡献规范：[CONTRIBUTING.md](CONTRIBUTING.md)。问题反馈请标明 Android、桥接和 Codex 桌面版本。
- 安全边界与私密漏洞报告：[SECURITY.md](SECURITY.md)。公开 Issue 不要贴配对二维码、令牌、私人对话或未脱敏截图。
- 本项目与 OpenAI、Codex、Tailscale 无隶属或背书关系；相关商标属于各自权利人。
