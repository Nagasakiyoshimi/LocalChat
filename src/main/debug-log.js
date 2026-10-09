import { EventEmitter } from 'node:events';

const MAX_LINES = 400;
const lines = [];

export const debugLog = new EventEmitter();

export function debug(message) {
  const now = new Date();
  const stamp = now.toTimeString().slice(0, 8) + '.' + String(now.getMilliseconds()).padStart(3, '0');
  const line = `${stamp} ${message}`;
  lines.push(line);
  if (lines.length > MAX_LINES) lines.shift();
  console.log('[localchat]', line);
  debugLog.emit('line', line);
}

export function debugDump() {
  return lines.join('\n');
}

export function debugClear() {
  lines.length = 0;
  debugLog.emit('line', '');
}
