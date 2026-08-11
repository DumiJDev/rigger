package io.rigger.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import java.io.IOException;

/**
 * Serves the Angular console from {@code classpath:/static/ui/}, falling back to {@code index.html}
 * for client-side routes so deep links and refreshes work.
 *
 * <p>Resolution is by existence, not by pattern: if the requested path names a real file it is
 * served. Pattern-based fallbacks are what broke this twice — a blanket {@code /ui/**} forward also
 * matched {@code index.html} itself and recursed into a StackOverflowError, and excluding only dotted
 * *first* segments still swallowed nested assets like {@code /ui/i18n/en.json}, which then arrived at
 * Transloco as HTML. Translations silently failed to parse and every page rendered blank, with
 * nothing in the browser console to explain it.
 *
 * <p><b>A missing asset must 404, not fall back.</b> Existence-based resolution fixed the recursion
 * but kept an unconditional fallback, so any file that was not there still came back as the SPA shell
 * with {@code 200 text/html} — the same shape as the bug above, just waiting for a different asset to
 * go missing. It was found again after deleting {@code favicon.ico}: the browser asked for it and got
 * a page. Only <em>route-shaped</em> paths fall back now, and a route is one whose last segment has no
 * dot: Angular's router never generates a path segment containing a dot, and every asset has an
 * extension. Getting a 404 for a mistyped asset is the whole point — HTML served in its place is what
 * made the failure silent.
 */
@Configuration
public class UiResourceConfig implements WebMvcConfigurer {

    private static final String INDEX = "index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/ui/**")
            .addResourceLocations("classpath:/static/ui/")
            .resourceChain(true)
            .addResolver(new PathResourceResolver() {
                @Override
                protected Resource getResource(String resourcePath, Resource location)
                        throws IOException {
                    // "/ui/" itself arrives with an empty path, which resolves to the directory
                    // rather than a file and 500s as an unresolvable resource.
                    if (resourcePath == null || resourcePath.isBlank()) {
                        return location.createRelative(INDEX);
                    }
                    Resource requested = location.createRelative(resourcePath);
                    if (requested.exists() && requested.isReadable()) {
                        return requested;
                    }
                    // Fall back only for something that looks like a client-side route. Returning
                    // null here lets Spring answer 404, which is what a missing asset deserves.
                    return looksLikeRoute(resourcePath) ? location.createRelative(INDEX) : null;
                }

                /**
                 * A route has no dot in its last segment. Angular's router never produces one, and
                 * every static asset has an extension — so this separates {@code /ui/deployments}
                 * (serve the shell) from {@code /ui/i18n/en.json} (404 if it is genuinely absent).
                 */
                private boolean looksLikeRoute(String resourcePath) {
                    int lastSlash = resourcePath.lastIndexOf('/');
                    return !resourcePath.substring(lastSlash + 1).contains(".");
                }
            });
    }
}
