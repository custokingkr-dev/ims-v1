package com.custoking.ims.firefighting.domain;

import com.custoking.ims.common.domain.FirefightingRequestStatus;
import com.custoking.ims.common.domain.UserRole;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.entity.FirefightingRequestEntity;
import com.custoking.ims.repo.FirefightingRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class FirefightingDomainService {

    private final FirefightingRequestRepository firefightingRequestRepository;

    public FirefightingDomainService(FirefightingRequestRepository firefightingRequestRepository) {
        this.firefightingRequestRepository = firefightingRequestRepository;
    }

    public void validateStatusTransition(FirefightingRequestEntity request, FirefightingRequestStatus newStatus) {
        FirefightingRequestStatus currentStatus = FirefightingRequestStatus.valueOf(request.getStatus());
        if (!currentStatus.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                String.format("Cannot transition firefighting request %s from %s to %s",
                    request.getCode(), currentStatus, newStatus));
        }
    }

    public void submitRequest(FirefightingRequestEntity request) {
        validateStatusTransition(request, FirefightingRequestStatus.SUBMITTED);
        request.setStatus(FirefightingRequestStatus.SUBMITTED.name());
        firefightingRequestRepository.save(request);
    }

    public void sendForAdminApproval(FirefightingRequestEntity request) {
        validateStatusTransition(request, FirefightingRequestStatus.AWAITING_ADMIN_APPROVAL);
        request.setStatus(FirefightingRequestStatus.AWAITING_ADMIN_APPROVAL.name());
        firefightingRequestRepository.save(request);
    }

    public void approveRequest(FirefightingRequestEntity request) {
        if (!FirefightingRequestStatus.valueOf(request.getStatus()).canBeApprovedByAdmin()) {
            throw new IllegalStateException("Request is not in a state that can be approved by admin");
        }
        validateStatusTransition(request, FirefightingRequestStatus.APPROVED);
        request.setStatus(FirefightingRequestStatus.APPROVED.name());
        firefightingRequestRepository.save(request);
    }

    public void rejectRequest(FirefightingRequestEntity request) {
        validateStatusTransition(request, FirefightingRequestStatus.REJECTED);
        request.setStatus(FirefightingRequestStatus.REJECTED.name());
        firefightingRequestRepository.save(request);
    }

    public void addQuotation(FirefightingRequestEntity request) {
        validateStatusTransition(request, FirefightingRequestStatus.QUOTATION_ADDED);
        request.setStatus(FirefightingRequestStatus.QUOTATION_ADDED.name());
        firefightingRequestRepository.save(request);
    }

    public void fulfillRequest(FirefightingRequestEntity request) {
        if (!FirefightingRequestStatus.valueOf(request.getStatus()).canBeFulfilledBySuperadmin()) {
            throw new IllegalStateException("Request is not in a state that can be fulfilled by superadmin");
        }
        validateStatusTransition(request, FirefightingRequestStatus.FULFILLED_BY_CUSTOKING);
        request.setStatus(FirefightingRequestStatus.FULFILLED_BY_CUSTOKING.name());
        firefightingRequestRepository.save(request);
    }

    public void cancelRequest(FirefightingRequestEntity request) {
        FirefightingRequestStatus currentStatus = FirefightingRequestStatus.valueOf(request.getStatus());
        if (currentStatus.canTransitionTo(FirefightingRequestStatus.CANCELLED)) {
            request.setStatus(FirefightingRequestStatus.CANCELLED.name());
            firefightingRequestRepository.save(request);
        } else {
            throw new IllegalStateException("Request cannot be cancelled from status: " + currentStatus);
        }
    }

    public List<FirefightingRequestEntity> findRequestsByStatus(Long schoolId, FirefightingRequestStatus status) {
        return firefightingRequestRepository.findBySchool_IdAndStatus(schoolId, status.name());
    }

    public List<FirefightingRequestEntity> findPendingApprovals(Long schoolId) {
        return firefightingRequestRepository.findBySchool_IdAndStatus(schoolId,
            FirefightingRequestStatus.AWAITING_ADMIN_APPROVAL.name());
    }

    public List<FirefightingRequestEntity> findAwaitingFulfillment() {
        return firefightingRequestRepository.findByStatus(
            FirefightingRequestStatus.QUOTATION_ADDED.name());
    }

    public boolean canUserApproveRequest(UserRole userRole, FirefightingRequestEntity request) {
        var scope = TenantContext.getScope();
        if (scope != null && scope.isSuperadmin()) {
            return true;
        }
        if (userRole == UserRole.APPROVER) {
            return FirefightingRequestStatus.valueOf(request.getStatus()).canBeApprovedByAdmin();
        }
        return false;
    }

    public boolean canUserFulfillRequest(UserRole userRole, FirefightingRequestEntity request) {
        var scope = TenantContext.getScope();
        return scope != null && scope.isSuperadmin()
               && FirefightingRequestStatus.valueOf(request.getStatus()).canBeFulfilledBySuperadmin();
    }
}