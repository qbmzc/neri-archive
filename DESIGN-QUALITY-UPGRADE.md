# 音质决策与文件替换设计

状态：设计已定，决策见第 11 节，未实施。
范围：在现有 neri-archive MVP 的监听 → 入队 → 下载链路上，补上「音质决策」与「可回退的文件替换」。
替代：此前的外部方案草案（未入库）。第 12 节逐条给出处置结论。
相关：`DESIGN-LIBRARY-VIEW.md`（音乐档案页两张表合并），两者共用本设计引入的 `SchemaMigrations`。

---

## 1. 目的

现有 MVP 已经具备歌单监听、跨歌单去重、持久化队列、完整性校验、手动/每周音质升级。本设计不重建这些，只解决三个具体缺陷：

- **P1** 升级决策看不到本地音质，导致大量无效下载。
- **P2** 替换时「保留哪个文件」只看字节数，判据单一。
- **P3** 落败文件立即物理删除，任何误判都不可逆。

---

## 2. 现状基线

以下均为当前代码事实，是本设计的前提。

| 事实 | 位置 |
|---|---|
| 只有一张 `songs` 表，曲目元数据与唯一文件指针同行（`path/level/bytes/sha256/sample_rate/bits/bitrate`） | `schema.sql:11-16` |
| **没有** `tracks` / `track_files` 拆分，**没有** `track_external_ids` | 全库 grep 无命中 |
| `songs.id` 就是网易云 `songId` | `NeteaseGateway.java:109,112,130` → `Track.id` → `ArchiveStore.java:87` |
| 跨歌单去重由 `songs` + `members` 天然实现（同一 `songId` 只有一行） | `schema.sql:17-21` |
| 同一歌曲的入队幂等由部分唯一索引保证 | `schema.sql:29` `one_active_song ON tasks(song_id) WHERE status IN ('QUEUED','RUNNING','PAUSED','AUTH_REQUIRED')` |
| 监听与下载已解耦：扫描只写快照+入队，worker 独立 `claim()` | `ArchiveEngine.java:45-56`、`:34-44` |
| 升级/首次下载的判据只有「本地有没有文件」 | `ArchiveStore.java:88-90` |
| 下载完成后按**字节数**决定保留新文件还是旧文件 | `DownloadWorker.java:57-67`、`MediaFiles.java:71-81` |
| 落败版本在 DB 提交后**立即删除** | `DownloadWorker.java:77-82` |
| 音频指纹能力已存在，但只服务于独立的存量扫描，不参与下载决策 | `DuplicateFileService.java` |
| 未找到的存量文件不自动清理（刻意的保守） | `README.md:109` |
| 单实例、SQLite、单连接池、`synchronized` + `TransactionTemplate`，无 JPA | `application.yml:19`、`ArchiveStore.java` |
| 重复文件扫描排除 `.work`，按路径前缀判断 | `DuplicateFileService.java:203` |
| 音质等级已有偏好序与比较函数 | `QualitySelector.java:9-14` |
| `schema.sql` 用 `CREATE TABLE IF NOT EXISTS`，项目**无迁移机制** | `schema.sql`、`pom.xml` |

---

## 3. 问题陈述

### P1 升级决策缺少本地音质信息

`ArchiveStore.java:88-90`：

```java
boolean shouldDownload = initial ? number(sub,"initial_download")==1 : !old.contains(song.id());
var existing = song(song.id());
if((shouldDownload && text(existing,"path").isBlank()) || (upgrade && !text(existing,"path").isBlank()))
    enqueue(song.id(), text(sub,"policy"));
```

`upgrade` 只判断「距上次升级超过 7 天」（`:85`）。因此每周自动升级会对**每一首**已下载歌曲入队，无论它是否还有提升空间。实际结果：为一首已是 `jymaster` 的歌重新走一遍 API 解析、下载 30MB、比字节数、发现旧的更大、丢弃刚下载的内容（`DownloadWorker.java:60-67`）。

流量与时间都被浪费，而决策所需的信息（`songs.level`）就在同一行记录里。

### P2 替换判据单一

`MediaFiles.largest()`（`:71-81`）只比较 `Files.size()`。对下载器接受的四种编码（flac/mp3/m4a/aac）字节数与音质大体单调，所以它在多数情况下可用——**但有一个系统性错误场景**：

网易云把 320kbps MP3 转码后标记为 `lossless` 返回时（假无损），FLAC 容器约 30MB，真 320 MP3 约 10MB。字节数稳定选择假 FLAC，删掉真 320。此时唯一的判据是错的，且没有第二判据交叉验证。

### P3 不可逆删除

`DownloadWorker.java:77-82` 在 `store.complete()` 提交后立即 `Files.deleteIfExists(version)`。P2 的误判、下成现场版/翻唱、平台侧换源，任何一种都导致原始文件永久丢失。

值得注意的是当前保守程度是反的：对**来历不明的孤儿文件**刻意不清理（`README.md:109`），对**明确知道被替换掉的文件**却立即物理删除。

---

## 4. 目标与非目标

**目标**

1. 升级决策使用本地已有音质，避免无效下载。
2. 替换判据从单一字节数升级为可解释的音质比较。
3. 被替换的文件在保留期内可恢复。

**非目标（明确不做）**

- 不拆分 `tracks` / `track_files`。
- 不引入 `track_external_ids`（`songs.id` 已是精确锚点）。
- 不引入模糊元数据匹配、跨来源身份归并（当前只有网易云一个来源）。
- 不引入 JPA、`@Version`、`SELECT ... FOR UPDATE`、ShedLock（单实例 + SQLite，均不适用）。
- 不引入 `seen` 表（现有内存 diff 更便宜且事务一致，见 `ArchiveStore.java:81-82`）。
- 不做音频指纹参与下载决策（指纹对同曲不同版本的区分度不足以支撑自动替换）。
- **不做假无损频谱检测**（推迟到阶段 D，见第 10 节）。
- 不做回收站 UI。

---

## 5. 设计

### 5.1 音质比较

现有代码有一个关键区分：`QualitySelector.order()` 给出的等级序是**平台档位偏好**，不是物理音质。两者都要用，但用途不同。

**判据一：平台档位序（判断「还有没有提升空间」）**

复用已有的 `QualitySelector.rank(level, policy)` / `better(candidate, current, policy)`。这是接口契约层面的比较，成本为零（不需要读文件、不需要探测）。

**判据二：物理音质序（判断「哪个文件该留下」）**

输入为 `MediaFiles.Probe(extension, duration, rate, bits, bitrate)`，逐级比较：

```
compare(a, b):
  1. 无损 / 有损          无损胜（归档场景下这是硬门槛）
  2. 两者均无损：sampleRate → bits → bitrate
  3. 两者均有损：bitrate → 编码类（aac > mp3，仅同码率时作决胜）→ sampleRate
  平局：保留已有文件（沿用当前 tie-break，避免重复下载产生抖动）
```

无损判定只依据实测，**不依据平台标签**：

```
isLossless(ext, bits) = ext=="flac" || (ext=="m4a" && bits>=16)
```

这条规则必须写对，因为 `MediaFiles.probe()` 把 `alac` 和 `aac` 都映射为扩展名 `m4a`（`MediaFiles.java:97`）。若只按扩展名分类，ALAC 会被误判为有损。

这个设计有意**不给编码一个全局权重的加权和**。加权和会得出「AAC 256 远好于 MP3 320」这类结论；而先分无损/有损、再在同类内比较码率，可以得到可解释、可测试的排序。

**「显著提升」门槛**（决定是否值得替换）：

| 情形 | 判定 |
|---|---|
| 有损 → 无损 | 显著 |
| 无损 → 无损，sampleRate 或 bits 严格更高 | 显著 |
| 有损 → 有损，bitrate ≥ 1.25× 且 ≥ +64kbps | 显著 |
| 其余 | 不显著 |

门槛存在的意义是避免 320 ↔ 256 之间反复替换。

### 5.2 决策点一：入队前（Tier 1）

**位置**：`ArchiveStore.applySnapshot()`，替换 `:90` 的入队条件。

**信息**：只有 `songs` 行，不调用任何 API。

**规则**：在原有条件上追加「该策略下确实还有更高档位」：

```
upgradeable(existing, policy):
    level = existing.level
    if level 为空 / "unknown" / 不在 QualitySelector.order(policy) 中:  return true   // 未知则保守放行
    return QualitySelector.rank(level, policy) > 0
```

**效果**：已是策略顶档的歌（FIDELITY 下的 `jymaster`、SURROUND 下的 `sky`）每周不再产生任何任务。这是收益最大、风险最小的一步——档位序是纯函数，未知值一律放行。

### 5.3 决策点二：下载前（Tier 2）

**位置**：`DownloadWorker.run()`，在 `resolve()`（`:36`）之后、`transfer()`（`:40`）之前。

**信息**：`resolve()` 已完成 API 调用并返回 `source.actual()`，因此这里可以得到精确的「服务这次实际给什么档位」。

**规则**：

```
current  = text(song,"level")
incoming = source.actual()
if !forced
   && localFilePresent(existing)          // 本地文件真的还在盘上
   && known(current, policy) && known(incoming, policy)
   && !QualitySelector.better(incoming, current, policy):
        标记 SKIPPED，不传输
else:
        继续 transfer

known(level, policy) = QualitySelector.rank(level, policy) != 999
```

**双端必须 known 才跳过**，这是硬性要求：`README.md:103` 承诺「未知档位不会自动替换已知档位文件」。任一端未知就放行，交由 Tier 3 裁决。

**本地文件缺失时也必须放行。** 这是实现阶段发现的缺口，设计初稿漏了：补下载场景下 `songs.level` 描述的是一个已经不存在的文件，拿它来比较会让 `repairMissing()` 的补下载被静默跳过，缺失文件永远回不来。因此跳过条件额外要求 `Files.isRegularFile(songs.path)`。

**效果**：省掉的是带宽和解码——最贵的部分。API 调用仍会发生一次（这是获得 `actual()` 的必要成本）。

**被跳过的任务需要一条出路**：SKIPPED 是终态，用户应当能强制重下。方案见 5.8。

### 5.4 决策点三：下载后裁决（Tier 3）

**位置**：替换 `DownloadWorker.java:57-67` 的选择逻辑与 `MediaFiles.largest()`。

**规则**：用 5.1 的判据二比较「新下载的文件」与「歌曲现有的全部版本」，保留胜者；平局保留已有文件。

**不再使用字节数做决胜。** 设计初稿曾写「字节数仅作最终决胜」，实现时去掉了：音质档案相同时（同编码、同采样率、同码率）比较字节数没有任何额外信息（时长已经校验），而「平局保留已有文件」才是正确的行为——它让重复下载不产生文件抖动。字节数因此完全退出判据。

**成本控制**：`songs` 行里已经存有上次探测的 `sample_rate/bits/bitrate`，`songs.path` 的后缀给出扩展名，因此**主版本不需要额外的 ffprobe**。只有 `versions()` 返回的额外文件（异常中断留下的同曲文件）才需要按需探测，这类文件很少。

**必须保留这一层。** Tier 1/2 用档位标签决策，Tier 3 用实测文件裁决。二者职责不同：前者省流量，后者防止「拿回来的东西其实更差」。加上前置决策后不能删掉兜底。

### 5.5 旧文件保留：回收站

**位置**：替换 `DownloadWorker.java:77-82` 的删除循环。

**位置可配置**：

```yaml
archive:
  trash: ${ARCHIVE_TRASH:}     # 留空 = <music>/.trash
```

- `ARCHIVE_TRASH` 为空时默认 `<music>/.trash`。
- 可指向音乐目录之外（例如 NAS 上单独一块盘），避免与音乐库混在一起。

**为什么默认放在音乐目录内**：`MediaFiles.move()` 用 `ATOMIC_MOVE`（`:121`）。同一文件系统内移动是原子的、不占用额外空间、不受目标盘容量影响。指向音乐目录之外时**必须校验与音乐目录是否同一文件系统**，跨文件系统会退化为复制（见下）。

**目录结构**：`<trash>/<songId>/<yyyyMMdd-HHmmss>-<原文件名>`
时间戳前缀保证不覆盖、按时间可排序、人工可读。

**移动失败绝不删除**（本节硬性规则）：

```
1. 尝试 ATOMIC_MOVE     同文件系统：原子、零空间成本
2. 退回普通 move        跨文件系统：复制后删除，占用目标盘空间
3. 仍失败 → 放弃移动，旧文件留在原地，记 warning
```

第 3 条的语义是「退回当前行为」：旧文件成为专辑目录里的孤儿文件，由现有策略容忍（`README.md:109`）。**任何情况下都不允许在移动失败后执行删除**——回收站的职责是保命，不能变成新的数据丢失路径。

**必须从扫描中排除**：

`DuplicateFileService` 用 `Files.walk(files.root)` 扫描（`:73`），当前只排除了 `.work`（`:203`）。回收站默认位于音乐目录内，不排除的话里面的文件会被哈希、计算指纹，并以「仅存量文件 / 疑似重复」出现在音乐档案页。

排除规则需要两条，同时覆盖「回收站配置在音乐目录内」和「配置在目录外但仍是点号目录」两种情况：

```java
private boolean isIgnored(Path path) {
    // 1) 显式配置的回收站，无论它是否位于音乐目录内
    if (trashRoot != null && path.startsWith(trashRoot)) return true;
    // 2) 任何点号开头的顶层目录：.work、.trash 及未来的工作目录
    Path relative = files.root.relativize(path);
    return relative.getNameCount() >= 1 && relative.getName(0).toString().startsWith(".");
}
```

把 `:203` 的 `.work` 前缀判断换成这条通用规则，保留原有的符号链接判断。

**启动时配置校验**（不通过则拒绝启动并给出明确原因）：

- 回收站目录不得等于音乐目录；
- 音乐目录不得位于回收站目录之内（否则排除规则会连带排除整个音乐库）；
- 回收站与音乐目录不在同一文件系统时记录 warning，说明将退化为复制且需要目标盘空间。

**实现注意**：`MediaFiles.safe()` 只接受音乐根目录内的路径（`:24-34`），回收站若在根目录外不能用它校验。需要为回收站单独维护 `trashRoot`，做独立的规范化与校验。

回收站文件不进 `file_inventory`、不被 `songs.path` 引用，因此对应用不可见。恢复方式是人工移动文件，本期不做 UI。

**副作用（正向）**：`versions()` 依赖专辑目录里的同曲文件发现历史版本（`MediaFiles.java:45-64`）。旧文件移入回收站后，专辑目录只保留当前文件，未来产生的孤儿文件会减少。

### 5.6 回收站清理

**位置**：`ArchiveEngine.scan()` 中已有的每日维护分支（`:55`），与 `repairMissing()` 并列。不新起调度器。

**规则**：递归删除 `<trash>/` 下 mtime 超过 `superseded-retention-days` 的文件，随后移除空目录。删除前确认路径位于 `trashRoot` 之内。清理异常只记日志。

`superseded-retention-days: 0` 表示不保留：跳过 5.5 的移动、直接删除（即当前行为）；已有回收站内容仍照常清理。

### 5.7 幂等与并发

**不需要新增机制。** 现有实现已经覆盖：

- 入队幂等：`one_active_song` 部分唯一索引（`schema.sql:29`）。
- 状态互斥：`ArchiveStore` 的 `synchronized` + 单连接池 + `TransactionTemplate`。
- 提交竞态：`complete()` 校验 `status == 'RUNNING'`（`:137`），等价于版本检查。
- SKIPPED 不在 `one_active_song` 的状态集合内，因此被跳过的歌在下一个升级窗口可以重新入队。

### 5.8 被跳过任务的强制重下（已决策：方案 A）

`SKIPPED` 是终态。如果用户不认可这个判断（例如怀疑本地那份无损是假的、想重新拉一份），必须有强制通道，否则构成死路。

**已采用方案 A**：新增 `tasks.forced` 列，`control("force")` 置位，Tier 2 见到 forced 则跳过比较直接传输。

| 方案 | 代价 | 结论 |
|---|---|---|
| **A. 新增 `tasks.forced` 列** | 项目第一次 schema 迁移 | **采用**，语义清晰 |
| B. 复用 `policy` 存 `FIDELITY_FORCE` | 零迁移 | 拒绝，污染 `policy` 语义 |
| C. 复用 `attempts` 存哨兵值（如 `-1`） | 零迁移 | 拒绝，魔法数字无文档 |

**迁移机制**：`schema.sql` 全部是 `CREATE TABLE IF NOT EXISTS`，`spring.sql.init.mode: always` 只对**新建库**生效，已有数据库不会获得新列，项目当前没有 Flyway/Liquibase。因此需要一个最小的幂等迁移步骤：

```java
// 启动时，schema.sql 初始化之后
if (!columnExists("tasks", "forced"))
    db.execute("ALTER TABLE tasks ADD COLUMN forced INTEGER NOT NULL DEFAULT 0");
```

这个 `SchemaMigrations` 组件是**共用基础设施**——`DESIGN-LIBRARY-VIEW.md` 新增的 `file_inventory.song_id` 列也走同一条路径。两处合计一次引入，约 15 行。

前端行为：SKIPPED 任务的按钮从「重试」改为「强制重下」，调用 `/api/tasks/{id}/force`。

### 5.9 可观测性

- 新增 `tasks.status = 'SKIPPED'`，跳过原因写入已有的 `error` 字段，中文直出给 UI。例如：
  - `服务返回档位 exhigh 不高于本地 lossless，未下载`
  - `本地已是该策略最高档位 jymaster`
- **Tier 1 的跳过不落库。** 它是最高频路径（每周全量扫描），且没有增量信息；为它写行会让 `tasks` 表无谓膨胀。
- Tier 2 的跳过落库（此时任务已存在）。SKIPPED 行相对稀疏，暂不需要单独保留策略；`tasks()` 查询本身有 `LIMIT 500`（`ArchiveStore.java:24`）。

`tasks.status` 是无 CHECK 约束的 TEXT（`schema.sql:24`），因此新增状态值**不需要 schema 变更**。

---

## 6. 数据与配置变更

**Schema**：新增 `tasks.forced INTEGER NOT NULL DEFAULT 0`（5.8）与 `file_inventory.song_id INTEGER`（见 `DESIGN-LIBRARY-VIEW.md`），两者共用 5.8 引入的 `SchemaMigrations`。索引新增 `CREATE INDEX IF NOT EXISTS idx_inventory_song ON file_inventory(song_id)`。

**配置**（`application.yml`，均带默认值）：

```yaml
archive:
  trash: ${ARCHIVE_TRASH:}                                  # 留空 = <music>/.trash
  superseded-retention-days: ${SUPERSEDED_RETENTION_DAYS:7}  # 0 = 不保留，回到当前行为
  upgrade:
    lossy-bitrate-ratio: ${UPGRADE_LOSSY_BITRATE_RATIO:1.25} # 有损→有损的显著提升门槛
```

三个配置项均沿用项目现有的「环境变量 + 默认值」写法（对照 `application.yml:26-35`）。

**部署文件同步**（否则环境变量无法生效）：

| 文件 | 改动 |
|---|---|
| `.env.example` | 增加 `ARCHIVE_TRASH=`（留空注释说明默认值） |
| `compose.yaml` | `environment` 增加 `ARCHIVE_TRASH: ${ARCHIVE_TRASH:-}` |
| `deploy/compose.nas.yaml` | 同上 |
| `Dockerfile` | 无需改动（`ARCHIVE_TRASH` 由 Compose 传入；若用户配置到 `/music` 之外，需自行加卷） |
| `README.md` | 环境变量表增加 `ARCHIVE_TRASH` 与 `SUPERSEDED_RETENTION_DAYS`，并说明回收站位置与恢复方式 |

`ARCHIVE_TRASH` 指向 `/music` 之外时，Compose 需要额外的卷映射，否则会落进容器可写层并在重建时丢失。README 需明确这一点。

---

## 7. 失败模式与恢复

| 场景 | 行为 |
|---|---|
| `moveToTrash` 与 DB 提交之间崩溃 | 与现状同类，但方向更安全：旧文件仍在专辑目录，下次 `versions()` 会重新发现它，无害 |
| DB 提交后、移入回收站前崩溃 | 旧的被替换文件残留在专辑目录，成为孤儿文件；沿用现有「不自动清理孤儿文件」的策略（`README.md:109`） |
| 回收站与音乐目录跨文件系统 | `ATOMIC_MOVE` 不支持 → 退回普通 `move`（复制后删除）。需要目标盘空间，速度更慢；启动时已 warning |
| 跨文件系统且目标盘空间不足 | 复制失败 → 旧文件留在原地，记 warning。**不删除** |
| `ARCHIVE_TRASH` 配置非法（等于音乐目录、或是音乐目录的父目录） | 启动失败并给出明确原因，不进入半可用状态 |
| 回收站位于音乐目录内且排除规则失效 | 回收站内容会以「仅存量文件」出现在音乐档案页，但不会被下载流程引用。纯展示噪声，不造成数据风险 |
| 磁盘空间不足（同文件系统） | 同文件系统内移动不消耗空间，回收站不加重此风险。下载前的可用空间检查在 `DownloadWorker.java:140-141` |
| 回收站清理失败 | 记日志，不影响下载与扫描 |
| `resolve()` 返回未知档位 | Tier 2 放行，由 Tier 3 按实测裁决 |
| 假无损被采纳 | Tier 3 无法识别（无法区分），旧文件保留 7 天，人工可恢复 |

---

## 8. 前端改动

`frontend/src/App.vue`：

- `:23` `states` 增加 `SKIPPED: '已跳过'`。该对象同时驱动 `:125` 的筛选下拉（`v-for="(label, key) in states"`），因此筛选器自动获得新选项。
- `:127` 重试按钮的允许集合增加 `'SKIPPED'`，但 SKIPPED 行的按钮文案改为「强制重下」并调用 `/api/tasks/{id}/force`（5.8），普通重试路径保持不变。
- `frontend/src/style.css`：`.status.SKIPPED` 可选补一条样式，否则落到 `.status` 的默认灰绿样式，也可接受。

---

## 9. 测试计划

| 测试 | 内容 |
|---|---|
| `QualityComparatorTest`（新） | 无损/有损门槛；无损内部按 sampleRate/bits 排序；有损内部按 bitrate 排序且同码率时 aac > mp3；**`m4a` + bits≥16 判为无损（ALAC）**；平局保留已有；显著提升门槛的四条分支 |
| `UpgradePlannerTest`（新） | Tier 1 四条规则：顶档跳过、非顶档入队、`level` 为空放行、`level` 为 `unknown` 放行 |
| `DownloadWorkerTest`（改） | Tier 2：双端已知且无提升时不发生传输、状态为 SKIPPED、原因写入 `error`；任一端未知时正常传输；`forced` 任务绕过比较；替换后旧文件出现在回收站且专辑目录只剩一个文件 |
| `TrashPolicyTest`（新） | 默认位置为 `<music>/.trash`；`ARCHIVE_TRASH` 覆盖生效；非法配置（等于音乐目录、是音乐目录父目录）被拒绝；`retention=0` 时不移动直接删除；**移动失败时旧文件保留、绝不删除** |
| `DuplicateFileServiceTest`（改） | 回收站内的音频不计入 `file_inventory`；`.work` 仍被排除；回收站配置在音乐目录之外时行为一致 |
| `ArchiveEngineTest`（新） | 回收站清理按 mtime 与保留天数工作；不触碰保留期内的文件；不越出 `trashRoot` |
| `StoreAndSecurityTest`（改） | SKIPPED 可被强制重下；SKIPPED 不阻塞同一歌曲的新任务入队；新增状态不影响 `stats()`；`SchemaMigrations` 对已有库补列、对新建库幂等 |
| `MigrationTest`（新） | 用旧版 schema 建库后跑迁移，`tasks.forced` 与 `file_inventory.song_id` 均补齐；重复执行不报错 |

---

## 10. 分期与验收

**阶段 A — 可回退的替换（5.5 + 5.6 + 扫描排除）**

最小、独立可发、不依赖其他阶段。改动集中在 `DownloadWorker`、`MediaFiles`、`DuplicateFileService`、`ArchiveEngine`，加上第 6 节的部署文件同步。

验收：`ARCHIVE_TRASH` 留空时旧文件出现在 `<music>/.trash/<songId>/`；指向音乐目录之外时行为一致且启动有跨文件系统 warning；非法配置拒绝启动；专辑目录只剩当前文件；音乐档案页不出现回收站内容；保留期后自动清理；移动失败时旧文件保留在原地。

> 先做 A 的理由：它让 B 和 C 的判断失误变成可恢复的。顺序反过来的话，B/C 一旦判错就是永久损失。

**阶段 B — 音质比较与替换裁决（5.1 + 5.4）**

验收：假无损场景下不再静默丢弃真 320（旧文件在回收站）；同档位重复下载不再抖动；新增单元测试全绿。

**阶段 C — 两级前置决策、强制重下与可观测性（5.2 + 5.3 + 5.8 + 5.9 + 第 8 节）**

含首次 schema 迁移（`SchemaMigrations` + `tasks.forced`）。

验收：已顶档的歌不再产生每周任务；服务返回同档位时任务标记 SKIPPED 且无传输发生；SKIPPED 可强制重下且确实绕过比较；旧库升级后列已补齐且重复启动不报错；UI 正确显示、筛选 SKIPPED 且按钮为「强制重下」。

**阶段 D — 可选，依实测决定**

假无损频谱检测（ffmpeg 高频滚降分析）。**前置条件是阶段 C 已上线并积累了足够的 SKIPPED / 替换数据**，能回答「假无损实际发生了多少次」。在此之前做检测是无的放矢——注意文件大小与码率对假无损没有区分度（假 FLAC 的码率与体积与真无损一致），这是唯一有效的自动手段比「粗筛」重得多的原因。

---

## 11. 已决策记录

**D1 回收站保留天数 —— 7 天（默认值，可配）。**
它是假无损与「下错版本」问题唯一的廉价兜底，这两类问题往往几天后才被发现。成本个人规模下可忽略。`superseded-retention-days: 0` 可回到当前行为。

**D2 接受本项目第一次 schema 迁移 —— 接受。**
新增 `tasks.forced`（5.8），配套引入 `SchemaMigrations` 幂等迁移组件。该组件同时服务 `DESIGN-LIBRARY-VIEW.md` 的 `file_inventory.song_id`，一次引入两处受益。理由：为避免一次 15 行的迁移而污染 `policy` 或使用哨兵魔法值，代价更高且难以维护。

**D3 假无损人工复核队列 —— 不做。**
靠阶段 A 的保留期兜底。若阶段 D 实测证明假无损高频发生，再一并设计复核队列，而不是现在预留空表。

**D4 回收站位置 —— 环境变量可配，默认在音乐目录内。**
`ARCHIVE_TRASH` 留空时用 `<music>/.trash`。默认留在音乐目录内是因为同文件系统内 `ATOMIC_MOVE` 原子且零空间成本，且 Docker 部署无需额外卷。指向目录外时支持，但启动校验并 warning，移动失败一律不删除。

---

## 12. 对原方案的处置对照

| 原方案条目 | 结论 | 理由 |
|---|---|---|
| 新建 `track_external_ids` 表（自称最关键） | **拒绝** | `songs.id` 已是网易云 `songId`，唯一性由主键保证，比 `uk_source_ext` 更强。跨来源扩展在只有单一来源时是纯负债 |
| 拆分 `tracks` / `track_files` | **拒绝** | 现有「单指针 + 内容哈希文件名」已解决同一问题，且不产生孤儿行。拆表要改 `ArchiveStore` 几乎所有方法、`members`/`tasks` 外键，以及 `DuplicateFileService` 里 `replace(s.path,'\','/')=f.path` 的字符串 join（`ArchiveStore.java:44`） |
| 新建 `download_tasks` 表 + `uk_dedup` | **拒绝** | `one_active_song` 已覆盖。且 `UNIQUE(source, external_id, target_quality, status)` 把 `status` 放进唯一键是错的：完成时 UPDATE status 会改变键，而两行同为 `DONE_NEW` 的重复反而被允许 |
| 有状态的 `DownloadDecider` 服务 + repository 查询 | **部分采纳** | 采纳「把比较逻辑抽成可测试的纯函数」；拒绝「决策集中在一处」。决策需要不同时机的信息：入队前只有 `songs` 行，下载前才有 `source.actual()`，下载后才有实测探测 |
| `QualityScore` 加权和 + `score()` | **拒绝，改为两级字典序** | `codecWeight*100_000` 一档即压倒 bitrate 全区间（AAC 256 会被判为远好于 MP3 320）；且巨大常数偏置让 `score()*1.15` 的乘法阈值失去意义。同一段代码里 `+THRESHOLD` 与 `*1.15` 两种判据并存，自相矛盾 |
| 元数据模糊匹配 `findByMeta` | **拒绝** | 单来源下 `songs.id` 是精确锚点，模糊匹配只会引入「把 A 歌的文件判给 B 歌」的风险 |
| `seen` 表 | **拒绝** | 现有内存 diff（`ArchiveStore.java:81-82`）更便宜且事务一致；新增状态源只会带来不一致 |
| staging + 软删 + 延迟清理 | **部分采纳** | staging 已存在（`.work/<id>.part`、`.tagged.*`），DB 提交顺序也已正确。**唯一缺的是延迟清理**，即 5.5 |
| 假无损「文件大小粗筛」 | **拒绝** | 对该场景无效：假 FLAC 的码率与体积与真无损一致甚至更大，粗筛方向是反的，永远不会触发 |
| `requireReviewWhenFakeSuspect` 开关 | **推迟** | 触发条件缺失（见上一条），现阶段等于空开关 |
| ffmpeg 频谱分析 | **推迟到阶段 D** | 方向正确，是唯一有效手段；但需先有阶段 A 的兜底与阶段 C 的数据 |
| 指纹 BER 校验防「下成现场版」 | **拒绝（理由不同）** | 防「下成现场版」的第一道闸门是已有的时长校验（`MediaFiles.java:102-105`，对条目元数据校验，非对旧文件），指纹对同曲不同版本的区分度不足以支撑自动替换 |
| ShedLock / Quartz 集群 | **拒绝** | `README.md:99` 明确单实例；SQLite + `maximum-pool-size: 1` 直接禁止多实例 |
| JPA `@Version` 乐观锁 | **拒绝** | 无 JPA 依赖；`complete()` 的状态校验（`:137`）已是等价机制 |
| `SELECT ... FOR UPDATE` | **拒绝** | SQLite 不支持行级锁 |
| `@Transactional` 与文件 IO 分离 | **采纳（现有代码已符合）** | 原方案第 6 节提出该原则，但第 4 节的 `commitReplacement` 示例自身在 `@Transactional` 内注释「事务外」，前后矛盾 |
| 阈值配置化 | **采纳** | 见第 6 节 |
| 落地顺序（先建表 → Decider → 状态机 → 替换流程） | **重排** | 改为：先做可回退的替换（阶段 A），再做比较与裁决（B），最后做前置决策（C）。理由见第 10 节 |
