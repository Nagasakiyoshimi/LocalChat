const api = window.localchat;
const $ = (selector) => document.querySelector(selector);

const GROUP_WINDOW_MS = 5 * 60 * 1000;
const IMAGE_EXT = /\.(png|jpe?g|gif|webp|avif|bmp|svg)$/i;
const URL_PATTERN = /(https?:\/\/[^\s<]+[^\s<.,:;"')\]!?])/g;

const ICONS = {
  plus: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>',
  globe: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/></svg>',
  hash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M5 9h14M5 15h14M10 4 8 20M16 4l-2 16"/></svg>',
  paperclip: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m21 11-8.5 8.5a5 5 0 0 1-7-7L14 4a3.5 3.5 0 0 1 5 5l-8.5 8.5a2 2 0 0 1-3-3L15 7"/></svg>',
  send: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 2 11 13M22 2l-7 20-4-9-9-4 20-7z"/></svg>',
  file: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>',
};

const state = {
  me: null,
  rooms: [],
  messages: {},
  peers: [],
  discover: [],
  current: 'lobby',
  unread: {},
};

// ---------- DOM helpers ----------

function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props ?? {})) {
    if (value == null || value === false) continue;
    if (key === 'class') el.className = value;
    else if (key === 'dataset') Object.assign(el.dataset, value);
    else if (key === 'style') for (const [prop, v] of Object.entries(value)) el.style.setProperty(prop, v);
    else if (key.startsWith('on')) el.addEventListener(key.slice(2).toLowerCase(), value);
    else el.setAttribute(key, value === true ? '' : value);
  }
  el.append(...children.flat().filter((c) => c != null && c !== false));
  return el;
}

function icon(name, className = '') {
  const span = h('span', { class: className, 'aria-hidden': 'true' });
  span.innerHTML = ICONS[name];
  return span;
}

const hueFor = (id = '') => [...id].reduce((acc, ch) => (acc * 31 + ch.charCodeAt(0)) % 360, 7);

function avatar(person, { small = false, presence = null } = {}) {
  return h(
    'span',
    {
      class: ['avatar', small && 'small', presence].filter(Boolean).join(' '),
      style: { '--hue': String(hueFor(person.id)) },
      'aria-hidden': 'true',
    },
    (person.name?.trim()[0] ?? '?').toUpperCase(),
  );
}

function linkify(text) {
  return text.split(URL_PATTERN).map((part, i) =>
    i % 2 ? h('a', { href: part, target: '_blank', rel: 'noopener noreferrer' }, part) : part,
  );
}

const timeFormat = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });
const dayFormat = new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'short', day: 'numeric' });

function dayLabel(ts) {
  const date = new Date(ts);
  const today = new Date();
  const yesterday = new Date(Date.now() - 86_400_000);
  if (date.toDateString() === today.toDateString()) return 'Today';
  if (date.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return dayFormat.format(date);
}

function formatBytes(bytes) {
  const units = ['byte', 'kilobyte', 'megabyte', 'gigabyte', 'terabyte'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return new Intl.NumberFormat(undefined, {
    style: 'unit',
    unit: units[unit],
    unitDisplay: 'short',
    maximumFractionDigits: unit ? 1 : 0,
  }).format(value);
}

let toastTimer;
function toast(message) {
  const el = $('#toast');
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (el.hidden = true), 3500);
}

const run = (promise) => Promise.resolve(promise).catch((err) => toast(err.message.replace(/^Error invoking remote method '[^']+': (Error: )?/, '')));

function prompt(title, value = '', okLabel = 'OK') {
  const dialog = $('#prompt-dialog');
  const input = $('#prompt-input');
  $('#prompt-title').textContent = title;
  $('#prompt-ok').textContent = okLabel;
  input.value = value;
  dialog.returnValue = '';
  dialog.showModal();
  input.select();
  return new Promise((resolve) => {
    dialog.addEventListener(
      'close',
      () => resolve(dialog.returnValue === 'ok' ? input.value.trim() || null : null),
      { once: true },
    );
  });
}

// ---------- Derived state ----------

const peerById = (id) => state.peers.find((p) => p.id === id);
const currentRoom = () => state.rooms.find((r) => r.id === state.current) ?? state.rooms[0];
const roomName = (room) => (room.type === 'dm' ? (peerById(room.peerId)?.name ?? room.name) : room.name);
const lastTs = (room) => state.messages[room.id]?.at(-1)?.ts ?? 0;

function roomSubtitle(room) {
  if (room.type === 'lobby') {
    const n = state.peers.length;
    return n ? `Everyone on this network · ${n} ${n === 1 ? 'device' : 'devices'} nearby` : 'Looking for devices on this network…';
  }
  if (room.type === 'dm') {
    const peer = peerById(room.peerId);
    return peer ? `Online · ${peer.device || peer.address}` : 'Offline · messages will not be delivered';
  }
  const online = state.peers.filter((p) => p.roomIds.includes(room.id)).length;
  return `${online + 1} ${online ? 'members' : 'member'} online`;
}

// ---------- Sidebar ----------

function renderMe() {
  $('#me').replaceChildren(
    avatar(state.me),
    h('span', {}, h('div', { class: 'me-name' }, state.me.name), h('div', { class: 'me-sub' }, 'Click to rename')),
  );
}

function unreadBadge(roomId) {
  const n = state.unread[roomId] ?? 0;
  return n ? h('span', { class: 'badge' }, n > 99 ? '99+' : String(n)) : null;
}

function roomButton(room, leading, sub) {
  return h(
    'li',
    {},
    h(
      'button',
      { class: `list-item ${room.id === state.current ? 'active' : ''}`, onclick: () => selectRoom(room.id) },
      leading,
      h('span', { class: 'label' }, roomName(room), sub ? h('span', { class: 'sub' }, sub) : null),
      unreadBadge(room.id),
    ),
  );
}

function renderSidebar() {
  const groups = state.rooms.filter((r) => r.type !== 'dm');
  $('#room-list').replaceChildren(
    ...groups.map((room) => roomButton(room, icon(room.type === 'lobby' ? 'globe' : 'hash', 'room-icon'))),
  );

  const dms = state.rooms.filter((r) => r.type === 'dm').sort((a, b) => lastTs(b) - lastTs(a));
  $('#dm-section').hidden = dms.length === 0;
  $('#dm-list').replaceChildren(
    ...dms.map((room) =>
      roomButton(
        room,
        avatar({ id: room.peerId, name: roomName(room) }, { small: true, presence: peerById(room.peerId) ? 'online' : 'offline' }),
      ),
    ),
  );

  $('#discover-section').hidden = state.discover.length === 0;
  $('#discover-list').replaceChildren(
    ...state.discover.map((room) =>
      h(
        'li',
        {},
        h(
          'div',
          { class: 'list-item' },
          icon('hash', 'room-icon'),
          h('span', { class: 'label' }, room.name, h('span', { class: 'sub' }, `${room.members} online`)),
          h('button', { class: 'join-button', onclick: () => joinRoom(room.id) }, 'Join'),
        ),
      ),
    ),
  );

  $('#peer-count').textContent = state.peers.length ? String(state.peers.length) : '';
  $('#peer-list').replaceChildren(
    ...(state.peers.length
      ? state.peers.map((peer) =>
          h(
            'li',
            {},
            h(
              'button',
              { class: 'list-item', title: `Message ${peer.name}`, onclick: () => openDirect(peer.id) },
              avatar(peer, { small: true, presence: 'online' }),
              h('span', { class: 'label' }, peer.name, h('span', { class: 'sub' }, peer.device || peer.address)),
            ),
          ),
        )
      : [h('li', { class: 'list-empty' }, 'No devices found yet. Open LocalChat on another device on the same Wi-Fi.')]),
  );

  const total = Object.values(state.unread).reduce((a, b) => a + b, 0);
  api.setBadge(total);
}

// ---------- Chat ----------

function renderHeader() {
  const room = currentRoom();
  $('#room-title').textContent = room.type === 'group' ? `# ${room.name}` : roomName(room);
  $('#room-subtitle').textContent = roomSubtitle(room);
  const leave = $('#leave-room');
  leave.hidden = room.type === 'lobby';
  leave.textContent = room.type === 'dm' ? 'Delete chat' : 'Leave room';
  $('#input').placeholder = `Message ${room.type === 'group' ? `#${room.name}` : roomName(room)}`;
}

function emptyState(room) {
  const [title, body] =
    room.type === 'lobby'
      ? ['Say hi to everyone nearby', 'Messages here go to every LocalChat user on this network.']
      : room.type === 'dm'
        ? [`This is the start of your chat with ${roomName(room)}`, 'Only the two of you can see these messages.']
        : [`Welcome to #${room.name}`, 'Others on this network can find and join this room from their sidebar.'];
  return h('div', { class: 'empty-state' }, h('div', {}, h('strong', {}, title), body));
}

function statusText(message) {
  switch (message.status) {
    case 'sending':
      return 'Sending…';
    case 'sent':
      return message.recipients > 1 ? `Delivered to ${message.recipients}` : 'Delivered';
    case 'partial':
      return `Delivered to ${message.delivered} of ${message.recipients}`;
    case 'failed':
      return 'Not delivered';
    case 'nobody':
      return 'No one online to receive this';
    default:
      return '';
  }
}

let pinnedToBottom = true;

function keepPinned() {
  const list = $('#messages');
  if (pinnedToBottom) list.scrollTop = list.scrollHeight;
}

function fileCard(file) {
  const actions = file.path
    ? h(
        'div',
        { class: 'file-actions' },
        h('button', { onclick: () => run(api.openFile(file.path)) }, 'Open'),
        h('button', { onclick: () => run(api.showFile(file.path)) }, 'Show in folder'),
      )
    : null;
  return h(
    'div',
    { class: 'file-card' },
    IMAGE_EXT.test(file.name) && file.url
      ? h('img', { src: file.url, alt: file.name, onload: keepPinned, onclick: () => run(api.openFile(file.path)) })
      : null,
    h(
      'div',
      { class: 'file-row' },
      icon('file', 'file-icon'),
      h('div', { class: 'file-info' }, h('div', { class: 'file-name', title: file.name }, file.name), h('div', { class: 'file-size' }, formatBytes(file.size))),
    ),
    actions,
  );
}

function messageNodes(message, prev) {
  const nodes = [];
  if (!prev || dayLabel(prev.ts) !== dayLabel(message.ts)) {
    nodes.push(h('div', { class: 'day-separator' }, dayLabel(message.ts)));
  }
  const mine = message.from.id === state.me.id;
  const grouped =
    prev && prev.from.id === message.from.id && message.ts - prev.ts < GROUP_WINDOW_MS && nodes.length === 0;

  nodes.push(
    h(
      'div',
      { class: ['message', mine && 'mine', grouped && 'grouped'].filter(Boolean).join(' '), dataset: { id: message.id } },
      avatar(message.from),
      h(
        'div',
        { class: 'body' },
        h(
          'div',
          { class: 'meta' },
          h('span', { class: 'author' }, message.from.name),
          h('time', { datetime: new Date(message.ts).toISOString() }, timeFormat.format(message.ts)),
        ),
        message.file ? fileCard(message.file) : h('div', { class: 'bubble', title: timeFormat.format(message.ts) }, linkify(message.text)),
        mine ? h('div', { class: `status ${message.status}` }, statusText(message)) : null,
      ),
    ),
  );
  return nodes;
}

function renderMessages() {
  const room = currentRoom();
  const list = $('#messages');
  const messages = state.messages[room.id] ?? [];
  if (!messages.length) {
    list.replaceChildren(emptyState(room));
    return;
  }
  list.replaceChildren(...messages.flatMap((m, i) => messageNodes(m, messages[i - 1])));
  list.scrollTop = list.scrollHeight;
  pinnedToBottom = true;
}

function appendMessage(message) {
  const list = $('#messages');
  const messages = state.messages[message.roomId];
  const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 120;
  list.querySelector('.empty-state')?.remove();
  list.append(...messageNodes(message, messages.at(-2)));
  if (nearBottom || message.from.id === state.me.id) list.scrollTop = list.scrollHeight;
}

function selectRoom(roomId) {
  if (!state.rooms.some((r) => r.id === roomId)) return;
  state.current = roomId;
  delete state.unread[roomId];
  renderSidebar();
  renderHeader();
  renderMessages();
  $('#input').focus();
}

// ---------- Actions ----------

async function joinRoom(roomId) {
  await run(api.joinRoom(roomId).then((room) => selectRoom(room.id)));
}

async function openDirect(peerId) {
  await run(api.openDirect(peerId).then((room) => selectRoom(room.id)));
}

function sendFiles(filePaths) {
  if (filePaths.length) run(api.sendFiles(state.current, filePaths));
}

function wireEvents() {
  $('#new-room').append(icon('plus'));
  $('#attach').append(icon('paperclip'));
  $('#send').append(icon('send'));

  $('#me').addEventListener('click', async () => {
    const name = await prompt('Your display name', state.me.name, 'Save');
    if (name) run(api.setName(name));
  });

  $('#new-room').addEventListener('click', async () => {
    const name = await prompt('Create a room', '', 'Create');
    if (name) run(api.createRoom(name).then((room) => selectRoom(room.id)));
  });

  $('#leave-room').addEventListener('click', () => {
    const room = currentRoom();
    const question =
      room.type === 'dm'
        ? `Delete your chat history with ${roomName(room)}?`
        : `Leave #${room.name}? Your history for this room will be deleted.`;
    if (confirm(question)) run(api.leaveRoom(room.id));
  });

  $('#open-downloads').addEventListener('click', () => run(api.openDownloads()));
  $('#attach').addEventListener('click', () => run(api.pickFiles(state.current)));

  const input = $('#input');
  $('#composer').addEventListener('submit', (event) => {
    event.preventDefault();
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    run(api.sendText(state.current, text));
  });
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      $('#composer').requestSubmit();
    }
  });

  const messages = $('#messages');
  messages.addEventListener('scroll', () => {
    pinnedToBottom = messages.scrollHeight - messages.scrollTop - messages.clientHeight < 120;
  });

  const chat = $('#chat');
  const overlay = $('#drop-overlay');
  let dragDepth = 0;
  const hasFiles = (event) => event.dataTransfer?.types.includes('Files');
  chat.addEventListener('dragenter', (event) => {
    if (!hasFiles(event)) return;
    dragDepth++;
    overlay.hidden = false;
  });
  chat.addEventListener('dragleave', () => {
    if (--dragDepth <= 0) {
      dragDepth = 0;
      overlay.hidden = true;
    }
  });
  chat.addEventListener('dragover', (event) => {
    if (hasFiles(event)) event.preventDefault();
  });
  chat.addEventListener('drop', (event) => {
    event.preventDefault();
    dragDepth = 0;
    overlay.hidden = true;
    sendFiles([...event.dataTransfer.files].map((file) => api.getPathForFile(file)).filter(Boolean));
  });
  window.addEventListener('dragover', (event) => event.preventDefault());
  window.addEventListener('drop', (event) => event.preventDefault());

  window.addEventListener('focus', () => {
    if (state.unread[state.current]) {
      delete state.unread[state.current];
      renderSidebar();
    }
  });
}

function subscribe() {
  api.onMe((me) => {
    state.me = me;
    renderMe();
  });

  api.onRooms((rooms) => {
    state.rooms = rooms;
    for (const id of Object.keys(state.messages)) {
      if (!rooms.some((r) => r.id === id)) delete state.messages[id];
    }
    if (!rooms.some((r) => r.id === state.current)) selectRoom('lobby');
    else {
      renderSidebar();
      renderHeader();
    }
  });

  api.onPeers(({ peers, discover }) => {
    state.peers = peers;
    state.discover = discover;
    renderSidebar();
    renderHeader();
  });

  api.onMessage((message) => {
    const list = (state.messages[message.roomId] ??= []);
    if (list.some((m) => m.id === message.id)) return;
    list.push(message);
    const isCurrent = message.roomId === state.current;
    if (isCurrent) appendMessage(message);
    if (message.incoming && (!isCurrent || !document.hasFocus())) {
      state.unread[message.roomId] = (state.unread[message.roomId] ?? 0) + 1;
    }
    renderSidebar();
  });

  api.onMessageUpdate((message) => {
    const list = state.messages[message.roomId];
    const index = list?.findIndex((m) => m.id === message.id) ?? -1;
    if (index >= 0) list[index] = message;
    const status = document.querySelector(`.message[data-id="${CSS.escape(message.id)}"] .status`);
    if (status) {
      status.className = `status ${message.status}`;
      status.textContent = statusText(message);
    }
  });

  api.onFocusRoom((roomId) => selectRoom(roomId));
}

async function init() {
  document.body.classList.add(`platform-${api.platform}`);
  Object.assign(state, await api.getState());
  wireEvents();
  subscribe();
  renderMe();
  selectRoom('lobby');
}

init();
