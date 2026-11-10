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
普通 CI 不需要发布私钥；签名发布工作流通过 GitHub Secrets 在临时目录恢复密钥，结束后删除。

## 从 GitHub Actions 下载与发布 APK

每次推送 `main` 或运行 **Actions → Build and Test**，Android 编译、单元测试和 lint
通过后都会保存 `codex-remote-android-debug-<提交号>`。打开该次运行，在 Artifacts
下载并解压，即可取得 `app-debug.apk`；运行摘要也有下载链接，产物保留 14 天。
调试 APK 使用开发签名，不能覆盖正式发行版；不要卸载带有配对数据的正式版来安装它。

正式 APK 使用 **Actions → Android Release**。先由维护者在
**Settings → Secrets and variables → Actions** 配置两个仓库 Secret：

| Secret | 内容 |
| --- | --- |
| `CODEX_SIGNING_STORE_BASE64` | 原发行 keystore 文件的 Base64 内容 |
| `CODEX_SIGNING_PASSWORD` | 原 keystore 和密钥密码，别名仍为 `codex-remote` |

在保存原签名文件的电脑上，可以直接通过 GitHub CLI 上传，不把密钥或密码发到聊天、
Issue、工作流日志或源码中：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('D:\签名文件目录\release.jks')) |
  gh secret set CODEX_SIGNING_STORE_BASE64 --repo faithererer/codex-remote
gh secret set CODEX_SIGNING_PASSWORD --repo faithererer/codex-remote
```

第二条命令在终端提示输入密码。Base64 只是传输编码，文件仍是私钥，交给 GitHub Secrets 保存。

推送 `v0.1.5` 这样的版本标签会自动签名发布；已有标签可以在 **Android Release → Run workflow**
填写 `v0.1.5` 补发。工作流构建标签对应的代码，检查标签与 `versionName` 一致、存在对应
`docs/RELEASE-<版本>.md`，并在 release 构建／单元测试／lint 通过后验证 APK 签名。
签名必须与 `docs/VERIFICATION.md` 记录的原发行证书一致；缺少 Secret 或证书不符会停止发布。

成功后 APK 自动加入对应 GitHub Release，并更新 `SHA256SUMS.txt`，保留同一发布中
已有 Windows／源码文件的校验条目。对应 Release 的说明会采用该标签中的版本说明。
签名产物也保留为 Actions artifact 30 天；工作流不安装到手机、不操作真实会话。

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
