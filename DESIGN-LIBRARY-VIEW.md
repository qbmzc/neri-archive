# 音乐档案页：存量文件与归档记录合并设计

状态：设计已定，未实施。
范围：把「音乐档案」页现有的两张表合并为一张统一视图，并修掉底层的路径字符串关联。
相关：`DESIGN-QUALITY-UPGRADE.md`。两者共用该设计引入的 `SchemaMigrations`；本设计不依赖其任何阶段。

---

## 1. 现状

### 1.1 页面结构

`音乐档案`（`App.vue` 的 `library` 页）自上而下：

1. 统计卡片 ×3：文件数 / 重复组 / 可回收空间
2. 重复检查结果（卡片式）
3. **表格一「扫描到的文件」** —— 来自 `file_inventory` + `audio_fingerprints`，筛选 `ALL / ARCHIVED / UNTRACKED / DUPLICATE`，服务端分页 50/页
4. **表格二「应用归档记录」** —— 来自 `songs WHERE path IS NOT NULL`，无分页

### 1.2 两张表的分工

| | `songs` | `file_inventory` |
|---|---|---|
| 主键语义 | **身份**（网易云 `songId`） | **位置**（`path`） |
| 性质 | 权威状态，被 `members` / `tasks` 外键引用 | 纯派生缓存 |
| 生命周期 | 长期存在；文件被删后记录仍在（`path` 指向缺失文件） | **每次扫描全表 DELETE 重建**（`ArchiveStore.java:56-58`） |
| 生产者 | 歌单扫描（`applySnapshot`） | 重复文件扫描（`DuplicateFileService`） |

分开是有正当理由的：一个是权威状态，一个是可重建缓存，主键语义还不同。

### 1.3 关键事实：两张表大面积重叠

`DuplicateFileService` 用 `Files.walk(files.root)` 扫描整个音乐目录（`:73`），而**下载的文件就落在音乐目录里**。所以每一首已归档的歌曲同时出现在两张表中：

- 表格一标记为「已有归档记录」（`file.song_id` 非空）
- 表格二再完整列一遍

表格一的筛选器本身已经承认了这个划分：`ARCHIVED` 即 `s.id IS NOT NULL`（`ArchiveStore.java:40`）。

两者的真实差集：

| 区域 | 含义 | 当前可见性 |
|---|---|---|
| 交集 | 已归档 且 文件在盘 | **重复展示两遍** |
| 只在 `songs` | 文件缺失 | 只在表格二可见 |
| 只在 `file_inventory` | 仅存量文件 | 只在表格一可见 |

---

## 2. 问题陈述

**L1 交集被重复展示。** 同一个文件在页面上出现两次，用户无法判断两者关系。

**L2 两张表的关联是路径字符串匹配。**

```sql
LEFT JOIN songs s ON replace(s.path,'\\','/')=f.path     -- ArchiveStore.java:44
```

函数包住了列，索引无法使用；文件一旦移动或改名关联即断；Windows 反斜杠靠 `replace` 兜；也没有一对一保证——若两行 `songs.path` 相同，`LEFT JOIN` 会产生笛卡尔积并让 `count(*)` 虚高。

**L3 表格二无分页。** `library()` 是全量 `SELECT`（`ArchiveStore.java:25`），README 也承认「媒体库未实现服务端分页」（`README.md:110`）。前端每次 `refresh()` 都会拉取全量数据（`App.vue:41`）。

**L4 两类行各自缺信息。**
- 「仅存量文件」没有音质信息（`level` / `sample_rate` / `bits` 只在 `songs` 里）
- 「文件缺失」在表格一完全不可见，只能靠「检查缺失文件」按钮被动触发

---

## 3. 目标与非目标

**目标**

1. 音乐档案页只剩一张表，一行 = 一个音频文件，三类行用状态列区分。
2. 修掉路径字符串关联，改为整数列等值连接。
3. 新下载的文件不必等下次扫描才出现。
4. 消除表格二的无分页全量加载。

**非目标**

- 不合并 `songs` 与 `file_inventory` 两张存储表（理由见 4.1）。
- 不引入音频指纹参与归档判定。
- 不改动重复检测算法本身（`assignDuplicateGroups` 保持不变）。
- 不做归档记录的手工编辑或删除。

---

## 4. 设计

### 4.1 存储层不合并

两条硬理由：

1. **生命周期冲突。** `replaceFileInventory()` 每次扫描 `DELETE FROM` 三张表后重建（`ArchiveStore.java:56-58`）。合并意味着 `members` 与 `tasks` 的外键指向一张会被定期清空的表。要么让缓存变成权威（一次扫描失败即毁掉归档记录），要么让任务引用一张随时被清空的表。
2. **主键语义不同。** 一首歌盘上零个文件时（缺失状态），它无法表示为「盘上文件」的一行。

合并的是**展示**，不是**存储**。这是本设计的核心取舍。

### 4.2 扫描时解析 `song_id`

**问题**：`file_inventory` 以 `path` 为主键，与 `songs` 的关联只能靠路径字符串。

**方案**：扫描时本来就走遍了音乐目录，它完全知道每个文件属于哪首歌。把这个解析结果落成一个整数列。

```java
// DuplicateFileService.scanNow() 开头，构建一次映射
Map<String,Long> songByPath = new HashMap<>();
for (var song : store.library())                       // 已按 path IS NOT NULL 过滤
    songByPath.put(text(song,"path").replace('\\','/'), number(song,"id"));

// 遍历中，relative 已在 :81 归一化为正斜杠
Long songId = songByPath.get(relative);
snapshot.add(new InventoryEntry(relative, bytes, modified, hash, audioHash, duration,
                                rawFingerprint, "", "", songId, now));
```

配套改动：

- `InventoryEntry` 增加 `Long songId` 字段（`ArchiveStore.java:12-13`）。注意 `DuplicateFileServiceTest.java:88` 直接构造了该 record，需要同步更新。
- `replaceFileInventory()` 的 INSERT 增加该列。
- 新增索引 `CREATE INDEX IF NOT EXISTS idx_inventory_song ON file_inventory(song_id)`。

**收益**：join 变为 `f.song_id = s.id`，可走索引；一对一天然成立（Map 天然去重，消除了 L2 的笛卡尔积隐患）；`songs.path` 后续变化不影响本次扫描结果的一致性。

### 4.3 合并展示层

统一视图的语义是「`songs`（有文件的）与 `file_inventory` 的**全外连接**」：

```sql
SELECT
    COALESCE(f.path, replace(s.path,'\','/'))     AS path,
    COALESCE(f.bytes, s.bytes)                    AS bytes,
    f.modified_at, f.sha256,
    a.duration_seconds AS audio_duration, a.fingerprint_hash AS audio_hash,
    a.match_type, COALESCE(d.raw_fingerprint,'')  AS audio_fingerprint,
    s.id AS song_id, s.name, s.artist, s.album,
    s.level, s.sample_rate, s.bits, s.downloaded_at, s.metadata_warning,
    CASE WHEN a.group_id='' OR a.group_id IS NULL THEN 1
         ELSE (SELECT count(*) FROM audio_fingerprints x WHERE x.group_id=a.group_id) END AS duplicate_count,
    CASE WHEN s.id IS NULL   THEN 'UNTRACKED'
         WHEN f.path IS NULL THEN 'MISSING'
         ELSE 'ARCHIVED' END                      AS state
FROM (SELECT * FROM songs WHERE path IS NOT NULL) s
FULL OUTER JOIN file_inventory f ON f.song_id = s.id
LEFT JOIN audio_fingerprints a       ON a.path = f.path
LEFT JOIN audio_fingerprint_data d   ON d.path = f.path
```

子查询 `WHERE path IS NOT NULL` 是必要的：`songs` 里的未下载条目不该被算成「缺失」。

三类行的产出：

| `state` | 条件 | 表格显示 |
|---|---|---|
| `ARCHIVED` | `s.id` 与 `f.path` 均非空 | 已归档 |
| `UNTRACKED` | `s.id` 为空 | 仅存量文件 |
| `MISSING` | `f.path` 为空 | **文件缺失** + 行内「补下载」按钮 |

**FULL OUTER JOIN 的可用性**：SQLite 3.39+ 支持。当前 `sqlite-jdbc 3.50.3.0` 捆绑 SQLite **3.50.3**（已从 jar 内 native 库确认），满足要求。

若不愿依赖该语法，等价的 `UNION ALL` 写法是「`file_inventory` 左连 `songs`」并上「`songs` 中不存在于 `file_inventory` 的行」，再在外层 `ORDER BY ... LIMIT ? OFFSET ?`。两者结果集相同，前者更简洁，后者更保守。

**筛选器**扩展为五项：`ALL / ARCHIVED / UNTRACKED / MISSING / DUPLICATE`。

**顺带解决 L4**：合并后「仅存量文件」也能看到 `songs` 侧的字段（有则显示），「文件缺失」行直接提供补下载入口——它当前藏在 `repairMissing()` 背后的按钮里。

### 4.4 新下载的文件立即出现

表格一依赖扫描结果，因此**新下载的歌在下次扫描前不出现在里面**。合并视图不是这个行为：新下载的歌已经有 `songs` 行，`s.path IS NOT NULL`，FULL OUTER JOIN 立刻产出 `ARCHIVED` 行（`f.*` 为 NULL，`bytes` 走 `COALESCE` 回退到 `s.bytes`）。

这正是「已经存在的和新下载的一起管理」的落地方式——两类数据各有权威来源，视图负责合并，而不是把新下载的文件写进扫描缓存。

### 4.5 接口收敛

合并后 `/api/library` 的唯一消费者消失（`App.vue:41` 是唯一调用点），可一并移除：

- 删除 REST 端点 `GET /api/library`（`ArchiveController.java:113-116`）
- 移除前端 `library` ref、`shownLibrary` computed 与其 `api('/library')` 调用（`App.vue:14, :26, :41`）

**注意保留 `ArchiveStore.library()` 方法本身**（`:25`）。它仍被两处使用：`ArchiveEngine.repairMissing()`（`:62`）遍历缺失文件，以及 4.2 构建 path→songId 映射。本设计只移除 REST 端点，不删除该方法。

---

## 5. 数据与配置变更

**Schema**：`file_inventory.song_id INTEGER` + 索引 `idx_inventory_song`。

迁移走 `DESIGN-QUALITY-UPGRADE.md` 引入的 `SchemaMigrations`（`ALTER TABLE file_inventory ADD COLUMN song_id INTEGER` + `CREATE INDEX IF NOT EXISTS`），与 `tasks.forced` 共用同一组件。

**已考虑并否决：扫描时 `DROP TABLE` + `CREATE TABLE`。** `file_inventory` 每次扫描本来就全表重建，理论上可以借机改结构、完全不需要迁移。否决原因是运行期 DDL 会引入与 `fileInventoryPage()` 的并发可见性问题（该方法未加 `synchronized`），收益不抵风险。既然 `SchemaMigrations` 已经为 `tasks.forced` 引入，复用它更简单可预测。

**配置**：本设计不新增配置项。

---

## 6. 前端改动

`frontend/src/App.vue`：

- 删除表格二整块（`:154-157` 及其 section 标题、空状态）
- 表格一改名为「音频文件」，增加「音质」「归档时间」两列（`level` / `sample_rate` / `bits` / `downloaded_at`，为空时显示 `—`）
- 状态列增加第四种取值：文件缺失（`MISSING`），并带「补下载」按钮，调用 `/api/library/repair`（若需按歌曲单独补，则新增 `/api/library/{id}/repair`）
- 筛选下拉增加 `<option value="MISSING">文件缺失</option>`
- 移除 `library` ref、`shownLibrary` computed 与 `api('/library')` 调用（`:14, :26, :41`）

`frontend/src/style.css`：为缺失状态补一条样式（可复用 `.status.FAILED` 的暖色）。

---

## 7. 失败模式

| 场景 | 行为 |
|---|---|
| 尚未扫描过（`file_inventory` 为空） | 已归档歌曲仍全部可见（`ARCHIVED`，`f.*` 为 NULL）；「仅存量文件」为空。页面不再像现在这样整块空白 |
| 扫描失败 | `replaceFileInventory` 是单事务，失败则保留上次快照；已归档行不受影响 |
| `songs.path` 未命中任何盘上文件 | 显示为「文件缺失」，与「仅存量文件」区分开 |
| 两行 `songs.path` 相同（异常数据） | Map 构建时后写覆盖前值，只关联一个；不再产生笛卡尔积 |
| 文件在盘但扫描未覆盖 | 不出现。扫描会排除 `.work` 与回收站（见 `DESIGN-QUALITY-UPGRADE.md` 5.5） |
| FULL OUTER JOIN 在旧驱动上不可用 | pom 固定 `sqlite-jdbc 3.50.3.0`；若将来降级，改用 4.3 的 UNION ALL 写法 |

---

## 8. 测试计划

| 测试 | 内容 |
|---|---|
| `StoreAndSecurityTest`（改） | 统一查询三类行各自的 `state` 取值正确；`MISSING` 行使用 `songs.bytes` 回退；`UNTRACKED` 行 `song_id` 为 NULL；分页在合并后仍正确（`total` 与实际行数一致） |
| `DuplicateFileServiceTest`（改） | 扫描写入的 `song_id` 与 `songs` 一致；`InventoryEntry` 构造器更新；未归档文件 `song_id` 为 NULL |
| `ArchiveStoreTest`（新） | 未扫描时已归档歌曲可见；`songs.path` 相同的数据不再产生重复行；筛选器五项各自返回正确子集 |
| `MigrationTest`（新） | 旧库补 `file_inventory.song_id` 与索引；重复执行幂等 |
| 前端 | 首屏在无扫描结果时正常渲染；三类状态的标签与按钮正确；分页与筛选联动 |

---

## 9. 分期与验收

本设计独立于 `DESIGN-QUALITY-UPGRADE.md`，可与其实施任意排序，唯一耦合是 `SchemaMigrations`。

**步骤 1 — 修关联（4.2）**
验收：`file_inventory.song_id` 在扫描后正确填充；查询不再使用 `replace(s.path,...)` 关联；存量文件页面的分类计数与改动前一致。

**步骤 2 — 合并视图（4.3 + 4.4）**
验收：页面只剩一张表；三类行状态正确；新下载未扫描的歌曲立即出现在表中；筛选五项可用。

**步骤 3 — 接口收敛（4.5 + 第 6 节）**
验收：`/api/library` 已移除且无引用；前端刷新不再拉取全量归档记录；`repairMissing()` 与 4.2 的映射构建仍正常（`library()` 方法本身保留）。

**顺序理由**：步骤 1 是步骤 2 的前提（没有 `song_id` 列就只能继续用字符串 join），且它本身是纯后端改动、可独立验证。步骤 3 是清理，放最后避免与前端改动互相干扰。
