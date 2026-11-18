import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Countdown } from './Countdown'
import { formatMmSs } from '../lib/time'

describe('Countdown', () => {
  afterEach(() => vi.useRealTimers())

  it('derives remaining time from the server timestamp', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-21T14:00:00.000Z'))
    render(<Countdown to="2026-11-21T14:00:45.000Z" />)
    expect(screen.getByTestId('countdown')).toHaveTextContent('00:45')
  })

  it('counts down and goes red under 10 seconds', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-21T14:00:00.000Z'))
    render(<Countdown to="2026-11-21T14:00:30.000Z" />)
    expect(screen.getByTestId('countdown')).not.toHaveClass('text-red-400')
    act(() => {
      vi.advanceTimersByTime(25_000)
    })
    expect(screen.getByTestId('countdown')).toHaveTextContent('00:05')
    expect(screen.getByTestId('countdown')).toHaveClass('text-red-400')
  })

  it('clamps at 00:00 once the server time has passed', () => {
    expect(formatMmSs(-5000)).toBe('00:00')
    render(<Countdown to="2026-11-21T14:00:00.000Z" nowMs={Date.parse('2026-11-21T14:05:00.000Z')} />)
    expect(screen.getByTestId('countdown')).toHaveTextContent('00:00')
  })
})
