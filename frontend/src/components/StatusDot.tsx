import type { MqttStatus } from '../mqtt/useMqtt'

const COLOURS: Record<MqttStatus, string> = {
  connected: 'bg-green-500',
  reconnecting: 'bg-amber-400 animate-pulse',
  offline: 'bg-red-500',
}

const LABELS: Record<MqttStatus, string> = {
  connected: 'Connected',
  reconnecting: 'Reconnecting',
  offline: 'Offline',
}

export interface StatusDotProps {
  status: MqttStatus
  label?: string
}

export function StatusDot({ status, label }: StatusDotProps) {
  return (
    <span className="inline-flex items-center gap-2" data-testid="status-dot" data-status={status}>
      <span
        className={`inline-block h-3 w-3 rounded-full ${COLOURS[status]}`}
        role="img"
        aria-label={LABELS[status]}
      />
      <span className="text-sm">{label ?? LABELS[status]}</span>
    </span>
  )
}
