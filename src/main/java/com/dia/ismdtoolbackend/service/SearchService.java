package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.SearchResponseDto;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;

import java.util.List;

public interface SearchService {

    SearchResponseDto search(String query, SearchType type, SearchSource source,
                             int limit, int offset, String lang,
                             List<String> ontologyIris, List<RelationType> relationTypes,
                             SecurityUser user);
}
