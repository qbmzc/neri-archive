<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
const clearTimeout = (id: number) => window.clearTimeout(id);
type Row = Record<string, any>;
const tab = ref('overview');
const tabs = [{ id: 'overview', icon: '◫', label: '总览' }, { id: 'subscriptions', icon: '▤', label: '歌单订阅' }, { id: 'tasks', icon: '↓', label: '下载队列' }, { id: 'library', icon: '♫', label: '音乐档案' }, { id: 'settings', icon: '⚙', label: '账号与设置' }];
const overview = ref<Row>({}); const subscriptions = ref<Row[]>([]); const tasks = ref<Row[]>([]); const library = ref<Row[]>([]);
const account = ref<Row | null>(null); const accountMessage = ref('尚未检查登录'); const loaded = ref(false); const busy = ref(false);
const toast = ref(''); const query = ref(''); const filter = ref('ALL'); const cookie = ref(''); const modal = ref(false);
const form = ref({ source: '', intervalMinutes: 15, initialDownload: true, policy: 'FIDELITY', autoUpgrade: false });
const qr = ref(''); const qrMessage = ref(''); let csrf: { header: string; token: string }; let refreshTimer: number; let qrTimer: number; let toastTimer: number;
const quality: Row = { jymaster: '超清母带', hires: 'Hi-Res', lossless: '无损', sky: '沉浸环绕', jyeffect: '高清环绕', exhigh: '极高', higher: '较高', standard: '标准', unknown: '未知档位' };
const states: Row = { QUEUED: '等待下载', RUNNING: '下载中', PAUSED: '已暂停', AUTH_REQUIRED: '需要登录', DONE: '已完成', FAILED: '失败', CANCELLED: '已取消' };
const currentTitle = computed(() => tabs.find(t => t.id === tab.value)?.label);
const shownTasks = computed(() => tasks.value.filter(t => filter.value === 'ALL' || t.status === filter.value));
const shownLibrary = computed(() => library.value.filter(s => `${s.name} ${s.artist} ${s.album}`.toLowerCase().includes(query.value.toLowerCase())));
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
  const results = await Promise.all([api('/overview'), api('/subscriptions'), api('/tasks'), api('/library')]);
  [overview.value, subscriptions.value, tasks.value, library.value] = results; loaded.value = true;
}
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
function percent(task: Row) { return task.total_bytes ? Math.min(100, Math.round(task.bytes_done / task.total_bytes * 100)) : 0; }
onMounted(async () => {
  try { csrf = await api('/csrf'); await refresh(); void checkAccount(); } catch (e) { notify((e as Error).message); }
  const poll = async () => { try { await refresh(); } catch (e) { notify((e as Error).message); } finally { refreshTimer = window.setTimeout(poll, 4000); } };
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
          <div class="task-list"><article v-for="task in shownTasks" :key="task.id" class="task-card"><div class="task-main"><div class="song-icon">♫</div><div class="song-name"><strong>{{ task.name }}</strong><small>{{ task.artist }} · #{{ task.id }}</small></div><span class="status" :class="task.status">{{ states[task.status] }}</span></div><div class="task-details"><span>请求 {{ quality[task.requested_level] || '待解析' }} <span class="separator">→</span> 实际 {{ quality[task.actual_level] || '待解析' }}</span><span>{{ bytes(task.bytes_done) }} / {{ task.total_bytes ? bytes(task.total_bytes) : '未知大小' }}</span></div><div class="progress"><div :style="{ width: percent(task) + '%' }"></div></div><p v-if="task.error" class="task-error">{{ task.error }}</p><div class="task-actions"><small>尝试 {{ task.attempts }} 次</small><button v-if="['RUNNING','QUEUED'].includes(task.status)" :disabled="busy" @click="action(() => api(`/tasks/${task.id}/pause`, 'POST'), '已暂停')">暂停</button><button v-if="['PAUSED','FAILED','CANCELLED','AUTH_REQUIRED'].includes(task.status)" :disabled="busy" @click="action(() => api(`/tasks/${task.id}/retry`, 'POST'), '已重新排队')">{{ task.status === 'PAUSED' ? '继续' : '重试' }}</button><button v-if="['RUNNING','QUEUED','PAUSED','AUTH_REQUIRED'].includes(task.status)" :disabled="busy" @click="action(() => api(`/tasks/${task.id}/cancel`, 'POST'), '已取消')">取消</button></div></article></div>
        </template>
        <template v-if="tab === 'library'">
          <div class="page-heading"><div><div class="eyebrow">COLLECTION</div><h1>音乐档案</h1><p>文件保存在服务器音乐目录，每首歌曲只归档一份。</p></div><button class="secondary" :disabled="busy" @click="action(() => api('/library/repair', 'POST'), '缺失文件已加入补下载队列')">检查缺失文件</button></div>
          <input v-model="query" class="search" placeholder="搜索歌曲、歌手或专辑…" aria-label="搜索媒体库">
          <div v-if="!shownLibrary.length" class="empty"><span>♫</span><h3>{{ query ? '没有匹配的音乐' : '档案正在等待第一首音乐' }}</h3><p>完整下载并校验通过后，歌曲会出现在这里。</p></div>
          <div v-else class="table-wrap"><table><thead><tr><th>歌曲 / 专辑</th><th>音质</th><th>文件</th><th>归档时间</th><th></th></tr></thead><tbody><tr v-for="song in shownLibrary" :key="song.id"><td><strong>{{ song.name }}</strong><small>{{ song.artist }} · {{ song.album }}</small><small class="path" :title="song.path">{{ song.path }}</small><small v-if="song.metadata_warning" class="danger-text">{{ song.metadata_warning }}</small></td><td><span class="quality">{{ quality[song.level] || '未知档位' }}</span><small>{{ song.sample_rate ? `${song.sample_rate / 1000} kHz` : '采样率未知' }} {{ song.bits ? `/ ${song.bits} bit` : '' }}</small></td><td>{{ bytes(song.bytes) }}</td><td>{{ date(song.downloaded_at) }}</td><td><button :disabled="busy" @click="action(() => api(`/library/${song.id}/upgrade`, 'POST'), '已安排音质检查')">检查升级</button></td></tr></tbody></table></div>
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
