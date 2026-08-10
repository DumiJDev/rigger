package io.rigger.api.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the Angular console for all {@code /ui/**} routes.
 *
 * <p>The build writes to {@code static/ui/index.html} inside the jar; this forwards client-side
 * routes (/ui/topology, /ui/deployments, …) to it so deep links and refreshes work.
 *
 * <p>The mappings deliberately exclude any path segment containing a dot. A blanket
 * {@code /ui/**} mapping also matches {@code /ui/index.html} and every hashed JS/CSS asset, so the
 * forward target matches the mapping again and recurses until the request dies with a
 * StackOverflowError — the whole UI 500s, including its own assets. Excluding dotted segments
 * leaves files to the static resource handler and forwards only route-shaped paths.
 */
@Controller
@RequestMapping("/ui")
public class UiController {

  private static final String INDEX = "forward:/ui/index.html";

  @GetMapping({"", "/"})
  public String uiRoot() {
    return INDEX;
  }

  /** Single-segment routes: /ui/dashboard, /ui/topology, … */
  @GetMapping("/{path:[^.]*}")
  public String uiRoute() {
    return INDEX;
  }

  /** Nested routes: /ui/workloads/deployments, … */
  @GetMapping("/{path:^(?!.*\\.).*$}/**")
  public String uiNestedRoute() {
    return INDEX;
  }
}
