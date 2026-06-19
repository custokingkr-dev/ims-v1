package com.custoking.ims.security;

import com.custoking.ims.repo.AppUserRepository;
import com.custoking.ims.service.RbacService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;
    private final RbacService rbacService;

    public AppUserDetailsService(AppUserRepository userRepository, RbacService rbacService) {
        this.userRepository = userRepository;
        this.rbacService = rbacService;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmailIgnoreCase(email)
                .map(user -> new AppUserDetails(user, rbacService.getUserPermissions(user.getId())))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }
}
