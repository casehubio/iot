package io.casehub.iot.bridge.persistence.jpa;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class JpaBridgeAuditStore implements BridgeAuditStore {

    private final EntityManager em;

    @Inject
    public JpaBridgeAuditStore(final EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public void save(final BridgeAuditEvent event) {
        em.persist(BridgeAuditEventMapper.toEntity(event));
    }

    @Override
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<BridgeAuditEvent> query(final BridgeAuditQuery query) {
        final CriteriaBuilder cb = em.getCriteriaBuilder();
        final CriteriaQuery<BridgeAuditJpaEntity> cq = cb.createQuery(BridgeAuditJpaEntity.class);
        final Root<BridgeAuditJpaEntity> root = cq.from(BridgeAuditJpaEntity.class);

        final List<Predicate> predicates = new ArrayList<>();

        if (query.tenancyId() != null) {
            predicates.add(cb.equal(root.get(BridgeAuditJpaEntity_.tenancyId), query.tenancyId()));
        }
        if (query.eventType() != null) {
            predicates.add(cb.equal(root.get(BridgeAuditJpaEntity_.eventType), query.eventType()));
        }
        if (query.deviceId() != null) {
            predicates.add(cb.equal(root.get(BridgeAuditJpaEntity_.deviceId), query.deviceId()));
        }
        if (query.correlationId() != null) {
            predicates.add(cb.equal(root.get(BridgeAuditJpaEntity_.correlationId), query.correlationId()));
        }
        if (query.from() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get(BridgeAuditJpaEntity_.receivedAt), query.from()));
        }
        if (query.to() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get(BridgeAuditJpaEntity_.receivedAt), query.to()));
        }

        cq.where(predicates.toArray(Predicate[]::new));
        cq.orderBy(cb.desc(root.get(BridgeAuditJpaEntity_.receivedAt)));

        return em.createQuery(cq)
            .setFirstResult(query.offset())
            .setMaxResults(query.limit())
            .getResultList()
            .stream()
            .map(BridgeAuditEventMapper::toDomain)
            .toList();
    }
}
