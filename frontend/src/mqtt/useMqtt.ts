import { useCallback, useEffect, useSyncExternalStore } from 'react'
import mqtt, { type MqttClient } from 'mqtt'

export type MqttStatus = 'connected' | 'reconnecting' | 'offline'
export type MessageHandler = (topic: string, payload: string) => void

export interface MqttIdentity {
  outletId: string
  deviceId: string
}

export interface UseMqtt {
  status: MqttStatus
  /** Subscribe to a topic. Returns an unsubscribe function. Survives reconnects. */
  subscribe: (topic: string, handler: MessageHandler) => () => void
  /** Drop the connection and stay disconnected until reconnect() (used by "Simulate offline"). */
  disconnect: () => void
  reconnect: () => void
  /** Increments on every (re)connect so callers can refetch snapshots. */
  connectCount: number
}

interface Snapshot {
  status: MqttStatus
  connectCount: number
}

// One connection per browser tab, shared by every hook instance.
let client: MqttClient | null = null
let clientIdentity: MqttIdentity | null = null
let snapshot: Snapshot = { status: 'reconnecting', connectCount: 0 }
let manuallyDisconnected = false
const listeners = new Set<() => void>()
const handlersByTopic = new Map<string, Set<MessageHandler>>()

function setSnapshot(next: Partial<Snapshot>): void {
  snapshot = { ...snapshot, ...next }
  listeners.forEach((listener) => listener())
}

function brokerUrl(): string {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${location.host}/mqtt`
}

function randomSuffix(): string {
  return Math.random().toString(36).slice(2, 6)
}

function subscribeBroker(topic: string): void {
  if (client !== null && client.connected) {
    client.subscribe(topic, { qos: 1 })
  }
}

function subscribeAllTopics(): void {
  handlersByTopic.forEach((_handlers, topic) => subscribeBroker(topic))
}

function dispatch(topic: string, payload: Uint8Array): void {
  const text = new TextDecoder().decode(payload)
  const handlers = handlersByTopic.get(topic)
  if (handlers !== undefined) {
    handlers.forEach((handler) => handler(topic, text))
  }
}

function ensureClient(identity: MqttIdentity): void {
  if (client !== null) {
    return
  }
  clientIdentity = identity
  const clientId = `tablet-${identity.outletId}-${identity.deviceId}-${randomSuffix()}`
  const created = mqtt.connect(brokerUrl(), {
    clientId,
    protocolVersion: 5,
    clean: true,
    reconnectPeriod: 1000,
    connectTimeout: 5000,
    keepalive: 30,
  })
  created.on('connect', () => {
    subscribeAllTopics()
    setSnapshot({ status: 'connected', connectCount: snapshot.connectCount + 1 })
  })
  created.on('reconnect', () => setSnapshot({ status: 'reconnecting' }))
  created.on('close', () => {
    setSnapshot({ status: manuallyDisconnected ? 'offline' : 'reconnecting' })
  })
  created.on('offline', () => setSnapshot({ status: 'offline' }))
  created.on('message', (topic, payload) => dispatch(topic, payload))
  client = created
}

function addHandler(topic: string, handler: MessageHandler): () => void {
  let handlers = handlersByTopic.get(topic)
  if (handlers === undefined) {
    handlers = new Set()
    handlersByTopic.set(topic, handlers)
    subscribeBroker(topic)
  }
  handlers.add(handler)
  return () => {
    const current = handlersByTopic.get(topic)
    if (current === undefined) {
      return
    }
    current.delete(handler)
    if (current.size === 0) {
      handlersByTopic.delete(topic)
      if (client !== null && client.connected) {
        client.unsubscribe(topic)
      }
    }
  }
}

function disconnectClient(): void {
  manuallyDisconnected = true
  if (client !== null) {
    client.end(true)
    client = null
  }
  setSnapshot({ status: 'offline' })
}

function reconnectClient(): void {
  manuallyDisconnected = false
  if (client === null && clientIdentity !== null) {
    setSnapshot({ status: 'reconnecting' })
    ensureClient(clientIdentity)
  }
}

function subscribeStore(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function getSnapshot(): Snapshot {
  return snapshot
}

export function useMqtt(identity: MqttIdentity): UseMqtt {
  const { outletId, deviceId } = identity
  const current = useSyncExternalStore(subscribeStore, getSnapshot)

  useEffect(() => {
    if (!manuallyDisconnected) {
      ensureClient({ outletId, deviceId })
    }
  }, [outletId, deviceId])

  const subscribe = useCallback(
    (topic: string, handler: MessageHandler) => addHandler(topic, handler),
    [],
  )

  return {
    status: current.status,
    connectCount: current.connectCount,
    subscribe,
    disconnect: disconnectClient,
    reconnect: reconnectClient,
  }
}
