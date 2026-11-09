# AirStudy 服务接口

> **状态：FROZEN**。路径、字段与错误码的变更需要同步审查客户端、服务端和契约检查。

## 1. 通用约定

### 1.1 传输

| 项 | 值 |
|---|---|
| Base URL | `https://api.projectfocus.app/v1/`（Debug 可注入） |
| Content-Type | `application/json; charset=utf-8` |
| 认证 | `Authorization: Bearer <accessToken>` |
| Access Token TTL | 30 分钟 |
| Refresh Token TTL | 30 天（滑动续期） |
| 时钟 | 所有时间戳为 **Unix 毫秒**（`Long`）。不用秒，不用 ISO 字符串 |

### 1.2 统一响应包络

**所有**响应（含错误）都是同一个形状：

```json
{
  "success": true,
  "data": { },
  "error": null,
  "message": null
}
```

```json
{
  "success": false,
  "data": null,
  "error": { "code": 1001, "message": "邮箱或密码错误", "traceId": "..." },
  "message": null
}
```

> **★ 为什么保留 `message` 的同时还要 `error.message`**
> 竞品的响应是 `{code, message, data}` 平铺（见 APK1 `ApiResponse.java`），
> 我们把它包成 `ApiEnvelope` 是为了在**不破坏竞品字段语义**的前提下，
> 把「业务错误」与「HTTP 层错误」分开。`message` 只用于调试期的
> 人类可读提示，客户端**不得**用它做逻辑判断 —— 要判断一律看 `error.code`。

### 1.3 错误码段位（冻结）

| 段 | 含义 | 客户端统一行为 |
|---|---|---|
| `0` | 成功（不出现） | — |
| `1xxx` | 认证/账号 | 1001 密码错、1002 账号不存在、1003 token 过期 → 清 token 回登录 |
| `2xxx` | 房间/组队 | 2009 「不在任何房间」是**正常**业务态，不弹错误提示 |
| `3xxx` | 记录/数据 | 3001 记录不存在（已删除）→ 从列表静默移除 |
| `4xxx` | 设备/客户端 | 4001 版本过低（强制升级）、4002 请求体非法 |
| `5xxx` | 服务端内部 | 5003 数据库不可用 → 显示「稍后重试」而不是「出错了」 |

字符说明：`2001~2008` 已被房间状态机占用（房间已满/已开始/已结束等），
新增房间类错误从 `2010` 起。

---

## 2. 端点表（机器校验）

> 下方表格由 `ApiContractDocTest` 解析并与 Ktor 路由比对。
> **修改任一列都需要同步改代码，否则测试红。**

<!-- ENDPOINTS:BEGIN -->
| 方法 | 路径 | 鉴权 | 请求体 | 响应 data |
|---|---|---|---|---|
| GET | `/health` | 否 | — | `{status, db, sseConnections}` |
| POST | `/auth/register/code` | 否（本品邮件传输适配） | `{email}` | 无 data；HTTP200 表示 relay 接受 |
| POST | `/auth/register` | 否 | `{email, code, password}` | 无 data；HTTP201，随后显式登录 |
| POST | `/auth/password-reset/code` | 否（本品邮件传输适配） | `{email}` | 无 data；HTTP200 表示 relay 接受，404 账号不存在 |
| POST | `/auth/password-reset` | 否 | `{email, code, new_password}` | 无 data；HTTP200，返回且不自动登录；400 无效验证码，404 账号不存在 |
| POST | `/auth/login` | 否 | `RegisterRequest` | `AuthUserDto` |
| POST | `/auth/refresh` | 否 | `RefreshRequest` | `AuthUserDto` |
| POST | `/auth/logout` | 否 | `RefreshRequest` | `null` |
| GET | `/auth/promo-image` | 否（本品适配） | — | `{url}` |
| GET | `/auth/verify` | 是 | — | `AuthUserDto` |
| GET | `/subscription/status` | 是（本品适配） | — | `{isActive, isPremium, remainingDays, expiresAt}` |
| GET | `/user/me` | 是 | — | `UserProfileDto` |
| PATCH | `/user/update` | 是 | `UpdateUserRequest` | `UserProfileDto` |
| POST | `/user/content/check` | 否（本品适配） | `SceneContentCheckRequest` | `SceneContentCheckPayload` |
| POST | `/session/sync` | 是 | `BulkSyncRequest` | `SyncResultDto` |
| GET | `/session/history` | 是 | — | `SessionHistoryDto` |
| DELETE | `/session/{id}` | 是 | — | `null` |
| GET | `/v2/flight-records` | 是 | `after`, `limit` | `CloudFlightRecordPage` |
| PUT | `/v2/flight-records/{mode}/{id}` | 是 | `FlightRecordArchiveUpload` | `FlightRecordMutationReceipt` |
| PATCH | `/v2/flight-records/{mode}/{id}/focus-scene` | 是 | `FlightRecordScenePatch` | `FlightRecordMutationReceipt` |
| DELETE | `/v2/flight-records/{mode}/{id}` | 是 | — | `FlightRecordMutationReceipt` |
| POST | `/start` | 是 | `StartSessionRequest` | `SessionDetails` |
| POST | `/room/create` | 是 | `CreateRoomRequestDto` | `RoomResponseDto` |
| GET | `/code/{code}` | 否 | — | `RoomResponseDto` |
| POST | `/join` | 是 | `JoinRoomRequestDto` | `RoomResponseDto` |
| POST | `/leave` | 是 | — | `null` |
| GET | `/room/{id}` | 是 | — | `RoomResponseDto` |
| GET | `/my-room` | 是 | — | `RoomResponseDto?` |
| GET | `/public` | 否 | — | `RoomResponseDto[]` |
| PUT | `/schedule` | 是 | `UpdateRoomStartTime` | `RoomResponseDto` |
| POST | `/chat` | 是 | `SendChatRequest` | `ChatMessage` |
| GET | `/events` | 是 | — | `text/event-stream` |
| POST | `/freeflight/admission/prepare` | 会员 JWT；非会员按需真实设备凭据（本品适配） | `FreeFlightDeviceRequest` | `FreeFlightPrepareReply` |
| POST | `/freeflight/admission/formal` | 会员 JWT；非会员按需真实设备凭据（本品适配） | `FreeFlightFormalRequest` | `FreeFlightFormalReply` |
| POST | `/freeflight/admission/operation` | 原操作主体；设备操作另需原凭据（本品适配） | `FreeFlightOperationRequest` | `FreeFlightOperationReply` |
| POST | `/freeflight/admission/local-start-failed` | 原操作主体；设备操作另需原凭据（本品适配） | `FreeFlightLocalFailureRequest` | `FreeFlightLocalFailureReply` |
| POST | `/v2/airports/search/location` | 否（本品完整机场目录适配） | `AirportLocationRequest` | `NearbyAirportDto[]` |
<!-- ENDPOINTS:END -->

云端记录使用 `down` / `up` 区分两种计时方式，记录标识使用 UTF-8 表单编码写入单个路径段。所有请求须登录；成功返回 `{success:true,message:"",data:...}`。完整归档只接受已完成或已取消的飞行，保存机场快照和已有位置样本，不传递本机类别外键或账号归属。归档首次保存后保持不变，场景通过独立接口修改。

修改请求可携带 `Idempotency-Key`，重发相同操作返回原确认；同一操作编号用于其他内容返回 HTTP409。`If-Match` 接受带双引号的非负修订号，版本冲突返回 HTTP409。删除保留账号内的删除标记，重复删除成功；旧设备再次上传已删除记录返回 HTTP410。删除、重试确认和账号累计统计在同一事务内保存。两种计时方式和不同账号可以使用相同记录标识。

增量读取的 `after` 默认为0，`limit` 为1至100，默认为50。按账号内递增修订号返回修改和删除标记，客户端以 `nextCursor` 继续读取，直到 `hasMore` 为false。旧版缺少完整快照的记录不会补造机场坐标或位置样本。旧版倒计时记录与完整归档按账号和标识去重后参与累计统计。

附近机场查询使用本项目配置的服务，POST `{latitude, longitude, radius}`，其中坐标为当前真实飞行位置，`radius` 为向上取整的公里范围；自由飞行固定 `limit=50&offset=0`。服务支持 `limit=1..50`、非负 `offset`、有效经纬度和 `radius=1..3200`，非法值及未实现的 `region` 请求返回 HTTP400。成功返回统一包络，使用 `Cache-Control: private, no-store`。匿名查询不授予起飞资格、会员或设备次数。

目录为已交接 OurAirports CC0 快照的完整 83,798 条记录，不使用旧的 9,068 条筛选子集；打包文件 SHA-256 为 `a29575efd8c744bccc834b15000d87cb015cfccc5d7c11784f3a547db091696f`。服务按距离、源记录 ID 排序；原版在线目录、排序及分区政策无法从安装包证明，因此这是明确的自有后端适配。客户端保留返回顺序，另按原版未取整的时间范围、实际 HMS 距离和出发机场码执行筛选；更换范围取消旧查询并核对飞行与代际。查询失败保持不可确认，不能解释为空结果或降落成功。

机器校验现扫描所有 `*Routes.kt`，补回先前单文件扫描漏掉的订阅状态端点。
`GET /auth/promo-image` 只返回本项目配置的图片 URL，不在服务器下载图片，也不授予登录或权益。
部署时设置可选环境变量 `AIRSTUDY_AUTH_PROMO_URL`（HTTP/HTTPS 且不含用户信息）；未配置、无效或读取失败时返回 HTTP 503、`success=false`、错误码 `5032`，省略 `data`。
响应使用 `Cache-Control: no-store`。图片本体下载及按设备日历日缓存由客户端处理；该配置策略不代表原版图片服务或选图规则已经复现。

注册不再接受“邮箱密码即可注册并返回 token”的旧旁路。`email`、`code` 在客户端按 Java trim 处理，`password` 保持原样；服务端以本品规则归一化邮箱，验证一次性 code 后创建账户，不创建 access/refresh token、不授予权益。昵称不在原注册请求中，本品初始昵称为 `Traveler`，可在登录后通过档案端点修改。

本品验证码策略为 30 秒重发间隔、10 分钟过期、最多 5 次猜测；SQL 存储 generation 绑定的 HMAC，失败次数不会随错误响应回滚。发送在事务外执行，只有对应代际可启用，注册与消费同一事务提交。无效/过期 code 为 HTTP400 / code1015，重发过快为429 / code1016，邮箱已注册为409 / code1010，传输不可用为503 / code5033；原 App 的 HTTP 状态分流已证，这些具体服务器策略和业务码是本品适配。

邮件 relay 由服务器所有者配置 `PF_REGISTRATION_MAIL_RELAY_URL`（HTTPS；本机调试允许 loopback HTTP），可选 `PF_REGISTRATION_MAIL_RELAY_TOKEN`。本品 POST JSON `{purpose:"register", recipient, code, validForSeconds}`，可选 Bearer 仅用于此 relay；不复用原邮件服务或凭据。relay 返回 2xx 才算接受发送，不代表已到收件箱；不跟随重定向。未配置时真实返回503，不显示发送成功，也不把 code 返回给客户端。测试只注入独立本机 mailbox/HTTP peer，没有真实发信。

### 2.1 与竞品的差异（有意为之，逐条说明）

| 端点 | 竞品行为 | 我们 | 理由 |
|---|---|---|---|
| `GET /room/{id}` | 公开可读 | **需登录** | 房间详情含邀请码 + 全部成员昵称。公开等于泄露他人名单 |
| `POST /leave` | 无 body，靠「当前房间」推断 | **保持一致** | 数据模型上不允许同时在两房间，改形状无收益 |
| `DELETE /user/update` | 竞品用 DELETE（明显笔误） | `PATCH /user/update` | DELETE 语义是删除资源。保留兼容：服务端同时接受 DELETE |
| `POST /start` | 创建房间 | 保持 | 名字反直觉（创建房间叫 start），但已在契约里冻结 |
| `POST /room/create` | 不存在 | **新增** | 竞品把「建房间」和「开始飞行」混在一个端点。分开才能支持「先建房间、定时开飞」 |

---

## 3. 关键 DTO 字段表（机器校验）

> 下方每个 DTO 的字段名由 `ApiContractDocTest` 与 `Dtos.kt` 的 `@SerialName` 比对。

### 3.1 `ApiEnvelope<T>`

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `data` | T | 是 | 成功时的负载 |
| `error` | ApiErrorDto | 是 | 失败时的错误 |
| `success` | Boolean | 是 | 显式成功标记（竞品无此字段，我们加） |
| `message` | String | 是 | 仅调试用，**不得**用于逻辑判断 |

### 3.2 `AuthUserDto`

| 字段 | 类型 | 可空 |
|---|---|---|
| `id` | String | 否 |
| `email` | String | 否 |
| `token` | String | 否 |

### 3.3 `UserProfileDto`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | |
| `username` | String | |
| `avatarId` | Int | |
| `isPremium` | Boolean | 付费墙判断依据 |
| `xp` | Int | 经验值 |
| `streak` | Int | 连续天数 |
| `totalGroupFlight` | Int | |
| `totalHostedFlight` | Int | |
| `challengesCompleted` | Int | |
| `totalFocusedMinutes` | Int | |
| `countriesVisited` | Int | |
| `perfectWeeks` | Int | |
| `rank` | String | 等级名 |

### 3.4 `FlightHistoryDto`（同步用，**刻意不含** 6+6 个机场冗余字段）

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `id` | String | 否 | 客户端 UUID，服务端以此做幂等键 |
| `roomId` | String | 是 | |
| `focusType` | String | 否 | `SOLO` / `GROUP` |
| `departureAirportCode` | String | 否 | |
| `destinationAirportCode` | String | 否 | |
| `startTimeMillis` | Long | 否 | |
| `endTimeMillis` | Long | 是 | 进行中为 null |
| `durationMinutes` | Int | 否 | 计划时长 |
| `actualFocusMinutes` | Int | 否 | 实际时长 |
| `distanceKm` | Double | 否 | |
| `status` | String | 否 | `COMPLETED` / `CANCELLED` |
| `categoryId` | Long | 是 | |
| `seat` | String | 是 | |
| `logJson` | String | 是 | 逃生舱 |
| `createdAt` | Long | 否 | |
| `updatedAt` | Long | 否 | |
| `quote` | String | 是 | **竞品无此字段**（我们修的缺陷） |
| `quoteAuthor` | String | 是 | 同上 |

> **★ 为什么同步 DTO 只带三字码，不带机场全名/经纬度**
> 服务端有同一份机场主数据，按 code 关联即可。传 12 个冗余字段会让
> 一次同步的 body 从 ~300B 涨到 ~1.2KB，而 83% 是服务端已知的常量。
> 这与**本地库**的冗余策略相反 —— 本地冗余是为了「历史冻结」，
> 服务端关联是因为它的机场数据与客户端同源且同步更新。

### 3.5 `BulkSyncRequest` / `SyncResultDto`

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessions` | `FlightHistoryDto[]` | 批量；服务端单次上限 **100 条**，超出返回 HTTP 400、错误码 3010 |
| `accepted` | Int | 已写入的新记录及当前账号已有记录的确认条数 |
| `rejected` | Int | 校验失败或同编号记录属于其他账号的条数 |
| `serverIds` | `String[]` | 仅包含本次确认且属于当前账号的记录编号 |

同一账号重复同步已保存的编号时计入 `accepted`，保留原记录内容。同编号属于其他账号时计入 `rejected`，不返回该编号，也不修改原记录或双方统计。混合批次按请求条目分别计数；同账号在一批中重复的编号会分别确认，但只保存一条记录。并发首次提交同一编号时，仅实际保存的一方获得确认。

此接口仍使用计划飞行记录模型，正计时归档、历史场景更新和删除同步需要独立的完整流程。

### 3.6 `StartSessionRequest` / `SessionDetails`

| 请求字段 | 类型 |
|---|---|
| `durationMinutes` | Int |
| `departureAirportCode` | String |
| `destinationAirportCode` | String |
| `categoryId` | Long? |

| 响应字段 | 类型 |
|---|---|
| `id` | String |
| `startTimeMillis` | Long |
| `durationMinutes` | Int |
| `distanceKm` | Double |

### 3.7 `RoomResponseDto` 与参与者

房间信息里出发/目的地各有 **6 个字段**：`{prefix}AirportCode` / `Country` / `Name` / `State` / `Latitude` / `Longitude`，
`prefix` ∈ `departure` / `destination`。

> 与 `flight_history` 表一致：**冗余快照，不做关联**。
> 房间是「即将飞的航线」，机场信息必须冻结在创建那一刻 ——
> 否则用户看着「北京→大阪」的房间，机场库更新后突然变成「北京→东京」。

`ParticipantDto`：`id` / `userId` / `username` / `avatarId` / `joinedAt` / `leftAt` / `completed` / `seatNumber` / `seatColumn`

---

## 4. SSE 事件契约（`GET /events`）

连接即推一帧 `room_snapshot`（只发给这条连接），之后是广播帧。

| event | data 字段 | 触发 |
|---|---|---|
| `room_snapshot` | `roomId, state, inviteCode, durationMinutes, startedAt, willStartAt, participantCount` | 连接建立时，一次 |
| `room_state` | `roomId, state` | 房间状态迁移 |
| `participant_joined` | `roomId, userId, username, avatarId, seatNumber?` | 有人加入 |
| `participant_left` | `roomId, userId, voluntarily` | 有人离开 |
| `chat` | `roomId, id, sender, message, createdAt` | 新消息 |
| `tick` | `roomId, remainingSeconds, elapsedSeconds` | 服务端权威倒计时（1s 间隔） |

**重连语义**：客户端断线后从 `Last-Event-ID` 续传不是我们支持的（服务端不保留事件缓冲）。
重连 = 收到新的 `room_snapshot` 并**用它覆盖本地状态**。这是刻意的简化：
保留缓冲要一个 ring buffer + 过期策略，而房间状态是「当前态」而非「事件流」。

---

## 5. 客户端契约测试清单

| 测试 | 守什么 |
|---|---|
| `ApiContractDocTest` | 本文档的端点表/字段表 ⇄ 代码 |
| `FocusApiContractTest` | Retrofit 注解的路径/方法 ⇄ 本文档 |
| `MockFocusApiTest` | Mock 实现的行为 ⇄ 真实服务端语义 |
| `ApiResultMapperTest` | 错误码段位 → UI 行为映射 |

---

## 附：冻结记录

| 日期 | 变更 | 影响 |
|---|---|---|
| 2026-10-26 | v1.0 冻结 22 个端点 + 6 类 DTO | 客户端 v0.1.0-m1 |


## 新增：本项目场景名称内容检测

`POST /user/content/check` 是本项目只读匿名适配端点，不读取/写入用户资料。
原回调严格接受布尔 `true` 已取证；原 HTTP envelope、鉴权和服务端审核规则未证。
本端点不调用原第三方服务、凭据或云审核。

请求为 `application/json`，`{"text":"深度阅读"}`。`text` 必须实际为 JSON
String；缺失、null、数字、布尔、数组、对象或畸形 JSON 返回 HTTP 400、
`error.code=4001`，无可用的 `data.allowed`。

规则允许/拒绝均使用 HTTP 200 和项目 `ApiEnvelope`：
`{"data":{"allowed":true}}` 或 `{"data":{"allowed":false}}`。
共享 `SceneContentCheckPayload.allowed` 保留 `JsonElement?`，服务端仅输出
`JsonPrimitive(Boolean)`；客户端只放行实际布尔 true，不能接受字符串 `"true"`。
未完成或未知故障必须是错误响应；现有 StatusPages 对未知异常返回 HTTP 500、
`error.code=5000`，不得降级为允许，也不得在客户端校验失败时保存。

默认使用项目实际规则引擎：先拒绝 raw ISO 控制字符，再复用现有
`AuthService.validateNickname` 的 trim、非空和 20 Unicode code point 限制；
NFKC 与 `Locale.ROOT` 大小写规范化后按项目自有基础辱骂词表做字面子串检测。
这不是原场景字段上限或原敏感词策略，也不是完整语义审核；UI 不因此增加
未经取证的 `maxLength=20`。规则词表的子串匹配可能拒绝含该字面文本的名称。

部署可设置 `PF_SCENE_CONTENT_DENY_TERMS_JSON` 为额外字面词数组，例如
`["spoiler","刷单"]`；它追加到基础词表，不清空基础规则。配置不是数组、含
非字符串、空白词或控制字符时启动失败，不安装 always-allow 回退。
空数组代表不追加，基础规则仍然有效。不新增第三方库或下载词库。


## 新增：本项目自由飞行额度准入

这里的四个 POST 是本项目接口和持久化政策，不是原服务地址/请求形状或赠送策略。使用统一 `ApiEnvelope`；HTTP 200 且 `success=true` 才有可读 data。服务端异常或不完整包络不能折算为允许，也不能被当成明确额度拒绝。响应 `Cache-Control: private, no-store`。设备凭据只进请求体，不进 URL/查询字符串。

会员路径不要求 `hwid/credential`，也不读取 Android ID。非会员实际需要设备额度时，`hwid` 是本项目运行时实际 Android ID 与包名的 SHA-256 摘要（64 个小写十六进制字符）；`credential` 是安装时持久化的随机 32 字节凭据，以无 padding URL-base64 表示（43 字符）。这不是原版硬件指纹或硬件认证。后台只保存其 HMAC，丢凭据、设备迁移、轮换服务端秘密的恢复政策尚未完成。客户端自报设备 ID 不能据此声称完全防刷。

有效 JWT 映射到真实存在的用户；每一阶段先查真实 `SubscriptionEntitlements`。只有 `isActive && isPremium && remainingDays>0` 才走会员，不读 `Users.isPremium` 或客户端缓存。无效/缺失 JWT 只可按游客设备策略处理；不会获得会员。原操作记录按实际用户，或真实设备游客主体绑定，换账号不能重放或读取。operationId 全局唯一，防同一捕获 bearer 过期后变成游客主体而重新扣次；同一主体的 sessionId 也唯一。会员记录的 hwid 为 null，不伪造或迁移成设备记录。设备额度在同一 hwid 下共享，换账号不重新发放。

### 准备与正式申请

- `prepare` 首先请求 `{hwid:null, credential:null}`（也可省略字段）；响应 `{allowed, source, reason, remainingUses?, totalUses?}`。真实会员直接获准，跳过设备捕获/校验/登记。非会员返回 `DEVICE_IDENTITY_REQUIRED` 后，Android 才捕获真实设备并带 `{hwid,credential}` 继续准备；设备登记/查询不扣次。默认无赠送。只有显式 `PF_FREEFLIGHT_INITIAL_DEVICE_USES` 配合 `PF_FREEFLIGHT_DEVICE_POLICY_ID`，或内部 `provisionDevice` 的真实授权记录才可用。未配置返回 `POLICY_UNCONFIGURED`；显式 0 不可准备，是本项目保守政策。原版首次登记成功即可准备，原默认赠送/重置量未知，两者不可混写。
- `formal` 首先请求 `{operationId, sessionId, requestFingerprint}`，不提供设备。真实会员直接获准；非会员收到明确 `deviceRequired=true` 后才捕获、持久绑定真实设备，再带 `{hwid,credential}` 继续同一 operation。此证明握手属于本项目协议，不等于原服务形状；无 transport 失败后的自动重发。operationId 是客户端持久化 UUID；sessionId 为本项目会话（1–128 个字母/数字/点/下划线/冒号/横线），requestFingerprint 为冻结请求的 SHA-256。会员新查询仍优先；设备仅在正式事务扣一次。操作、主体、会话、指纹不可变，原决定与扣次同事务保存。同一操作重放返回原决定/原时刻；同主体会话换操作、相同 operation 跨主体或改指纹返回 409。成功扣到剩余 0 仍获准。
- 正式响应 `{operationId, sessionId, requestFingerprint, approved, source, reason, remainingUses?, decidedAtMillis, deviceRequired}`。source 为 `MEMBER / DEVICE / NONE`；reason 为 `ALLOWED / POLICY_UNCONFIGURED / DEVICE_NOT_REGISTERED / DEVICE_DISABLED / EXHAUSTED / DEVICE_IDENTITY_REQUIRED`。`deviceRequired=true` 仅可配 `approved=false/source=NONE/reason=DEVICE_IDENTITY_REQUIRED/remainingUses=null`：不是最终决定，不新增扣次或覆盖已有决定，时间只是本次检查时刻，不得发给引擎 Decided。已有设备操作也可能要求补原证明，因此它不证明该 operation 从未消费；必须继续相同 ID，由原事务去重/重放，不能另建扣次。最终回复 deviceRequired=false，decidedAtMillis 才是持久决定时刻。业务拒绝是 HTTP 200 的真实 `approved=false` 决定；设备资料非法为 400，凭据不符为 401，操作关联冲突为 409。获准不包含云飞行记录 ID，Android `Accepted.remoteRecordId=null`。

### 未确认、查询与本地失败

实际正式网络请求独立于屏幕，使用捕获的 bearer，离开真实账号/Core 捕获 lease 后才发，不在 lease 内等网络。第 8 秒尚无决定时，Android 必须先提交真实本地待确认收据再发 `Pending`。它不开始飞行、不退款、不取消实际正式调用；实际晚成功仍交给引擎，真正起飞仍需原账号/host/runtime lease。自己的 HTTP 传输上限为 60 秒，与 8 秒 Pending 边界不同。不自动重发消费 POST。

- `operation` 请求 `{hwid, credential, operationId}`；响应 `{found, decision?, localStartFailureRecorded}`。只查询同一原操作，不扣次。会员原操作凭原账号即可查询/对账，设备操作要求原实际设备证明；无设备时不把会员伪装成游客设备。`found=false` 不证明原请求绝不会迟到，因此仍未确认；不可生成拒绝/退款或新消费。
- `local-start-failed` 请求 `{hwid, credential, operationId, sessionId, requestFingerprint}`。只允许匹配已获准操作；响应 `{operationId, recordedAtMillis, refunded:false}`。服务端保存首次失败对账时刻，重复返回原收据。不补额度、不生成云飞行 ID、不替换原获准决定。Android 先持久化失败队列才能释放本地操作，再实际发此接口；后续重试也不能发新的正式消费。

成功启动 ACK 是独立本地边界，没有伪造一个服务器“退款/飞行”接口：真实仓库保存了首份 archive、退出 Core lease 后调用 `localStartCommitted`；Android 先保存成功 ACK 时刻，再释放内存中的捕获 bearer/设备凭据。写 ACK 失败保持已启动结果，暴露 `startAcknowledgements` 的 Required 状态，并可精确重试 `retryStartAcknowledgement`，不重扣、不释放飞行、不走未启动失败对账。跨进程尚未记录 ACK 的批准只能显示需要核对本地 archive，不能猜成功或退款；日志不保存原 bearer。已持久记录本地未启动失败的旧 UUID 不能再次开始：新一轮本地 request 明确拒绝并要求新准备，保留原后台批准/失败收据，不重发消费、不丢弃旧运行中实际晚批准。这是本项目本地终态政策，不是原服务已证明的恢复规则。

本阶段仍无原服务运行验收、PostgreSQL 并发/真机验收、默认赠送/重置/自动退款/账号代际迁移政策，也不等于完整自由飞行产品已验收。
