import { useEffect, useSyncExternalStore } from 'react'

const TONES_HZ = [880, 1100, 1320]
const TONE_SECONDS = 0.14
const GAP_SECONDS = 0.06

let context: AudioContext | null = null
let unlocked = false
const listeners = new Set<() => void>()

function setUnlocked(value: boolean): void {
  if (unlocked !== value) {
    unlocked = value
    listeners.forEach((listener) => listener())
  }
}

function getContext(): AudioContext | null {
  if (context === null) {
    const Ctor = typeof window === 'undefined' ? undefined : window.AudioContext
    if (Ctor === undefined) {
      return null
    }
    const created = new Ctor()
    created.onstatechange = () => setUnlocked(created.state === 'running')
    context = created
  }
  return context
}

/** Must be called from a user gesture. */
export function unlockAudio(): void {
  const audio = getContext()
  if (audio === null) {
    return
  }
  void audio.resume().then(() => setUnlocked(audio.state === 'running'))
}

/** Three short tones. Returns false when audio is still locked. */
export function playBeep(): boolean {
  const audio = getContext()
  if (audio === null || audio.state !== 'running') {
    return false
  }
  const start = audio.currentTime + 0.02
  TONES_HZ.forEach((frequency, index) => {
    const oscillator = audio.createOscillator()
    const gain = audio.createGain()
    const at = start + index * (TONE_SECONDS + GAP_SECONDS)
    oscillator.type = 'square'
    oscillator.frequency.value = frequency
    gain.gain.setValueAtTime(0.0001, at)
    gain.gain.exponentialRampToValueAtTime(0.25, at + 0.01)
    gain.gain.exponentialRampToValueAtTime(0.0001, at + TONE_SECONDS)
    oscillator.connect(gain)
    gain.connect(audio.destination)
    oscillator.start(at)
    oscillator.stop(at + TONE_SECONDS + 0.02)
  })
  return true
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** True once a gesture has unlocked audio; registers a tap-anywhere unlock. */
export function useAudioUnlocked(): boolean {
  const value = useSyncExternalStore(subscribe, () => unlocked)
  useEffect(() => {
    const events = ['pointerdown', 'keydown', 'touchstart']
    const handler = () => unlockAudio()
    events.forEach((name) => window.addEventListener(name, handler, { passive: true }))
    return () => events.forEach((name) => window.removeEventListener(name, handler))
  }, [])
  return value
}
