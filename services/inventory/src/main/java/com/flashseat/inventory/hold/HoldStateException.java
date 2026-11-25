package com.flashseat.inventory.hold;

import java.util.UUID;

public class HoldStateException extends RuntimeException {

    public enum Problem {
        NOT_FOUND("does not exist"),
        EXPIRED("has expired"),
        CONFIRMED("is already confirmed and cannot be released"),
        NOT_ACTIVE("is no longer active");

        private final String description;

        Problem(String description) {
            this.description = description;
        }
    }

    private final Problem problem;

    public HoldStateException(UUID holdId, Problem problem) {
        super("Hold " + holdId + " " + problem.description);
        this.problem = problem;
    }

    public Problem getProblem() {
        return problem;
    }
}
