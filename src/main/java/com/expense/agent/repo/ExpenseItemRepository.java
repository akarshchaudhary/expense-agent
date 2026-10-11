package com.expense.agent.repo;

import com.expense.agent.domain.ExpenseItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ExpenseItemRepository extends JpaRepository<ExpenseItem, UUID> {
    List<ExpenseItem> findByClaimIdOrderByCreatedAtAsc(UUID claimId);
}
