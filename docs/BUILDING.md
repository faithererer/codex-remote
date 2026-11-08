# 从源码构建

构建只需要源码与公开依赖，不需要维护者的账号、手机配对信息或签名私钥。
建议在单独的开发目录与空白测试配置中进行，不接管正在使用的对话。

## 工具版本

| 工具 | 版本 |
| --- | --- |
| .NET SDK | 10.0.x，Windows 桥接必须在 Windows 上构建和运行测试 |
| JDK | 17 |
| Gradle | 8.13 |
| Android SDK Platform / Build Tools | 35 / 35.0.0 |
| Node.js | 22 或更新版本，用于辅助脚本 |

不要求修改系统 PATH；可把工具解压到工作目录，使用完整路径或仅对当前终端设置环境变量。
本仓库不附带 Gradle Wrapper，CI 用 setup-gradle 获取固定版本。

## Android 开发构建

在当前终端设置 `JAVA_HOME`、`ANDROID_HOME`，安装 SDK 平台和构建工具：

```sh
sdkmanager "platforms;android-35" "build-tools;35.0.0"
gradle -p android assembleDebug testDebugUnitTest lintDebug --console=plain
```

调试 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`，使用开发签名，
不能覆盖不同签名的已发行 APK。不要为运行测试而卸载带有正式配对数据的 App。

## Android release

用 `keytool` 自行生成发布 keystore，保存在源码目录以外。仅在当前终端设置：

- `CODEX_SIGNING_STORE`：该 keystore 的绝对路径。
- `CODEX_SIGNING_PASSWORD`：keystore 和密钥密码。
- 密钥别名必须为 `codex-remote`。

```sh
gradle -p android assembleRelease testReleaseUnitTest lintRelease --console=plain
```

产物为 `android/app/build/outputs/apk/release/app-release.apk`。
完成后清除当前终端中的密码变量，不把密钥、密码或 `local.properties` 提交到 Git。
仓库 CI 不需要也不持有维护者的 release 私钥。

## Windows 便携程序

```powershell
dotnet publish windows/CodexRemote.csproj -c Release -r win-x64 --self-contained true -o dist/windows --nologo
$output = Join-Path $env:TEMP 'codex-remote-self-tests.json'
$process = Start-Process -FilePath (Resolve-Path 'dist/windows/CodexRemote.exe') -ArgumentList @('--self-test', ('"' + $output + '"')) -WindowStyle Hidden -Wait -PassThru
Get-Content -LiteralPath $output
```

自测使用独立的临时 DPAPI 数据，不读取正式配对凭据，也不发送模型指令。
发布便携包时同时附带 `LICENSE`、`THIRD-PARTY-NOTICES.md`、`licenses/` 和部署说明。
不要打包 `%LOCALAPPDATA%\CodexRemote`。

## 一次构建两端

`node tools/build.mjs` 默认从源码目录相邻的 `toolchains/` 寻找工具，也支持
`CODEX_DOTNET`、`CODEX_GRADLE`、`JAVA_HOME`、`ANDROID_HOME`。
需要先提供你自己的 Android release 签名环境变量；脚本不会输出密码。

## 测试边界

- `node tools/audit-source.mjs` 检查暂存／跟踪文件中的已知敏感文件和令牌模式，不能替代人工检查。
- `--api-test` 需要本机局域网或 Tailscale 私有 IPv4，使用两个临时身份和私网端口；两者都存在时验证双入口身份与去重。它不修改防火墙，也不等于两台真实电脑或手机跨设备连接。
- `--smoke` 运行原桌面的握手、工具契约和实时状态自检，并只读模型目录，不发送、切模型、编辑、审批、中断或 Fork。未知版本号／归档哈希不会阻止自检；通过不等于该构建所有功能已验收。
- Android 双连接仪器测试仅可在无正式配对的空白测试配置中运行；用运行参数提供临时配对数据，不在源码写入凭据。测试不发送指令，结束仅移除该测试新增的身份。
- 无法可靠执行的真机 UI 自动化已显式忽略；手动验收按 `ACCEPTANCE.md` 进行。
- CI 不执行真实会话控制、真实 Fork、多 PC 或锁屏／移动网络测试。
