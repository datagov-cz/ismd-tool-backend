package com.dia.ismdtoolbackend.service.impl;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Registers {@link DiagramServiceImpl} and its focused classes as real Spring beans, so
 * {@link DiagramTransactions} is proxied and opens its transactions as in production. The importing
 * test supplies the collaborators: repositories, mapper, reconciler, Fuseki and materialize.
 */
@TestConfiguration
@Import({DiagramServiceImpl.class, DiagramTransactions.class, DiagramLifecycleOperation.class,
        DiagramLayoutOperation.class, DiagramConflictOperation.class, DiagramSnapshotReader.class,
        DiagramScopeReader.class, DiagramListReader.class, DiagramLabelReader.class, DiagramLiveContentReader.class,
        DiagramResponseAssembler.class})
public class DiagramServiceBeans {
}
