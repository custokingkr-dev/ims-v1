package com.custoking.ims.dto.attendance;

public record SubmitAttendanceDayRequest(String date) {

    public String effectiveDate() {
        return date == null || date.isBlank() ? "today" : date;
    }
}
