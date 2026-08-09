package io.rigger.swarm.client;

/** Wraps exceptions thrown by docker-java operations into a Rigger-typed exception. */
public class DockerApiException extends RuntimeException {
    public DockerApiException(String message) { super(message); }
    public DockerApiException(String message, Throwable cause) { super(message, cause); }
}
