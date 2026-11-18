import { NavLink, Outlet } from 'react-router'

const LINKS: ReadonlyArray<{ to: string; label: string; end?: boolean }> = [
  { to: '/ops', label: 'Live', end: true },
  { to: '/ops/runs', label: 'Runs' },
  { to: '/ops/faults', label: 'Faults' },
  { to: '/ops/impact', label: 'Impact' },
]

export default function OpsLayout() {
  return (
    <div>
      <nav className="flex gap-4 border-b border-slate-800 p-4">
        {LINKS.map((link) => (
          <NavLink
            key={link.to}
            to={link.to}
            end={link.end}
            className={({ isActive }) => (isActive ? 'font-semibold text-white' : 'text-slate-400')}
          >
            {link.label}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </div>
  )
}
