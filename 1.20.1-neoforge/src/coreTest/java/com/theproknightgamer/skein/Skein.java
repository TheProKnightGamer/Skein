package com.theproknightgamer.skein;

public final class Skein {
    public static final Logger LOGGER = new Logger();

    public static final class Logger {
        public void error(String message, Object... args) {
            System.err.println(message);
            for (Object arg : args) {
                if (arg instanceof Throwable error) error.printStackTrace();
            }
        }
    }
}
