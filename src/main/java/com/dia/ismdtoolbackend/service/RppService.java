package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.RppSearchResultDto;

import java.util.List;

public interface RppService {
    List<RppSearchResultDto> searchAgendas(String q, int limit);
    List<RppSearchResultDto> searchIsvs(String q, int limit, String preferredAgendaCode);
}
