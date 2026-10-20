package com.custoking.ims.billingservice.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateInvoiceRequest(
        @NotBlank String school,
        String orderRef,
        @jakarta.validation.constraints.Positive Long schoolId,
        @jakarta.validation.constraints.Size(max=500) String description,
        @tools.jackson.databind.annotation.JsonDeserialize(using=StrictLedgerIntegerDeserializer.class) @jakarta.validation.constraints.Positive Integer qty,
        @tools.jackson.databind.annotation.JsonDeserialize(using=StrictLedgerLongDeserializer.class) @jakarta.validation.constraints.PositiveOrZero Long rate,
        @tools.jackson.databind.annotation.JsonDeserialize(using=StrictLedgerLongDeserializer.class) @jakarta.validation.constraints.PositiveOrZero Long amount,
        @jakarta.validation.constraints.Size(max=2000) String notes) {
    public java.util.Map<String,Object> toMap() {
        var m=new java.util.LinkedHashMap<String,Object>();
        Object[] fields={"school",school,"orderRef",orderRef,"schoolId",schoolId,"description",description,"qty",qty,"rate",rate,"amount",amount,"notes",notes};
        for(int i=0;i<fields.length;i+=2)if(fields[i+1]!=null)m.put((String)fields[i],fields[i+1]);return m;
    }
}
