package com.vipul.urlshortener.exception;

public class LinkBlockedException extends RuntimeException {
    public LinkBlockedException() {
        super("This link was blocked because its destination was flagged as unsafe");
    }
}
