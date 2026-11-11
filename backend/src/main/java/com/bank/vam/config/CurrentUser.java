package com.bank.vam.config;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who is acting: the authenticated user's name, or "system" when nobody is signed in. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static String name() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // permitAll in dev gives every request an anonymous token; that is nobody, not a user.
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return "system";
        }
        return auth.getName();
    }

    /** {@code userId} when given, else {@link #name()}. */
    public static String orCurrent(String userId) {
        return userId == null || userId.isBlank() ? name() : userId;
    }
}
