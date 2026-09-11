# 验证记录

## 2026-09-11 音质决策、回收站与音乐档案合并

环境：macOS、Azul JDK 25.0.1（编译目标仍为 21）、Maven 3.9.6、Node 22.23.1。

- 67 项离线自动化测试，0 失败、0 错误、1 跳过（LiveGatewayTest 需联网开关）：
  - 9 项音质比较：无损/有损门槛、ALAC 与 AAC 共用 `m4a` 扩展名的区分、无损与有损各自的排序、显著提升门槛的四条分支、songs 行探测规格缺失时退回探测。
  - 14 项下载与替换：实测音质决定保留哪个文件（正反两个方向都与旧的字节数判据相反）、数据库提交失败时保留原文件、被替换文件进回收站且内容完整、跳过条件（服务无更高档位 / 本地文件缺失时不得跳过 / 未知档位不得跳过 / 强制重下绕过比较）。
  - 7 项回收站与路径：默认位置、`ARCHIVE_TRASH` 覆盖、非法配置被拒、`retention=0` 直接删除、保留期清理与空目录回收、废弃文件移动、`.work`/回收站/点号目录的扫描排除。
  - 6 项扫描解析 `song_id` 与回收站排除。
  - 13 项存储与接口：每周升级跳过已到策略顶档的歌（未知档位保守放行）、SKIPPED 不阻塞新任务且可强制重下、统一视图三类行（已归档 / 文件缺失 / 仅存量文件）与筛选、未扫描时已归档歌曲仍可见。
  - 4 项迁移：旧库补列、重复执行幂等、缺表时报错而非静默跳过，以及按真实启动顺序对已发布的 0.1.0 库执行 schema.sql 与迁移。
- Vue TypeScript 检查与 Vite 生产构建通过。
- 发布后修复的两个缺陷，均先写出能复现故障的回归测试再改代码：
  - **启动崩溃**：`schema.sql` 在 `SchemaMigrations` 之前执行，其中 `CREATE INDEX ... ON file_inventory(song_id)` 引用了迁移尚未添加的列，已发布的 0.1.0 库启动即抛 `no such column: song_id`。索引改为只在迁移中创建。用一种 0.1.0 建的库（含订阅/歌曲/任务/存量记录）实测 0.2.1 产物：正常启动、补齐 `tasks.forced` 与 `file_inventory.song_id`，默认启动扫描后 `file_inventory.song_id` 正确回填。
  - **环境变量失效**：`SUPERSEDED_RETENTION_DAYS` 未在 `application.yml` 中显式映射，`@Value` 的宽松绑定实际查找的是 `ARCHIVE_SUPERSEDED_RETENTION_DAYS`，导致该变量被静默忽略、保留天数恒为 7。已在 `application.yml` 中映射，并以环境变量实跑产物确认生效。

未验证：Docker 镜像构建与 Compose 启动；跨文件系统的回收站（`ARCHIVE_TRASH` 指向另一块盘）会走复制回退路径，代码已处理并会在启动时告警，但未在真实跨设备环境执行。

---

日期：2026-09-05。环境：Windows、Temurin JDK 21.0.4、Node 24.1.0、Maven 3.9.9、本机 ffmpeg/ffprobe。

## 已通过

- Vue TypeScript 检查与 Vite 生产构建。
- 24 项离线自动化测试（0 失败、0 错误）：
  - 8 项 SQLite 事务、跨歌单去重、仅新增、重启状态、取消/重试和 Spring Security 会话/CSRF/真实密码登录测试。
  - 5 项 URL、完整 ID 列表、音频时长、加密存储与协议加密测试。
  - 6 项下载传输与路径测试：有效 Range 续传、服务器忽略 Range、资源变更、截断、错误范围、暂停及路径越界。
  - 4 项音质测试：服务端静默降级、试听拒绝、认证/网络错误、不同策略与未知档位。
  - 1 项真实 FLAC 测试：ffmpeg 生成测试音频，ffprobe 验证，写标签后解码哈希保持一致。
- 单独启用的公开接口联网测试：歌单 `3778678` 完整返回 200 首，资源解析接口返回 code 200；未下载该歌单音频。
- 可执行 JAR 启动成功，HTTP 健康检查可用。
- 实际浏览器管理员登录成功，页面加载正常。
- 浏览器创建“仅新增”订阅后，后台显示真实歌单名“热歌榜”、200 首歌曲；下载队列保持为空，确认首次基线不触发下载。
- 网易云二维码图片生成成功，轮询显示“等待扫码”。没有使用用户账号完成扫码授权。
- 桌面与 390px 手机宽度下检查页面布局。

## 未验证

- 本机没有 Docker CLI/Engine，未执行镜像构建、Compose 启动或 ARM64 实机验收。
- 未获取用户 Cookie 或登录用户网易云账号，因此账号扫码确认、会员档位、受限歌曲完整下载尚需在用户账号与目标环境中验收。
- 离线套件默认跳过 LiveGatewayTest；联网测试已单独运行通过。缺少 ffmpeg 的环境会跳过真实媒体工具测试，其余测试不依赖 ffmpeg。

## 复现

```powershell
mvn test
cd frontend
npm ci
npm run build
cd ..

# 可选：只读公开接口测试，不使用账号、不下载音乐
$env:NERI_LIVE_SMOKE='true'
mvn -Dtest=LiveGatewayTest test
Remove-Item Env:NERI_LIVE_SMOKE
```

浏览器验收数据位于被忽略的 `.tools/preview`，与正式运行默认使用的 `data`、`music` 目录隔离。
