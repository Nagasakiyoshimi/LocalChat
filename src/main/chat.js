import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { Network, MAX_TEXT_LENGTH } from './network.js';

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
  }

  get #data() {
    return this.#store.data;
  }

  start() {
    return this.#network.start();
  }

  stop() {
    return this.#network.stop();
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
    return { peers, discover: [...discover.values()] };
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
    this.#data.rooms = this.#data.rooms.filter((r) => r.id !== roomId);
    delete this.#data.messages[roomId];
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

  #onIncoming(msg) {
    const room = this.#roomForIncoming(msg);
    if (!room) return;
    if (this.#data.messages[room.id]?.some((m) => m.id === msg.id)) return;

    const record = {
      id: msg.id,
      roomId: room.id,
      from: msg.from,
      ts: msg.ts,
      text: msg.text,
      incoming: true,
      ...(msg.file && { file: { ...msg.file, url: pathToFileURL(msg.file.path).href } }),
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
        .filter((r) => r.type === 'group')
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
