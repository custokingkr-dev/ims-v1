package com.custoking.ims.billingservice.persistence;

import com.custoking.ims.billingservice.api.dto.CreateBillingPaymentRequest;
import com.custoking.ims.billingservice.security.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class BillingPaymentSecurityIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static HikariDataSource pool;
    static JdbcClient jdbc;
    static BillingInvoiceRepository repo;
    static TransactionTemplate tx;
    static com.custoking.ims.billingservice.application.BillingInvoiceService service;
    @BeforeAll static void setup() {
        pg=new PostgreSQLContainer<>("postgres:16"); pg.start();
        Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).schemas("billing").defaultSchema("billing").locations("classpath:db/migration").load().migrate();
        pool=new HikariDataSource(); pool.setJdbcUrl(pg.getJdbcUrl()); pool.setUsername(pg.getUsername()); pool.setPassword(pg.getPassword()); pool.setMaximumPoolSize(4);
        jdbc=JdbcClient.create(pool); repo=new BillingInvoiceRepository(jdbc,"billing"); tx=new TransactionTemplate(new JdbcTransactionManager(pool));
        service=new com.custoking.ims.billingservice.application.BillingInvoiceService(repo,new com.custoking.ims.billingservice.outbox.OutboxWriter(jdbc,"billing"),new tools.jackson.databind.ObjectMapper());
        jdbc.sql("INSERT INTO billing.billing_customers(code,name,branch_id,branch_name) VALUES ('SECURITY_TEST','Customer',10,'Canonical branch')").update();
    }
    @AfterAll static void stop() { if(pool!=null)pool.close(); if(pg!=null)pg.stop(); }
    @BeforeEach void actor() { TenantContext.set(new TenantContext(42L,"admin@example.test","SUPERADMIN",null,null)); }
    @AfterEach void clear() { TenantContext.clear(); }
    long invoice(long total) {
        return jdbc.sql("INSERT INTO billing.billing_invoices(invoice_no,customer_id,branch_id,branch_name,invoice_date,due_date,subtotal,discount_amount,tax_amount,grand_total,balance_amount,status,payment_status,approval_status) SELECT :number,id,10,'Canonical branch',CURRENT_DATE,CURRENT_DATE,:total,0,0,:total,:total,'ISSUED','UNPAID','APPROVED' FROM billing.billing_customers WHERE code='SECURITY_TEST' RETURNING id")
                .param("number",UUID.randomUUID().toString()).param("total",total).query(Long.class).single();
    }
    CreateBillingPaymentRequest payment(long invoice,long amount,String key) { return new CreateBillingPaymentRequest(invoice,amount,null,"UPI","reference",null,key); }
    BillingInvoiceRepository.PaymentRow pay(CreateBillingPaymentRequest request) { return tx.execute(s -> service.createBillingPayment(request)); }
    long count(long id) { return jdbc.sql("SELECT count(*) FROM billing.billing_payments WHERE invoice_id=:id").param("id",id).query(Long.class).single(); }
    @Test void replayStableAndChangedRequestConflicts() {
        long id=invoice(1000); var request=payment(id,250,"stable-key"); var first=pay(request);
        assertThat(pay(request)).isEqualTo(first);
        assertThatThrownBy(() -> pay(payment(id,251,"stable-key"))).isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException)ex).getStatusCode().value()).isEqualTo(409));
        assertThat(count(id)).isEqualTo(1); assertThat(repo.schoolInvoice(id)).containsEntry("paidAmount",250L).containsEntry("balanceAmount",750L);
        assertThat(first.branchId()).isEqualTo(10); assertThat(first.branchName()).isEqualTo("Canonical branch"); assertThat(first.receivedBy()).isEqualTo("admin@example.test");
        assertThat(jdbc.sql("SELECT count(*) FROM billing.outbox_events WHERE event_key=:key").param("key","BillingPaymentRecorded:"+first.id()).query(Long.class).single()).isEqualTo(1);
    }
    @Test void scopedKeyCanBeUsedForDifferentInvoice() {
        long one=invoice(1000),two=invoice(1000); pay(payment(one,100,"shared-key")); pay(payment(two,200,"shared-key")); assertThat(count(one)).isEqualTo(1); assertThat(count(two)).isEqualTo(1);
    }
    @Test void negativeZeroOversizedAndFutureInputsHaveNoSideEffects() {
        long id=invoice(1000);
        for(long amount:new long[]{-1,0,1001,Long.MAX_VALUE}) assertThatThrownBy(() -> pay(payment(id,amount,"invalid-key"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pay(new CreateBillingPaymentRequest(id,100L,LocalDate.now().plusDays(1),"UPI",null,null,"future-key"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count(id)).isZero(); assertThat(repo.schoolInvoice(id)).containsEntry("paidAmount",0L).containsEntry("balanceAmount",1000L);
    }
    @Test void everyNonSuperadminRoleDeniedWithoutSideEffects() {
        long id=invoice(1000);
        for(String role:List.of("ADMIN","SCHOOL_ADMIN","OPERATIONS","PRINCIPAL","TEACHER")) {
            TenantContext.set(new TenantContext(2L,"restricted@x",role,10L,null));
            assertThatThrownBy(() -> pay(payment(id,100,"denied-key"))).isInstanceOf(ResponseStatusException.class);
        }
        TenantContext.clear(); assertThatThrownBy(() -> pay(payment(id,100,"denied-key"))).isInstanceOf(ResponseStatusException.class); assertThat(count(id)).isZero();
    }
    @Test void competingPaymentsCannotOverpay() throws Exception {
        long id=invoice(1000); CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> one=() -> { actor(); start.await(); try { pay(payment(id,700,"concurrent-one")); return true; } catch(IllegalArgumentException ex){return false;} finally{TenantContext.clear();} };
            Callable<Boolean> two=() -> { actor(); start.await(); try { pay(payment(id,700,"concurrent-two")); return true; } catch(IllegalArgumentException ex){return false;} finally{TenantContext.clear();} };
            var a=executor.submit(one);var b=executor.submit(two);start.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(count(id)).isEqualTo(1);assertThat(repo.schoolInvoice(id)).containsEntry("paidAmount",700L).containsEntry("balanceAmount",300L);
    }
    @Test void concurrentSameKeyReturnsSamePayment() throws Exception {
        long id=invoice(1000); CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Long> call=() -> {actor();start.await();try{return pay(payment(id,700,"concurrent-key")).id();}finally{TenantContext.clear();}};
            var a=executor.submit(call);var b=executor.submit(call);start.countDown();assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(count(id)).isEqualTo(1);
    }
    @Test void insertFailureRollsBackAndSqlTextIsData() {
        long id=invoice(1000);
        assertThatThrownBy(() -> pay(new CreateBillingPaymentRequest(id,100L,null,"UPI","x".repeat(256),null,"rollback-key"))).isInstanceOf(RuntimeException.class);
        assertThat(count(id)).isZero();assertThat(repo.schoolInvoice(id)).containsEntry("paidAmount",0L);
        String text="'); DROP TABLE billing.billing_payments; --";
        assertThat(pay(new CreateBillingPaymentRequest(id,100L,null,"UPI",text,text,"injection-key")).notes()).isEqualTo(text);
    }
    @Test void decimalRoundingAndOverflowKeepHistoricalLedgerUnits() {
        var customer=jdbc.sql("SELECT id FROM billing.billing_customers WHERE code='SECURITY_TEST'").query(Long.class).single();
        var request=Map.<String,Object>of("customerId",customer,"discountPercent",new java.math.BigDecimal("12.50"),"items",List.of(Map.of("description","item","quantity",1,"unitPrice",101,"taxRate",new java.math.BigDecimal("12.50"))));
        var invoice=tx.execute(s -> repo.createSchoolInvoice(request));
        assertThat(invoice).containsEntry("subtotal",101L).containsEntry("discountAmount",13L).containsEntry("taxAmount",13L).containsEntry("grandTotal",101L);
        assertThatThrownBy(() -> tx.execute(s -> repo.createSchoolInvoice(Map.of("customerId",customer,"items",List.of(Map.of("quantity",2,"unitPrice",Long.MAX_VALUE)))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.execute(s -> repo.createSchoolInvoice(Map.of("customerId",customer,"items",List.of(Map.of("quantity",1,"unitPrice",1,"taxRate","NaN")))))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void authoritativeAuditFailureRollsBackPaymentAndBalance() {
        long id=invoice(1000);
        jdbc.sql("CREATE FUNCTION billing.reject_security_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled audit failure'; END $$").update();
        jdbc.sql("CREATE TRIGGER security_audit_failure BEFORE INSERT ON billing.outbox_events FOR EACH ROW EXECUTE FUNCTION billing.reject_security_audit()").update();
        try {
            assertThatThrownBy(() -> pay(payment(id,100,"audit-rollback"))).isInstanceOf(RuntimeException.class);
            assertThat(count(id)).isZero();assertThat(repo.schoolInvoice(id)).containsEntry("paidAmount",0L).containsEntry("balanceAmount",1000L);
        } finally {
            jdbc.sql("DROP TRIGGER security_audit_failure ON billing.outbox_events").update();
            jdbc.sql("DROP FUNCTION billing.reject_security_audit()").update();
        }
    }
}
