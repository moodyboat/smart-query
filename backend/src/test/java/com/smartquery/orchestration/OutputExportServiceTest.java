package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import com.smartquery.entity.OutputArtifact;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutputExportServiceTest {

    @Test
    void generatesEveryGovernedExportFormatWithItsRealFileSignature() {
        OutputArtifactService artifacts = mock(OutputArtifactService.class);
        OutputArtifact artifact = new OutputArtifact();
        List<Map<String, Object>> columns = List.of(
            Map.of("field", "customerName", "title", "客户名称"),
            Map.of("field", "riskScore", "title", "风险评分"));
        OutputArtifactService.OutputViewRow row = new OutputArtifactService.OutputViewRow(
            0, Map.of("customerName", "甲公司", "riskScore", 0.92),
            Map.of("customerName", "甲公司", "riskScore", 0.92),
            List.of(Map.of("source", "qa")), List.of(Map.of("rule", "riskScore>=0.8")),
            List.of("run:2:record:1"));
        OutputArtifactService.OutputView view = new OutputArtifactService.OutputView(
            artifact, Map.of("title", "风险结果"), Map.of(), 1, 200, 1, columns, List.of(row));
        when(artifacts.view(7L, 1, 200)).thenReturn(view);
        OutputExportService service = new OutputExportService(artifacts, new ObjectMapper());

        assertSignature(service, artifact, "EXPORT_XLSX",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{0x50, 0x4b});
        assertSignature(service, artifact, "EXPORT_CSV", "text/csv",
            new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
        assertSignature(service, artifact, "EXPORT_PDF", "application/pdf",
            "%PDF".getBytes(StandardCharsets.US_ASCII));
        assertSignature(service, artifact, "EXPORT_JSON", "application/json",
            new byte[]{'['});
        assertSignature(service, artifact, "EXPORT_PNG", "image/png",
            new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
    }

    @Test
    void rejectsNonExportArtifacts() {
        OutputArtifactService artifacts = mock(OutputArtifactService.class);
        OutputArtifact artifact = new OutputArtifact();
        artifact.setOutputKind("TABLE");
        when(artifacts.view(3L, 1, 200)).thenReturn(new OutputArtifactService.OutputView(
            artifact, Map.of(), Map.of(), 1, 200, 0, List.of(), List.of()));

        BusinessException error = assertThrows(BusinessException.class,
            () -> new OutputExportService(artifacts, new ObjectMapper()).export(3L));
        assertEquals(422, error.getCode());
    }

    private void assertSignature(OutputExportService service, OutputArtifact artifact,
                                 String outputKind, String mimeType, byte[] expected) {
        artifact.setOutputKind(outputKind);
        artifact.setMimeType(mimeType);
        OutputExportService.ExportFile file = service.export(7L);
        assertEquals(mimeType, file.mimeType());
        assertTrue(file.bytes().length > expected.length);
        assertArrayEquals(expected, java.util.Arrays.copyOf(file.bytes(), expected.length));
    }
}
