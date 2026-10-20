package com.custoking.ims.billingservice.api.dto;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.LinkedHashMap;
public record CreateBillingCustomerRequest(
        @Size(max=255) String code, @NotBlank @Size(max=255) String name,
        @Email @Size(max=255) String email, @Size(max=255) String phone,
        @Size(max=255) String gstin, @Size(max=2000) String addressLine,
        @Positive Long branchId, @Size(max=255) String branchName, Boolean active) {
    public Map<String,Object> toMap() {
        Map<String,Object> result=new LinkedHashMap<>();
        Object[] keys={"code",code,"name",name,"email",email,"phone",phone,"gstin",gstin,"addressLine",addressLine,"branchId",branchId,"branchName",branchName,"active",active};
        for(int i=0;i<keys.length;i+=2)if(keys[i+1]!=null)result.put((String)keys[i],keys[i+1]);return result;
    }
}
