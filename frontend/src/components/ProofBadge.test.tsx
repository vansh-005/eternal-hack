import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { P99Value, ProofBadge } from './ProofBadge'

describe('ProofBadge', () => {
  it('renders kind, N and run id', () => {
    render(<ProofBadge kind="MEASURED" n={12408} runId="run-20261121-1930-P3" />)
    expect(screen.getByTestId('proof-badge')).toHaveTextContent(
      'MEASURED · N=12,408 · run-20261121-1930-P3',
    )
  })
})

describe('P99Value', () => {
  it('hides p99 when n < 1000', () => {
    render(<P99Value valueMs={812} n={999} />)
    expect(screen.getByTestId('p99-hidden')).toHaveTextContent('—')
    expect(screen.getByTestId('p99-hidden')).toHaveAttribute('title', 'p99 hidden below N = 1,000')
    expect(screen.queryByTestId('p99-value')).toBeNull()
  })

  it('shows p99 when n >= 1000', () => {
    render(<P99Value valueMs={812.4} n={1000} />)
    expect(screen.getByTestId('p99-value')).toHaveTextContent('812 ms')
  })
})
