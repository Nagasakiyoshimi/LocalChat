import { EventEmitter } from 'node:events';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { RELAY_URL, RELAY_URL_SOURCE, normalizeInviteCode } from './config.js';
import { debug } from './debug-log.js';

const MAX_FILE_BYTES = 8 * 1024 * 1024;
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

export function makeInviteCode() {
  const bytes = crypto.randomBytes(8);
  let code = '';
  for (let i = 0; i < 8; i++) code += ALPHABET[bytes[i] % ALPHABET.length];
  return code;
}

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
  #pingTimer = null;
  #connectStarted = 0;
  #lastError = '';
  #offlineNotice = '';

  constructor(self) {
    super();
    this.#self = self;
  }

  counts() {
    return Object.fromEntries(this.#members);
  }

  start() {
    this.#closing = false;
    debug(`invite start ${this.#url}`);
    this.#connect();
    if (!process.env.LOCALCHAT_RELAY) {
      const started = Date.now();
      debug('invite url lookup start');
      resolveRelayUrl(this.#url).then((url) => {
        debug(`invite url lookup ${Date.now() - started}ms -> ${url}`);
        if (this.#closing || url === this.#url) return;
        this.#url = url;
        debug('invite switching url');
        this.#retry = 0;
        this.#socket?.close();
      });
    }
  }

  stop() {
    this.#closing = true;
    clearTimeout(this.#timer);
    clearInterval(this.#pingTimer);
    this.#socket?.close();
    this.#socket = null;
    this.#open = false;
    this.#ready = false;
  }

  create(name) {
    return this.register(name, makeInviteCode());
  }

  register(name, code) {
    const normalized = normalizeInviteCode(code);
    return this.#register(name, normalized);
  }

  join(code) {
    const normalized = normalizeInviteCode(code);
    this.#codes.add(normalized);
    debug(`invite join ${normalized}`);
    return this.#request({ type: 'join', code: normalized });
  }

  leave(code) {
    const normalized = normalizeInviteCode(code);
    this.#codes.delete(normalized);
    debug(`invite leave ${normalized}`);
    if (this.#ready) this.#send({ type: 'leave', code: normalized });
  }

  async sendMessage(code, envelope) {
    debug(`invite send text ${code} ${envelope.text?.length ?? 0} chars`);
    await this.#request({ type: 'message', code, envelope });
  }

  async sendFile(code, envelope, filePath) {
    const stat = await fs.promises.stat(filePath);
    if (stat.size > MAX_FILE_BYTES) throw new Error('Invite rooms can send files up to 8 MB');
    debug(`invite send file ${code} ${stat.size} bytes`);
    const data = (await fs.promises.readFile(filePath)).toString('base64');
    await this.#request({
      type: 'message',
      code,
      envelope,
      file: { name: path.basename(filePath), size: stat.size, data },
    });
  }

  async #register(name, code) {
    let attempt = 0;
    let announcedWait = false;
    while (!this.#closing) {
      if (!this.#ready) {
        if (!announcedWait) {
          debug(`register ${code} waiting for invite server`);
          announcedWait = true;
        }
        await delay(250);
        continue;
      }
      announcedWait = false;
      attempt += 1;
      const started = Date.now();
      debug(`register ${code} attempt ${attempt}`);
      try {
        const created = await this.#request({ type: 'create', name, code });
        this.#codes.add(code);
        debug(`registered ${code} in ${Date.now() - started}ms room=${created.roomId}`);
        return created;
      } catch (error) {
        debug(`register ${code} attempt ${attempt} failed after ${Date.now() - started}ms: ${error.message}`);
        if (this.#closing || /empty|invalid/i.test(error.message)) throw error;
        await delay(Math.min(500 * attempt, 5_000));
      }
    }
    throw new Error('Invite server stopped');
  }

  #connect() {
    if (this.#closing || this.#socket) return;
    let socket;
    this.#connectStarted = Date.now();
    if (!this.#offlineNotice) debug(`invite connecting ${this.#url}`);
    try {
      socket = new WebSocket(this.#url);
    } catch (error) {
      this.#reportOffline(error.message);
      this.#schedule();
      this.emit('warning', error);
      return;
    }
    this.#socket = socket;
    socket.addEventListener('open', () => {
      this.#open = true;
      this.#retry = 1000;
      this.#reportOnline();
      debug(`invite socket open in ${Date.now() - this.#connectStarted}ms`);
      const { id, name, device } = this.#self();
      this.#send({ type: 'hello', id, name, device });
      debug('invite hello sent');
      clearInterval(this.#pingTimer);
      this.#pingTimer = setInterval(() => {
        if (this.#open) this.#send({ type: 'ping' });
      }, 20_000);
    });
    socket.addEventListener('message', (event) => {
      let packet;
      try {
        packet = JSON.parse(String(event.data));
      } catch {
        debug('invite ignored non-json message');
        return;
      }
      this.#onPacket(packet);
    });
    socket.addEventListener('close', (event) => {
      const reason = this.#lastError || event?.reason || 'connection failed';
      this.#lastError = '';
      this.#socket = null;
      this.#open = false;
      this.#ready = false;
      this.#queue = [];
      clearInterval(this.#pingTimer);
      for (const [, pending] of this.#pending) pending.reject(new Error('Invite server disconnected'));
      this.#pending.clear();
      if (!this.#closing) {
        this.#reportOffline(String(reason));
        this.#schedule();
      }
    });
    socket.addEventListener('error', (event) => {
      this.#lastError = event?.error?.message || event?.message || 'connection failed';
    });
  }

  #reportOffline(reason) {
    const message = reason || 'connection failed';
    if (this.#offlineNotice === message) return;
    this.#offlineNotice = message;
    debug(`invite offline: ${message}`);
  }

  #reportOnline() {
    if (!this.#offlineNotice) return;
    this.#offlineNotice = '';
    debug(`invite online ${this.#url}`);
  }

  #schedule() {
    clearTimeout(this.#timer);
    this.#timer = setTimeout(() => {
      this.#timer = null;
      this.#connect();
    }, this.#retry);
    this.#retry = Math.min(this.#retry * 2, 15_000);
  }

  #send(packet) {
    this.#socket?.send(JSON.stringify(packet));
  }

  #request(packet) {
    const req = crypto.randomBytes(8).toString('hex');
    packet.req = req;
    const started = Date.now();
    if (this.#ready) debug(`request ${packet.type} ${packet.code ?? ''} queued`);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#pending.delete(req);
        debug(`request ${packet.type} ${packet.code ?? ''} timed out after ${Date.now() - started}ms`);
        reject(new Error('Invite server did not respond'));
      }, 20_000);
      this.#pending.set(req, {
        resolve: (value) => {
          clearTimeout(timer);
          debug(`request ${packet.type} ${packet.code ?? value.code ?? ''} ok in ${Date.now() - started}ms`);
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
      if (!this.#socket && !this.#timer) this.#connect();
      return;
    }
    for (const packet of this.#queue.splice(0)) {
      debug(`send ${packet.type} ${packet.code ?? ''}`);
      this.#send(packet);
    }
  }

  #onPacket(packet) {
    if (packet.type === 'ping') return;

    if (packet.type === 'hello' && packet.ok) {
      this.#ready = true;
      debug(`invite hello ok in ${Date.now() - this.#connectStarted}ms rejoining ${this.#codes.size} rooms`);
      for (const code of this.#codes) this.#queue.push({ type: 'join', code, req: `rejoin:${code}` });
      this.#flush();
      return;
    }

    if (packet.type === 'members') {
      this.#members.set(packet.roomId, packet.members?.length ?? 0);
      debug(`invite members ${packet.code ?? packet.roomId} ${packet.members?.length ?? 0}`);
      this.emit('members');
      return;
    }

    if (packet.type === 'message') {
      debug(`invite message in ${packet.envelope?.room?.id ?? ''} history=${Boolean(packet.history)}`);
      this.emit('message', packet);
      return;
    }

    const pending = packet.req ? this.#pending.get(packet.req) : null;
    if (packet.type === 'error') {
      debug(`invite error ${packet.error || 'Invite failed'} req=${packet.req ?? ''}`);
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

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function resolveRelayUrl(fallback) {
  try {
    const response = await fetch(`${RELAY_URL_SOURCE}?t=${Date.now()}`, {
      cache: 'no-store',
      headers: { 'cache-control': 'no-cache' },
      signal: AbortSignal.timeout(4000),
    });
    const url = (await response.text()).trim();
    if (/^wss?:\/\//i.test(url)) return url;
    debug(`invite url lookup ignored "${url.slice(0, 80)}"`);
  } catch (error) {
    debug(`invite url lookup failed: ${error.message}`);
  }
  return fallback;
}

export { normalizeInviteCode };
