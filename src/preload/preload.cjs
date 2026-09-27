const { contextBridge, ipcRenderer, webUtils } = require('electron');

const subscribe = (channel) => (callback) => {
  const listener = (_event, payload) => callback(payload);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.removeListener(channel, listener);
};

contextBridge.exposeInMainWorld('localchat', {
  platform: process.platform,

  getState: () => ipcRenderer.invoke('chat:state'),
  sendText: (roomId, text) => ipcRenderer.invoke('chat:send-text', roomId, text),
  sendFiles: (roomId, filePaths) => ipcRenderer.invoke('chat:send-files', roomId, filePaths),
  pickFiles: (roomId) => ipcRenderer.invoke('chat:pick-files', roomId),
  createRoom: (name) => ipcRenderer.invoke('chat:create-room', name),
  joinRoom: (roomId) => ipcRenderer.invoke('chat:join-room', roomId),
  leaveRoom: (roomId) => ipcRenderer.invoke('chat:leave-room', roomId),
  openDirect: (peerId) => ipcRenderer.invoke('chat:open-direct', peerId),
  setName: (name) => ipcRenderer.invoke('chat:set-name', name),

  openFile: (filePath) => ipcRenderer.invoke('file:open', filePath),
  showFile: (filePath) => ipcRenderer.invoke('file:show', filePath),
  openDownloads: () => ipcRenderer.invoke('file:open-downloads'),
  getPathForFile: (file) => webUtils.getPathForFile(file),
  setBadge: (count) => ipcRenderer.invoke('app:set-badge', count),

  onMe: subscribe('chat:me'),
  onRooms: subscribe('chat:rooms'),
  onPeers: subscribe('chat:peers'),
  onMessage: subscribe('chat:message'),
  onMessageUpdate: subscribe('chat:message-update'),
  onFocusRoom: subscribe('chat:focus-room'),
});
