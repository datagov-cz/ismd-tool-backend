package com.dia.ismdtoolbackend.enums;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Every error the API returns: the stable code the FE branches on, the HTTP status it travels with, and
 * the message shown when the failure carries no more specific one. The constant name is the wire value.
 */
@Getter
public enum ErrorCode {

    // Request shape
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Požadavek má neplatný formát."),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "Chybí povinný parametr."),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Parametr má neplatnou hodnotu."),
    REQUEST_VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Neplatná data v požadavku."),
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "Požadavek obsahuje neplatnou hodnotu."),
    DATA_INTEGRITY_VIOLATION(HttpStatus.BAD_REQUEST,
            "Zápis porušuje omezení databáze — pravděpodobně již existuje záznam se stejnou hodnotou."),

    // Access
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Přihlášení je neplatné nebo vypršelo."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Přístup odepřen: nemáte oprávnění k této operaci."),

    // Generic
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Požadovaný záznam nebyl nalezen."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Nastala neočekávaná chyba."),
    IO_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Při čtení nebo zápisu dat došlo k chybě."),

    // Ontology
    ONTOLOGY_NOT_FOUND(HttpStatus.NOT_FOUND, "Slovník nebyl nalezen."),
    ONTOLOGY_ALREADY_EXISTS(HttpStatus.CONFLICT, "Slovník s tímto IRI již existuje."),
    ONTOLOGY_CREATION_CONFLICT(HttpStatus.CONFLICT, "Slovník se nepodařilo vytvořit kvůli kolizi s existujícím slovníkem."),
    ONTOLOGY_VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Slovník obsahuje neplatná data."),
    ONTOLOGY_PROCESSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Slovník se nepodařilo zpracovat."),
    ONTOLOGY_STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Slovník se nepodařilo uložit."),
    ONTOLOGY_ANALYSIS_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Analýza slovníku selhala."),
    ONTOLOGY_DOWNLOAD_BLOCKED_BY_VALIDATION(HttpStatus.BAD_REQUEST,
            "Slovník nelze stáhnout, protože obsahuje závažné chyby."),
    EXPORT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Export slovníku selhal."),

    // Upload
    UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Nahrání slovníku selhalo."),
    UPLOAD_FILE_EMPTY(HttpStatus.BAD_REQUEST, "Nahraný soubor je prázdný."),
    UPLOAD_FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Nahraný soubor překračuje maximální povolenou velikost."),
    UPLOAD_RDF_FORMAT_UNSUPPORTED(HttpStatus.BAD_REQUEST, "Formát nahraného souboru není podporován."),
    UPLOAD_RDF_PARSE_FAILED(HttpStatus.BAD_REQUEST, "Nahraný soubor se nepodařilo načíst jako RDF."),
    UPLOAD_RDF_PARSE_TIMEOUT(HttpStatus.UNPROCESSABLE_CONTENT, "Zpracování nahraného souboru trvalo příliš dlouho."),
    UPLOAD_DATA_EMPTY(HttpStatus.BAD_REQUEST, "Nahraný soubor neobsahuje žádná data."),
    UPLOAD_ONTOLOGY_IRI_MISSING(HttpStatus.BAD_REQUEST, "Nahraný slovník nemá IRI."),
    UPLOAD_IRI_TRAILING_SLASH_COLLISION(HttpStatus.BAD_REQUEST,
            "Nahraný soubor obsahuje IRI, která se liší pouze koncovým lomítkem."),
    UPLOAD_RDF_STORE_FAILED(HttpStatus.BAD_GATEWAY, "Nahraný slovník se nepodařilo zapsat do úložiště RDF."),
    UPLOAD_METADATA_SAVE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Metadata nahraného slovníku se nepodařilo uložit."),
    MISSING_INSCHEME_DECISION_REQUIRED(HttpStatus.BAD_REQUEST,
            "Některé pojmy nejsou přiřazeny ke slovníku; je potřeba rozhodnutí uživatele."),

    // Concept
    CONCEPT_NOT_FOUND(HttpStatus.NOT_FOUND, "Pojem nebyl nalezen."),
    CONCEPT_VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Pojem obsahuje neplatná data."),
    CONCEPT_STORAGE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Pojem se nepodařilo uložit."),

    // Comment
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Komentář nebyl nalezen."),
    COMMENT_INVALID(HttpStatus.BAD_REQUEST, "Komentář obsahuje neplatná data."),

    // Validation
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validace selhala."),
    VALIDATION_REPORT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Validační report se nepodařilo uložit nebo načíst."),
    VALIDATION_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Validační služba není momentálně dostupná."),

    // Diagram
    DIAGRAM_VERSION_CONFLICT(HttpStatus.CONFLICT, "Diagram mezitím uložil někdo jiný."),
    DIAGRAM_NAME_CONFLICT(HttpStatus.CONFLICT, "Diagram s tímto názvem už ve slovníku existuje."),
    DIAGRAM_EDIT_CONFLICT(HttpStatus.CONFLICT, "Na některém pojmu má rozpracovanou změnu i jiný diagram."),
    DIAGRAM_CONFLICT_RESOLUTION_INVALID(HttpStatus.BAD_REQUEST, "Zvolené řešení kolize není platné."),
    DIAGRAM_SAVED_READBACK_FAILED(HttpStatus.BAD_GATEWAY, "Diagram byl uložen, ale nepodařilo se jej znovu načíst."),
    DIAGRAM_CONTENT_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "Obsah diagramu se nepodařilo načíst."),

    // Storage and external services
    RDF_STORE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Operace nad úložištěm RDF selhala."),
    NKD_RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Záznam nebyl v Národním katalogu dat nalezen."),
    NKD_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Národní katalog dat není momentálně dostupný."),
    SPARQL_ENDPOINT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Data nejsou momentálně dostupná."),
    EXTERNAL_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Externí služba není momentálně dostupná."),
    EXTERNAL_SERVICE_REJECTED(HttpStatus.BAD_GATEWAY, "Externí služba požadavek odmítla."),
    EXTERNAL_SERVICE_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "Externí služba vrátila neplatnou odpověď."),

    // Admin
    OUTBOX_ROW_NOT_RETRYABLE(HttpStatus.CONFLICT, "Záznam neexistuje nebo není ve stavu FAILED; není co opakovat."),
    RECONCILIATION_IN_PROGRESS(HttpStatus.CONFLICT, "Kontrola konzistence již probíhá.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }
}