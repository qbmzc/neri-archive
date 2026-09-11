<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
const clearTimeout = (id: number) => window.clearTimeout(id);
type Row = Record<string, any>;
const tab = ref('overview');
const tabs = [{ id: 'overview', icon: '◫', label: '总览' }, { id: 'subscriptions', icon: '▤', label: '歌单订阅' }, { id: 'tasks', icon: '↓', label: '下载队列' }, { id: 'library', icon: '♫', label: '音乐档案' }, { id: 'logs', icon: '≡', label: '运行日志' }, { id: 'settings', icon: '⚙', label: '账号与设置' }];
const logs = ref<Row[]>([]); const logLevel = ref('ALL'); const logQuery = ref(''); const logAuto = ref(true); const logError = ref(''); const logBusy = ref(false);
async function refreshLogs() {
  if (logBusy.value) return; logBusy.value = true;
  try { logs.value = await api('/logs?level=' + encodeURIComponent(logLevel.value) + '&query=' + encodeURIComponent(logQuery.value)); logError.value = ''; }
  catch (e) { logError.value = (e as Error).message; } finally { logBusy.value = false; }
}
watch([tab, logLevel], () => { if (tab.value === 'logs') void refreshLogs(); });
const overview = ref<Row>({}); const subscriptions = ref<Row[]>([]); const tasks = ref<Row[]>([]);
const duplicates = ref<Row>({ state: 'IDLE', groups: [] });
const inventory = ref<Row>({ items: [], total: 0, page: 1, pageSize: 50, totalPages: 1 });
const filePage = ref(1); const fileQuery = ref(''); const fileFilter = ref('ALL'); let inventoryRequest = 0;
const account = ref<Row | null>(null); const accountMessage = ref('尚未检查登录'); const loaded = ref(false); const busy = ref(false);
const toast = ref(''); const filter = ref('ALL'); const cookie = ref(''); const modal = ref(false);
const form = ref({ source: '', intervalMinutes: 15, initialDownload: true, policy: 'FIDELITY', autoUpgrade: false });
const qr = ref(''); const qrMessage = ref(''); let csrf: { header: string; token: string }; let refreshTimer: number; let qrTimer: number; let toastTimer: number;
const quality: Row = { jymaster: '超清母带', hires: 'Hi-Res', lossless: '无损', sky: '沉浸环绕', jyeffect: '高清环绕', exhigh: '极高', higher: '较高', standard: '标准', unknown: '未知档位' };
const states: Row = { QUEUED: '等待下载', RUNNING: '下载中', PAUSED: '已暂停', AUTH_REQUIRED: '需要登录', DONE: '已完成', FAILED: '失败', CANCELLED: '已取消', SKIPPED: '已跳过' };
const currentTitle = computed(() => tabs.find(t => t.id === tab.value)?.label);
const shownTasks = computed(() => tasks.value.filter(t => filter.value === 'ALL' || t.status === filter.value));
function notify(message: string) { toast.value = message; clearTimeout(toastTimer); toastTimer = window.setTimeout(() => toast.value = '', 6500); }
async function api(path: string, method = 'GET', data?: unknown) {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (method !== 'GET' && csrf) headers[csrf.header] = csrf.token;
  const response = await fetch('/api' + path, { method, headers, body: data === undefined ? undefined : JSON.stringify(data) });
  if (response.status === 401) { window.location.href = '/login'; throw new Error('管理会话已过期'); }
  const text = await response.text();
  let result: any = {};
  try { result = text ? JSON.parse(text) : {}; } catch { throw new Error('服务器响应无效，请刷新重试'); }
  if (!response.ok) throw new Error(result.message || '操作未完成，请刷新后重试');
  return result;
}
async function refresh() {
  const firstLoad = !loaded.value;
  const results = await Promise.all([api('/overview'), api('/subscriptions'), api('/tasks'), api('/library/duplicates')]);
  [overview.value, subscriptions.value, tasks.value, duplicates.value] = results; loaded.value = true;
  if (firstLoad || tab.value === 'library') await refreshInventory();
}
async function refreshInventory() {
  const request = ++inventoryRequest;
  const path = `/library/files?page=${filePage.value}&pageSize=50&filter=${encodeURIComponent(fileFilter.value)}&query=${encodeURIComponent(fileQuery.value.trim())}`;
  const result = await api(path);
  if (request !== inventoryRequest) return;
  inventory.value = result; filePage.value = result.page;
}
async function searchInventory() { filePage.value = 1; await refreshInventory(); }
async function changeFilePage(page: number) { filePage.value = page; await refreshInventory(); }
async function checkAccount() {
  try { account.value = await api('/account'); accountMessage.value = '网易云已连接'; }
  catch (e) { account.value = null; accountMessage.value = (e as Error).message; }
}
async function action(fn: () => Promise<unknown>, message = '已更新') {
  if (busy.value) return; busy.value = true;
  try { await fn(); await refresh(); notify(message); } catch (e) { notify((e as Error).message); } finally { busy.value = false; }
}
function edit(sub?: Row) {
  form.value = sub ? { source: String(sub.id), intervalMinutes: sub.interval_minutes, initialDownload: !!sub.initial_download, policy: sub.policy, autoUpgrade: !!sub.auto_upgrade } : { source: '', intervalMinutes: 15, initialDownload: true, policy: 'FIDELITY', autoUpgrade: false };
  modal.value = true;
}
async function saveSubscription() {
  await action(async () => { await api('/subscriptions', 'POST', form.value); modal.value = false; }, '订阅已保存，后台将检查歌单');
}
function remove(sub: Row) {
  if (window.confirm(`停止订阅「${sub.name}」？已下载音乐会保留，已入队任务继续处理。`)) action(() => api(`/subscriptions/${sub.id}`, 'DELETE'), '已解除订阅');
}
async function importCookie() {
  await action(async () => { await api('/account/cookie', 'POST', { cookie: cookie.value }); cookie.value = ''; await checkAccount(); }, '网易云已连接，等待登录的任务已恢复');
}
async function createQr() {
  clearTimeout(qrTimer); qr.value = ''; qrMessage.value = '正在获取二维码…';
  await action(async () => { const res = await api('/account/qr', 'POST'); qr.value = res.image; qrMessage.value = '使用网易云音乐 App 扫码并确认'; qrTimer = window.setTimeout(pollQr, 3000); }, '二维码已生成');
}
async function pollQr() {
  try {
    const res = await api('/account/qr/check', 'POST'); qrMessage.value = res.message;
    if (res.code === 803) { qr.value = ''; await checkAccount(); await refresh(); notify('网易云登录成功'); return; }
    if (res.code === 800) return;
    qrTimer = window.setTimeout(pollQr, 3000);
  } catch (e) { qrMessage.value = (e as Error).message; }
}
async function logoutAdmin() { await fetch('/logout', { method: 'POST', headers: { [csrf.header]: csrf.token } }); window.location.href = '/login'; }
function bytes(value: number) { if (!value) return '0 B'; const i = Math.min(3, Math.floor(Math.log(value) / Math.log(1024))); return (value / 1024 ** i).toFixed(i ? 1 : 0) + [' B', ' KB', ' MB', ' GB'][i]; }
function date(value: number) { return value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '等待首次检查'; }
function audioFingerprint(value: unknown) { return typeof value === 'number' && value >= 0 ? value.toString(16).padStart(8, '0') : '未生成'; }
function percent(task: Row) { return task.total_bytes ? Math.min(100, Math.round(task.bytes_done / task.total_bytes * 100)) : 0; }
onMounted(async () => {
  try { csrf = await api('/csrf'); await refresh(); void checkAccount(); } catch (e) { notify((e as Error).message); }
  const poll = async () => { try { if (tab.value === 'logs') { if (logAuto.value) await refreshLogs(); } else await refresh(); } catch (e) { notify((e as Error).message); } finally { refreshTimer = window.setTimeout(poll, 4000); } };
  refreshTimer = window.setTimeout(poll, 4000);
});
onUnmounted(() => { clearTimeout(refreshTimer); clearTimeout(qrTimer); clearTimeout(toastTimer); });
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <a class="brand" href="/"><span class="brand-mark">n<span>•</span></span><span>Neri Archive<small>你的私人音乐档案</small></span></a>
      <div class="nav-label">工作空间</div>
      <nav><button v-for="item in tabs" :key="item.id" :class="{ selected: tab === item.id }" @click="tab = item.id"><span class="nav-icon">{{ item.icon }}</span>{{ item.label }}<span v-if="item.id === 'tasks' && overview.active" class="nav-count">{{ overview.active }}</span></button></nav>
      <div class="sidebar-bottom"><div class="storage"><span class="live-dot"></span> 私人存储<small>剩余 {{ bytes(overview.freeBytes) }}</small></div><button class="text-button" @click="logoutAdmin">退出管理账号 ↗</button><small class="version">NERI ARCHIVE / 0.1</small></div>
    </aside>
    <main>
      <header class="topbar"><span>工作空间 <span class="separator">/</span> {{ currentTitle }}</span><button class="account-pill" @click="tab = 'settings'"><span class="live-dot" :class="{ offline: !account }"></span>{{ account ? account.nickname || '网易云已连接' : '连接网易云账号' }}</button></header>
      <div class="content">
        <template v-if="tab === 'overview'">
          <div class="page-heading"><div><div class="eyebrow">YOUR MUSIC, PRESERVED.</div><h1>让喜欢的音乐，留下来。</h1><p>关注歌单的每一次更新，自动保存可用的最高音质。</p></div><button class="primary" @click="edit()">＋ 添加歌单</button></div>
          <div class="stats"><article><span>正在订阅</span><strong>{{ overview.subscriptions ?? '—' }}<small>个歌单</small></strong><div>持续关注你的音乐收藏</div></article><article><span>已归档歌曲</span><strong>{{ overview.downloaded ?? '—' }}<small>首</small></strong><div>跨歌单共享，避免重复保存</div></article><article><span>待处理任务</span><strong>{{ overview.active ?? '—' }}<small>项</small></strong><div>{{ overview.failed ? `${overview.failed} 项任务需要处理` : '下载队列正在自动管理' }}</div></article></div>
          <section class="hero"><div><span class="eyebrow">AUTOMATIC ARCHIVING</span><h2>你负责发现好音乐。<br>剩下的，交给归档。</h2><p>歌单变更检查 · 完整音频校验 · 自动选择音质</p><button class="hero-button" @click="tab = 'subscriptions'">管理我的订阅 <span>↗</span></button></div><div class="record-art" aria-hidden="true"><div class="record"><div class="record-label">neri<br><small>KEEP THE SOUND</small></div></div><div class="record-note">A COLLECTION<br>THAT GROWS WITH YOU.</div></div></section>
          <div class="section-title"><h2>最近的归档任务 <span>{{ tasks.length }}</span></h2><button class="text-button" @click="tab = 'tasks'">查看全部 →</button></div>
          <div v-if="!tasks.length" class="empty"><span>♫</span><h3>{{ loaded ? (subscriptions.length ? '等待歌单的下一次更新' : '从一张喜欢的歌单开始') : '正在读取你的档案…' }}</h3><p>{{ subscriptions.length ? '新增音乐进入归档队列后，会显示在这里。' : '添加网易云歌单链接，新音乐会在这里与你见面。' }}</p><button class="secondary" @click="edit()">{{ subscriptions.length ? '添加更多歌单' : '添加第一个歌单' }}</button></div>
          <div v-else class="list-card"><div v-for="task in tasks.slice(0, 5)" :key="task.id" class="song-row"><div class="song-icon">♫</div><div class="song-name"><strong>{{ task.name }}</strong><small>{{ task.artist }}</small></div><span class="quality">{{ quality[task.actual_level] || '等待解析' }}</span><span class="status" :class="task.status">{{ states[task.status] }}</span></div></div>
        </template>
        <template v-if="tab === 'subscriptions'">
          <div class="page-heading"><div><div class="eyebrow">PLAYLISTS</div><h1>歌单订阅</h1><p>后台按周期检查变更，网页关闭后仍会继续。</p></div><button class="primary" @click="edit()">＋ 添加歌单</button></div>
          <div v-if="!subscriptions.length" class="empty"><span>▤</span><h3>还没有订阅的歌单</h3><p>粘贴歌单链接，建立你的第一份音乐档案。</p></div>
          <div class="playlist-grid"><article v-for="sub in subscriptions" :key="sub.id" class="playlist-card"><div class="playlist-top"><div class="playlist-art">▤<small>{{ sub.id }}</small></div><span class="status" :class="sub.enabled ? 'DONE' : 'PAUSED'">{{ sub.enabled ? '监听中' : '已暂停' }}</span></div><h2>{{ sub.name }}</h2><p>{{ sub.track_count }} 首歌曲 <span>·</span> 每 {{ sub.interval_minutes }} 分钟检查</p><div class="playlist-info"><span>{{ sub.policy === 'FIDELITY' ? '高保真优先' : '环绕优先' }}</span><span v-if="sub.auto_upgrade">每周音质升级</span></div><small class="muted">上次检查：{{ date(sub.last_scan) }}</small><div v-if="sub.error" class="inline-error">{{ sub.error }}</div><div class="card-actions"><button :disabled="busy || !sub.enabled" @click="action(() => api(`/subscriptions/${sub.id}/scan`, 'POST'), '已安排检查')">立即检查</button><button :disabled="busy" @click="action(() => api(`/subscriptions/${sub.id}`, 'PATCH', { enabled: !sub.enabled }))">{{ sub.enabled ? '暂停' : '继续' }}</button><button @click="edit(sub)">设置</button><button class="danger-text" @click="remove(sub)">移除</button></div></article></div>
        </template>
        <template v-if="tab === 'tasks'">
          <div class="page-heading"><div><div class="eyebrow">DOWNLOADS</div><h1>下载队列</h1><p>实际音质由资源返回结果决定，试听片段不会入库。显示最近 500 项。</p></div><select v-model="filter" aria-label="筛选任务状态"><option value="ALL">全部状态</option><option v-for="(label, key) in states" :value="key">{{ label }}</option></select></div>
          <div v-if="!shownTasks.length" class="empty"><span>↓</span><h3>这里暂时没有任务</h3><p>歌单中的新增音乐会自动进入下载队列。</p></div>
          <div class="task-list"><article v-for="task in shownTasks" :key="task.id" class="task-card"><div class="task-main"><div class="song-icon">♫</div><div class="song-name"><strong>{{ task.name }}</strong><small>{{ task.artist }} · #{{ task.id }}</small></div><span class="status" :class="task.status">{{ states[task.status] }}</span></div><div class="task-details"><span>请求 {{ quality[task.requested_level] || '待解析' }} <span class="separator">→</span> 实际 {{ quality[task.actual_level] || '待解析' }}</span><span>{{ bytes(task.bytes_done) }} / {{ task.total_bytes ? bytes(task.total_bytes) : '未知大小' }}</span></div><div class="progress"><div :style="{ width: percent(task) + '%' }"></div></div><p v-if="task.error" class="task-error">{{ task.error }}</p><div class="task-actions"><small>尝试 {{ task.attempts }} 次</small><button v-if="['RUNNING','QUEUED'].includes(task.status)" :disabled="busy" @click="action(() => api(`/tasks/${task.id}/pause`, 'POST'), '已暂停')">暂停</button><button v-if="['PAUSED','FAILED','CANCELLED','AUTH_REQUIRED','SKIPPED'].includes(task.status)" :disabled="busy" @click="action(() => api(`/tasks/${task.id}/${task.status === 'SKIPPED' ? 'force' : 'retry'}`, 'POST'), task.status === 'SKIPPED' ? '已强制重新下载' : '已重新排队')">{{ task.status === 'PAUSED' ? '继续' : task.status === 'SKIPPED' ? '强制重下' : '重试' }}</button><button v-if="['RUNNING','QUEUED','PAUSED','AUTH_REQUIRED'].includes(task.status)" :disabled="busy" @click="action(() => api(`/tasks/${task.id}/cancel`, 'POST'), '已取消')">取消</button></div></article></div>
        </template>
        <template v-if="tab === 'library'">
          <div class="page-heading"><div><div class="eyebrow">COLLECTION</div><h1>音乐档案</h1><p>文件保存在服务器音乐目录，每首歌曲只归档一份。</p></div><div class="button-row"><button class="secondary" :disabled="busy || duplicates.state === 'RUNNING'" @click="action(() => api('/library/duplicates/scan', 'POST'), '已开始扫描现有音频文件')">{{ duplicates.state === 'RUNNING' ? '正在扫描…' : '检查重复文件' }}</button><button class="secondary" :disabled="busy" @click="action(() => api('/library/repair', 'POST'), '缺失文件已加入补下载队列')">检查缺失文件</button></div></div>
          <section class="duplicate-summary">
            <div><span>已清点音频</span><strong>{{ duplicates.fileCount ?? 0 }}</strong><small>个文件</small></div>
            <div><span>重复或疑似重复</span><strong>{{ duplicates.duplicateGroups ?? 0 }}</strong><small>组 / {{ duplicates.duplicateFiles ?? 0 }} 个副本</small></div>
            <div><span>可释放空间</span><strong>{{ bytes(duplicates.reclaimableBytes) }}</strong><small>{{ duplicates.completedAt ? `上次完成 ${date(duplicates.completedAt)}` : '等待首次扫描' }}</small></div>
          </section>
          <p v-if="duplicates.error" class="task-error" role="alert">重复文件扫描失败：{{ duplicates.error }}</p>
          <p v-else-if="duplicates.fingerprintWarning" class="inline-error">{{ duplicates.fingerprintWarning }}</p>
          <p v-else-if="duplicates.errorCount" class="inline-error">有 {{ duplicates.errorCount }} 个文件未能完成全部检查，详情请查看运行日志。</p>
          <section v-if="duplicates.groups?.length" class="duplicate-results">
            <div class="duplicate-results-heading"><div><h2>重复检查结果</h2><small>共 {{ duplicates.duplicateGroups }} 个独立分组</small></div><small>仅供确认，不会自动删除文件</small></div>
            <div class="duplicate-group-list">
              <article v-for="(group, index) in duplicates.groups" :key="group.matchType + group.files[0]" class="duplicate-group-card">
                <header><div><span class="group-number">重复组 {{ index + 1 }}</span><strong>{{ group.files.length }} 个文件</strong></div><span class="match-badge" :class="group.matchType">{{ group.matchType === 'EXACT' ? 'SHA-256 完全相同' : '疑似同一音频' }}</span></header>
                <div class="group-metrics"><span>保留最大文件 {{ bytes(group.bytes) }}</span><span>预计可释放 {{ bytes(group.reclaimableBytes) }}</span></div>
                <div class="group-files"><code v-for="path in group.files" :key="path">{{ path }}</code></div>
              </article>
            </div>
          </section>
          <div class="section-title inventory-title"><h2>音频文件 <span>{{ inventory.total }}</span></h2><small>每页 50 个</small></div>
          <form class="file-toolbar" @submit.prevent="searchInventory"><input v-model="fileQuery" placeholder="搜索文件路径、歌曲、歌手或专辑…" aria-label="搜索音频文件"><select v-model="fileFilter" aria-label="筛选音频文件" @change="searchInventory"><option value="ALL">全部文件</option><option value="ARCHIVED">已归档</option><option value="UNTRACKED">仅存量文件</option><option value="MISSING">文件缺失</option><option value="DUPLICATE">重复副本</option></select><button>搜索</button></form>
          <div v-if="!inventory.items?.length" class="empty compact"><span>⌕</span><h3>{{ fileQuery ? '没有匹配的音频文件' : '尚未清点到音频文件' }}</h3><p>完成重复文件扫描后，存量音频会分页显示在这里；已归档的歌曲无需等待扫描。</p></div>
          <div v-else class="table-wrap inventory-table"><table><thead><tr><th>文件 / 歌曲</th><th>状态</th><th>音质</th><th>大小</th><th>时间</th><th>内容指纹</th><th></th></tr></thead><tbody><tr v-for="file in inventory.items" :key="file.path"><td><strong>{{ file.name || file.path.split('/').pop() }}</strong><small v-if="file.song_id">{{ file.artist }} · {{ file.album }}</small><small class="path" :title="file.path">{{ file.path }}</small><small v-if="file.metadata_warning" class="danger-text">{{ file.metadata_warning }}</small></td><td><span v-if="file.duplicate_count > 1" class="status FAILED">{{ file.match_type === 'EXACT' ? '完全相同' : '疑似同一音频' }} · {{ file.duplicate_count }} 个</span><span v-else-if="file.state === 'MISSING'" class="status AUTH_REQUIRED">文件缺失</span><span v-else-if="file.state === 'ARCHIVED'" class="status DONE">已归档</span><span v-else class="status PAUSED">仅存量文件</span></td><td><template v-if="file.song_id"><span class="quality">{{ quality[file.level] || '未知档位' }}</span><small>{{ file.sample_rate ? `${file.sample_rate / 1000} kHz` : '采样率未知' }} {{ file.bits ? `/ ${file.bits} bit` : '' }}</small></template><small v-else>—</small></td><td>{{ bytes(file.bytes) }}</td><td>{{ date(file.downloaded_at || file.modified_at) }}</td><td><code>音频 {{ audioFingerprint(file.audio_hash) }}</code><small v-if="file.sha256">文件 {{ file.sha256.slice(0, 12) }}…</small><small v-else>未扫描</small></td><td><button v-if="file.song_id" :disabled="busy" @click="action(() => api(`/library/${file.song_id}/upgrade`, 'POST'), file.state === 'MISSING' ? '已安排补下载' : '已安排音质检查')">{{ file.state === 'MISSING' ? '补下载' : '检查升级' }}</button></td></tr></tbody></table></div>
          <nav v-if="inventory.totalPages > 1" class="pagination" aria-label="音频文件分页"><button :disabled="filePage <= 1" @click="changeFilePage(filePage - 1)">上一页</button><span>第 {{ filePage }} / {{ inventory.totalPages }} 页</span><button :disabled="filePage >= inventory.totalPages" @click="changeFilePage(filePage + 1)">下一页</button></nav>
        </template>
        <template v-if="tab === 'logs'">
          <div class="page-heading"><div><div class="eyebrow">RUNTIME LOGS</div><h1>运行日志</h1><p>当前进程保留最近 1000 条，最多显示 500 条，最新在前。重启后清空。</p></div><button class="secondary" :disabled="logBusy" @click="refreshLogs">刷新日志</button></div>
          <div class="log-toolbar"><select v-model="logLevel" aria-label="日志级别"><option value="ALL">全部级别</option><option v-for="level in ['INFO', 'WARN', 'ERROR', 'DEBUG']" :key="level">{{ level }}</option></select><form @submit.prevent="refreshLogs"><input v-model="logQuery" placeholder="搜索任务编号、路径或错误…" aria-label="搜索日志"><button :disabled="logBusy">搜索</button></form><label class="check"><input v-model="logAuto" type="checkbox">每 4 秒刷新</label></div>
          <p v-if="logError" class="task-error" role="alert">{{ logError }}</p>
          <div v-if="!logs.length" class="empty">暂无匹配日志</div>
          <div v-else class="runtime-logs"><article v-for="entry in logs" :key="entry.id"><div><time>{{ new Date(entry.timestamp).toLocaleString('zh-CN') }}</time> <strong :class="'log-' + entry.level">{{ entry.level }}</strong> <small>{{ entry.logger }}</small></div><pre>{{ entry.message }}</pre></article></div>
        </template>
        <template v-if="tab === 'settings'">
          <div class="page-heading"><div><div class="eyebrow">CONNECTION & STORAGE</div><h1>账号与设置</h1><p>连接你的网易云音乐账号，使用账号可获取的音乐资源。</p></div></div>
          <div class="settings-grid"><section class="panel"><h2>网易云音乐</h2><p class="connection-text"><span class="live-dot" :class="{ offline: !account }"></span>{{ account ? account.nickname || '已连接' : accountMessage }}</p><div class="button-row"><button class="primary" :disabled="busy" @click="createQr">扫码登录</button><button class="secondary" :disabled="busy" @click="action(checkAccount, '登录状态已检查')">检查登录状态</button><button v-if="account" :disabled="busy" @click="action(async () => { clearTimeout(qrTimer); await api('/account', 'DELETE'); account = null; accountMessage = '已断开连接'; }, '网易云账号已断开')">断开</button></div><div v-if="qr || qrMessage" class="qr-area"><img v-if="qr" :src="qr" alt="网易云登录二维码"><p>{{ qrMessage }}</p></div><details><summary>使用 Cookie 登录</summary><p>扫码不可用时，导入浏览器中网易云的 Cookie，需包含 MUSIC_U。内容仅用于服务端连接，保存时加密。</p><textarea v-model="cookie" placeholder="MUSIC_U=…; __csrf=…" autocomplete="off" spellcheck="false" aria-label="网易云 Cookie"></textarea><button class="secondary" :disabled="busy || !cookie.trim()" @click="importCookie">验证并保存</button></details></section>
          <section class="panel"><h2>存储与运行</h2><dl><dt>音乐目录</dt><dd class="code-path">{{ overview.musicPath }}</dd><dt>可用空间</dt><dd>{{ bytes(overview.freeBytes) }}</dd><dt>默认策略</dt><dd>高保真优先 · 首次全量 · 删歌保留文件</dd><dt>歌单导出</dt><dd>music/playlists/歌单ID.m3u8</dd></dl><p class="hint">存储路径、管理员密码和下载并发通过部署环境变量配置。修改后重启服务生效。</p><p class="hint">SQLite 数据库与 credentials.key 请一起备份。自动升级可在每张歌单的设置中开启。</p></section></div>
        </template>
        <footer>为每一次喜欢，留一份回响。<span>Neri Archive · 私人音乐归档</span></footer>
      </div>
    </main>
    <div v-if="toast" class="toast" role="status">{{ toast }}<button @click="toast = ''" aria-label="关闭提示">×</button></div>
    <div v-if="modal" class="modal-backdrop" @click.self="modal = false"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title"><div class="section-title"><h2 id="modal-title">订阅一张歌单</h2><button @click="modal = false" aria-label="关闭">×</button></div><p>自动跟随歌单更新，保存你喜欢的音乐。</p><form @submit.prevent="saveSubscription"><label>歌单链接或 ID<input v-model="form.source" required placeholder="https://music.163.com/#/playlist?id=…" autofocus></label><div class="form-grid"><label>检查周期（分钟）<input v-model.number="form.intervalMinutes" type="number" min="5" max="10080" required></label><label>音质偏好<select v-model="form.policy"><option value="FIDELITY">高保真优先</option><option value="SURROUND">环绕版本优先</option></select></label></div><label class="check"><input v-model="form.initialDownload" type="checkbox">首次订阅下载全部歌曲</label><p class="field-hint">关闭后，只下载以后新增的歌曲。已有订阅的首次策略不会改变。</p><label class="check"><input v-model="form.autoUpgrade" type="checkbox">每周检查更高音质并自动升级</label><div class="modal-actions"><button type="button" class="secondary" @click="modal = false">取消</button><button type="submit" class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存订阅' }}</button></div></form></section></div>
  </div>
</template>
