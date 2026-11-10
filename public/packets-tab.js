// Вкладка «Онлайн-боты»: только список запущенных ботов и действия с одним ботом.
// Подключается после app.js. Массовых рассылок здесь нет намеренно.
(function () {
  const listEl = document.getElementById('runningList');
  const countEl = document.getElementById('runningCount');
  const refreshBtn = document.getElementById('runningRefreshBtn');
  if (!listEl) return;

  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  async function api(method, url, body) {
    const res = await fetch(url, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
  }

  async function loadRunning() {
    try {
      const bots = await api('GET', '/api/bots/running');
      countEl.textContent = `Онлайн: ${bots.length}`;
      if (!bots.length) {
        listEl.innerHTML = '<div style="font-size:12px;color:var(--text2);">Нет запущенных ботов</div>';
        return;
      }
      listEl.innerHTML = bots.map(b => `
        <div class="running-bot" data-id="${esc(b.id)}"
             style="background:var(--bg3);border:1px solid var(--border);border-radius:8px;padding:10px;display:flex;flex-wrap:wrap;gap:8px;align-items:center;">
          <strong style="min-width:120px;">🟢 ${esc(b.username)}</strong>
          <span style="font-size:12px;color:var(--text2);">${esc(b.server || '—')}</span>
          <input type="text" class="rb-chat" placeholder="Сообщение в чат" maxlength="256"
                 style="flex:1;min-width:140px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);padding:6px;">
          <button type="button" class="rb-send tab-btn">Отправить</button>
          <button type="button" class="rb-jump tab-btn">Прыжок</button>
          <button type="button" class="rb-off tab-btn">Отключить</button>
        </div>`).join('');
    } catch (e) {
      countEl.textContent = 'Ошибка загрузки: ' + e.message;
    }
  }

  listEl.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button');
    if (!btn) return;
    const row = btn.closest('.running-bot');
    const id = row?.dataset.id;
    if (!id) return;
    try {
      if (btn.classList.contains('rb-send')) {
        const text = row.querySelector('.rb-chat').value.trim();
        if (!text) return;
        await api('POST', `/api/bots/${encodeURIComponent(id)}/chat`, { text });
        row.querySelector('.rb-chat').value = '';
      } else if (btn.classList.contains('rb-jump')) {
        await api('POST', `/api/bots/${encodeURIComponent(id)}/jump`);
      } else if (btn.classList.contains('rb-off')) {
        await api('POST', `/api/bots/${encodeURIComponent(id)}/disconnect`);
        await loadRunning();
      }
    } catch (e) {
      alert('Ошибка: ' + e.message);
    }
  });

  refreshBtn.addEventListener('click', loadRunning);

  // Обёртка над switchTab из app.js: не трогаем сам app.js
  const originalSwitchTab = window.switchTab;
  window.switchTab = function (tab) {
    originalSwitchTab(tab);
    const panel = document.getElementById('runningTab');
    const btn = document.getElementById('tabRunning');
    const active = tab === 'running';
    panel.style.display = active ? 'block' : 'none';
    if (btn) btn.classList.toggle('active', active);
    if (active) loadRunning();
  };

  setInterval(() => {
    if (document.getElementById('runningTab').style.display === 'block') loadRunning();
  }, 5000);
})();
