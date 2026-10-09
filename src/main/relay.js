import { EventEmitter } from 'node:events';
import fs from 'node:fs';
import path from 'node:path';
import { RELAY_URL, RELAY_URL_SOURCE, normalizeInviteCode } from './config.js';

const MAX_FILE_BYTES = 8 * 1024 * 1024;

export class Relay extends EventEmitter {
  #self;
  #url = RELAY_URL;
  #socket = null;
  #open = false;
  #ready = false;
  #closing = false;
  #queue = [];
  #pending = new Map();
  #codes = new Set();
  #members = new Map();
  #retry = 1000;
  #timer = null;

  constructor(self) {
    super();
    this.#self = self;
  }

  counts() {
    return Object.fromEntries(this.#members);
  }

  async start() {
    this.#closing = false;
    if (!process.env.LOCALCHAT_RELAY) this.#url = await resolveRelayUrl(this.#url);
    this.#connect();
  }

  stop() {
    this.#closing = true;
    clearTimeout(this.#timer);
    this.#socket?.close();
    this.#socket = null;
    this.#open = false;
    this.#ready = false;
  }

  create(name) {
    return this.#request({ type: 'create', name });
  }

  join(code) {
    const normalized = normalizeInviteCode(code);
    this.#codes.add(normalized);
    return this.#request({ type: 'join', code: normalized });
  }

  leave(code) {
    const normalized = normalizeInviteCode(code);
    this.#codes.delete(normalized);
    if (this.#ready) this.#send({ type: 'leave', code: normalized });
  }

  async sendMessage(code, envelope) {
    await this.#request({ type: 'message', code, envelope });
  }

  async sendFile(code, envelope, filePath) {
    const stat = await fs.promises.stat(filePath);
    if (stat.size > MAX_FILE_BYTES) throw new Error('Invite rooms can send files up to 8 MB');
    const data = (await fs.promises.readFile(filePath)).toString('base64');
    await this.#request({
      type: 'message',
      code,
      envelope,
      file: { name: path.basename(filePath), size: stat.size, data },
    });
  }

  #connect() {
    if (this.#closing || this.#socket) return;
    let socket;
    try {
      socket = new WebSocket(this.#url);
    } catch (error) {
      this.#schedule();
      this.emit('warning', error);
      return;
    }
    this.#socket = socket;
    socket.addEventListener('open', () => {
      this.#open = true;
      this.#retry = 1000;
      const { id, name, device } = this.#self();
      this.#send({ type: 'hello', id, name, device });
    });
    socket.addEventListener('message', (event) => {
      let packet;
      try {
        packet = JSON.parse(String(event.data));
      } catch {
        return;
      }
      this.#onPacket(packet);
    });
    socket.addEventListener('close', () => {
      this.#socket = null;
      this.#open = false;
      this.#ready = false;
      for (const [, pending] of this.#pending) pending.reject(new Error('Invite server disconnected'));
      this.#pending.clear();
      if (!this.#closing) this.#schedule();
    });
    socket.addEventListener('error', () => {});
  }

  #schedule() {
    clearTimeout(this.#timer);
    this.#timer = setTimeout(() => this.#connect(), this.#retry);
    this.#retry = Math.min(this.#retry * 2, 15_000);
  }

  #send(packet) {
    this.#socket?.send(JSON.stringify(packet));
  }

  #request(packet) {
    const req = cryptoRandom();
    packet.req = req;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#pending.delete(req);
        reject(new Error('Invite server did not respond'));
      }, 20_000);
      this.#pending.set(req, {
        resolve: (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        reject: (error) => {
          clearTimeout(timer);
          reject(error);
        },
      });
      this.#queue.push(packet);
      this.#flush();
    });
  }

  #flush() {
    if (!this.#ready) {
      if (!this.#socket) this.#connect();
      return;
    }
    for (const packet of this.#queue.splice(0)) this.#send(packet);
  }

  #onPacket(packet) {
    if (packet.type === 'hello' && packet.ok) {
      this.#ready = true;
      for (const code of this.#codes) this.#queue.push({ type: 'join', code, req: `rejoin:${code}` });
      this.#flush();
      return;
    }

    if (packet.type === 'members') {
      this.#members.set(packet.roomId, packet.members?.length ?? 0);
      this.emit('members');
      return;
    }

    if (packet.type === 'message') {
      this.emit('message', packet);
      return;
    }

    const pending = packet.req ? this.#pending.get(packet.req) : null;
    if (packet.type === 'error') {
      pending?.reject(new Error(packet.error || 'Invite failed'));
      if (packet.req) this.#pending.delete(packet.req);
      if (!pending) this.emit('warning', new Error(packet.error || 'Invite failed'));
      return;
    }

    if (packet.type === 'created' || packet.type === 'joined' || packet.type === 'ack') {
      if (packet.code) this.#codes.add(packet.code);
      pending?.resolve(packet);
      if (packet.req) this.#pending.delete(packet.req);
      if (packet.roomId && packet.members) {
        this.#members.set(packet.roomId, packet.members.length);
        this.emit('members');
      }
    }
  }
}

function cryptoRandom() {
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

async function resolveRelayUrl(fallback) {
  try {
    const response = await fetch(RELAY_URL_SOURCE, { signal: AbortSignal.timeout(4000) });
    const url = (await response.text()).trim();
    if (/^wss?:\/\//i.test(url)) return url;
  } catch {
    // Use the built-in address when GitHub cannot be reached.
  }
  return fallback;
}

export { normalizeInviteCode };
