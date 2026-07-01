package com.custoking.ims.identityservice.api.dto;

import java.util.List;

public record UpdateRoleRequest(String description, List<String> permissions, Long actorId) {}
