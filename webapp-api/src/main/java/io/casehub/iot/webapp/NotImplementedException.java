package io.casehub.iot.webapp;

public class NotImplementedException extends RuntimeException {
    private final String operation;

    public NotImplementedException(String operation) {
        super("Not implemented: " + operation);
        this.operation = operation;
    }

    public String operation() { return operation; }
}
