# 私有接口 v1

HTTPS/WSS，默认端口 48761。只有 `/api/v1/pair` 不要求 Bearer 设备凭证。所有请求仍受 Tailscale 来源限制。没有浏览器跨域授权，不接受 URL 查询形式的令牌，不跟随重定向。

| 路径 | 作用 |
| --- | --- |
| POST `/api/v1/pair` | 电脑确认、一次性配对；需 hostId、secret、deviceName |
| GET `/api/v1/status` | hostId、instanceId、桌面连接、兼容性与错误状态 |
| GET `/api/v1/threads` | 本机原桌面现有会话目录 |
| GET `/api/v1/projects` | 这台电脑已登记的本地项目及当前实例；排除 SSH／云项目 |
| GET `/api/v1/threads/{threadId}` | 原会话历史、实时状态、输入请求和可显示执行结果 |
| GET `/api/v1/threads/{threadId}/fork-context` | 本机原会话的已确认工作目录、轮次、Fork 及工作树可用性 |
| WSS `/api/v1/events?instance=...&after=...` | 每台电脑一条复用事件流；支持有界续传及 reset |
| POST `/api/v1/operations` | 有限类型的控制请求，不是任意 RPC 入口 |
| GET `/api/v1/operations/{operationId}` | 查询已持久化操作结果，不重新执行 |

操作包括 hostId、instanceId、threadId、operationId(UUID)、type；针对轮次的操作还包括 turnId。支持 send、steer、interrupt、decision、answer、activate、create、fork。activate 只打开目录中已存在的原桌面会话。create 为用户明确点击“创建并发送”的新建操作，threadId 必须为空，不得借它操作已有会话。

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
