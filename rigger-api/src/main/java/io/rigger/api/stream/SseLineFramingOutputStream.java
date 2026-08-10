package io.rigger.api.stream;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Splits log output into lines and sends each one as a Server-Sent Event through an
 * {@link SseEmitter}, which owns the wire framing and the async request lifecycle.
 *
 * <p>An earlier attempt wrote {@code data:} framing by hand into a {@code StreamingResponseBody}.
 * The headers went out correctly but the streaming thread was interrupted immediately, so the
 * browser received an open, permanently empty stream and no error — hence going through the
 * framework's supported SSE path instead of hand-rolling it.
 *
 * <p>Each {@code write(byte[], off, len)} call carries one Docker log frame. Newlines inside a frame
 * split it into separate events, and any remainder is sent at the end of the frame rather than held
 * back, since docker-java's payloads don't reliably end with a newline.
 */
public class SseLineFramingOutputStream extends OutputStream {

    private final SseEmitter emitter;
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);

    public SseLineFramingOutputStream(SseEmitter emitter) {
        this.emitter = emitter;
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
        emitIfPending();
    }

    private void emitIfPending() throws IOException {
        if (lineBuffer.size() > 0) emit();
    }

    private void emit() throws IOException {
        String line = lineBuffer.toString(StandardCharsets.UTF_8);
        lineBuffer.reset();
        emitter.send(SseEmitter.event().data(line));
    }

    @Override
    public void close() throws IOException {
        emitIfPending();
    }
}
