package io.rigger.api.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Re-frames a raw byte stream of log output into Server-Sent Events, one event per line.
 *
 * <p>Docker's log stream arrives as arbitrary chunks that don't align to line boundaries, so this
 * buffers until it sees a newline before emitting — a naive per-chunk wrapper would split single
 * log lines across several SSE events and corrupt them in the browser.
 *
 * <p>Only used for the {@code text/event-stream} variant of the pod-logs endpoint; the plain-text
 * variant that {@code riggerctl logs} consumes keeps writing raw bytes.
 */
public class SseLineFramingOutputStream extends OutputStream {

    private final OutputStream delegate;
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);

    public SseLineFramingOutputStream(OutputStream delegate) {
        this.delegate = delegate;
    }

    @Override
    public void write(int b) throws IOException {
        if (b == '\n') {
            emit();
        } else if (b != '\r') {
            lineBuffer.write(b);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        for (int i = off; i < off + len; i++) {
            write(b[i]);
        }
    }

    private void emit() throws IOException {
        String line = lineBuffer.toString(StandardCharsets.UTF_8);
        lineBuffer.reset();
        delegate.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
        delegate.flush();
    }

    /** Flushes any trailing partial line so the last log line isn't swallowed when the stream ends. */
    @Override
    public void close() throws IOException {
        if (lineBuffer.size() > 0) emit();
        delegate.flush();
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }
}
