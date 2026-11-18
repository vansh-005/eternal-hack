export function FixtureBanner({ what }: { what: string }) {
  return (
    <div
      role="alert"
      data-testid="fixture-banner"
      className="mb-4 rounded border-2 border-dashed border-yellow-400 bg-yellow-500/10 p-3 font-semibold text-yellow-200"
    >
      FIXTURE — not a run result. {what} The API did not return data, so sample values are shown for layout only.
    </div>
  )
}
