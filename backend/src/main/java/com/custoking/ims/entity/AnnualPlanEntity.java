package com.custoking.ims.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "annual_plan_entries")
public class AnnualPlanEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String termName;
    private String category;
    private String status;
    private String quantity;
    private long amount;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTermName() { return termName; }
    public void setTermName(String termName) { this.termName = termName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getQuantity() { return quantity; }
    public void setQuantity(String quantity) { this.quantity = quantity; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
}
