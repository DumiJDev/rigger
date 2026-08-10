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
 * served, otherwise the SPA shell is returned. Pattern-based fallbacks are what broke this twice —
 * a blanket {@code /ui/**} forward also matched {@code index.html} itself and recursed into a
 * StackOverflowError, and excluding only dotted *first* segments still swallowed nested assets like
 * {@code /ui/i18n/en.json}, which then arrived at Transloco as HTML. Translations silently failed to
 * parse and every page rendered blank, with nothing in the browser console to explain it.
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
                    return requested.exists() && requested.isReadable()
                        ? requested
                        : location.createRelative(INDEX);
                }
            });
    }
}
