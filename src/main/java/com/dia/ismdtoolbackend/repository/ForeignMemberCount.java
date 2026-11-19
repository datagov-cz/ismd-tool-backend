package com.dia.ismdtoolbackend.repository;

/**
 * Foreign property/relationship counts for one concept. A null component means none
 * (the field is omitted from the response rather than serialized as 0).
 */
public record ForeignMemberCount(Integer properties, Integer relationships) {
}
