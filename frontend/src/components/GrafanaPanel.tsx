export function grafanaBase(): string {
  return import.meta.env.VITE_GRAFANA_URL ?? `http://${location.hostname}:3000`
}

export interface GrafanaPanelProps {
  dashboardUid: string
  panelId: number
  height?: number
  from?: string
  to?: string
  slug?: string
  title?: string
}

export function grafanaPanelUrl(props: GrafanaPanelProps, base: string = grafanaBase()): string {
  const { dashboardUid, panelId, slug = 'live-slos', from = 'now-15m', to = 'now' } = props
  const query = new URLSearchParams({
    orgId: '1',
    panelId: String(panelId),
    refresh: '5s',
    theme: 'dark',
    from,
    to,
  })
  return `${base}/d-solo/${dashboardUid}/${slug}?${query.toString()}`
}

export function GrafanaPanel(props: GrafanaPanelProps) {
  const { height = 260, title = `Grafana panel ${props.panelId}` } = props
  return (
    <iframe
      src={grafanaPanelUrl(props)}
      title={title}
      loading="lazy"
      className="w-full rounded border border-slate-700"
      style={{ height }}
    />
  )
}
