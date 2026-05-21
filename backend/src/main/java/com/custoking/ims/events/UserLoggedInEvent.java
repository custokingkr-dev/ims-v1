package com.custoking.ims.events;

import org.springframework.context.ApplicationEvent;

/** Published on successful user login. */
public class UserLoggedInEvent extends ApplicationEvent {

    private final long userId;
    private final String email;
    private final String ipAddress;

    public UserLoggedInEvent(Object source, long userId, String email, String ipAddress) {
        super(source);
        this.userId = userId;
        this.email = email;
        this.ipAddress = ipAddress;
    }

    public long getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getIpAddress() { return ipAddress; }
}
