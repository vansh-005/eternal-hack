import type { OrderSnapshot } from '../../api/types'
import { formatElapsed, useNow } from '../../lib/time'

export interface OrderQueueProps {
  orders: OrderSnapshot[]
  isError: boolean
  /** Compact horizontal strip (used under the takeover overlay). */
  strip?: boolean
}

function stageLabel(order: OrderSnapshot): string {
  if (order.status === 'PREPARING' && order.stage !== null && order.stage !== 'NOT_STARTED') {
    return `PREPARING · ${order.stage}`
  }
  return order.status
}

function itemsText(order: OrderSnapshot): string {
  return order.items.map((item) => `${item.qty}× ${item.name}`).join(', ')
}

export function OrderQueue({ orders, isError, strip = false }: OrderQueueProps) {
  const now = useNow(1000)
  if (strip) {
    return (
      <ul data-testid="order-queue-strip" className="flex gap-2 overflow-x-auto">
        {orders.map((order) => (
          <li key={order.orderId} className="min-w-40 shrink-0 rounded bg-slate-900 p-2 text-sm text-slate-100">
            <div className="font-semibold">#{order.orderRef}</div>
            <div className="text-xs text-slate-300">{stageLabel(order)}</div>
          </li>
        ))}
        {orders.length === 0 && <li className="text-sm text-red-100">No active orders</li>}
      </ul>
    )
  }
  return (
    <section aria-label="Order queue" data-testid="order-queue">
      <h2 className="mb-2 text-lg font-semibold">Orders ({orders.length})</h2>
      {isError && <p className="mb-2 text-sm text-amber-300">Order list unavailable, retrying…</p>}
      <ul className="space-y-2">
        {orders.map((order) => (
          <li key={order.orderId} className="rounded-lg border border-slate-700 bg-slate-900 p-3">
            <div className="flex items-center justify-between gap-2">
              <span className="text-xl font-semibold">#{order.orderRef}</span>
              <span className="rounded bg-slate-700 px-2 py-0.5 text-xs font-semibold">{stageLabel(order)}</span>
            </div>
            <p className="text-sm text-slate-300">{itemsText(order)}</p>
            {order.acceptedAt !== null && (
              <p className="text-xs text-slate-400">Accepted {formatElapsed(order.acceptedAt, now)} ago</p>
            )}
          </li>
        ))}
        {orders.length === 0 && <li className="text-slate-400">No active orders.</li>}
      </ul>
    </section>
  )
}
