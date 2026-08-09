package io.rigger.api.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the React SPA for all /ui/** routes.
 * Vite builds to static/ui/index.html inside the JAR.
 * This controller handles deep links (React Router client-side routing).
 */
@Controller
@RequestMapping("/ui")
public class UiController {

  // /ui → index.html
  @GetMapping({"", "/"})
  public String uiRoot() {
    return "forward:/ui/index.html";
  }

  // /ui/dashboard, /ui/nodes, etc. → index.html (React Router handles it)
  @GetMapping("/**")
  public String uiRoutes() {
    return "forward:/ui/index.html";
  }
}
