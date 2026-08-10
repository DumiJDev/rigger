package io.rigger.api.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Entry points for the console that the resource handler can't serve on its own.
 *
 * <p>{@code /ui} redirects so the console works without a trailing slash. {@code /ui/} is forwarded
 * explicitly because it reaches the resource handler with an empty path, which is rejected as an
 * unresolvable resource before any custom resolver runs — the console's own root URL would 500.
 *
 * <p>Everything else under {@code /ui/} — assets and client-side routes alike — is handled by
 * {@link io.rigger.api.config.UiResourceConfig}, which resolves by file existence. These mappings
 * are deliberately exact paths: a wildcard here would take precedence over the resource handler and
 * shadow the real files.
 */
@Controller
public class UiController {

  @GetMapping("/ui")
  public String uiRootRedirect() {
    return "redirect:/ui/";
  }

  @GetMapping("/ui/")
  public String uiRoot() {
    return "forward:/ui/index.html";
  }
}
