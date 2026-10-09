import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { Network, MAX_TEXT_LENGTH } from './network.js';
import { Relay, makeInviteCode, normalizeInviteCode } from './relay.js';
import { debug } from './debug-log.js';

const LOBBY = { id: 'lobby', type: 'lobby', name: 'Everyone' };
const MAX_MESSAGES_PER_ROOM = 2_000;
const MAX_NAME_LENGTH = 40;

const dmRoomId = (a, b) => `dm:${[a, b].sort().join(':')}`;

const cleanName = (name) => {
  const value = String(name ?? '').trim().slice(0, MAX_NAME_LENGTH);
  if (!value) throw new Error('Name cannot be empty');
  return value;
};

/**
 * Chat domain logic on top of the network layer: rooms, history and delivery.
 *
 * Events: `me`, `rooms`, `peers`, `message` (record, room), `message-update` (record).
 */
export class Chat extends EventEmitter {
  #store;
  #network;
  #relay;
  #downloadDir;

  constructor({ store, downloadDir }) {
    super();
    this.#store = store;
    this.#downloadDir = downloadDir;

    const { data } = store;
    if (!data.rooms.some((r) => r.id === LOBBY.id)) data.rooms.unshift({ ...LOBBY });
    for (const list of Object.values(data.messages)) {
      for (const m of list) if (m.status === 'sending') m.status = 'failed';
    }

    this.#network = new Network({ self: () => this.#selfInfo(), downloadDir });
    this.#network.on('peers', () => this.emit('peers', this.peersState()));
    this.#network.on('message', (msg) => this.#onIncoming(msg));
    this.#network.on('warning', (err) => console.warn('[network]', err.message));

    this.#relay = new Relay(() => {
      const { id, name } = this.#data.me;
      return { id, name, device: os.hostname() };
    });
    this.#relay.on('members', () => this.emit('peers', this.peersState()));
    this.#relay.on('message', (packet) => this.#onRelayMessage(packet));
    this.#relay.on('warning', (err) => debug(`invite warning: ${err.message}`));
  }

  get #data() {
    return this.#store.data;
  }

  async start() {
    await this.#network.start();
    this.#relay.start();
    for (const room of this.#data.rooms) {
      if (room.via !== 'invite' || !room.code) continue;
      const pending = room.inviteState === 'pending' || room.inviteState === 'failed';
      const task = pending ? this.#relay.register(room.name, room.code) : this.#relay.join(room.code);
      task
        .then(() => this.#markInviteReady(room))
        .catch((err) => debug(`invite ${room.code}: ${err.message}`));
    }
  }

  async stop() {
    this.#relay.stop();
    await this.#network.stop();
  }

  state() {
    const { me, rooms, messages } = this.#data;
    return { me, rooms, messages, downloadDir: this.#downloadDir, ...this.peersState() };
  }

  peersState() {
    const peers = this.#network.peers.map(({ id, name, device, address, rooms }) => ({
      id,
      name,
      device,
      address,
      roomIds: rooms.map((r) => r.id),
    }));
    const joined = new Set(this.#data.rooms.map((r) => r.id));
    const discover = new Map();
    for (const peer of this.#network.peers) {
      for (const room of peer.rooms) {
        if (joined.has(room.id)) continue;
        const entry = discover.get(room.id) ?? { id: room.id, name: room.name, members: 0 };
        entry.members++;
        discover.set(room.id, entry);
      }
    }
    return { peers, discover: [...discover.values()], inviteMembers: this.#relay?.counts() ?? {} };
  }

  // ---------- Profile & rooms ----------

  setName(name) {
    this.#data.me.name = cleanName(name);
    this.#changed({ me: true });
    return this.#data.me;
  }

  createRoom(name) {
    const room = { id: randomUUID(), type: 'group', name: cleanName(name) };
    this.#data.rooms.push(room);
    this.#changed({ rooms: true });
    return room;
  }

  createInvite(name) {
    const clean = cleanName(name);
    const code = makeInviteCode();
    const room = this.#addRoom({
      id: `invite:${code}`,
      type: 'group',
      name: clean,
      code,
      via: 'invite',
      inviteState: 'pending',
    });
    debug(`create room "${clean}" ${code} shown locally`);
    this.#relay
      .register(clean, code)
      .then((created) => {
        if (created.roomId && created.roomId !== room.id) {
          room.id = created.roomId;
          room.code = created.code;
        }
        if (created.name) room.name = created.name;
        this.#markInviteReady(room);
        debug(`create room ${room.code} registered`);
      })
      .catch((err) => {
        room.inviteState = 'failed';
        this.#store.save();
        this.emit('rooms', this.#data.rooms);
        debug(`create room ${code} failed: ${err.message}`);
      });
    return room;
  }

  async joinInvite(code) {
    const normalized = normalizeInviteCode(code);
    const existing = this.#data.rooms.find((room) => room.code === normalized);
    if (existing) {
      await this.#relay.join(normalized);
      return existing;
    }
    const joined = await this.#relay.join(normalized);
    return (
      this.#findRoom(joined.roomId) ??
      this.#addRoom({ id: joined.roomId, type: 'group', name: joined.name, code: joined.code, via: 'invite' })
    );
  }

  joinRoom(roomId) {
    const existing = this.#findRoom(roomId);
    if (existing) return existing;
    const found = this.#network.peers.flatMap((p) => p.rooms).find((r) => r.id === roomId);
    if (!found) throw new Error('Room is no longer available');
    const room = { id: found.id, type: 'group', name: found.name };
    this.#data.rooms.push(room);
    this.#changed({ rooms: true, peers: true });
    return room;
  }

  leaveRoom(roomId) {
    if (roomId === LOBBY.id) throw new Error('You cannot leave the Everyone room');
    const room = this.#findRoom(roomId);
    this.#data.rooms = this.#data.rooms.filter((r) => r.id !== roomId);
    if (room?.via === 'invite' && room.code) this.#relay.leave(room.code);
    if (room?.type === 'dm') delete this.#data.messages[roomId];
    this.#changed({ rooms: true, peers: true });
  }

  openDirect(peerId) {
    const id = dmRoomId(this.#data.me.id, peerId);
    const existing = this.#findRoom(id);
    if (existing) return existing;
    const peer = this.#network.peers.find((p) => p.id === peerId);
    if (!peer) throw new Error('That device is no longer online');
    return this.#addRoom({ id, type: 'dm', name: peer.name, peerId });
  }

  // ---------- Sending ----------

  async sendText(roomId, text) {
    const value = String(text ?? '').trim();
    if (!value) return;
    if (value.length > MAX_TEXT_LENGTH) throw new Error(`Messages are limited to ${MAX_TEXT_LENGTH} characters`);
    const room = this.#requireRoom(roomId);
    const record = this.#newRecord(room, { text: value });
    await this.#deliver(record, room, (peer, envelope) => this.#network.sendMessage(peer, envelope));
  }

  async sendFiles(roomId, filePaths) {
    const room = this.#requireRoom(roomId);
    await Promise.all(
      filePaths.map(async (filePath) => {
        const stat = await fs.promises.stat(filePath);
        if (!stat.isFile()) return;
        const record = this.#newRecord(room, {
          file: {
            name: path.basename(filePath),
            size: stat.size,
            path: filePath,
            url: pathToFileURL(filePath).href,
          },
        });
        await this.#deliver(record, room, (peer, envelope) =>
          this.#network.sendFile(peer, envelope, filePath, stat.size),
        );
      }),
    );
  }

  async #deliver(record, room, send) {
    this.#append(record);
    this.emit('message', record, room);

    if (room.via === 'invite') {
      const envelope = {
        id: record.id,
        from: record.from,
        ts: record.ts,
        text: record.text,
        room: { id: room.id, type: 'group', name: room.name },
      };
      try {
        if (record.file?.path) await this.#relay.sendFile(room.code, envelope, record.file.path);
        else await this.#relay.sendMessage(room.code, envelope);
        const others = Math.max(0, (this.#relay.counts()[room.id] ?? 1) - 1);
        record.recipients = others;
        record.delivered = others;
        record.status = 'sent';
      } catch (err) {
        record.status = 'failed';
        record.error = err.message;
      }
      this.#store.save();
      this.emit('message-update', record);
      return;
    }

    const targets = this.#targets(room);
    const envelope = {
      id: record.id,
      from: record.from,
      ts: record.ts,
      text: record.text,
      room: { id: room.id, type: room.type, name: room.type === 'group' ? room.name : '' },
    };
    const results = await Promise.allSettled(targets.map((peer) => send(peer, envelope)));
    const delivered = results.filter((r) => r.status === 'fulfilled').length;

    record.recipients = targets.length;
    record.delivered = delivered;
    record.status =
      targets.length === 0
        ? 'nobody'
        : delivered === targets.length
          ? 'sent'
          : delivered === 0
            ? 'failed'
            : 'partial';
    this.#store.save();
    this.emit('message-update', record);
  }

  #targets(room) {
    const peers = this.#network.peers;
    switch (room.type) {
      case 'lobby':
        return peers;
      case 'dm':
        return peers.filter((p) => p.id === room.peerId);
      default:
        return peers.filter((p) => p.rooms.some((r) => r.id === room.id));
    }
  }

  // ---------- Receiving ----------

  async #onRelayMessage(packet) {
    const envelope = packet.envelope;
    if (!envelope?.room?.id) return;
    let file = packet.file ? { name: packet.file.name, size: Number(packet.file.size) || 0 } : undefined;
    if (packet.file?.data) {
      file = await this.#saveIncomingFile(packet.file.name, packet.file.data);
    }
    this.#onIncoming({ ...envelope, file }, { history: Boolean(packet.history) });
  }

  async #saveIncomingFile(name, base64) {
    await fs.promises.mkdir(this.#downloadDir, { recursive: true });
    const safe = path.basename(String(name || 'file')).replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_').slice(0, 200) || 'file';
    const parsed = path.parse(safe);
    let filePath;
    for (let i = 0; ; i++) {
      filePath = path.join(this.#downloadDir, i ? `${parsed.name} (${i})${parsed.ext}` : safe);
      try {
        const handle = await fs.promises.open(filePath, 'wx');
        await handle.writeFile(Buffer.from(base64, 'base64'));
        await handle.close();
        break;
      } catch (err) {
        if (err.code !== 'EEXIST') throw err;
      }
    }
    const stat = await fs.promises.stat(filePath);
    return { name: path.basename(filePath), size: stat.size, path: filePath, url: pathToFileURL(filePath).href };
  }

  #onIncoming(msg, { history = false } = {}) {
    const room = history ? this.#findRoom(msg.room?.id) : this.#roomForIncoming(msg);
    if (!room) return;
    if (this.#data.messages[room.id]?.some((m) => m.id === msg.id)) return;

    const mine = msg.from?.id === this.#data.me.id;
    const record = {
      id: msg.id,
      roomId: room.id,
      from: msg.from,
      ts: msg.ts,
      text: msg.text,
      incoming: !mine,
      quiet: history,
      ...(mine && { status: 'sent' }),
      ...(msg.file && { file: { ...msg.file, ...(msg.file.path ? { url: pathToFileURL(msg.file.path).href } : {}) } }),
    };
    this.#append(record);
    this.emit('message', record, room);
  }

  #roomForIncoming({ room, from }) {
    if (room.type === 'lobby') return this.#findRoom(LOBBY.id);
    if (room.type === 'dm') {
      const id = dmRoomId(this.#data.me.id, from.id);
      if (room.id !== id) return null;
      const existing = this.#findRoom(id);
      if (existing && existing.name !== from.name) {
        existing.name = from.name;
        this.#changed({ rooms: true, announce: false });
      }
      return existing ?? this.#addRoom({ id, type: 'dm', name: from.name, peerId: from.id });
    }
    return this.#findRoom(room.id) ?? null;
  }

  // ---------- Helpers ----------

  #selfInfo() {
    const { me, rooms } = this.#data;
    return {
      id: me.id,
      name: me.name,
      device: os.hostname(),
      rooms: rooms
        .filter((r) => r.type === 'group' && r.via !== 'invite')
        .slice(0, 50)
        .map(({ id, name }) => ({ id, name })),
    };
  }

  #newRecord(room, extra) {
    const { id, name } = this.#data.me;
    return { id: randomUUID(), roomId: room.id, from: { id, name }, ts: Date.now(), text: '', status: 'sending', ...extra };
  }

  #append(record) {
    const list = (this.#data.messages[record.roomId] ??= []);
    list.push(record);
    if (list.length > MAX_MESSAGES_PER_ROOM) list.splice(0, list.length - MAX_MESSAGES_PER_ROOM);
    this.#store.save();
  }

  #findRoom(id) {
    return this.#data.rooms.find((r) => r.id === id);
  }

  #requireRoom(id) {
    const room = this.#findRoom(id);
    if (!room) throw new Error('Room not found');
    return room;
  }

  #markInviteReady(room) {
    if (room.inviteState === 'ready') return;
    room.inviteState = 'ready';
    this.#store.save();
    this.emit('rooms', this.#data.rooms);
  }

  #addRoom(room) {
    this.#data.rooms.push(room);
    this.#changed({ rooms: true, announce: false });
    return room;
  }

  #changed({ me = false, rooms = false, peers = false, announce = true }) {
    this.#store.save();
    if (announce) this.#network.announce();
    if (me) this.emit('me', this.#data.me);
    if (rooms) this.emit('rooms', this.#data.rooms);
    if (peers) this.emit('peers', this.peersState());
  }
}
