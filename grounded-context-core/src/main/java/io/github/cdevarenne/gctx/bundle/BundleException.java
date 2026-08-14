package io.github.cdevarenne.gctx.bundle;

/** A bundle file is malformed or violates the OKF spec. */
public class BundleException extends RuntimeException {

    public BundleException(String message) {
        super(message);
    }
}
