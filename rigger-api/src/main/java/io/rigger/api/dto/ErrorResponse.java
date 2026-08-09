package io.rigger.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** RFC 7807 Problem Details response for all API errors. */
public record ErrorResponse(
        @JsonProperty("status")  int status,
        @JsonProperty("title")   String title,
        @JsonProperty("detail")  String detail,
        @JsonProperty("instance")String instance
) {
    public static ErrorResponse of(int status, String title, String detail, String path) {
        return new ErrorResponse(status, title, detail, path);
    }
}
