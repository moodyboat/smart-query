package com.smartquery.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartquery.common.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Generates only the export formats governed by registered platform capabilities. */
@Service
public class OutputExportService {
    private static final int MAX_EXPORT_ROWS = 10_000;
    private final OutputArtifactService outputArtifactService;
    private final ObjectMapper objectMapper;

    public OutputExportService(OutputArtifactService outputArtifactService, ObjectMapper objectMapper) {
        this.outputArtifactService = outputArtifactService;
        this.objectMapper = objectMapper;
    }

    public ExportFile export(Long artifactId) {
        OutputArtifactService.OutputView first = outputArtifactService.view(artifactId, 1, 200);
        String kind = String.valueOf(first.artifact().getOutputKind()).toUpperCase(Locale.ROOT);
        if (!kind.startsWith("EXPORT_")) throw new BusinessException(422, "该输出目标不是文件导出制品");
        if (first.totalRows() > MAX_EXPORT_ROWS) {
            throw new BusinessException(413, "单次导出最多" + MAX_EXPORT_ROWS + "条，请先增加输出转换或过滤条件");
        }
        List<OutputArtifactService.OutputViewRow> rows = new ArrayList<>(first.rows());
        for (int page = 2; rows.size() < first.totalRows(); page++) {
            rows.addAll(outputArtifactService.view(artifactId, page, 200).rows());
        }
        String format = kind.substring("EXPORT_".length());
        String extension = format.toLowerCase(Locale.ROOT);
        String fileName = safeFileName(first.contentSpec().get("fileName"), "result." + extension, extension);
        return switch (format) {
            case "XLSX" -> new ExportFile(fileName, first.artifact().getMimeType(), xlsx(first, rows));
            case "CSV" -> new ExportFile(fileName, first.artifact().getMimeType(), csv(first, rows));
            case "JSON" -> new ExportFile(fileName, first.artifact().getMimeType(), json(rows));
            case "PNG" -> new ExportFile(fileName, first.artifact().getMimeType(), png(first, rows));
            case "PDF" -> new ExportFile(fileName, first.artifact().getMimeType(), pdf(first, rows));
            default -> throw new BusinessException(422, "平台未治理该导出格式: " + format);
        };
    }

    private byte[] xlsx(OutputArtifactService.OutputView view,
                        List<OutputArtifactService.OutputViewRow> rows) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String sheetName = safeSheetName(view.contentSpec().get("sheetName"));
            Sheet sheet = workbook.createSheet(sheetName);
            Row header = sheet.createRow(0);
            for (int index = 0; index < view.columns().size(); index++) {
                header.createCell(index).setCellValue(String.valueOf(view.columns().get(index).get("title")));
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                for (int columnIndex = 0; columnIndex < view.columns().size(); columnIndex++) {
                    String field = String.valueOf(view.columns().get(columnIndex).get("field"));
                    writeCell(row.createCell(columnIndex), readPath(rows.get(rowIndex).display(), field));
                }
            }
            Sheet lineage = workbook.createSheet("数据血缘");
            Row lineageHeader = lineage.createRow(0);
            List.of("rowIndex", "sourceRefs", "sources", "evidence").forEach(value ->
                lineageHeader.createCell(lineageHeader.getLastCellNum() < 0 ? 0 : lineageHeader.getLastCellNum()).setCellValue(value));
            for (int index = 0; index < rows.size(); index++) {
                OutputArtifactService.OutputViewRow value = rows.get(index);
                Row row = lineage.createRow(index + 1);
                row.createCell(0).setCellValue(value.rowIndex());
                row.createCell(1).setCellValue(jsonText(value.sourceRefs()));
                row.createCell(2).setCellValue(jsonText(value.sources()));
                row.createCell(3).setCellValue(jsonText(value.evidence()));
            }
            sheet.createFreezePane(0, 1);
            lineage.createFreezePane(0, 1);
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("XLSX导出失败: " + e.getMessage());
        }
    }

    private byte[] csv(OutputArtifactService.OutputView view,
                       List<OutputArtifactService.OutputViewRow> rows) {
        StringBuilder result = new StringBuilder("\uFEFF");
        List<String> headings = view.columns().stream().map(item -> String.valueOf(item.get("title"))).toList();
        result.append(String.join(",", headings.stream().map(this::csvCell).toList()))
            .append(",_sourceRefs,_evidence\r\n");
        for (OutputArtifactService.OutputViewRow row : rows) {
            List<String> values = new ArrayList<>();
            for (Map<String, Object> column : view.columns()) {
                values.add(csvCell(text(readPath(row.display(), String.valueOf(column.get("field"))))));
            }
            values.add(csvCell(jsonText(row.sourceRefs())));
            values.add(csvCell(jsonText(row.evidence())));
            result.append(String.join(",", values)).append("\r\n");
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] json(List<OutputArtifactService.OutputViewRow> rows) {
        List<Map<String, Object>> result = rows.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rowIndex", row.rowIndex());
            item.put("result", row.result());
            item.put("sources", row.sources());
            item.put("evidence", row.evidence());
            item.put("sourceRefs", row.sourceRefs());
            return item;
        }).toList();
        try { return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result); }
        catch (Exception e) { throw new BusinessException("JSON导出失败: " + e.getMessage()); }
    }

    private byte[] png(OutputArtifactService.OutputView view,
                       List<OutputArtifactService.OutputViewRow> rows) {
        try {
            BufferedImage image = tableImage(view, rows);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("PNG导出失败: " + e.getMessage());
        }
    }

    private byte[] pdf(OutputArtifactService.OutputView view,
                       List<OutputArtifactService.OutputViewRow> rows) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BufferedImage image = tableImage(view, rows);
            int offset = 0;
            int pageHeight = 1100;
            while (offset < image.getHeight()) {
                int height = Math.min(pageHeight, image.getHeight() - offset);
                BufferedImage slice = image.getSubimage(0, offset, image.getWidth(), height);
                PDPage page = new PDPage(new PDRectangle(image.getWidth(), height));
                document.addPage(page);
                var pdImage = LosslessFactory.createFromImage(document, slice);
                try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                    content.drawImage(pdImage, 0, 0, image.getWidth(), height);
                }
                offset += height;
            }
            document.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException("PDF导出失败: " + e.getMessage());
        }
    }

    private BufferedImage tableImage(OutputArtifactService.OutputView view,
                                     List<OutputArtifactService.OutputViewRow> rows) {
        int visibleRows = Math.min(rows.size(), 500);
        int columnCount = Math.max(1, Math.min(view.columns().size(), 8));
        int width = Math.max(900, columnCount * 180);
        int rowHeight = 30;
        int height = Math.max(180, 80 + (visibleRows + 1) * rowHeight);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setFont(new Font("Microsoft YaHei", Font.BOLD, 18));
        graphics.setColor(new Color(24, 49, 83));
        graphics.drawString(text(view.contentSpec().getOrDefault("title", "平台输出结果")), 18, 30);
        graphics.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        graphics.setColor(new Color(101, 116, 139));
        graphics.drawString("记录数 " + rows.size() + " · 导出保留血缘与审计关联", 18, 52);
        int top = 70;
        int cellWidth = width / columnCount;
        graphics.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
        for (int column = 0; column < columnCount; column++) {
            graphics.setColor(new Color(232, 241, 253));
            graphics.fillRect(column * cellWidth, top, cellWidth, rowHeight);
            graphics.setColor(new Color(31, 82, 145));
            drawClipped(graphics, text(view.columns().get(column).get("title")), column * cellWidth + 7,
                top + 19, cellWidth - 12);
        }
        graphics.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
        for (int rowIndex = 0; rowIndex < visibleRows; rowIndex++) {
            int y = top + (rowIndex + 1) * rowHeight;
            graphics.setColor(rowIndex % 2 == 0 ? Color.WHITE : new Color(248, 250, 252));
            graphics.fillRect(0, y, width, rowHeight);
            for (int column = 0; column < columnCount; column++) {
                String field = String.valueOf(view.columns().get(column).get("field"));
                graphics.setColor(new Color(51, 65, 85));
                drawClipped(graphics, text(readPath(rows.get(rowIndex).display(), field)),
                    column * cellWidth + 7, y + 19, cellWidth - 12);
            }
        }
        graphics.dispose();
        return image;
    }

    private void drawClipped(Graphics2D graphics, String value, int x, int y, int width) {
        FontMetrics metrics = graphics.getFontMetrics();
        String text = value == null ? "" : value;
        while (text.length() > 1 && metrics.stringWidth(text) > width) text = text.substring(0, text.length() - 1);
        graphics.drawString(text, x, y);
    }

    private Object readPath(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) current = map.get(part);
            else if (current instanceof List<?> list && part.matches("\\d+")) {
                int index = Integer.parseInt(part);
                current = index < list.size() ? list.get(index) : null;
            } else return null;
        }
        return current;
    }
    private void writeCell(Cell cell, Object value) {
        if (value instanceof Number number) cell.setCellValue(number.doubleValue());
        else if (value instanceof Boolean bool) cell.setCellValue(bool);
        else cell.setCellValue(text(value));
    }
    private String csvCell(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    private String jsonText(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "[]"; }
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String safeSheetName(Object raw) {
        String value = text(raw);
        if (value.isBlank()) value = "结果";
        value = value.replaceAll("[\\\\/*?:\\[\\]]", "_");
        return value.substring(0, Math.min(31, value.length()));
    }
    private String safeFileName(Object raw, String fallback, String extension) {
        String value = text(raw);
        if (value.isBlank()) value = fallback;
        value = value.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
        if (!value.toLowerCase(Locale.ROOT).endsWith("." + extension)) value += "." + extension;
        return value.substring(0, Math.min(160, value.length()));
    }

    public record ExportFile(String fileName, String mimeType, byte[] bytes) {}
}
