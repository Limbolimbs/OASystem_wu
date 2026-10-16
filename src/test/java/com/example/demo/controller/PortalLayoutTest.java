package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.StandardLinkBuilder;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class PortalLayoutTest {
    private String resource(String name) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String navigation(boolean admin, boolean sales) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new StandardLinkBuilder() {
            @Override protected String computeContextPath(IExpressionContext context, String base, Map<String, Object> parameters) { return ""; }
        });
        var context = new Context(Locale.JAPAN);
        context.setVariables(Map.of("active", "attendance", "admin", admin, "sales", sales));
        return engine.process("fragments/sidebar", Set.of("sidebar"), context);
    }

    @Test void sharedNavigationRetainsEmployeeAndSalesBoundaries() {
        String employee = navigation(false, false);
        assertTrue(employee.contains("href=\"/attendance\""));
        assertTrue(employee.contains("href=\"/notices\""));
        assertTrue(employee.contains("href=\"/my-page\""));
        assertTrue(employee.contains("action=\"/logout\""));
        for (String url : List.of("/home", "/sales", "/users", "/departments", "/sessions", "/console"))
            assertFalse(employee.contains("href=\"" + url + "\""), url);
        assertTrue(navigation(false, true).contains("href=\"/sales\""));
        assertFalse(navigation(false, true).contains("href=\"/users\""));
        assertTrue(navigation(true, true).contains("href=\"/departments\""));
    }

    @Test void sharedNavigationProvidesSkipLinkCurrentPageAndPostLogout() {
        String html = navigation(true, true);
        assertTrue(html.contains("href=\"#mainContent\""));
        assertTrue(html.contains("id=\"portalNavigation\""));
        assertEquals(1, html.split("aria-current=\"page\"", -1).length - 1);
        assertTrue(html.contains("method=\"post\""));
        assertTrue(html.contains("data-logout"));
        assertTrue(html.contains("data-navigation-close"));
        assertTrue(html.contains("data-navigation-backdrop"));
    }

    @Test void authenticatedPagesUseSharedAssetsAndKeepJqueryAheadOfNavigation() throws Exception {
        for (String page : List.of("home", "users", "user-form", "sales", "departments", "attendance", "attendance-history", "attendance-manage", "notices", "my-page")) {
            String html = resource("templates/" + page + ".html");
            assertTrue(html.contains("/css/portal-layout.css"), page);
            assertTrue(html.contains("/css/portal-components.css"), page);
            assertTrue(html.contains("fragments/portal :: header"), page);
            assertTrue(html.contains("fragments/portal :: feedback"), page);
            assertTrue(html.contains("id=\"mainContent\" tabindex=\"-1\""), page);
            assertTrue(html.indexOf("jquery-3.7.1.min.js") < html.indexOf("/js/portal-navigation.js"), page);
            if (!page.equals("users")) assertFalse(html.contains("/css/users.css"), page);
        }
        assertFalse(resource("templates/log-in.html").contains("/css/portal-layout.css"));
        assertTrue(resource("templates/log-in.html").contains("fragments/portal :: feedback"));
        String shared = resource("templates/fragments/portal.html");
        assertTrue(shared.contains("/css/interaction-feedback.css"));
        assertTrue(shared.contains("/js/interaction-feedback.js"));
        assertTrue(shared.contains("defer"));
    }

    @Test void sharedStylesOwnShellAndMobileLogoutHasNoHideRule() throws Exception {
        for (String page : List.of("users", "home")) {
            String css = resource("static/css/" + page + ".css");
            for (String shared : List.of(":root", ".sidebar", ".topbar", ".content")) assertFalse(css.contains(shared + " {"), page + shared);
        }
        String css = resource("static/css/portal-layout.css");
        assertFalse(css.matches("(?s).*\\.sidebar-bottom[^}]*display:\\s*none.*"));
        assertTrue(css.contains("prefers-reduced-motion"));
        assertTrue(css.contains(".navigation-ready .sidebar"));
    }
}
