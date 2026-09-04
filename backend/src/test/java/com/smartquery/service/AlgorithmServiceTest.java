package com.smartquery.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.entity.Algorithm;
import com.smartquery.entity.MiningModel;
import com.smartquery.entity.MiningPipeline;
import com.smartquery.mapper.AlgorithmMapper;
import com.smartquery.mapper.MiningModelMapper;
import com.smartquery.mapper.MiningPipelineMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlgorithmServiceTest {

    private AlgorithmMapper algorithmMapper;
    private MiningModelMapper modelMapper;
    private MiningPipelineMapper pipelineMapper;
    private AlgorithmService service;

    @BeforeEach
    void setUp() {
        algorithmMapper = mock(AlgorithmMapper.class);
        modelMapper = mock(MiningModelMapper.class);
        pipelineMapper = mock(MiningPipelineMapper.class);
        when(modelMapper.selectList(any())).thenReturn(List.of());
        when(pipelineMapper.selectList(any())).thenReturn(List.of());
        service = new AlgorithmService(algorithmMapper, modelMapper, pipelineMapper, new ObjectMapper());
    }

    @Test
    void resolvesCommonAlgorithmAliasToBuiltinIdentifier() {
        Algorithm knn = algorithm("knn", true);
        knn.setAliases("[\"k_neighbors\"]");
        when(algorithmMapper.selectList(any())).thenReturn(List.of(knn));

        Algorithm resolved = service.getByAlgorithmId("k_neighbors");

        assertEquals("knn", resolved.getAlgorithmId());
    }

    @Test
    void disabledAlgorithmIsNotSelectable() {
        Algorithm disabled = algorithm("custom_tree", false);
        when(algorithmMapper.selectList(any())).thenReturn(List.of(disabled));

        assertEquals(null, service.getByAlgorithmId("custom_tree"));
    }

    @Test
    void modelBindingKeepsExecutableSnapshotAfterCatalogChanges() {
        Algorithm original = algorithm("custom_logit", true);
        original.setVersionNo(3);
        when(algorithmMapper.selectList(any())).thenReturn(List.of(original));

        AlgorithmService.AlgorithmBinding binding = service.activeBinding("custom_logit", "classification");
        MiningModel model = new MiningModel();
        model.setAlgorithm(binding.algorithmId());
        model.setModelType("classification");
        service.applyBinding(model, binding);

        when(algorithmMapper.selectList(any())).thenReturn(List.of());
        AlgorithmService.AlgorithmBinding restored = service.resolveModelBinding(model);

        assertEquals(3, restored.versionNo());
        assertEquals(original.getPythonCodeTemplate(), restored.pythonCodeTemplate());
        assertTrue(restored.snapshot().contains("codeSha256"));
    }

    @Test
    void rejectsTamperedModelSnapshot() {
        Algorithm original = algorithm("custom_logit", true);
        when(algorithmMapper.selectList(any())).thenReturn(List.of(original));
        AlgorithmService.AlgorithmBinding binding = service.activeBinding("custom_logit", "classification");
        MiningModel model = new MiningModel();
        model.setAlgorithm("custom_logit");
        model.setAlgorithmSnapshot(binding.snapshot().replace("LogisticRegression", "DecisionTreeClassifier"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.resolveModelBinding(model));

        assertTrue(error.getMessage().contains("校验失败"));
    }

    @Test
    void managementViewCountsNestedPipelineReferences() {
        Algorithm custom = algorithm("custom_logit", false);
        custom.setId(9L);
        custom.setIsBuiltin(0);
        when(algorithmMapper.selectList(any())).thenReturn(List.of(custom));
        MiningPipeline pipeline = new MiningPipeline();
        pipeline.setNodes("[{\"type\":\"training\",\"config\":{\"algorithm\":\"custom_logit\"}}]");
        when(pipelineMapper.selectList(any())).thenReturn(List.of(pipeline));

        Algorithm governed = service.getAllForManagement().get(0);

        assertEquals(1, governed.getPipelineReferenceCount());
        assertEquals(false, governed.getDeletable());
    }

    @Test
    void referencedCustomAlgorithmCannotBeDeleted() {
        Algorithm custom = algorithm("custom_logit", false);
        custom.setId(9L);
        custom.setIsBuiltin(0);
        when(algorithmMapper.selectById(9L)).thenReturn(custom);
        MiningModel model = new MiningModel();
        model.setAlgorithm("custom_logit");
        when(modelMapper.selectList(any())).thenReturn(List.of(model));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> service.deleteAlgorithm(9L));

        assertTrue(error.getMessage().contains("仍被引用"));
        verify(algorithmMapper, never()).deleteById(9L);
    }

    @Test
    void unreferencedCustomAlgorithmMustBeDisabledBeforeSoftDelete() {
        Algorithm custom = algorithm("custom_logit", true);
        custom.setId(9L);
        custom.setIsBuiltin(0);
        when(algorithmMapper.selectById(9L)).thenReturn(custom);

        assertThrows(IllegalArgumentException.class, () -> service.deleteAlgorithm(9L));
        custom.setEnabled(0);
        service.deleteAlgorithm(9L);

        verify(algorithmMapper).deleteById(9L);
    }

    private Algorithm algorithm(String id, boolean enabled) {
        Algorithm algorithm = new Algorithm();
        algorithm.setAlgorithmId(id);
        algorithm.setName(id);
        algorithm.setModelTypes("[\"classification\"]");
        algorithm.setParamsSchema("[]");
        algorithm.setPythonCodeTemplate(
            "from sklearn.linear_model import LogisticRegression\nclf = LogisticRegression(**params)");
        algorithm.setAliases("[]");
        algorithm.setEnabled(enabled ? 1 : 0);
        algorithm.setVersionNo(1);
        algorithm.setDeleted(0);
        return algorithm;
    }
}
