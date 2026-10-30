package com.vipul.urlshortener.exception;

public class UnsafeDestinationException extends IllegalArgumentException {
    public UnsafeDestinationException() {
        super("This destination is listed as unsafe and cannot be shortened");
    }
}
