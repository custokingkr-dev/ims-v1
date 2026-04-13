package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "supply_orders")
public class SupplyOrderEntity {
    @Id
    private String code;
    private String title;
    private String category;
    private String items;
    private long amount;
    private String status;
    private LocalDate orderDate;
    private String actionLabel;
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getItems() { return items; }
    public void setItems(String items) { this.items = items; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getOrderDate() { return orderDate; }
    public void setOrderDate(LocalDate orderDate) { this.orderDate = orderDate; }
    public String getActionLabel() { return actionLabel; }
    public void setActionLabel(String actionLabel) { this.actionLabel = actionLabel; }
}
