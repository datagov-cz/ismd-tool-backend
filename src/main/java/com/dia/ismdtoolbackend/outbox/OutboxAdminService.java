package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.outbox.dto.OutboxEntryDto;
import com.dia.ismdtoolbackend.outbox.dto.OutboxStatusDto;

import java.util.List;

public interface OutboxAdminService {
    OutboxStatusDto status();
    List<OutboxEntryDto> failed();
    int drainNow();
    boolean retry(Long id);
}
