package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.exception.OntologyUploadParseTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The parse deadline both answers the request and stops the parser. */
class UploadFileReaderDeadlineTest {

    private final UploadFileReader reader = new UploadFileReader("10MB", 1);

    /** An endless Turtle document that trickles in: the parser keeps reading until something stops it. */
    private static final class EndlessTurtle extends InputStream {
        private final AtomicLong reads;
        private byte[] chunk = new byte[0];
        private int pos;
        private long n;

        EndlessTurtle(AtomicLong reads) {
            this.reads = reads;
        }

        @Override
        public int read() {
            if (pos == chunk.length) {
                reads.incrementAndGet();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                chunk = ("<urn:s" + n++ + "> <urn:p> <urn:o> .\n").getBytes(StandardCharsets.UTF_8);
                pos = 0;
            }
            return chunk[pos++];
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) return 0;
            b[off] = (byte) read();
            return 1;
        }
    }

    @Test
    void aParseThatOutrunsTheDeadlineFailsAndStopsReading() throws Exception {
        AtomicLong reads = new AtomicLong();
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1_000L);
        when(file.getOriginalFilename()).thenReturn("endless.ttl");
        when(file.getInputStream()).thenReturn(new EndlessTurtle(reads));

        long start = System.nanoTime();
        assertThatThrownBy(() -> reader.read(file)).isInstanceOf(OntologyUploadParseTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));

        long atTimeout = reads.get();
        Thread.sleep(300);
        assertThat(reads.get()).as("the parser stopped reading after the deadline").isLessThanOrEqualTo(atTimeout + 1);
    }

    @Test
    void aTimelyFileParses() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "ok.ttl", "text/turtle",
                "<urn:s> <urn:p> <urn:o> .".getBytes(StandardCharsets.UTF_8));

        assertThat(reader.read(file).size()).isEqualTo(1);
    }
}
