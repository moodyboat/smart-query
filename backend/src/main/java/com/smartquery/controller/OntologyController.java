package com.smartquery.controller;

import com.smartquery.entity.*;
import com.smartquery.service.OntologyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ontology")
@RequiredArgsConstructor
public class OntologyController {

    private final OntologyService ontologyService;

    // ==================== Metrics ====================

    @GetMapping("/{dataSourceId}/metrics")
    public ResponseEntity<Map<String, Object>> listMetrics(@PathVariable Long dataSourceId) {
        List<OntologyMetric> metrics = ontologyService.listMetrics(dataSourceId);
        return ResponseEntity.ok(Map.of("success", true, "data", metrics));
    }

    @PostMapping("/{dataSourceId}/metrics")
    public ResponseEntity<Map<String, Object>> createMetric(
            @PathVariable Long dataSourceId,
            @RequestBody OntologyMetric metric) {
        metric.setDataSourceId(dataSourceId);
        OntologyMetric created = ontologyService.createMetric(metric);
        return ResponseEntity.ok(Map.of("success", true, "data", created));
    }

    @PutMapping("/metrics/{id}")
    public ResponseEntity<Map<String, Object>> updateMetric(
            @PathVariable Long id,
            @RequestBody OntologyMetric updates) {
        OntologyMetric updated = ontologyService.updateMetric(id, updates);
        return ResponseEntity.ok(Map.of("success", true, "data", updated));
    }

    @DeleteMapping("/metrics/{id}")
    public ResponseEntity<Map<String, Object>> deleteMetric(@PathVariable Long id) {
        ontologyService.deleteMetric(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/metrics/{id}/resolve-sql")
    public ResponseEntity<Map<String, Object>> resolveMetricSql(@PathVariable Long id) {
        Map<String, Object> resolved = ontologyService.resolveMetricToSql(id);
        return ResponseEntity.ok(Map.of("success", true, "data", resolved));
    }

    // ==================== Dimensions ====================

    @GetMapping("/{dataSourceId}/dimensions")
    public ResponseEntity<Map<String, Object>> listDimensions(@PathVariable Long dataSourceId) {
        List<OntologyDimension> dimensions = ontologyService.listDimensions(dataSourceId);
        return ResponseEntity.ok(Map.of("success", true, "data", dimensions));
    }

    @GetMapping("/{dataSourceId}/dimensions/tree")
    public ResponseEntity<Map<String, Object>> getDimensionTree(@PathVariable Long dataSourceId) {
        List<Map<String, Object>> tree = ontologyService.getDimensionTree(dataSourceId);
        return ResponseEntity.ok(Map.of("success", true, "data", tree));
    }

    @PostMapping("/{dataSourceId}/dimensions")
    public ResponseEntity<Map<String, Object>> createDimension(
            @PathVariable Long dataSourceId,
            @RequestBody OntologyDimension dimension) {
        dimension.setDataSourceId(dataSourceId);
        OntologyDimension created = ontologyService.createDimension(dimension);
        return ResponseEntity.ok(Map.of("success", true, "data", created));
    }

    @PutMapping("/dimensions/{id}")
    public ResponseEntity<Map<String, Object>> updateDimension(
            @PathVariable Long id,
            @RequestBody OntologyDimension updates) {
        OntologyDimension updated = ontologyService.updateDimension(id, updates);
        return ResponseEntity.ok(Map.of("success", true, "data", updated));
    }

    @DeleteMapping("/dimensions/{id}")
    public ResponseEntity<Map<String, Object>> deleteDimension(@PathVariable Long id) {
        ontologyService.deleteDimension(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ==================== Glossary ====================

    @GetMapping("/{dataSourceId}/glossary")
    public ResponseEntity<Map<String, Object>> listGlossary(@PathVariable Long dataSourceId) {
        List<OntologyGlossary> entries = ontologyService.listGlossary(dataSourceId);
        return ResponseEntity.ok(Map.of("success", true, "data", entries));
    }

    @PostMapping("/{dataSourceId}/glossary")
    public ResponseEntity<Map<String, Object>> createGlossaryEntry(
            @PathVariable Long dataSourceId,
            @RequestBody OntologyGlossary entry) {
        entry.setDataSourceId(dataSourceId);
        OntologyGlossary created = ontologyService.createGlossaryEntry(entry);
        return ResponseEntity.ok(Map.of("success", true, "data", created));
    }

    @PutMapping("/glossary/{id}")
    public ResponseEntity<Map<String, Object>> updateGlossaryEntry(
            @PathVariable Long id,
            @RequestBody OntologyGlossary updates) {
        OntologyGlossary updated = ontologyService.updateGlossaryEntry(id, updates);
        return ResponseEntity.ok(Map.of("success", true, "data", updated));
    }

    @DeleteMapping("/glossary/{id}")
    public ResponseEntity<Map<String, Object>> deleteGlossaryEntry(@PathVariable Long id) {
        ontologyService.deleteGlossaryEntry(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/{dataSourceId}/glossary/resolve")
    public ResponseEntity<Map<String, Object>> resolveTerm(
            @PathVariable Long dataSourceId,
            @RequestParam String q) {
        List<Map<String, Object>> resolved = ontologyService.resolveTerm(dataSourceId, q);
        return ResponseEntity.ok(Map.of("success", true, "data", resolved));
    }

    // ==================== Indicator Config ====================

    @GetMapping("/{dataSourceId}/indicator-configs")
    public ResponseEntity<Map<String, Object>> listIndicatorConfigs(@PathVariable Long dataSourceId) {
        List<OntologyIndicatorConfig> configs = ontologyService.listIndicatorConfigs(dataSourceId);
        return ResponseEntity.ok(Map.of("success", true, "data", configs));
    }

    @PostMapping("/{dataSourceId}/indicator-configs")
    public ResponseEntity<Map<String, Object>> createIndicatorConfig(
            @PathVariable Long dataSourceId,
            @RequestBody OntologyIndicatorConfig config) {
        config.setDataSourceId(dataSourceId);
        OntologyIndicatorConfig created = ontologyService.createIndicatorConfig(config);
        return ResponseEntity.ok(Map.of("success", true, "data", created));
    }

    @PutMapping("/indicator-configs/{id}")
    public ResponseEntity<Map<String, Object>> updateIndicatorConfig(
            @PathVariable Long id,
            @RequestBody OntologyIndicatorConfig updates) {
        OntologyIndicatorConfig updated = ontologyService.updateIndicatorConfig(id, updates);
        return ResponseEntity.ok(Map.of("success", true, "data", updated));
    }

    @DeleteMapping("/indicator-configs/{id}")
    public ResponseEntity<Map<String, Object>> deleteIndicatorConfig(@PathVariable Long id) {
        ontologyService.deleteIndicatorConfig(id);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
