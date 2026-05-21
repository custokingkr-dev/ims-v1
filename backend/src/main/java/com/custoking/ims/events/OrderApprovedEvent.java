package com.custoking.ims.events;

import org.springframework.context.ApplicationEvent;

/** Published when a supply order or firefighting request is approved. */
public class OrderApprovedEvent extends ApplicationEvent {

    public enum OrderType { SUPPLY, FIREFIGHTING }

    private final OrderType orderType;
    private final String orderCode;
    private final long schoolId;
    private final Long approvedBy;

    public OrderApprovedEvent(Object source, OrderType orderType, String orderCode,
                               long schoolId, Long approvedBy) {
        super(source);
        this.orderType = orderType;
        this.orderCode = orderCode;
        this.schoolId = schoolId;
        this.approvedBy = approvedBy;
    }

    public OrderType getOrderType() { return orderType; }
    public String getOrderCode() { return orderCode; }
    public long getSchoolId() { return schoolId; }
    public Long getApprovedBy() { return approvedBy; }
}
