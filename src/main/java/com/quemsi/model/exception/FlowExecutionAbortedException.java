package com.quemsi.model.exception;

public class FlowExecutionAbortedException extends RuntimeException{
    private static final long serialVersionUID = 1L;
    public FlowExecutionAbortedException(String message) {
        super(message);
    }
    public FlowExecutionAbortedException(String message, Throwable cause) {
        super(message, cause);
    }
    public FlowExecutionAbortedException(Throwable cause) {
        super(cause);
    }
    public FlowExecutionAbortedException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
