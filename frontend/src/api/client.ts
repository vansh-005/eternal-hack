import type { ErrorBody } from './types'

export class ApiError extends Error {
  readonly status: number
  readonly body: ErrorBody | null

  constructor(status: number, body: ErrorBody | null, fallbackMessage: string) {
    super(body?.message ?? fallbackMessage)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }

  get code(): string {
    return this.body?.error ?? `HTTP_${this.status}`
  }
}

export interface RequestOptions {
  body?: unknown
  headers?: Record<string, string>
  signal?: AbortSignal
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (text.length === 0) {
    return null
  }
  try {
    return JSON.parse(text) as unknown
  } catch {
    return null
  }
}

function isErrorBody(value: unknown): value is ErrorBody {
  return typeof value === 'object' && value !== null && 'error' in value
}

async function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  let body: string | undefined
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }
  const response = await fetch(path, { method, headers, body, signal: options.signal })
  const parsed = await readJson(response)
  if (!response.ok) {
    const errorBody = isErrorBody(parsed) ? parsed : null
    throw new ApiError(response.status, errorBody, `${method} ${path} failed with ${response.status}`)
  }
  return parsed as T
}

export function apiGet<T>(path: string, options?: RequestOptions): Promise<T> {
  return request<T>('GET', path, options)
}

export function apiPost<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T> {
  return request<T>('POST', path, { ...options, body })
}

export function apiDelete<T>(path: string, options?: RequestOptions): Promise<T> {
  return request<T>('DELETE', path, options)
}

export function tabletHeaders(outletId: string, deviceId: string): Record<string, string> {
  return { 'X-Outlet-Id': outletId, 'X-Device-Id': deviceId }
}
