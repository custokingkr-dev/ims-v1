package com.custoking.ims.catalog.domain;

import com.custoking.ims.common.domain.OrderStatus;
import com.custoking.ims.entity.CatalogOrderEntity;
import com.custoking.ims.repo.CatalogOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class CatalogDomainService {

    private final CatalogOrderRepository catalogOrderRepository;

    public CatalogDomainService(CatalogOrderRepository catalogOrderRepository) {
        this.catalogOrderRepository = catalogOrderRepository;
    }

    public void validateOrderStatusTransition(CatalogOrderEntity order, OrderStatus newStatus) {
        OrderStatus currentStatus = OrderStatus.valueOf(order.getStatus());
        if (!currentStatus.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                String.format("Cannot transition order %s from %s to %s",
                    order.getId(), currentStatus, newStatus));
        }
    }

    public void submitOrder(CatalogOrderEntity order) {
        validateOrderStatusTransition(order, OrderStatus.SUBMITTED);
        order.setStatus(OrderStatus.SUBMITTED.name());
        catalogOrderRepository.save(order);
    }

    public void approveOrder(CatalogOrderEntity order) {
        validateOrderStatusTransition(order, OrderStatus.APPROVED);
        order.setStatus(OrderStatus.APPROVED.name());
        catalogOrderRepository.save(order);
    }

    public void startOrderProcessing(CatalogOrderEntity order) {
        validateOrderStatusTransition(order, OrderStatus.IN_PROGRESS);
        order.setStatus(OrderStatus.IN_PROGRESS.name());
        catalogOrderRepository.save(order);
    }

    public void markOrderReadyForDelivery(CatalogOrderEntity order) {
        validateOrderStatusTransition(order, OrderStatus.READY_FOR_DELIVERY);
        order.setStatus(OrderStatus.READY_FOR_DELIVERY.name());
        catalogOrderRepository.save(order);
    }

    public void deliverOrder(CatalogOrderEntity order) {
        validateOrderStatusTransition(order, OrderStatus.DELIVERED);
        order.setStatus(OrderStatus.DELIVERED.name());
        catalogOrderRepository.save(order);
    }

    public void cancelOrder(CatalogOrderEntity order) {
        OrderStatus currentStatus = OrderStatus.valueOf(order.getStatus());
        if (currentStatus.canTransitionTo(OrderStatus.CANCELLED)) {
            order.setStatus(OrderStatus.CANCELLED.name());
            catalogOrderRepository.save(order);
        } else {
            throw new IllegalStateException("Order cannot be cancelled from status: " + currentStatus);
        }
    }

    public List<CatalogOrderEntity> findOrdersByStatus(Long schoolId, OrderStatus status) {
        return catalogOrderRepository.findBySchool_IdAndStatus(schoolId, status.name());
    }

    public List<CatalogOrderEntity> findPendingOrders(Long schoolId) {
        return catalogOrderRepository.findBySchool_IdAndStatusIn(schoolId,
            List.of(OrderStatus.SUBMITTED.name(), OrderStatus.APPROVED.name(), OrderStatus.IN_PROGRESS.name()));
    }
}