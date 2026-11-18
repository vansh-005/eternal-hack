import { useParams } from 'react-router'

export default function CaseDetailPage() {
  const { caseId } = useParams()
  return (
    <main className="p-6">
      <h1 className="text-2xl font-semibold">Case timeline (F-04)</h1>
      <p className="text-slate-400">Placeholder. Case {caseId}</p>
    </main>
  )
}
