import dgram from 'node:dgram';
import http from 'node:http';
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

export const PROTOCOL = 'localchat/1';
const MULTICAST_GROUP = '224.0.0.168';
const DISCOVERY_PORT = 53318;
const ANNOUNCE_INTERVAL_MS = 3_000;
const SWEEP_INTERVAL_MS = 2_000;
const PEER_TTL_MS = 10_000;
const MESSAGE_TIMEOUT_MS = 5_000;
const MAX_JSON_BYTES = 256 * 1024;
export const MAX_TEXT_LENGTH = 10_000;
const ROOM_TYPES = new Set(['lobby', 'group', 'dm']);

const localInterfaces = () =>
  Object.values(os.networkInterfaces())
    .flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal);

const broadcastAddress = ({ address, netmask }) => {
  const mask = netmask.split('.').map(Number);
  return address
    .split('.')
    .map((octet, i) => octet | (~mask[i] & 255))
    .join('.');
};

const normalizeAddress = (address = '') => address.replace(/^::ffff:/, '');

const isString = (value, max) => typeof value === 'string' && value.length > 0 && value.length <= max;

const safeFileName = (name) =>
  path
    .basename(name)
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_')
    .replace(/^\.+/, '')
    .slice(0, 200) || 'file';

function sendJson(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) });
  res.end(data);
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_JSON_BYTES) throw new Error('Payload too large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

function parseEnvelope(body) {
  const { id, from, room, ts, text = '' } = body ?? {};
  if (!isString(id, 100)) throw new Error('Invalid message id');
  if (!isString(from?.id, 100) || !isString(from?.name, 100)) throw new Error('Invalid sender');
  if (!isString(room?.id, 200) || !ROOM_TYPES.has(room?.type) || typeof room?.name !== 'string') {
    throw new Error('Invalid room');
  }
  if (typeof text !== 'string' || text.length > MAX_TEXT_LENGTH) throw new Error('Invalid text');
  return {
    id,
    from: { id: from.id, name: from.name },
    room: { id: room.id, type: room.type, name: room.name.slice(0, 100) },
    ts: Number.isFinite(ts) ? ts : Date.now(),
    text,
  };
}

/**
 * Peer-to-peer transport: UDP multicast/broadcast for discovery and a small
 * HTTP server for message and file delivery.
 *
 * Events: `peers` (peer list changed), `message` (validated incoming message), `warning`.
 */
export class Network extends EventEmitter {
  #self;
  #downloadDir;
  #socket = null;
  #server = null;
  #port = 0;
  #peers = new Map();
  #joinedInterfaces = new Set();
  #timers = [];

  constructor({ self, downloadDir }) {
    super();
    this.#self = self;
    this.#downloadDir = downloadDir;
  }

  get peers() {
    return [...this.#peers.values()];
  }

  async start() {
    await this.#startServer();
    await this.#startDiscovery();
    this.announce();
    this.#timers.push(
      setInterval(() => this.announce(), ANNOUNCE_INTERVAL_MS),
      setInterval(() => this.#sweep(), SWEEP_INTERVAL_MS),
    );
  }

  async stop() {
    this.#timers.forEach(clearInterval);
    this.#timers = [];
    if (this.#socket) {
      this.#send({ protocol: PROTOCOL, type: 'bye', id: this.#self().id });
      await new Promise((resolve) => setTimeout(resolve, 150));
      this.#socket.close();
      this.#socket = null;
    }
    this.#server?.closeAllConnections();
    this.#server?.close();
    this.#server = null;
  }

  announce(target) {
    if (!this.#socket) return;
    this.#joinMulticast();
    const { id, name, device, rooms } = this.#self();
    this.#send({ protocol: PROTOCOL, type: 'announce', id, name, device, port: this.#port, rooms }, target);
  }

  async sendMessage(peer, envelope) {
    const res = await fetch(this.#url(peer, '/api/message'), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(envelope),
      signal: AbortSignal.timeout(MESSAGE_TIMEOUT_MS),
    });
    await res.text();
    if (!res.ok) throw new Error(`Peer responded with ${res.status}`);
  }

  async sendFile(peer, envelope, filePath, size) {
    const meta = { ...envelope, file: { name: path.basename(filePath), size } };
    const res = await fetch(this.#url(peer, '/api/file'), {
      method: 'POST',
      headers: {
        'content-type': 'application/octet-stream',
        'x-localchat-meta': encodeURIComponent(JSON.stringify(meta)),
      },
      body: Readable.toWeb(fs.createReadStream(filePath)),
      duplex: 'half',
    });
    await res.text();
    if (!res.ok) throw new Error(`Peer responded with ${res.status}`);
  }

  #url(peer, pathname) {
    return `http://${peer.address}:${peer.port}${pathname}`;
  }

  // ---------- HTTP server ----------

  #startServer() {
    return new Promise((resolve, reject) => {
      this.#server = http.createServer((req, res) => {
        this.#handle(req, res).catch((err) => {
          if (res.headersSent) res.destroy();
          else sendJson(res, 400, { error: err.message });
        });
      });
      this.#server.once('error', reject);
      this.#server.listen(0, () => {
        this.#port = this.#server.address().port;
        resolve();
      });
    });
  }

  async #handle(req, res) {
    const { pathname } = new URL(req.url, 'http://localhost');

    if (req.method === 'GET' && pathname === '/api/info') {
      const { id, name, device } = this.#self();
      return sendJson(res, 200, { protocol: PROTOCOL, id, name, device, port: this.#port });
    }

    if (req.method === 'POST' && pathname === '/api/message') {
      const message = parseEnvelope(await readJson(req));
      this.#touch(message.from.id, req);
      this.emit('message', message);
      return sendJson(res, 200, { ok: true });
    }

    if (req.method === 'POST' && pathname === '/api/file') {
      const raw = req.headers['x-localchat-meta'];
      const meta = JSON.parse(decodeURIComponent(raw ?? ''));
      const message = parseEnvelope(meta);
      const size = Number(meta.file?.size);
      if (!isString(meta.file?.name, 1000) || !Number.isSafeInteger(size) || size < 0) {
        throw new Error('Invalid file metadata');
      }
      this.#touch(message.from.id, req);
      const filePath = await this.#receiveFile(req, safeFileName(meta.file.name), size);
      this.emit('message', { ...message, file: { name: path.basename(filePath), size, path: filePath } });
      return sendJson(res, 200, { ok: true });
    }

    sendJson(res, 404, { error: 'Not found' });
  }

  async #receiveFile(req, name, expectedSize) {
    await fs.promises.mkdir(this.#downloadDir, { recursive: true });
    const { name: base, ext } = path.parse(name);
    let handle;
    let filePath;
    for (let i = 0; !handle; i++) {
      filePath = path.join(this.#downloadDir, i ? `${base} (${i})${ext}` : name);
      try {
        handle = await fs.promises.open(filePath, 'wx');
      } catch (err) {
        if (err.code !== 'EEXIST') throw err;
      }
    }

    let received = 0;
    try {
      await pipeline(
        req,
        async function* (source) {
          for await (const chunk of source) {
            received += chunk.length;
            if (received > expectedSize) throw new Error('File is larger than announced');
            yield chunk;
          }
        },
        handle.createWriteStream(),
      );
      if (received !== expectedSize) throw new Error('File transfer incomplete');
      return filePath;
    } catch (err) {
      await fs.promises.rm(filePath, { force: true });
      throw err;
    }
  }

  #touch(peerId, req) {
    const peer = this.#peers.get(peerId);
    if (!peer) return;
    peer.lastSeen = Date.now();
    peer.address = normalizeAddress(req.socket.remoteAddress) || peer.address;
  }

  // ---------- Discovery ----------

  #startDiscovery() {
    return new Promise((resolve, reject) => {
      const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
      socket.once('error', reject);
      socket.on('message', (buf, rinfo) => this.#onPacket(buf, rinfo));
      socket.bind(DISCOVERY_PORT, () => {
        socket.off('error', reject);
        socket.on('error', (err) => this.emit('warning', err));
        socket.setBroadcast(true);
        socket.setMulticastTTL(1);
        socket.setMulticastLoopback(true);
        this.#socket = socket;
        this.#joinMulticast();
        resolve();
      });
    });
  }

  #joinMulticast() {
    for (const { address } of localInterfaces()) {
      if (this.#joinedInterfaces.has(address)) continue;
      try {
        this.#socket.addMembership(MULTICAST_GROUP, address);
        this.#joinedInterfaces.add(address);
      } catch {
        // Interface may not support multicast; broadcast still covers it.
      }
    }
  }

  #send(packet, target) {
    const buf = Buffer.from(JSON.stringify(packet));
    const targets = target
      ? [target]
      : [MULTICAST_GROUP, ...new Set(localInterfaces().map(broadcastAddress))];
    for (const address of targets) {
      this.#socket.send(buf, DISCOVERY_PORT, address, () => {});
    }
  }

  #onPacket(buf, rinfo) {
    let packet;
    try {
      packet = JSON.parse(buf.toString('utf8'));
    } catch {
      return;
    }
    if (packet?.protocol !== PROTOCOL || !isString(packet.id, 100) || packet.id === this.#self().id) return;

    if (packet.type === 'bye') {
      if (this.#peers.delete(packet.id)) this.emit('peers');
      return;
    }

    const { port } = packet;
    if (packet.type !== 'announce' || !Number.isInteger(port) || port <= 0 || port > 65_535) return;

    const rooms = Array.isArray(packet.rooms)
      ? packet.rooms
          .filter((r) => isString(r?.id, 200) && typeof r?.name === 'string')
          .slice(0, 100)
          .map((r) => ({ id: r.id, name: r.name.slice(0, 100) }))
      : [];

    const prev = this.#peers.get(packet.id);
    const peer = {
      id: packet.id,
      name: isString(packet.name, 100) ? packet.name : 'Unknown',
      device: typeof packet.device === 'string' ? packet.device.slice(0, 100) : '',
      address: rinfo.address,
      port,
      rooms,
      lastSeen: Date.now(),
    };
    this.#peers.set(peer.id, peer);

    if (!prev) this.announce(rinfo.address);

    const changed =
      !prev ||
      prev.name !== peer.name ||
      prev.address !== peer.address ||
      prev.port !== peer.port ||
      JSON.stringify(prev.rooms) !== JSON.stringify(peer.rooms);
    if (changed) this.emit('peers');
  }

  #sweep() {
    const cutoff = Date.now() - PEER_TTL_MS;
    let changed = false;
    for (const [id, peer] of this.#peers) {
      if (peer.lastSeen < cutoff) {
        this.#peers.delete(id);
        changed = true;
      }
    }
    if (changed) this.emit('peers');
  }
}
