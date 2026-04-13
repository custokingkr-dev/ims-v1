package com.custoking.ims.controller;

import com.custoking.ims.model.AuthUser;
import com.custoking.ims.service.DatabaseStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.custoking.ims.model.Role;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/fee-structure")
public class FeeStructureController {
    private final DatabaseStore store;

    public FeeStructureController(DatabaseStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestParam(required = false) String academicYearId) {
        store.requireUser(authorization);
        return store.feeStructureData(academicYearId);
    }

    @PostMapping("/item")
    public Map<String, Object> addItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.addFeeStructureItem(request, actor);
    }

    @PutMapping("/item/{id}")
    public Map<String, Object> updateItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.updateFeeStructureItem(id, request, actor);
    }

    @DeleteMapping("/item/{id}")
    public Map<String, Object> deleteItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.deleteFeeStructureItem(id, actor);
    }

    @PostMapping("/band")
    public Map<String, Object> createBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.createFeeStructureBand(request, actor);
    }

    @PutMapping("/band/{id}")
    public Map<String, Object> updateBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.updateFeeStructureBand(id, request, actor);
    }

    @DeleteMapping("/band/{id}")
    public Map<String, Object> deleteBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.deleteFeeStructureBand(id, actor);
    }

    @PatchMapping("/band/{id}")
    public Map<String, Object> patchBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String id,
                                         @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.patchFeeStructureBand(id, request, actor);
    }

    @GetMapping("/match")
    public Map<String, Object> matchBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestParam String classId) {
        store.requireUser(authorization);
        return store.matchFeeStructureBand(classId);
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> export(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestParam(required = false) String academicYearId,
                                         @RequestParam(defaultValue = "pdf") String format) {
        store.requireUser(authorization);
        byte[] pdf = store.exportFeeStructurePdf(academicYearId, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=fee-structure-" + (academicYearId == null ? "current" : academicYearId) + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }


    private void forbidSuperAdmin(AuthUser actor) {
        if (actor.role() == Role.SUPERADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        }
    }
}
