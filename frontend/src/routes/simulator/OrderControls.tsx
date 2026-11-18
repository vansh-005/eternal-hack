import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { applyOrderAction, createOrder, type OrderAction } from '../../api/queries'
import type { OrderSnapshot } from '../../api/types'
import { legalActions } from './transitions'

export interface OrderControlsProps {
  outletId: string
  order: OrderSnapshot | null
  onOrder: (order: OrderSnapshot) => void
}

interface ActionButton {
  label: string
  enabled: boolean
  action: OrderAction
  tone?: 'primary' | 'danger' | 'muted'
}

const TONES = {
  primary: 'bg-sky-600 hover:bg-sky-500',
  danger: 'bg-red-600 hover:bg-red-500 font-bold',
  muted: 'bg-slate-600 hover:bg-slate-500',
}

export function OrderControls({ outletId, order, onOrder }: OrderControlsProps) {
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const legal = legalActions(order)

  const run = async (work: () => Promise<OrderSnapshot>, label: string): Promise<void> => {
    setBusy(true)
    setMessage(null)
    try {
      const snapshot = await work()
      queryClient.setQueryData(['order', snapshot.orderId], snapshot)
      onOrder(snapshot)
      setMessage(`${label}: ${snapshot.orderId} is now ${snapshot.status} (v${snapshot.version})`)
    } catch (error) {
      setMessage(
        error instanceof ApiError ? `${label} rejected: ${error.code} — ${error.message}` : `${label} failed: network error`,
      )
    } finally {
      setBusy(false)
    }
  }

  const buttons: ActionButton[] = [
    { label: 'Accept', enabled: legal.accept, action: { kind: 'accept' } },
    { label: 'Start prep', enabled: legal.startPrep, action: { kind: 'stage', stage: 'PREP' } },
    { label: 'Into oven', enabled: legal.intoOven, action: { kind: 'stage', stage: 'OVEN' } },
    { label: 'Mark ready', enabled: legal.ready, action: { kind: 'ready' } },
    { label: 'Pick up', enabled: legal.pickup, action: { kind: 'pickup' } },
    { label: 'Deliver', enabled: legal.deliver, action: { kind: 'deliver' } },
    { label: 'Customer cancels', enabled: legal.cancel, action: { kind: 'cancel', initiatedBy: 'CUSTOMER' }, tone: 'danger' },
    { label: 'Restaurant cancels', enabled: legal.cancel, action: { kind: 'cancel', initiatedBy: 'RESTAURANT' }, tone: 'muted' },
    { label: 'Reinstate (test)', enabled: legal.reinstate, action: { kind: 'reinstate' }, tone: 'muted' },
  ]

  const buttonClass = 'rounded px-4 py-3 text-left text-white disabled:cursor-not-allowed disabled:opacity-30'

  return (
    <section aria-label="Order controls" className="space-y-3">
      <h2 className="text-lg font-semibold">Order controls · {outletId}</h2>
      <button
        type="button"
        disabled={busy}
        onClick={() =>
          void run(() => createOrder(outletId, [{ name: 'Margherita', qty: 1, pricePaise: 45000 }]), 'New order')
        }
        className={`${buttonClass} w-full ${TONES.primary} font-semibold`}
      >
        New order (Margherita ₹450)
      </button>
      <div className="grid grid-cols-2 gap-2">
        {buttons.map((button) => (
          <button
            key={button.label}
            type="button"
            disabled={busy || order === null || !button.enabled}
            onClick={() => order !== null && void run(() => applyOrderAction(order.orderId, button.action), button.label)}
            className={`${buttonClass} ${TONES[button.tone ?? 'primary']}`}
          >
            {button.label}
          </button>
        ))}
      </div>
      {message !== null && (
        <p role="status" className="rounded bg-slate-800 px-3 py-2 text-sm text-slate-200">
          {message}
        </p>
      )}
    </section>
  )
}
