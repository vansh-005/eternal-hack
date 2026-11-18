import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { useOutletOrders } from '../../api/queries'
import { StatusDot } from '../../components/StatusDot'
import { formatClock, useNow } from '../../lib/time'
import { playBeep, useAudioUnlocked } from '../../lib/sound'
import { useMqtt } from '../../mqtt/useMqtt'
import { CancelCard } from './CancelCard'
import { OrderQueue } from './OrderQueue'
import { TakeoverOverlay } from './TakeoverOverlay'
import { isTakeover, useTabletCases } from './useTabletCases'

const OFFLINE_MS = 20_000

export default function TabletPage() {
  const [params] = useSearchParams()
  const outletId = params.get('outlet') ?? 'outlet-demo'
  const deviceId = params.get('device') ?? 'tab-1'
  const mqtt = useMqtt({ outletId, deviceId })
  const audioUnlocked = useAudioUnlocked()
  const clock = useNow(1000)
  const orders = useOutletOrders(outletId)
  const { cases, closeLocally } = useTabletCases({
    outletId,
    deviceId,
    mqtt,
    onAlert: () => {
      playBeep()
    },
    // F-02 plugs the call screen in here (CallMessage on the calls topic).
    onCallMessage: undefined,
  })

  const [offlineUntil, setOfflineUntil] = useState<number | null>(null)
  const offlineTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const { disconnect, reconnect } = mqtt

  const goOffline = () => {
    disconnect()
    setOfflineUntil(Date.now() + OFFLINE_MS)
    offlineTimer.current = setTimeout(() => {
      reconnect()
      setOfflineUntil(null)
    }, OFFLINE_MS)
  }

  useEffect(
    () => () => {
      if (offlineTimer.current !== null) {
        clearTimeout(offlineTimer.current)
        reconnect()
      }
    },
    [reconnect],
  )

  const activeOrders = orders.data ?? []
  const open = cases.filter((entry) => entry.closing === null)
  const takeovers = open.filter(isTakeover)
  const firstTakeover = takeovers[0]
  const offlineSeconds = offlineUntil === null ? 0 : Math.max(0, Math.ceil((offlineUntil - clock) / 1000))

  return (
    <main className="min-h-screen bg-slate-950 p-3 text-slate-100 sm:p-4">
      <header className="mb-3 flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <h1 className="text-2xl font-semibold">{outletId}</h1>
        <span className="text-sm text-slate-400">{deviceId}</span>
        <StatusDot status={mqtt.status} label={`MQTT ${mqtt.status}`} />
        <span data-testid="clock" className="font-mono text-lg tabular-nums">
          {formatClock(clock)}
        </span>
        <button
          type="button"
          onClick={goOffline}
          disabled={offlineUntil !== null}
          className="rounded border border-slate-600 px-3 py-1 text-sm disabled:opacity-60"
        >
          {offlineUntil === null ? 'Simulate offline' : `Offline… back in ${offlineSeconds}s`}
        </button>
      </header>

      {!audioUnlocked && (
        <div role="status" className="mb-3 rounded bg-amber-500 px-3 py-2 text-center font-semibold text-black">
          Tap anywhere to enable alert sound
        </div>
      )}

      <div className="grid gap-4 md:grid-cols-5">
        <div className="order-1 md:order-2 md:col-span-3">
          <h2 className="mb-2 text-lg font-semibold">Cancellations ({open.length})</h2>
          <div className="space-y-3" data-testid="card-list">
            {cases.map((entry) => (
              <CancelCard
                key={entry.caseId}
                entry={entry}
                outletId={outletId}
                deviceId={deviceId}
                onClosed={closeLocally}
              />
            ))}
            {cases.length === 0 && <p className="text-slate-400">No open cancellations.</p>}
          </div>
        </div>
        <div className="order-2 md:order-1 md:col-span-2">
          <OrderQueue orders={activeOrders} isError={orders.isError} />
        </div>
      </div>

      {firstTakeover !== undefined && (
        <TakeoverOverlay
          entry={firstTakeover}
          more={takeovers.length - 1}
          outletId={outletId}
          deviceId={deviceId}
          onClosed={closeLocally}
          queueStrip={<OrderQueue orders={activeOrders} isError={orders.isError} strip />}
        />
      )}
    </main>
  )
}
