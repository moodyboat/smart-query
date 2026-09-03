package com.smartquery.orchestration;

import com.smartquery.common.BusinessException;
import com.smartquery.orchestration.execution.LineageSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a typed scalar index without relying on database-specific JSON functions. */
@Service
public class OutputArtifactIndexService {
    private static final String INSERT_SQL = """
        INSERT INTO sq_output_artifact_cell
        (artifact_id, row_index, field_path, value_type, text_value, text_sort_value,
         number_value, boolean_value, value_hash)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ContentHashService contentHashService;

    @Value("${smart-query.orchestration.output-query.max-indexed-fields-per-row:300}")
    private int maxIndexedFieldsPerRow = 300;

    public OutputArtifactIndexService(JdbcTemplate jdbcTemplate,
                                      ContentHashService contentHashService) {
        this.jdbcTemplate = jdbcTemplate;
        this.contentHashService = contentHashService;
    }

    public void index(Long artifactId, List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>(500);
        for (int rowIndex = 0; rowIndex < records.size(); rowIndex++) {
            Map<String, Object> display = display(records.get(rowIndex));
            Map<String, Object> scalars = new LinkedHashMap<>();
            flatten(display, "", 0, scalars);
            if (scalars.size() > maxIndexedFieldsPerRow) {
                throw new BusinessException(413, "输出第" + (rowIndex + 1) + "行包含" + scalars.size()
                    + "个可查询字段，超过" + maxIndexedFieldsPerRow + "个限制");
            }
            for (Map.Entry<String, Object> entry : scalars.entrySet()) {
                CellValue value = cellValue(entry.getValue());
                Object hashSource = "STRING".equals(value.type()) ? value.text() : entry.getValue();
                batch.add(new Object[]{artifactId, rowIndex, entry.getKey(), value.type(),
                    value.text(), value.sortText(), value.number(), value.bool(),
                    contentHashService.sha256(hashSource)});
                if (batch.size() >= 500) flush(batch);
            }
        }
        flush(batch);
    }

    private Map<String, Object> display(Map<String, Object> record) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object rawSources = record.get(LineageSupport.SOURCE_SNAPSHOTS);
        if (rawSources instanceof List<?> sources && sources.size() == 1
                && sources.get(0) instanceof Map<?, ?> source) {
            source.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        record.forEach((key, value) -> {
            if (!key.startsWith("__")) result.put(key, value);
        });
        return result;
    }

    private void flatten(Object value, String path, int depth, Map<String, Object> result) {
        if (depth > 12) throw new BusinessException(413, "输出字段嵌套深度超过12层: " + path);
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.startsWith("__")) continue;
                String child = path.isEmpty() ? key : path + "." + key;
                validatePath(child);
                flatten(entry.getValue(), child, depth + 1, result);
            }
            return;
        }
        if (value instanceof List<?>) {
            // Arrays remain available in the row detail but are not scalar query fields.
            return;
        }
        if (!path.isEmpty()) result.put(path, value);
    }

    private void validatePath(String path) {
        if (path.length() > 300) throw new BusinessException(413, "输出字段路径超过300字符");
        if (path.indexOf('\u0000') >= 0) throw new BusinessException(422, "输出字段路径包含非法字符");
    }

    private CellValue cellValue(Object raw) {
        if (raw == null) return new CellValue("NULL", null, null, null, null);
        if (raw instanceof Boolean bool) {
            return new CellValue("BOOLEAN", bool.toString(), bool.toString(), null, bool ? 1 : 0);
        }
        if (raw instanceof Number number) {
            try {
                BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros();
                int scale = Math.max(decimal.scale(), 0);
                int integerDigits = decimal.precision() - decimal.scale();
                if (scale <= 10 && integerDigits <= 28) {
                    String text = decimal.toPlainString();
                    return new CellValue("NUMBER", text, text, decimal, null);
                }
            } catch (NumberFormatException ignored) {
                // Non-finite or vendor-specific numbers remain queryable as strings.
            }
        }
        String text = String.valueOf(raw);
        return new CellValue("STRING", text,
            text.length() <= 1000 ? text : text.substring(0, 1000), null, null);
    }

    private void flush(List<Object[]> batch) {
        if (batch.isEmpty()) return;
        jdbcTemplate.batchUpdate(INSERT_SQL, batch);
        batch.clear();
    }

    private record CellValue(String type, String text, String sortText,
                             BigDecimal number, Integer bool) {}
}
