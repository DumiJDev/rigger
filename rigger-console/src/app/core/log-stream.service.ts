import { Injectable, inject } from '@angular/core';
import { AuthService } from './auth.service';

/**
 * Streams pod logs from the SSE variant of the logs endpoint.
 *
 * <p>Uses {@code fetch} with a stream reader rather than {@code EventSource}: EventSource can't
 * set an Authorization header, and the API requires a bearer token on every request. That means
 * parsing the {@code data:} framing here, which is a small price for not having to put the token
 * in a query string.
 */
@Injectable({ providedIn: 'root' })
export class LogStreamService {
  private readonly auth = inject(AuthService);

  /**
   * Opens a stream and invokes onLine for each log line.
   * @returns a function that aborts the stream.
   */
  stream(
    namespace: string,
    pod: string,
    follow: boolean,
    onLine: (line: string) => void,
    onError: (message: string) => void,
    onDone: () => void,
  ): () => void {
    const controller = new AbortController();
    const url =
      `/api/v1/namespaces/${encodeURIComponent(namespace)}/pods/${encodeURIComponent(pod)}` +
      `/logs?follow=${follow}`;

    void (async () => {
      try {
        const res = await fetch(url, {
          headers: {
            Accept: 'text/event-stream',
            Authorization: `Bearer ${this.auth.token ?? ''}`,
          },
          signal: controller.signal,
        });

        if (!res.ok) {
          onError(`HTTP ${res.status}`);
          return;
        }
        if (!res.body) {
          onError('empty-body');
          return;
        }

        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });

          // SSE events are separated by a blank line; anything after the last separator is a
          // partial event and has to stay buffered until the rest arrives.
          const events = buffer.split('\n\n');
          buffer = events.pop() ?? '';
          for (const event of events) {
            for (const line of event.split('\n')) {
              if (line.startsWith('data:')) onLine(line.slice(5).replace(/^ /, ''));
            }
          }
        }
        onDone();
      } catch (e) {
        // Aborting is the normal way to stop following — not an error worth showing.
        if ((e as Error)?.name !== 'AbortError') onError((e as Error)?.message ?? 'stream-failed');
        else onDone();
      }
    })();

    return () => controller.abort();
  }
}
