package com.custoking.ims.notificationservice.application;

public interface NotificationDeliveryProvider {

    void deliver(NotificationDeliveryRequest request);
}
