package com.vipul.urlshortener.exception;

public class LinkPendingException extends RuntimeException {
    public LinkPendingException() {
        super("This link is still being safety-checked");
    }
}
