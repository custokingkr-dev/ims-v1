package com.custoking.ims.platformservice.application;

public interface NotificationDeliveryProvider {

    void deliver(NotificationDeliveryRequest request);
}
