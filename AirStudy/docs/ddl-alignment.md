# AirStudy 数据结构（Room v24）

## 0. 总览

| 表 | 历史基线列数 | 当前列数 | 状态 |
|---|---:|---:|---|
| `airports` | 13 | **15** | ⚠️ **主动 +2 列**（中文名，修「全英文界面」缺陷） |
| `flight_history` | 31 | **36** | ★ 本品 +5 列：quote/quoteAuthor、syncedAt、sceneSnapshotJson/plannedDepartureJson |
| `focus_categories` | 8 | 8 | ✅ 列一致（起实体声明唯一索引 `(name, isDefault)`） |
| `blocked_apps` | 6 | 6 | ✅ 列一致（起实体声明唯一索引 `(categoryId, packageName)`） |
| `stamps` | 10 | **11** | ⚠️ 1 列**类型变更**（修资源 ID 债）+ `assetVersion`（实体补齐） |
| `room` | — | 16 | ★ 新增（v15） |
| `room_participant` | — | 10 | ★ 新增（v16） |
| `room_message` | — | 7 | ★ 新增（v17） |
| `achievement` | — | 6 | ★ 新增（v18） |
| `daily_stat` | — | 4 | ★ 新增（v19） |
| `app_setting` | — | 3 | ★ 新增（v20） |
| `free_flight_archive` | — | 7 | ★ 本品独立正计时归档（v24） |
| `free_flight_position` | — | 8 | ★ 本品独立正计时位置序列（v24） |

## 1. `flight_history` —— 保留 31 列基线，独立保存 5 列本品数据

### 1.1 逐字段对照（36 vs 31）

| # | 列名 | 竞品类型 | 我们类型 | 一致性 | 说明 |
|---:|---|---|---|---|---|
| 1 | `id` | TEXT PK | TEXT PK | ✅ | UUID 字符串。与 `SessionState.sessionId` 是同一个值 |
| 2 | `roomId` | TEXT null | TEXT null | ✅ | |
| 3 | `focusType` | TEXT NN | TEXT NN | ✅ | `SOLO` / `GROUP` |
| 4 | `roomParticipantCount` | INTEGER null | INTEGER null | ✅ | |
| 5 | `departureAirportCode` | TEXT NN | TEXT NN | ✅ | |
| 6 | `departureAirportName` | TEXT NN | TEXT NN | ✅ | |
| 7 | `departureCity` | TEXT NN | TEXT NN | ✅ | |
| 8 | `departureCountry` | TEXT NN | TEXT NN | ✅ | |
| 9 | `departureLatitude` | REAL NN | REAL NN | ✅ | |
| 10 | `departureLongitude` | REAL NN | REAL NN | ✅ | |
| 11~16 | `destination*` 6 列 | 同构 | 同构 | ✅ | 与出发地对称 |
| 17 | `startTimeMillis` | INTEGER NN | INTEGER NN | ✅ | 索引 |
| 18 | `endTimeMillis` | INTEGER null | INTEGER null | ✅ | 进行中为 null |
| 19 | `durationMinutes` | INTEGER NN | INTEGER NN | ✅ | **计划**时长 |
| 20 | `actualFocusMinutes` | INTEGER NN | INTEGER NN | ✅ | **实际**时长 |
| 21 | `distanceKm` | REAL NN | REAL NN | ✅ | = `durationMinutes/60*800` |
| 22 | `status` | TEXT NN | TEXT NN | ✅ | 见 §1.2 |
| 23 | `categoryId` | INTEGER null (FK) | INTEGER null (FK) | ✅ | ON DELETE **SET NULL** |
| 24 | `seat` | TEXT null | TEXT null | ✅ | |
| 25 | `hasAnnouncedDeparture` | INTEGER NN | INTEGER NN | ✅ | TTS 幂等标记 |
| 26 | `hasAnnouncedDescent` | INTEGER NN | INTEGER NN | ✅ | |
| 27 | `hasAnnouncedArrival` | INTEGER NN | INTEGER NN | ✅ | |
| 28 | `logJson` | TEXT null | TEXT null | ✅ | 逃生舱 |
| 29 | `isSynced` | INTEGER NN | INTEGER NN | ✅ | |
| 30 | `createdAt` | INTEGER NN | INTEGER NN | ✅ | 排序键 |
| 31 | `updatedAt` | INTEGER NN | INTEGER NN | ✅ | |
| **32** | **`quote`** | **—** | **TEXT null** | ⚠️ **+我们** | 落地金句 |
| **33** | **`quoteAuthor`** | **—** | **TEXT null** | ⚠️ **+我们** | 金句作者 |
| **34** | **`syncedAt`** | **—** | **INTEGER null** | ★ 本品 | 同步审计（v21，v22 实体补齐） |
| **35** | **`sceneSnapshotJson`** | **—** | **TEXT null** | ★ 本品 v23 | 独立场景及展示快照，不占用 categoryId |
| **36** | **`plannedDepartureJson`** | **—** | **TEXT null** | ★ 本品 v23 | 计划日期/时区/HH:mm，与 duration 独立 |

> **持久化适配**：22→23 仅追加两列，旧行默认 NULL；31 个基线字段与类别外键不变。
> scene 记录 optionId/name/iconKey/color/isCustom/cloudId/clientUuid/displayName，plannedDeparture 记录 date/hour/minute/zoneId。
> 检查点与终态保留数据库最新原始 JSON（包括未知字段或明确 NULL），恢复时独立解码；这不是已证明的原包服务端结果格式。
> 保存计划不等于立即启动；调度与服务器结果不由这两列实现。

### 1.2 状态值域与检查点恢复（按 /更正）

早期报告称原版取消仅删除记录，并称本品通过墙钟补算冷恢复；这两项旧描述已被后续取证和实现替代，不能继续作为当前规则。

当前倒计时自然终态在采样内保存 `COMPLETED`，取消保存 `CANCELLED`，之后执行屏蔽清理；下机用于确认或重试已经保存的终态。取消保留实际已存整分钟、冻结的计划全距离及最新座位。计划全距离不等于已实际飞行里程。

冷恢复以已提交检查点为依据，不凭离线墙钟增加专注量；同进程暂停与冷恢复分开处理。数据库事务负责保存一致的会话状态，手机进程恢复与云同步分别验收。

独立正计时使用 v24 新归档保存有效毫秒和真实累计距离，不将自由飞行强行转换成此表的计划时长/全距离。其界面、聚合及服务接线另行验收。

### 1.3 索引对照

| 索引 | 竞品 | 我们 | 说明 |
|---|---|---|---|
| `index_flight_history_categoryId` | ✅ | ✅ | 名字必须逐字一致，否则 `identity_hash` 不同 |
| `index_flight_history_startTimeMillis` | ✅ | ✅ | |
| `(status, isSynced)` 复合 | — | ✅ v7 | 待同步队列查询 |
| `endTimeMillis` | — | ✅ v8 | `latestCompleted()` 排序 |

> **★ 为什么我们敢加索引而 `airports` 表一个都不加**（见 `AirportEntity` 注释）：
> `airports` 是 83k 行只读主数据，写入时每加一个索引就多一次 B-tree 维护；
> `flight_history` 是用户数据，行数在千级，写入频率极低（一天几条），读频率高。
> 两条表的取舍方向正好相反。

---

## 2. `stamps` —— 一列类型变更

| 列名 | 竞品类型 | 我们类型 | 说明 |
|---|---|---|---|
| `id` | TEXT PK | TEXT PK | ✅ |
| `countryName` | TEXT NN | TEXT NN | ✅ |
| `countryCode` | TEXT NN | TEXT NN | ✅ |
| `firstVisitDate` | INTEGER NN | INTEGER NN | ✅ |
| `latestVisitDate` | INTEGER NN | INTEGER NN | ✅ |
| `visitCount` | INTEGER NN | INTEGER NN | ✅ |
| `focusHours` | REAL NN | REAL NN | ✅ |
| **`illustrationResId`** | **INTEGER NN** | **TEXT NN** | ⚠️ **类型变更**（v6） |
| `airportCodes` | TEXT NN | TEXT NN | ✅ |
| `unlockedLandmarkIds` | TEXT NN | TEXT NN | ✅ |

**为什么改类型**：竞品存的是 `R.drawable.xxx` 的**整型资源 ID**。
资源 ID 是 aapt 在编译期分配的，**每次发版都会变**。

真实后果：用户升级 App 后，本地数据库里存的 `2131230876` 指向了
另一个 drawable —— 于是「日本的樱花印章」变成了「韩国的泡菜印章」，
或者直接 `Resources$NotFoundException` 崩溃。

证据：竞品 v6 迁移里有 `UPDATE stamps SET illustrationResId = 0`，
说明它踩过这个坑，但选择了「清空重下」而不是改类型（治标）。

我们改成 TEXT 存**稳定 asset key**（如 `stamp_jp_sakura`），
映射表在代码里，随版本更新只需要加 key，历史数据不会错位。

---

## 3. `airports` / `focus_categories` / `blocked_apps`

### 3.1 `airports`（15 vs 13）—— **主动 +2 列**

竞品 13 列：`id / iataCode / icaoCode / name / city / country / latitude /
longitude / elevation / continent / type / popularity / isPremiumOnly`

我们 +2 列：

| # | 列名 | 类型 | 可空 | 理由 |
|---:|---|---|---|---|
| 14 | `nameZh` | TEXT | **是** | 机场中文名（「北京首都国际机场」） |
| 15 | `cityZh` | TEXT | **是** | 城市中文名（「北京」） |

> **★★ 为什么必须加，而且必须可空**
>
> 竞品的数据源是 OurAirports（纯英文）。它的机场列表在中国区显示的是
> `Shanghai Pudong International Airport` / `Beijing` —— 中文用户在
> 「选择目的地」这个**最核心的操作**里要认英文全称。这是它在中文区
> 评分只有 3.x 的主要原因之一，也是我们能立刻拉开差距的一处。
>
> 但**必须可空**：83,799 条机场里绝大多数 small_airport 不会有中文名，
> 若设 NOT NULL 就要填假值（空串），而空串在列表页会渲染成一行空白 ——
> 比显示英文更糟。可空 + 展示层降级（`nameZh ?: name`）才是对的。
>
> **为什么不是把 name 直接改成中文**：那会破坏英文检索，
> 外籍用户与航旅常客（认 IATA/ICAO 和英文名）会立刻失去可用性。
> 中英并存是唯一不牺牲任一方人群的方案。
>
> 数据填充是 M3 的内容工作（large 约 700 + medium 约 4,700 条，人工/半自动校对）；
> M2 只负责把**管道打通**：字段、索引层、展示降级。

### 3.2 `focus_categories` / `blocked_apps` —— 逐字段完全一致

- `focus_categories.id` 是 `INTEGER PRIMARY KEY AUTOINCREMENT`
- `blocked_apps` 外键 **ON DELETE CASCADE**（删分类时规则一起删）

> **★ `focus_categories` 与 `blocked_apps` 的 CASCADE/SET NULL 差异是刻意的**：
> 删分类时「屏蔽规则」跟着删才干净（否则成为孤儿规则，永远拦人不解释原因），
> 但「历史记录」不能删（用户飞过的记录是事实，不能因为改名/删分类就消失）。
> 竞品做了同样的区分，我们照抄。

---

## 4. 我们新增的 6 张表

| 表 | 列数 | 用途 | 为什么竞品没有 |
|---|---:|---|---|
| `room` | 16 | 组队房间本地缓存 | 竞品放内存 |
| `room_participant` | 10 | 房间参与者 | 同上 |
| `room_message` | 7 | 聊天本地缓存 | 同上 |
| `achievement` | 6 | 徽章进度 | 竞品纯内存计算，杀进程清零 |
| `daily_stat` | 4 | 统计页预聚合 | 竞品每次进统计页把全部记录读出来 fold |
| `app_setting` | 3 | 取代散装 SharedPreferences | 竞品用 40+ 个裸 key |

---

## 5. 迁移路径（v1 → v21）

完整 22 段迁移见 `AppDatabase.VERSION` 的表格注释与 `migrations/Migrations.kt`。

| 段 | 版本 | 性质 |
|---|---|---|
| v1~v4 | 1→4 | **重演竞品演进史**（单表 → 5 表 → 31 列 → 并入） |
| v5~v14 | 4→14 | ★ 我们的 schema 修正（+2 列、类型变更、5 个索引） |
| v15~v20 | 14→20 | ★ 新增 6 表 |
| v21 | 20→21 | ★ `flight_history.syncedAt`（多端同步审计） |

> **★ 绝不加 `fallbackToDestructiveMigration()`**
> 竞品加了。后果是任何一次漏写迁移，用户的全部飞行历史被静默清空，
> 而且没有任何提示。我们让它在测试里炸（`MigrationRegistryTest`），
> 而不是在用户手机上炸。

---

## 6. 自动校验（本文档与代码的单向绑定）

`SchemaDocParityTest` 会：

1. 读本文的 §0 总览表，得到「表名 → 列数」
2. 读最新导出的 `schemas/*/23.json`，得到真实的「表名 → 列数」
3. 断言两者相等
4. 顺带断言 `quote` / `quoteAuthor` 在 `flight_history` 里真实存在
   （因为 §1.1 声称如此，而这两列是我们最重要的偏离）

**这条测试的价值**：我写这份报告时是手抄的列数。
手抄必然出错 —— 第一次就抄错了 `room_participant` 的列数。
测试当场抓到。这就是「文档要有测试守着」的实际收益，
不是理论上的好习惯。
