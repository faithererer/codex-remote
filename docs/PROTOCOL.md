# 私有接口 v1

HTTPS/WSS，默认端口 48761。只有 `/api/v1/pair` 不要求 Bearer 设备凭证。局域网入口限制 RFC1918 来源，Tailscale 入口限制 `100.64.0.0/10` 来源，以实际连接的本地／远端地址判断，不信任转发头。没有浏览器跨域授权，不接受 URL 查询形式的令牌，不跟随重定向。

| 路径 | 作用 |
| --- | --- |
| POST `/api/v1/pair` | 电脑确认、一次性配对；需 hostId、secret、deviceName |
| GET `/api/v1/status` | hostId、instanceId、桌面连接、兼容性与错误状态 |
| GET `/api/v1/threads` | 本机原桌面现有会话目录 |
| GET `/api/v1/projects` | 这台电脑已登记的本地项目及当前实例；排除 SSH／云项目 |
| GET `/api/v1/threads/{threadId}` | 原会话历史、实时状态、输入请求和可显示执行结果 |
| GET `/api/v1/threads/{threadId}/fork-context` | 本机原会话的已确认工作目录、轮次、Fork 及工作树可用性 |
| GET `/api/v1/threads/{threadId}/models` | 原会话模型／强度和本机提供商的可验证配置目录，不包含账号密钥 |
| WSS `/api/v1/events?instance=...&after=...` | 每台电脑一条复用事件流；支持有界续传及 reset |
| POST `/api/v1/operations` | 有限类型的控制请求，不是任意 RPC 入口 |
| GET `/api/v1/operations/{operationId}` | 查询已持久化操作结果，不重新执行 |

操作包括 hostId、instanceId、threadId、operationId(UUID)、type；针对轮次的操作还包括 turnId。支持 send、steer、interrupt、decision、answer、activate、create、fork、model、edit。activate 只打开目录中已存在的原桌面会话。create 为用户明确点击“创建并发送”的新建操作，threadId 必须为空，不得借它操作已有会话。

hostId 来自每台电脑本地持久化身份，不使用电脑名或 IP。instanceId 在桥接启动、IPC 断开或原桌面后端进程变化时更新。实际发出控制前再次检查桌面后端身份及目标轮次。审批还匹配真实未解决请求的 ID、类型和轮次；未知请求不转交手机执行。

操作去重记录在联系桌面前同步写盘，包含完整请求的指纹。相同 operationId 及相同内容返回原记录；同 ID 不同内容拒绝。崩溃、超时或发出后错误保持 uncertain，不自动补发。即便重连后查不到记录，手机仍要求人工核对，不据此自动执行。

事件队列有界；缓存丢失、过旧游标、实例改变、慢消费者队列溢出时，重新读取真实状态。不同电脑连接的事件、身份、凭据、草稿、历史和操作锁定按 hostId 隔离。通知目的地使用 hostId/threadId 的完整身份，不依赖标题。

本机适配器只有桌面目录／历史／项目读取、打开原会话、新建本地会话、Fork 原会话、原会话 follower 操作的固定允许列表。不会提供远程工具枚举、通用 MCP 调用、shell、文件写入或原始 app-server 端口。

## 0.1.1 展示字段（向后兼容）

会话响应增加 `latestReasoning`，为当前运行轮次（空闲时为最后一轮）的最新非空摘要段，或 `null`。新轮次没有摘要时不能继承上一轮。`messages` 为兼容旧客户端也只保留这一条思考记录，不再堆积所有摘要；手机用独立的单行状态原位更新它。

命令记录增加独立的 `command`、`output`、`exitCode`、`status`、`durationMs` 和 `turnId`，避免从拼接文本猜测多行命令与结果的分界。`text` 仍为旧客户端保留。`mcpToolCall` 增加 `toolName`。这些字段只影响展示，不改变控制、配对、实例校验与操作去重协议。

## 0.1.2 文件差异与创建

`fileChange` 增加 `changes` 数组，每项为 `path`、`kind`、`movePath`、`diff` 和 `truncated`。只转发原 Codex 提供的差异，不读取磁盘文件。单文件最多 96,000 字符、单次修改总计最多 320,000 字符、最多 100 个文件，截断明确标记。原轮次汇总 `diff` 最多 160,000 字符。二进制或无差异数据时不能伪造内容。

create 操作另含 `text`（首条指令）、可选 `title`、`location`（default/project）及 `projectId`。default 调用原桌面的 projectless 创建入口，由电脑决定默认工作目录；project 在提交前重新读取原桌面项目表，只接受 `projectKind=local` 且 `hostId=local` 的真实项目 ID，使用 local 环境。客户端不能指定任意路径、云端、SSH、worktree、分支、模型或权限覆盖。

新建也先持久化去重意图，再调用原桌面。返回的真正 `threadId` 才可用于后续会话操作，`clientThreadId` 绝不能作为就绪会话 ID。超时或断线时保留未确认状态，不自动再次创建；新实例拒绝旧创建请求。手机草稿、项目选择和发送锁按电脑隔离。新建成功后触发目录刷新；如第一轮失败，不重发首条指令。

AI Markdown 在手机本地解析，不执行 HTML、不加载远程图片；仅对用户明确点击的合法 HTTP/HTTPS 链接调用浏览器。

## 0.1.3 消息去重与 Fork

优先用原轮次 params.clientUserMessageId 与 items.userMessage.clientId 匹配原始输入回显。回显替换输入的临时行，稳定显示 ID 为 turnId-user，并保留 nativeId。旧快照无客户端标识时，只匹配轮次的第一项、且正文相同的无标识 userMessage。不同客户端标识、不同轮次、后续同文消息及 steeringUserMessage 不合并，不按正文全局去重。

Android 的操作锁在点击时同步设置；仓库层采用按 hostId 的 tryLock，拒绝同机并发意图而非排队提交。持久化操作 ID 去重和未确认状态锁仍保留，不用时间窗口代替幂等。

fork 操作的 threadId 是原会话，另带 environment（same-directory/worktree）、latestTurnId 和空 turnId。桥接重新核对本机桌面目录、真实快照、实例及最新轮次；临时会话、运行中、有待处理请求、缺少工作目录或来源变化均拒绝。worktree 还要求原工作目录或其父级存在 Git 元数据。只向原桌面 fork_thread 传 threadId 与 environment.type；路径、分支、提示词、模型及权限覆盖不转发。

Fork 同样在派发前持久化意图，派发后异常保留 uncertain。same-directory 只有 status=created 且 synchronization.status=complete、真实 threadId 格式有效时才自动打开。worktree 的 queued/clientThreadId 仅表示已提交，不能作为真实子会话路由。设置未完成时保留来源电脑隔离的结果提示，不自动发送指令或再次 Fork。用户可在电脑核对后清除提示；清除不是撤销或重试。

## 0.1.4 模型、编辑及完成标识

`model` 操作带 `catalogInstanceId`、`expectedModel`、`expectedEffort`、`expectedProvider`、`model`、`effort`。仅接受该电脑且提供商匹配的已配置目录项，提交前重新读取和校验。原生 `thread-follower-update-thread-settings` v2 只传 model、effort，附 ifModelEquals／ifEffortEquals 条件，省略 activeTurnId 使其用于下一轮；不转发任意设置、路径或权限。条件未满足时不应用，客户端提示重新选择。

配置目录只在明确配置 `model_catalog_json`、提供商相符、未检测到项目级配置时使用；TOML 使用解析器而非正则，不对手机开放配置文件读取。解析失败只保留当前模型，不以其他 PC、猜测模型或凭据换取可选项。

会话响应增加 `editTarget`（turnId、fingerprint、text、allowed、reason）。`edit` 操作携带 `editInstanceId`、`editTurnId`、`editFingerprint` 和修改后的 text，校验仍是最后一轮、无运行／待处理事项且原消息未变。当前只支持单条纯文字初始消息，原轮次必须明确使用桌面默认权限；含追加、附件或结构化上下文的消息留在电脑处理。调用原生 `thread-follower-edit-last-user-turn` v2，固定 allowEarlierMessages=false、shouldSendPermissionOverrides=false，不自行发原始 rollback 或直接修改记录。原生会先撤回再重生成；失败或断线可能只完成前半步，必须人工核对，不自动再次提交。

会话响应增加 `latestTurnId`；agentMessage 增加 `phase`、`turnStatus`。只有 final_answer 且 turnStatus=completed 才标注完成。手机未读完成标记和首页筛选用 Keystore 加密保存，标记键含 hostId／threadId，读取还核对 turnId，不修改桌面的已读状态。

## 0.1.4 运行时兼容性

`status` 增加 `knownBuild`、`compatibilityMode=runtime-protocol` 和 `compatibilityNote`；原有 `compatible` 表示当前握手与任务目录契约通过，不表示该构建每个操作已实测。桌面版本与归档哈希仅作诊断，不作为全局控制白名单。桥接把最近成功自检的构建、哈希、时间保存在本机 `compatibility.dpapi`，升级或 IPC 重连时重新检查。

建立连接前确认唯一原桌面 app-server、IPC 握手客户端身份、原生工具目录及 list_threads 输入契约；桌面工具每次调用核对实际公布的 schema。会话控制校验本机身份、完整历史结构、已知轮次状态和 requests；不支持的结构仅阻止该目标的控制，不写会话数据库或启动替代后端。

原生 follower 请求仍使用明确的方法版本。只有路由明确返回 no-client-found、no-handler-for-request 或 request-version-mismatch 才认定未派发并记为 rejected；不会自动改低版本或重发。派发后其他错误、超时和断线仍为 uncertain，持久化锁和人工核对保留。不增加远程工具枚举、任意 RPC 或版本检查绕过接口。

## 0.1.5 局域网与连接地址

配对码仍为 v1，`url` 可为 HTTPS 的 RFC1918 私有 IPv4 或 Tailscale IPv4，显式指定端口。选中的网络只决定二维码中的地址；同一桥接的可用局域网和 Tailscale 入口共用证书、hostId、instanceId、配对、事件和操作去重锁，不创建第二个 Codex 实例。

手机修改连接地址不调用配对或控制接口。先用新地址访问 `/status`，沿用原证书固定与设备凭证，再核对 hostId 和接口版本，成功才写入该电脑的地址。失败保留原地址。手机关闭旧连接并丢弃其迟到事件／状态，重新读取真实会话，未确认操作仍查询原 operationId；不清锁、不重发、不自动改用另一台电脑。

监听地址变化时重建网络监听，但不重启 Codex、不生成新身份或主动清除事件实例。只有原来的桥接／后端实例生命周期会改变 instanceId。地址发现不使用 DNS、广播、中央服务器或任意公网地址；IPv6、主机名和公网形式的局域网地址暂未支持。
