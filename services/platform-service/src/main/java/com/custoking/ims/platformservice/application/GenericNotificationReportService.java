package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.GenericNotificationReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GenericNotificationReportService {
    private final GenericNotificationReportRepository reports;
    private final TransactionTemplate transaction;
    public GenericNotificationReportService(GenericNotificationReportRepository reports, PlatformTransactionManager manager) {
        this.reports = reports; transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); transaction.setTimeout(10);
    }
    public void reconcile(GenericNotificationReport report) {
        transaction.executeWithoutResult(tx -> reports.reconcile(report));
    }
}
