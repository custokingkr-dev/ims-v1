package com.custoking.ims.schoolcoreservice.api.dto;

/** Body for POST /attendance/absentees/notify. All optional except date. */
public record NotifyAbsenteesRequest(String date, String sectionId, Long schoolId, Long actorId) {}
