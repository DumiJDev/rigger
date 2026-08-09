package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record Selector(@JsonProperty("matchLabels") Map<String, String> matchLabels) {
  public static Selector empty() {
    return new Selector(Map.of());
  }
}
