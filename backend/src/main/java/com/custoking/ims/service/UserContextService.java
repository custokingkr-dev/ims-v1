package com.custoking.ims.service;

import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.model.Role;
import com.custoking.ims.security.AppUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserContextService {

    public AuthUser requireUser(String ignored) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails details)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        AppUserEntity user = details.getUser();
        return new AuthUser(user.getId(), user.getFullName(), user.getEmail(),
                Role.valueOf(user.getRole()), user.getBranchId(), user.getBranchName(), null);
    }

    public AuthUser requireSuperAdmin(String authorization) {
        AuthUser user = requireUser(authorization);
        if (user.role() != Role.SUPERADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN access required");
        }
        return user;
    }
}
