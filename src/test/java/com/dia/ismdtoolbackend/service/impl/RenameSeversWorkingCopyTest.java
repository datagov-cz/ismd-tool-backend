package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A working copy is tracked by shared identity with its NKD twin — its {@code conceptIri} IS the twin's
 * IRI. Relocating the IRI (a rename) orphans it from the twin, so the generic edit path severs it to a
 * draft ({@code is_published} → false). This pins the sever at the choke point both edit paths share,
 * {@code updateMetadataFromEditResult}, and the sync path's opt-out.
 *
 * <p>Exercises the private helper directly: the sever must fire on IRI relocation regardless of which
 * edit path (outbox or direct) reached it, and the surrounding {@code editConcept} orchestration is not
 * what is under test here.
 */
class RenameSeversWorkingCopyTest {

    private static final String OLD_IRI = "https://slovník.gov.cz/test/pojem/obec";
    private static final String NEW_IRI = "https://slovník.gov.cz/test/pojem/mesto";

    private ConceptMetadataEntity workingCopy() {
        ConceptMetadataEntity e = new ConceptMetadataEntity();
        e.setConceptIri(OLD_IRI);
        e.setIsPublished(true);
        return e;
    }

    private void invokeUpdate(ConceptMetadataEntity metadata, ConceptEditModel edit,
                              ConceptEditor.EditResult editResult, boolean severOnRename) throws Exception {
        Constructor<?> ctor = ConceptServiceImpl.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        ConceptServiceImpl service =
                (ConceptServiceImpl) ctor.newInstance(new Object[ctor.getParameterCount()]);

        Method m = ConceptServiceImpl.class.getDeclaredMethod("updateMetadataFromEditResult",
                ConceptMetadataEntity.class, ConceptEditModel.class,
                ConceptEditor.EditResult.class, boolean.class);
        m.setAccessible(true);
        m.invoke(service, metadata, edit, editResult, severOnRename);
    }

    @Test
    void rename_severs_workingCopy_toDraft() throws Exception {
        ConceptMetadataEntity metadata = workingCopy();
        ConceptEditor.EditResult renamed = new ConceptEditor.EditResult(NEW_IRI, true, 1);

        invokeUpdate(metadata, new ClassConceptEditModel(), renamed, true);

        assertEquals(NEW_IRI, metadata.getConceptIri(), "IRI must relocate");
        assertEquals(Boolean.FALSE, metadata.getIsPublished(), "rename must sever the working copy");
    }

    @Test
    void editWithoutRename_leaves_workingCopy_tracked() throws Exception {
        ConceptMetadataEntity metadata = workingCopy();
        ConceptEditor.EditResult noRename = new ConceptEditor.EditResult(OLD_IRI, false, 1);

        invokeUpdate(metadata, new ClassConceptEditModel(), noRename, true);

        assertEquals(OLD_IRI, metadata.getConceptIri());
        assertTrue(metadata.getIsPublished(), "an edit that does not relocate the IRI keeps the working copy");
    }

    @Test
    void syncPath_optsOut_soAcceptAllRenameStaysWorkingCopy() throws Exception {
        // The sync path passes false: a name/identifier sync relocates the IRI too, but "accept ALL"
        // must leave the concept a faithful, still-tracked working copy. Its own sever check runs later.
        ConceptMetadataEntity metadata = workingCopy();
        ConceptEditor.EditResult renamed = new ConceptEditor.EditResult(NEW_IRI, true, 1);

        invokeUpdate(metadata, new ClassConceptEditModel(), renamed, false);

        assertEquals(NEW_IRI, metadata.getConceptIri(), "IRI still relocates on the sync path");
        assertTrue(metadata.getIsPublished(), "sync opts out of the generic sever");
    }

    @Test
    void rename_ofNonWorkingCopy_isANoOpOnPublishedFlag() throws Exception {
        ConceptMetadataEntity draft = new ConceptMetadataEntity();
        draft.setConceptIri(OLD_IRI);
        draft.setIsPublished(false);
        ConceptEditor.EditResult renamed = new ConceptEditor.EditResult(NEW_IRI, true, 1);

        invokeUpdate(draft, new ClassConceptEditModel(), renamed, true);

        assertEquals(Boolean.FALSE, draft.getIsPublished(), "a plain draft has nothing to sever");
    }
}