package com.metrovoc.reprise.export;

import java.io.IOException;

/** Receives rendered frames in order: RGBA, bottom row first, as the GPU stores them. */
interface FrameSink {
    void frame(byte[] rgba) throws IOException;

    /** Finishes the file; true if it was written completely. */
    boolean finish() throws IOException;

    void abort();
}
