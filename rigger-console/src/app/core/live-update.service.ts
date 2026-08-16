import { Injectable, inject } from '@angular/core';
import { AuthService } from './auth.service';

const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 30000;

/**
 * Opens a long-lived SSE connection and calls `onMessage` for every event received — the payload
 * itself is ignored on purpose. Topology and pods pages already know how to refetch their own
 * state (that's what {@code RefreshService}'s polling tick already drove); this just replaces the
 * timer with a server push, so there's no partial-state diff/merge logic to get wrong here or on
 * the server. See {@code NamespaceSseHub} (rigger-api) for the matching backend piece.
 *
 * <p>Uses {@code fetch} + a manual reader rather than {@code EventSource}, same reason as
 * {@link import('./log-stream.service').LogStreamService}: {@code EventSource} can't carry an
 * `Authorization` header, and the API requires a bearer token on every request.
 *
 * <p>Unlike log streaming (user-initiated, pausable), these connections are meant to stay open for
 * as long as the page is — so, unlike `LogStreamService`, this reconnects automatically with
 * exponential backoff (capped at 30s, reset once a connection is cleanly established) until the
 * caller explicitly stops it.
 */
@Injectable({ providedIn: 'root' })
export class LiveUpdateService {
  private readonly auth = inject(AuthService);

  /**
   * @returns a function that stops the connection and any pending reconnect.
   */
  watch(url: string, onMessage: () => void): () => void {
    let stopped = false;
    let controller: AbortController | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let backoffMs = INITIAL_BACKOFF_MS;

    const connectOnce = async () => {
      controller = new AbortController();
      try {
        const res = await fetch(url, {
          headers: {
            Accept: 'text/event-stream',
            Authorization: `Bearer ${this.auth.token ?? ''}`,
          },
          signal: controller.signal,
        });
        if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);

        backoffMs = INITIAL_BACKOFF_MS; // connected cleanly — forget any prior backoff
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });

          const events = buffer.split('\n\n');
          buffer = events.pop() ?? '';
          for (const event of events) {
            if (event.split('\n').some((line) => line.startsWith('data:'))) onMessage();
          }
        }
      } catch (e) {
        if ((e as Error)?.name === 'AbortError') return; // stopped deliberately — no reconnect
      }
      scheduleReconnect();
    };

    const scheduleReconnect = () => {
      if (stopped) return;
      reconnectTimer = setTimeout(() => void connectOnce(), backoffMs);
      backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
    };

    void connectOnce();

    return () => {
      stopped = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      controller?.abort();
    };
  }
}
