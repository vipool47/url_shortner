package com.vipul.urlshortener.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PendingVerificationState {

    private final AtomicBoolean mayHavePending = new AtomicBoolean(true);

    public boolean mayHavePending() {
        return mayHavePending.get();
    }

    public void markPending() {
        mayHavePending.set(true);
    }

    public void markNoPending() {
        mayHavePending.set(false);
    }
}
