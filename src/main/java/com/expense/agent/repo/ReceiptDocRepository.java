package com.expense.agent.repo;

import com.expense.agent.domain.ReceiptDoc;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptDocRepository extends JpaRepository<ReceiptDoc, UUID> {
    List<ReceiptDoc> findByClaimIdOrderByCreatedAtAsc(UUID claimId);
    Optional<ReceiptDoc> findByClaimIdAndFileHash(UUID claimId, String fileHash);
}
