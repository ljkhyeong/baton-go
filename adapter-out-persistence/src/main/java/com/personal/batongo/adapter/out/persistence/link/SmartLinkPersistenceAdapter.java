package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.domain.link.SmartLink;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class SmartLinkPersistenceAdapter implements SmartLinkRepository {

    private final SpringDataSmartLinkRepository repository;

    public SmartLinkPersistenceAdapter(SpringDataSmartLinkRepository repository) {
        this.repository = repository;
    }

    @Override
    public SmartLink save(SmartLink smartLink) {
        return repository.save(smartLink);
    }

    @Override
    public Optional<SmartLink> findById(UUID id) {
        return repository.findById(id);
    }

    @Override
    public Optional<SmartLink> findByIdForUpdate(UUID id) {
        return repository.findByIdForUpdate(id);
    }

    @Override
    public Optional<SmartLink> findByCodeHash(String codeHash) {
        return repository.findByCodeHash(codeHash);
    }
}
