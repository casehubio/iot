package io.casehub.iot.webapp.rest;

public enum DriftStatus {
    CONVERGED,
    PERMITTED_DRIFT,
    UNEXPECTED_DRIFT,
    ABSENT,
    UNKNOWN,
    UNMONITORED
}
