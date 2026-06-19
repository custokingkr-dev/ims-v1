package com.custoking.ims.security;

import com.custoking.ims.entity.AppUserEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class AppUserDetails implements UserDetails {

    private final AppUserEntity user;
    /**
     * Effective permission codes loaded from the RBAC tables at authentication time.
     * Cached here so RbacService.hasPermission() avoids a DB query on every request.
     * Empty set means "not yet loaded" — RbacService will fall back to the DB.
     */
    private final Set<String> permissions;

    public AppUserDetails(AppUserEntity user) {
        this(user, Set.of());
    }

    public AppUserDetails(AppUserEntity user, Set<String> permissions) {
        this.user = user;
        this.permissions = permissions != null ? Set.copyOf(permissions) : Set.of();
    }

    public AppUserEntity getUser() {
        return user;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (!permissions.isEmpty()) {
            return permissions.stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }
}
