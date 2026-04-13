package com.custoking.ims.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "superadmin_order_seq")
public class SuperadminOrderSeqEntity {
    @Id
    private String id = "SINGLETON";
    private long orderSeq = 0;
    private long invoiceSeq = 0;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public long getOrderSeq() { return orderSeq; }
    public void setOrderSeq(long orderSeq) { this.orderSeq = orderSeq; }
    public long getInvoiceSeq() { return invoiceSeq; }
    public void setInvoiceSeq(long invoiceSeq) { this.invoiceSeq = invoiceSeq; }
}
