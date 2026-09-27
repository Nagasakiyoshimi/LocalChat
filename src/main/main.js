import { app, BrowserWindow, ipcMain, dialog, shell, Notification, nativeTheme } from 'electron';
import path from 'node:path';
import os from 'node:os';
import { randomUUID } from 'node:crypto';
import { Store } from './store.js';
import { Chat } from './chat.js';

// LOCALCHAT_PROFILE lets several instances run side by side on one machine (useful for testing).
const profile = process.env.LOCALCHAT_PROFILE;
if (profile) app.setPath('userData', path.join(app.getPath('appData'), `LocalChat-${profile}`));

app.setName('LocalChat');
if (process.platform === 'win32') app.setAppUserModelId('com.localchat.app');

const downloadDir = path.join(app.getPath('downloads'), profile ? `LocalChat (${profile})` : 'LocalChat');

let win = null;
let store;
let chat;

const send = (channel, payload) => win?.webContents.send(channel, payload);

function createWindow() {
  win = new BrowserWindow({
    width: 1100,
    height: 720,
    minWidth: 760,
    minHeight: 480,
    title: profile ? `LocalChat (${profile})` : 'LocalChat',
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#111318' : '#f6f7f9',
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'default',
    webPreferences: {
      preload: path.join(import.meta.dirname, '../preload/preload.cjs'),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
    },
  });

  win.loadFile(path.join(import.meta.dirname, '../renderer/index.html'));
  win.on('closed', () => {
    win = null;
  });
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (event) => event.preventDefault());
}

function showWindow() {
  if (!win) createWindow();
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function notify(message, room) {
  if (win?.isFocused() || !Notification.isSupported()) return;
  const title =
    room.type === 'dm' ? message.from.name : `${message.from.name} in ${room.type === 'lobby' ? 'Everyone' : room.name}`;
  const body = message.file ? `Sent a file: ${message.file.name}` : message.text.slice(0, 200);
  const notification = new Notification({ title, body });
  notification.on('click', () => {
    showWindow();
    send('chat:focus-room', room.id);
  });
  notification.show();
}

function registerIpc() {
  const handlers = {
    'chat:state': () => chat.state(),
    'chat:send-text': (roomId, text) => chat.sendText(roomId, text),
    'chat:send-files': (roomId, filePaths) => chat.sendFiles(roomId, filePaths),
    'chat:pick-files': async (roomId) => {
      const result = await dialog.showOpenDialog(win, {
        title: 'Send files',
        properties: ['openFile', 'multiSelections'],
      });
      if (!result.canceled) await chat.sendFiles(roomId, result.filePaths);
    },
    'chat:create-room': (name) => chat.createRoom(name),
    'chat:join-room': (roomId) => chat.joinRoom(roomId),
    'chat:leave-room': (roomId) => chat.leaveRoom(roomId),
    'chat:open-direct': (peerId) => chat.openDirect(peerId),
    'chat:set-name': (name) => chat.setName(name),
    'file:open': (filePath) => shell.openPath(filePath),
    'file:show': (filePath) => shell.showItemInFolder(filePath),
    'file:open-downloads': () => shell.openPath(downloadDir),
    'app:set-badge': (count) => app.setBadgeCount(Math.max(0, Number(count) || 0)),
  };
  for (const [channel, handler] of Object.entries(handlers)) {
    ipcMain.handle(channel, (_event, ...args) => handler(...args));
  }
}

app.whenReady().then(async () => {
  store = new Store(path.join(app.getPath('userData'), 'localchat.json'), () => ({
    me: { id: randomUUID(), name: os.userInfo().username || os.hostname() },
    rooms: [],
    messages: {},
  }));
  chat = new Chat({ store, downloadDir });

  chat.on('me', (me) => send('chat:me', me));
  chat.on('rooms', (rooms) => send('chat:rooms', rooms));
  chat.on('peers', (peers) => send('chat:peers', peers));
  chat.on('message', (message, room) => {
    send('chat:message', message);
    if (message.incoming) notify(message, room);
  });
  chat.on('message-update', (message) => send('chat:message-update', message));

  registerIpc();
  createWindow();

  try {
    await chat.start();
  } catch (err) {
    dialog.showErrorBox(
      'LocalChat could not start networking',
      `${err.message}\n\nAnother app may be using UDP port 53318, or your firewall is blocking LocalChat.`,
    );
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

let quitting = false;
app.on('before-quit', async (event) => {
  if (quitting || !chat) return;
  event.preventDefault();
  quitting = true;
  try {
    await chat.stop();
  } finally {
    store.flush();
    app.quit();
  }
});
