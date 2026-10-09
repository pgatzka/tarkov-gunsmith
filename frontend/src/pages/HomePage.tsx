import { useEffect, useState } from 'react';
import { getHealth } from '../api/client';

export function HomePage() {
  const [backendStatus, setBackendStatus] = useState('checking…');

  useEffect(() => {
    const controller = new AbortController();
    getHealth(controller.signal)
      .then((health) => setBackendStatus(health.status))
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          setBackendStatus(
            error instanceof Error ? `unreachable (${error.message})` : 'unreachable',
          );
        }
      });
    return () => controller.abort();
  }, []);

  return (
    <main>
      <h1>Tarkov Gunsmith</h1>
      <p>Random weapon build generator — coming soon.</p>
      <p>
        Backend: <code>{backendStatus}</code>
      </p>
    </main>
  );
}
