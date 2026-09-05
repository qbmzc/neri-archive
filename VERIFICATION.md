# 验证记录

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
