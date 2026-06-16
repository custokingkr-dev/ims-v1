package com.custoking.ims.dto.attendance;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.LinkedHashMap;
import java.util.Map;

public record DailyAttendanceRequest(
        @NotBlank String date,
        @NotBlank String classId,
        @NotBlank String sectionId,
        @Min(0) Integer totalEnrolled,
        @Min(0) Integer presentCount
) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("date", date);
        map.put("classId", classId);
        map.put("sectionId", sectionId);
        if (totalEnrolled != null) map.put("totalEnrolled", totalEnrolled);
        if (presentCount != null) map.put("presentCount", presentCount);
        return map;
    }
}
