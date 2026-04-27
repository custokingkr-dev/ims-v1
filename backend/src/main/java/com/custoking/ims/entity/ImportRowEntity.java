package com.custoking.ims.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "import_rows")
public class ImportRowEntity {
    @Id private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "batch_id") private ImportBatchEntity batch;
    @Column(name = "row_no")
    private int rowNumber;
    private String name;
    private String className;
    private String sectionName;
    private String admissionNo;
    private String phone;
    private String status;
    @Column(columnDefinition = "TEXT") private String message;
    @Column(name = "raw_json", columnDefinition = "TEXT") private String rawJson;
    @Column(name = "normalized_json", columnDefinition = "TEXT") private String normalizedJson;
    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public ImportBatchEntity getBatch() { return batch; } public void setBatch(ImportBatchEntity batch) { this.batch = batch; }
    public int getRowNumber() { return rowNumber; } public void setRowNumber(int rowNumber) { this.rowNumber = rowNumber; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public String getClassName() { return className; } public void setClassName(String className) { this.className = className; }
    public String getSectionName() { return sectionName; } public void setSectionName(String sectionName) { this.sectionName = sectionName; }
    public String getAdmissionNo() { return admissionNo; } public void setAdmissionNo(String admissionNo) { this.admissionNo = admissionNo; }
    public String getPhone() { return phone; } public void setPhone(String phone) { this.phone = phone; }
    public String getStatus() { return status; } public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; } public void setMessage(String message) { this.message = message; }
    public String getRawJson() { return rawJson; } public void setRawJson(String rawJson) { this.rawJson = rawJson; }
    public String getNormalizedJson() { return normalizedJson; } public void setNormalizedJson(String normalizedJson) { this.normalizedJson = normalizedJson; }
}
