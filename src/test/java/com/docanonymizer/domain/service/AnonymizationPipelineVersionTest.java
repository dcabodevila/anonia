package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AnonymizationPipelineVersionTest {

    @Test
    void exposesCurrentToolVersion() {
        assertEquals("0.4.4", AnonymizationPipeline.TOOL_VERSION);
    }
}
