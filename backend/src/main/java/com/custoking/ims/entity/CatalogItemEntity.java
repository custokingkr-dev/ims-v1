package com.custoking.ims.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "catalog_items")
public class CatalogItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "catalog_item_seq")
    @SequenceGenerator(name = "catalog_item_seq", sequenceName = "seq_catalog_items", allocationSize = 1)
    private Long id;
    private String title;
    private String subtitle;
    private String icon;
    private String orderType;
    private long sampleAmount;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public long getSampleAmount() { return sampleAmount; }
    public void setSampleAmount(long sampleAmount) { this.sampleAmount = sampleAmount; }
}
