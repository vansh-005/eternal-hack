import { useSearchParams } from 'react-router'
import { StatusDot } from '../../components/StatusDot'
import { useMqtt } from '../../mqtt/useMqtt'

export default function TabletPage() {
  const [params] = useSearchParams()
  const outletId = params.get('outlet') ?? 'outlet-demo'
  const deviceId = params.get('device') ?? 'tab-1'
  const mqtt = useMqtt({ outletId, deviceId })

  return (
    <main className="p-4">
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-2xl font-semibold">Tablet</h1>
        <span className="text-sm text-slate-400">
          {outletId} · {deviceId}
        </span>
        <StatusDot status={mqtt.status} label={`MQTT ${mqtt.status}`} />
      </header>
      <p className="mt-4 text-slate-400">Placeholder (F-01).</p>
    </main>
  )
}
