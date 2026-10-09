import { Link } from 'react-router';

export function NotFoundPage() {
  return (
    <main>
      <h1>Not found</h1>
      <p>
        <Link to="/">Back to start</Link>
      </p>
    </main>
  );
}
