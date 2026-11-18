import { Navigate, Route, Routes } from 'react-router'
import SimulatorPage from './routes/simulator/SimulatorPage'
import TabletPage from './routes/tablet/TabletPage'
import OpsLayout from './routes/ops/OpsLayout'
import LivePage from './routes/ops/LivePage'
import CaseDetailPage from './routes/ops/CaseDetailPage'
import RunsPage from './routes/ops/RunsPage'
import FaultsPage from './routes/ops/FaultsPage'
import ImpactPage from './routes/ops/ImpactPage'

export function App() {
  return (
    <Routes>
      <Route path="/simulator" element={<SimulatorPage />} />
      <Route path="/tablet" element={<TabletPage />} />
      <Route path="/ops" element={<OpsLayout />}>
        <Route index element={<LivePage />} />
        <Route path="cases/:caseId" element={<CaseDetailPage />} />
        <Route path="runs" element={<RunsPage />} />
        <Route path="faults" element={<FaultsPage />} />
        <Route path="impact" element={<ImpactPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/simulator" replace />} />
    </Routes>
  )
}
