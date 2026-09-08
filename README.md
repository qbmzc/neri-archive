# Neri Archive

Java 21 + Spring Boot + Vue 3 的网易云歌单监听与音乐归档应用。源于本仓库的网易云协议实现，独立于 Android 项目构建。

实施设计见 [PLAN.md](PLAN.md)。

## 功能

- 独立管理员登录，CSRF 防护，网易云 Cookie 导入验证、扫码登录入口及登录失效处理。
- 多歌单订阅、默认 15 分钟轮询、手动检查、暂停订阅，支持首次全量或仅下载新增。
- 完整 trackIds 校验、分批歌曲详情、快照与任务事务提交、跨歌单去重。
- 高保真优先或环绕优先，核验实际返回档位，拒绝接口标记的试听；不会将 MP3 转成 FLAC 冒充无损。
- SQLite 持久化队列，并发 1–8，默认 2；暂停、取消、手动重试、指数退避、容器重启恢复。
- 仅当 MD5、大小、实际档位匹配时尝试 Range 续传；不支持 Range 则安全重下。链接过期会重新解析。
- 文件长度/MD5（接口提供时）、音轨、时长验证，SHA-256 索引；ffmpeg 以 stream copy 写入基础标签，不重新编码。
- 歌词、翻译/音译歌词、独立封面文件，M3U8 导出；跨歌单只存一份音频。
- 启动后清点音乐目录中的现有音频：SHA-256 确认文件完全相同，Chromaprint 音频指纹识别标签、容器或编码不同但声音内容相近的疑似副本；支持手动重扫、搜索及分页查看全部存量文件，不会自动删除文件。
- 手动音质升级、可选每周自动升级、每日缺失文件检查和手动补下载。新文件成功提交后才删除旧音频。

## Docker Compose

要求 Docker Engine 与 Compose 插件。以下命令在本目录执行。

```sh
cp .env.example .env
# 编辑 .env，填写至少 12 字符的 ADMIN_PASSWORD
mkdir -p music
# Linux 上容器以 UID/GID 10001 运行，挂载目录必须允许该用户写入。
sudo chown 10001:10001 music
docker compose up -d --build
```

浏览器打开 http://localhost:8080，管理员用户名为 `admin`，密码是 `.env` 中的 `ADMIN_PASSWORD`。

账号与设置 → 扫码登录，或导入包含 `MUSIC_U` 的网易云 Cookie → 添加歌单链接/ID。扫描结果与失败原因在订阅卡片显示；首次检查通常在 10 秒内开始，具体完成时间取决于网络和歌单规模。

NAS/局域网访问可在 `.env` 设置 `BIND_ADDRESS=0.0.0.0`，并将 `MUSIC_PATH` 改为 NAS 音乐目录绝对路径。HTTPS 反向代理部署时将 `COOKIE_SECURE=true`。本应用无需暴露数据库端口。

```sh
docker compose logs -f --tail=100
docker compose down
docker compose up -d --build
```

修改 `.env` 后用 `up -d` 重建容器配置。`down` 保留数据卷；不要在需要保留档案时使用 `down -v`。

Dockerfile 使用可供 amd64/arm64 构建的基础镜像；双架构实际镜像需要在相应平台或 buildx 环境验证。仓库不包含已发布镜像，Compose 从本地源码构建。

仓库同时提供 GitHub Actions 构建的 GHCR 镜像（push 到 `main` 或 `v*` tag 时自动构建，amd64）：

```sh
docker pull ghcr.io/qbmzc/neri-archive:latest
# 或指定版本镜像 ghcr.io/qbmzc/neri-archive:0.1.0
```

## 本地 Java 开发

要求 JDK 21、Maven 3.6.3+、Node.js 22.12+（或兼容的 Node 24）、ffmpeg/ffprobe 在 PATH 中。

```powershell
# Windows，全量前后端构建
.\build.ps1
# 本次环境已准备项目内 Maven 时，可使用：
# .\build.ps1 -Maven "$PWD\.tools\apache-maven-3.9.9\bin\mvn.cmd"
$env:ADMIN_PASSWORD = '请替换成你自己的长密码'
java -jar target/neri-archive-0.1.0.jar
```

```sh
# Linux / macOS
sh build.sh
export ADMIN_PASSWORD='replace-with-your-own-long-password'
java -jar target/neri-archive-0.1.0.jar
```

单独运行 `mvn test` 执行后端测试。`frontend` 内执行 `npm run build` 进行 TypeScript 检查与构建。`npm run dev` 只用于前端开发，API 代理到 8080；完整登录与同源验收以打包后的 8080 服务为准。

## 数据与文件

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| ADMIN_PASSWORD | 必填，至少 12 字符 | 管理员密码，无公共默认密码 |
| ARCHIVE_DATA | ./data | SQLite 和加密凭据；容器固定为 /data |
| ARCHIVE_MUSIC | ./music | 音乐目录；容器固定为 /music |
| DOWNLOAD_PARALLELISM | 2 | 下载并发，限制 1–8 |
| PORT | 8080 | 本地服务端口；Compose 中用于宿主端口 |
| COOKIE_SECURE | false | HTTPS 部署设置 true |
| FFMPEG / FFPROBE | ffmpeg / ffprobe | 媒体工具命令或绝对路径 |
| FPCALC | fpcalc | Chromaprint 音频指纹工具命令或绝对路径；Docker 镜像已内置 |
| ARCHIVE_SCHEDULING | true | 测试时可关闭后台调度 |
| DUPLICATE_SCAN_ON_STARTUP | true | 服务启动后自动扫描现有音频文件；大型媒体库可关闭并改为手动扫描 |

歌曲路径：`歌手/专辑/歌曲名 [歌曲ID]-内容哈希.扩展名`。哈希路径让升级期间旧文件保持可读。M3U8 位于 `playlists/歌单ID.m3u8`，使用相对路径。

`credentials.key` 是独立的 AES-GCM 密钥文件；`credentials.enc` 是加密会话。两者与 SQLite 同在持久卷内，密钥不写入镜像或数据库。备份前停止容器，备份整个数据卷和音乐目录；不要只复制运行中的 `archive.db` 而漏掉 WAL。恢复时一起恢复密钥，否则无法解密会话。

## 明确的行为与限制

- 仅单实例、单网易云账号。SQLite 应在本地盘；音乐目录可位于 NAS。
- 删除订阅保留已下载音频，也不取消已排队任务；取消任务请到下载队列操作。
- 暂停订阅停止后续扫描；已排队下载继续。暂停下载则在下载队列操作。
- 同一歌曲同时受不同歌单策略影响时，正在执行的任务使用先入队策略；后续可以手动检查高保真升级。
- 最高音质受账号、歌曲资源与接口返回限制。缺失采样规格不会伪造；未知档位不会自动替换已知档位文件。
- 网易云协议直接参考原项目移植，不依赖第三方公共 API 网关。扫码入口可能受平台设备验证影响；失败会明确提示，提供 Cookie 导入作为替代。
- 没有从当前 Android 应用读取或迁移用户 Cookie。真实账号扫码、会员档位和下载需用户在本机或目标服务器验证。
- 歌词/封面获取失败不影响音频，媒体库会显示提示。当前封面为独立文件，未嵌入音频；没有歌词编辑器、在线播放器、多用户或公共分享功能。
- 失败任务最多自动尝试 5 次，登录失效任务在重新连接账号后恢复。重新扫描不会无限重试已有失败任务，可在队列手动重试。
- 下载过程中断后可恢复任务；只有服务器资源身份与续传响应可验证时才复用部分数据，否则重新下载。
- 进程在文件移动与数据库提交之间崩溃，可能留下未被引用的完整文件；不会将半成品记为成功。当前没有自动清理此类孤立文件及旧版本 sidecar，避免误删用户文件。
- 当前列表适合个人规模；下载历史展示最近 500 项，媒体库未实现服务端分页。

## 许可

沿用原项目 GPL-3.0-or-later。`NeteaseCrypto.java` 保留原开发者版权声明。参见本目录 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。
