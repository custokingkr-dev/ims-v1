package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "import_batches")
public class ImportBatchEntity {
    @Id
    private String id;
    @Column(unique = true)
    private String fileToken;
    @Column(unique = true)
    private String jobId;
    private int totalRows;
    private int validCount;
    private int errorCount;
    private int warningCount;
    private String status;
    private int pct;
    private int inserted;
    private int skipped;
    @Lob
    private String skippedJson;
    private OffsetDateTime createdAt = OffsetDateTime.now();
    private OffsetDateTime completedAt;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFileToken() { return fileToken; }
    public void setFileToken(String fileToken) { this.fileToken = fileToken; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public int getValidCount() { return validCount; }
    public void setValidCount(int validCount) { this.validCount = validCount; }
    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int errorCount) { this.errorCount = errorCount; }
    public int getWarningCount() { return warningCount; }
    public void setWarningCount(int warningCount) { this.warningCount = warningCount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getPct() { return pct; }
    public void setPct(int pct) { this.pct = pct; }
    public int getInserted() { return inserted; }
    public void setInserted(int inserted) { this.inserted = inserted; }
    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }
    public String getSkippedJson() { return skippedJson; }
    public void setSkippedJson(String skippedJson) { this.skippedJson = skippedJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
