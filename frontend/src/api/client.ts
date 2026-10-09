// Thin fetch wrapper for the backend API. All paths are relative to /api, which
// the Vite dev server (and nginx in production) proxies to the backend.

const BASE_URL = '/api';

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: { Accept: 'application/json', ...init?.headers },
  });
  if (!response.ok) {
    throw new ApiError(response.status, `${init?.method ?? 'GET'} ${path} -> ${response.status}`);
  }
  return (await response.json()) as T;
}

export function get<T>(path: string, init?: RequestInit): Promise<T> {
  return request<T>(path, init);
}

export function post<T>(path: string, body: unknown, init?: RequestInit): Promise<T> {
  return request<T>(path, {
    ...init,
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...init?.headers },
    body: JSON.stringify(body),
  });
}

export interface Health {
  status: string;
}

export function getHealth(signal?: AbortSignal): Promise<Health> {
  return get<Health>('/actuator/health', { signal });
}
