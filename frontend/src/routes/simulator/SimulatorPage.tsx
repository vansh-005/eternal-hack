import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useCaseForOrder, useOrderSnapshot } from '../../api/queries'
import type { OrderSnapshot } from '../../api/types'
import { LoadControls } from './LoadControls'
import { OrderControls } from './OrderControls'

function readStoredOrderId(): string | null {
  try {
    return sessionStorage.getItem('simulator.orderId')
  } catch {
    return null
  }
}

function storeOrderId(orderId: string): void {
  try {
    sessionStorage.setItem('simulator.orderId', orderId)
  } catch {
    // storage unavailable: selection just won't survive a reload
  }
}

function SnapshotPanel({ order, caseId }: { order: OrderSnapshot | undefined; caseId: string | null }) {
  if (order === undefined) {
    return <p className="text-slate-400">No order yet. Create one to begin.</p>
  }
  return (
    <div className="space-y-2" data-testid="order-snapshot">
      <p className="text-3xl font-bold">
        #{order.orderRef} <span className="text-base font-normal text-slate-400">{order.orderId}</span>
      </p>
      <p>
        <span data-testid="order-status" className="rounded bg-slate-700 px-2 py-1 font-semibold">
          {order.status}
        </span>{' '}
        <span className="text-sm text-slate-300">
          stage {order.stage ?? '—'} · v{order.version}
        </span>
      </p>
      <pre className="overflow-x-auto rounded bg-slate-900 p-3 text-xs">{JSON.stringify(order, null, 2)}</pre>
      {caseId !== null ? (
        <Link className="text-sky-400 underline" to={`/ops/cases/${caseId}`}>
          Open timeline
        </Link>
      ) : (
        order.status === 'CANCELLED' && <p className="text-sm text-slate-400">Waiting for NoCook to open a case…</p>
      )}
    </div>
  )
}

export default function SimulatorPage() {
  const [params] = useSearchParams()
  const outletId = params.get('outlet') ?? 'outlet-demo'
  const [orderId, setOrderId] = useState<string | null>(readStoredOrderId)
  const snapshot = useOrderSnapshot(orderId)
  const order = snapshot.data
  const caseQuery = useCaseForOrder(orderId, outletId, order?.status === 'CANCELLED')

  const selectOrder = (next: OrderSnapshot) => {
    storeOrderId(next.orderId)
    setOrderId(next.orderId)
  }

  return (
    <main className="min-h-screen bg-slate-950 p-4 text-slate-100 sm:p-6">
      <h1 className="mb-4 text-2xl font-semibold">Simulator</h1>
      <div className="grid gap-6 md:grid-cols-2">
        <OrderControls outletId={outletId} order={order ?? null} onOrder={selectOrder} />
        <section aria-label="Selected order" className="space-y-4">
          <h2 className="text-lg font-semibold">Selected order (live, 1 s)</h2>
          <SnapshotPanel order={order} caseId={caseQuery.data?.caseId ?? null} />
        </section>
      </div>
      <div className="mt-6">
        <LoadControls />
      </div>
    </main>
  )
}
