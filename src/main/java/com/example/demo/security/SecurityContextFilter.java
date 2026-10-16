package com.example.demo.security;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;

public class SecurityContextFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityRequestContext.begin(request);
        response.setHeader("X-Request-ID", SecurityRequestContext.current().correlationId());
        try { chain.doFilter(request, response); } finally { SecurityRequestContext.clear(); }
    }
}
