package com.dia.ismdtoolbackend.constants;

/** Logging constants shared across request handling. */
public class FormatConstants {

    private FormatConstants() {
    }

    public static final class Converter {
        /** MDC key holding the request correlation id. */
        public static final String LOG_REQUEST_ID = "requestId";

        private Converter() {
        }
    }
}