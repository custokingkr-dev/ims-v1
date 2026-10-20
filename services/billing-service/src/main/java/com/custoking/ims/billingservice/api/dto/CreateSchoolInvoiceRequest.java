package com.custoking.ims.billingservice.api.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
public record CreateSchoolInvoiceRequest(
        @NotNull @Positive Long customerId,
        @Positive Long branchId, @Size(max=255) String branchName,
        LocalDate invoiceDate, LocalDate dueDate,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal discountPercent,
        @Size(max=2000) String notes,
        @NotEmpty @Size(max=500) List<@NotNull @Valid Item> items) {
    public record Item(@NotBlank @Size(max=255) String description,
                       @tools.jackson.databind.annotation.JsonDeserialize(using=StrictLedgerLongDeserializer.class) @NotNull @Positive Long quantity,
                       @tools.jackson.databind.annotation.JsonDeserialize(using=StrictLedgerLongDeserializer.class) @NotNull @PositiveOrZero Long unitPrice,
                       @DecimalMin("0") @DecimalMax("100") @Digits(integer=3,fraction=2) BigDecimal taxRate) {
        Map<String,Object> toMap(){Map<String,Object> m=new LinkedHashMap<>();m.put("description",description);m.put("quantity",quantity);m.put("unitPrice",unitPrice);if(taxRate!=null)m.put("taxRate",taxRate);return m;}
    }
    public Map<String,Object> toMap(){
        Map<String,Object> m=new LinkedHashMap<>();m.put("customerId",customerId);
        if(branchId!=null)m.put("branchId",branchId);if(branchName!=null)m.put("branchName",branchName);
        if(invoiceDate!=null)m.put("invoiceDate",invoiceDate.toString());if(dueDate!=null)m.put("dueDate",dueDate.toString());
        if(discountPercent!=null)m.put("discountPercent",discountPercent);if(notes!=null)m.put("notes",notes);
        m.put("items",items.stream().map(Item::toMap).toList());return m;
    }
}
