import TelegramBot from 'node-telegram-bot-api';
import { SocksProxyAgent } from 'socks-proxy-agent';
import 'dotenv/config';

const TG_TOKEN = process.env.TG_TOKEN || '';
const TG_ALLOWED_IDS = new Set((process.env.TG_ALLOWED_IDS || '').split(',').map(x => x.trim()).filter(x => /^-?\d+$/.test(x)).map(Number));
const NODE_API_URL = (process.env.NODE_API_URL || 'http://localhost:3000').replace(/\/$/, '');
const TG_PROXY = (process.env.TG_PROXY || '').trim();

let botInstance = null;

if (!TG_TOKEN) {
  console.warn('[TG] TG_TOKEN не задан — Telegram-бот не запущен.');
} else {
  let options = { polling: { interval: 500, autoStart: true } };
  if (TG_PROXY) {
    const [host, port, user, pw] = TG_PROXY.split(':');
    const auth = user ? `${encodeURIComponent(user)}:${encodeURIComponent(pw || '')}@` : '';
    const proxyUrl = `socks5h://${auth}${host}:${port}`;
    options.request = { agent: new SocksProxyAgent(proxyUrl) };
    console.log(`[TG] Прокси: ${host}:${port}${user ? ' (с авторизацией)' : ''}`);
  } else {
    console.log('[TG] Без прокси');
  }

  const bot = new TelegramBot(TG_TOKEN, options);
  botInstance = bot;
  const pending = new Map();
  const logSubs = new Map();
  const worldCache = new Map();
  const logOffsets = new Map();

  const esc = s => String(s ?? '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  const allowed = chatId => TG_ALLOWED_IDS.size === 0 || TG_ALLOWED_IDS.has(Number(chatId));
  const deny = msg => bot.sendMessage(msg.chat.id, `⛔ Доступ запрещён. Ваш ID: <code>${msg.chat.id}</code>`, { parse_mode: 'HTML' });
  const statusEmoji = s => ({online:'🟢', connecting:'🟡', error:'🔴'}[s] || '⚫');
  const dimLabel = d => ({'minecraft:overworld':'🌍 Верхний мир','minecraft:the_nether':'🔥 Незер','minecraft:the_end':'🌑 Край',overworld:'🌍 Верхний мир',nether:'🔥 Незер',end:'🌑 Край'}[d] || (d ? `📍 ${d}` : '—'));

  async function apiGet(path) {
    const r = await fetch(`${NODE_API_URL}${path}`);
    if (!r.ok) throw new Error(`${r.status} ${await r.text()}`);
    return r.json();
  }
  async function apiPost(path, json = {}) {
    const r = await fetch(`${NODE_API_URL}${path}`, { method:'POST', headers:{'content-type':'application/json'}, body:JSON.stringify(json) });
    if (!r.ok) throw new Error(`${r.status} ${await r.text()}`);
    return r.json();
  }
  const opts = { parse_mode:'HTML' };

  async function mainMenu(chatId, messageId = null) {
    let lines = '';
    try {
      const bots = await apiGet('/api/bots');
      lines = bots.map(b => `${statusEmoji(b.status)} <b>${esc(b.account?.username || b.id)}</b>`).join('\n') || '<i>Ботов нет</i>';
    } catch (e) { lines = `⚠️ Ошибка получения ботов: ${esc(e.message)}`; }
    const reply_markup = { inline_keyboard:[
      [{text:'📋 Список ботов',callback_data:'list'},{text:'🔗 Подключить всех',callback_data:'connect_all'}],
      [{text:'⏏️ Отключить всех',callback_data:'disconnect_all'},{text:'💬 Чат всем',callback_data:'chat_all'}],
      [{text:'📊 Статус всех',callback_data:'status_all'}]
    ]};
    const text = `🤖 <b>UltBot Manager</b>\n\n${lines}\n\nВыберите действие:`;
    if (messageId) return bot.editMessageText(text,{chat_id:chatId,message_id:messageId,...opts,reply_markup});
    return bot.sendMessage(chatId,text,{...opts,reply_markup});
  }

  bot.onText(/^\/(start|menu)$/, async msg => { if (!allowed(msg.chat.id)) return deny(msg); await mainMenu(msg.chat.id); });
  bot.onText(/^\/cancel$/, msg => { pending.delete(msg.chat.id); bot.sendMessage(msg.chat.id,'❌ Отменено.'); });
  bot.onText(/^\/help$/, msg => { if (!allowed(msg.chat.id)) return deny(msg); bot.sendMessage(msg.chat.id,'📖 <b>Команды:</b>\n/menu — главное меню\n/help — справка\n/cancel — отмена ввода',{parse_mode:'HTML'}); });

  bot.on('message', async msg => {
    if (!msg.text || msg.text.startsWith('/')) return;
    if (!pending.has(msg.chat.id)) return;
    if (!allowed(msg.chat.id)) return deny(msg);
    const p = pending.get(msg.chat.id); pending.delete(msg.chat.id);
    const text = msg.text.trim();
    try {
      if (p.action === 'connect') {
        const parts = text.split(':');
        const host=parts[0], port=parts[1]||'25565', version=parts[2]||'';
        await apiPost(`/api/bots/${p.botId}/connect`,{host,port,version});
        await bot.sendMessage(msg.chat.id,`🔗 Подключаю к <code>${esc(host)}:${esc(port)}</code>...`,opts);
      } else if (p.action === 'chat') {
        await apiPost(`/api/bots/${p.botId}/chat`,{text});
        await bot.sendMessage(msg.chat.id,`✅ Отправлено: <code>${esc(text)}</code>`,opts);
      } else if (p.action === 'chat_all') {
        const r=await apiPost('/api/bots/all/chat',{text});
        await bot.sendMessage(msg.chat.id,`✅ Отправлено ${r.sent||0} ботам: <code>${esc(text)}</code>`,opts);
      }
    } catch(e) { await bot.sendMessage(msg.chat.id,`⚠️ Ошибка: ${esc(e.message)}`,opts); }
  });

  async function answer(c, text='') { try { await bot.answerCallbackQuery(c.id,{text:text||undefined}); } catch {} }
  bot.on('callback_query', async c => {
    const chatId=c.message?.chat?.id, data=c.data||'';
    if (!chatId) return;
    if (!allowed(chatId)) return answer(c,'⛔ Доступ запрещён');
    try {
      if (data==='main') { await answer(c); return mainMenu(chatId,c.message.message_id); }
      if (data==='list') {
        const bots=await apiGet('/api/bots');
        if(!bots.length) return answer(c,'Ботов нет');
        const rows=bots.map(b=>[{text:`${statusEmoji(b.status)} ${b.account?.username||b.id}`,callback_data:`bot:${b.id}`}]);
        rows.push([{text:'◀️ Главное меню',callback_data:'main'}]);
        await bot.editMessageText('📋 Выберите бота:',{chat_id:chatId,message_id:c.message.message_id,reply_markup:{inline_keyboard:rows}}); return answer(c);
      }
      if (data==='status_all') {
        const bots=await apiGet('/api/bots');
        const lines=bots.map(b=>{const h=b.hud||{};return `${statusEmoji(b.status)} <b>${esc(b.account?.username||b.id)}</b>\n   ❤️ ${esc(h.health??'—')}  🍖 ${esc(h.food??'—')}  📍 ${esc(h.x??'—')} ${esc(h.y??'—')} ${esc(h.z??'—')}\n   ${esc(dimLabel(b.dimension))}`;});
        const text='📊 <b>Статус всех ботов:</b>\n\n'+(lines.join('\n\n')||'<i>Ботов нет</i>');
        const rm={inline_keyboard:[[{text:'🔁 Обновить',callback_data:'status_all'},{text:'◀️ Главное меню',callback_data:'main'}]]};
        await bot.editMessageText(text,{chat_id:chatId,message_id:c.message.message_id,...opts,reply_markup:rm}); return answer(c);
      }
      if (data==='connect_all') {
        const bots=await apiGet('/api/bots'); const server=bots.find(b=>b.server)?.server;
        if(!server) { await bot.sendMessage(chatId,'⚠️ Подключи хотя бы одного бота вручную первым.'); return answer(c); }
        await apiPost('/api/bots/all/connect',server); return answer(c,`Подключаю всех к ${server.host}:${server.port}...`);
      }
      if (data==='disconnect_all') { await apiPost('/api/bots/all/disconnect'); return answer(c,'Все боты отключены'); }
      if (data==='chat_all') { pending.set(chatId,{action:'chat_all'}); await bot.sendMessage(chatId,'💬 Введи сообщение:\n/cancel — отмена'); return answer(c); }
      if (data.startsWith('bot:')) {
        const id=data.slice(4), bots=Object.fromEntries((await apiGet('/api/bots')).map(b=>[b.id,b])); const b=bots[id];
        if(!b) return answer(c,'Бот не найден');
        const h=b.hud||{}, d=dimLabel(b.dimension); const text=b.server?`${statusEmoji(b.status)} <b>${esc(b.account?.username||id)}</b>\nСтатус: <code>${esc(b.status)}</code>\nСервер: <code>${esc(b.server.host)}:${esc(b.server.port)}</code>\n❤️ ${esc(h.health??'—')}  🍖 ${esc(h.food??'—')}  📍 ${esc(h.x??'—')} ${esc(h.y??'—')} ${esc(h.z??'—')}\nМир: ${esc(d)}`:`${statusEmoji(b.status)} <b>${esc(b.account?.username||id)}</b>\nСтатус: <code>${esc(b.status)}</code>\nМир: ${esc(d)}`;
        const rows=[];
        if(b.status==='online') { rows.push([{text:'⏏️ Отключить',callback_data:`disc:${id}`},{text:'🔄 Переподключить',callback_data:`reconn:${id}`}]); rows.push([{text:'💬 Чат',callback_data:`chat:${id}`},{text:'📦 Инвентарь',callback_data:`inv:${id}`}]); rows.push([{text:'📋 Логи',callback_data:`log:${id}`},{text:'🔔 Слежка',callback_data:`logsub:${id}`}]); rows.push([{text:'⬆️ Прыжок',callback_data:`jump:${id}`},{text:'⚔️ Атака',callback_data:`attack:${id}`},{text:'🎯 Использовать',callback_data:`use:${id}`}]); const ab=b.autoBal||{},ah=b.autoHeal||{}; rows.push([{text:ab.active?'🔴 Стоп бал':'💰 Авто-бал+инвест',callback_data:`autobal:${id}`},{text:ah.active?'🔴 Стоп хил':'💊 Авто-хил',callback_data:`autoheal:${id}`}]); }
        else rows.push([{text:'🔗 Подключить',callback_data:`conn:${id}`},{text:'🔄 Переподключить',callback_data:`reconn:${id}`}]);
        rows.push([{text:'◀️ Назад',callback_data:'list'},{text:'🔁 Обновить',callback_data:`bot:${id}`}]);
        await bot.editMessageText(text,{chat_id:chatId,message_id:c.message.message_id,...opts,reply_markup:{inline_keyboard:rows}}); return answer(c);
      }
      if (data.startsWith('conn:')) { pending.set(chatId,{action:'connect',botId:data.slice(5)}); await bot.sendMessage(chatId,'🔗 Введи сервер: <code>host:port:version</code>\n/cancel — отмена',opts); return answer(c); }
      if (data.startsWith('disc:')) { await apiPost(`/api/bots/${data.slice(5)}/disconnect`); return answer(c,'Отключен'); }
      if (data.startsWith('reconn:')) { const id=data.slice(7), b=(await apiGet('/api/bots')).find(x=>x.id===id); if(!b?.server){pending.set(chatId,{action:'connect',botId:id}); await bot.sendMessage(chatId,'🔄 Введи сервер: <code>host:port:version</code>\n/cancel — отмена',opts); return answer(c);} await apiPost(`/api/bots/${id}/disconnect`); setTimeout(()=>apiPost(`/api/bots/${id}/connect`,b.server).catch(()=>{}),1000); return answer(c,'🔄 Переподключаю...'); }
      if (data.startsWith('chat:')) { pending.set(chatId,{action:'chat',botId:data.slice(5)}); await bot.sendMessage(chatId,'💬 Введи сообщение:\n/cancel — отмена'); return answer(c); }
      if (data.startsWith('autobal:')) { const id=data.slice(8), b=(await apiGet('/api/bots')).find(x=>x.id===id), active=!(b?.autoBal?.active); await apiPost(`/api/bots/${id}/autoBal`,{active,interval:60}); return answer(c,active?'💰 Авто-бал запущен':'⏹ Авто-бал остановлен'); }
      if (data.startsWith('autoheal:')) { const id=data.slice(9), b=(await apiGet('/api/bots')).find(x=>x.id===id), active=!(b?.autoHeal?.active); await apiPost(`/api/bots/${id}/autoHeal`,{active}); return answer(c,active?'💊 Авто-хил запущен':'⏹ Авто-хил остановлен'); }
      if (data.startsWith('jump:')) { await apiPost(`/api/bots/${data.slice(5)}/jump`); return answer(c,'⬆️ Прыжок!'); }
      if (data.startsWith('attack:')) { await apiPost(`/api/bots/${data.slice(7)}/attack`); return answer(c,'⚔️ Атака!'); }
      if (data.startsWith('use:')) { await apiPost(`/api/bots/${data.slice(4)}/use`); return answer(c,'🎯 Использовано'); }
      if (data.startsWith('inv:')) { const id=data.slice(4), items=await apiGet(`/api/bots/${id}/inventory`); if(!items.length) await bot.sendMessage(chatId,'📦 Инвентарь пуст.'); else await bot.sendMessage(chatId,'📦 <b>Инвентарь:</b>\n'+items.slice(0,30).map(i=>`  [${i.slot}] <b>${esc(i.displayName||i.name)}</b> ×${i.count}`).join('\n'),opts); return answer(c); }
      if (data.startsWith('log:')) { const id=data.slice(4), b=(await apiGet('/api/bots')).find(x=>x.id===id), log=(b?.chatLog||[]).slice(-15); if(!log.length) await bot.sendMessage(chatId,'📋 Лог пуст.'); else await bot.sendMessage(chatId,`📋 <b>Лог ${esc(b.account?.username||id)}:</b>\n`+log.map(l=>`<code>${esc(String(l.msg||'').slice(0,120))}</code>`).join('\n'),opts); return answer(c); }
      if (data.startsWith('logsub:')) { const id=data.slice(7), subs=logSubs.get(chatId)||new Set(); if(subs.has(id)){subs.delete(id); await answer(c,'🔕 Отписались от логов'); await bot.sendMessage(chatId,'🔕 Слежка за логом отключена.');}else{subs.add(id);logSubs.set(chatId,subs);await answer(c,'🔔 Слежка включена');await bot.sendMessage(chatId,'🔔 Слежка за логом включена. Обновления раз в 5 сек.');} return; }
      await answer(c);
    } catch(e) { await answer(c,`Ошибка: ${e.message}`); }
  });

  async function notifyAll(text) {
    for (const chatId of TG_ALLOWED_IDS) { try { await bot.sendMessage(chatId,text,opts); } catch {} }
  }
  setInterval(async () => {
    try {
      const bots=await apiGet('/api/bots');
      for(const b of bots){
        const id=b.id, dim=b.dimension, prev=worldCache.get(id);
        if(prev!==undefined && dim && dim!==prev) await notifyAll(`${dimLabel(dim)} <b>${esc(b.account?.username||id)}</b> сменил мир\nИзмерение: <code>${esc(dim)}</code>`);
        worldCache.set(id,dim);
        const log=b.chatLog||[], offset=logOffsets.has(id)?logOffsets.get(id):log.length, fresh=log.slice(offset);
        logOffsets.set(id,log.length);
        if(fresh.length){ for(const [chatId,subs] of logSubs){if(subs.has(id)){const lines=fresh.slice(-10).map(e=>`<code>${esc(String(e.msg||'').slice(0,120))}</code>`).join('\n');try{await bot.sendMessage(chatId,`📋 <b>${esc(b.account?.username||id)}:</b>\n${lines}`,opts);}catch{}}} }
      }
    } catch(e) { console.warn('[TG] Поллер ошибка:',e.message); }
  },5000);

  bot.on('polling_error', err => console.warn('[TG] Polling error:', err.message));
  console.log('[TG] Node.js Telegram-мост запущен...');

}

export function startTelegramBot() { return botInstance; }
