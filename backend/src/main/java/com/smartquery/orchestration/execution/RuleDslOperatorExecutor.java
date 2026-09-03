package com.smartquery.orchestration.execution;

import com.smartquery.common.BusinessException;
import com.smartquery.orchestration.OperatorTypes;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, in-process interpreter for the structured rule DSL. It never
 * evaluates source code. Capabilities that need arbitrary code or external I/O
 * must be implemented by a separate sandbox executor.
 */
@Component
public class RuleDslOperatorExecutor implements OperatorExecutor {
    private static final Pattern SIMPLE_CONDITION = Pattern.compile(
        "^\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+?)\\s*$");
    private static final Pattern RANGE = Pattern.compile("^(\\d+)([mhdw])$");
    private static final String EVIDENCE = LineageSupport.EVIDENCE;

    @Override
    public String implementationType() {
        return "RULE_DSL";
    }

    @Override
    public OperatorExecutionResult execute(OperatorExecutionContext context) {
        if (!OperatorTypes.RULE.equals(context.operatorType())) {
            throw new BusinessException(422, "RULE_DSL执行器只能运行RULE算子");
        }
        List<Map<String, Object>> steps = maps(context.implementationPayload().get("steps"), "steps");
        if (steps.isEmpty()) throw new BusinessException(422, "规则组合至少需要一个step");

        ExecutionPlan plan = plan(steps);
        Map<String, List<Map<String, Object>>> outputs = new LinkedHashMap<>();
        List<LeadDraft> leads = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        for (String stepId : plan.order()) {
            Map<String, Object> step = plan.steps().get(stepId);
            List<Map<String, Object>> input = inputFor(stepId, plan.dependencies(), outputs, context.records());
            StepResult result = apply(stepId, text(step.get("op")), config(step), input, context);
            outputs.put(stepId, result.records());
            leads.addAll(result.leads());
            logs.add(stepId + ":" + step.get("op") + " " + input.size() + "->" + result.records().size());
        }

        List<Map<String, Object>> finalRecords = new ArrayList<>();
        for (String terminal : plan.terminals()) finalRecords.addAll(outputs.getOrDefault(terminal, List.of()));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("records", cleanRecords(finalRecords));
        output.put("recordCount", finalRecords.size());
        output.put("leadCount", leads.size());
        return new OperatorExecutionResult(output, leads, String.join("; ", logs));
    }

    private StepResult apply(String id, String op, Map<String, Object> config,
                             List<Map<String, Object>> input, OperatorExecutionContext context) {
        if (op == null) throw new BusinessException(422, "规则步骤[" + id + "]缺少op");
        return switch (op) {
            case "filter" -> records(filter(input, config, false));
            case "compare" -> records(compare(input, config));
            case "text_match" -> records(textMatch(input, config));
            case "derive" -> records(derive(input, config));
            case "group_by" -> records(groupBy(input, config));
            case "aggregate" -> records(aggregate(input, config));
            case "time_window" -> records(timeWindow(input, config, context));
            case "lookup" -> records(lookup(input, config));
            case "rank" -> records(rank(input, config));
            case "sequence" -> records(sequence(input, config));
            case "threshold" -> records(filter(input, config, true));
            case "lead_output" -> leadOutput(input, config);
            default -> throw new BusinessException(422, "规则能力[" + op + "]没有安全运行时实现");
        };
    }

    private List<Map<String, Object>> compare(List<Map<String, Object>> input, Map<String, Object> config) {
        String field = required(config, "field");
        String operator = required(config, "operator");
        Object expected = config.containsKey("valueField")
            ? new FieldRef(String.valueOf(config.get("valueField"))) : config.get("value");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> record : input) {
            Object actual = field(record, field);
            Object right = expected instanceof FieldRef ref ? field(record, ref.name()) : expected;
            if (test(actual, operator, right)) {
                Map<String, Object> copy = copy(record);
                addEvidence(copy, "RULE", "字段比较", field, actual,
                    field + " " + operator + " " + printable(right));
                result.add(copy);
            }
        }
        return result;
    }

    private List<Map<String, Object>> filter(List<Map<String, Object>> input,
                                              Map<String, Object> config, boolean threshold) {
        String expression = required(config, "expression");
        Matcher matcher = SIMPLE_CONDITION.matcher(expression);
        if (!matcher.matches()) {
            throw new BusinessException(422, "仅支持安全条件格式：field ==/!=/>=/<=/>/< value");
        }
        String field = matcher.group(1);
        String operator = matcher.group(2);
        String literal = matcher.group(3);
        Object expected = parseLiteral(literal);
        boolean keepUnmatched = bool(config.get("keepUnmatched"), false);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> record : input) {
            Object actual = field(record, field);
            boolean matched = test(actual, operator, expected);
            if (matched || (threshold && keepUnmatched)) {
                Map<String, Object> copy = copy(record);
                if (threshold) {
                    copy.put("decisionResult", matched ? "HIT" : "NOT_HIT");
                    copy.put("decisionLevel", matched
                        ? String.valueOf(config.getOrDefault("hitLevel", "HIGH"))
                        : String.valueOf(config.getOrDefault("missLevel", "LOW")));
                }
                if (matched) addEvidence(copy, threshold ? "THRESHOLD" : "RULE",
                    threshold ? "阈值命中" : "条件过滤", field, actual, expression);
                result.add(copy);
            }
        }
        return result;
    }

    private List<Map<String, Object>> textMatch(List<Map<String, Object>> input,
                                                 Map<String, Object> config) {
        String field = required(config, "field");
        String mode = String.valueOf(config.getOrDefault("mode", "any")).toLowerCase(Locale.ROOT);
        if ("regex".equals(mode)) {
            throw new BusinessException(422, "正则匹配需要隔离沙箱；当前安全运行时支持any/all/none");
        }
        List<String> keywords = strings(config.get("keywords"), "keywords");
        if (keywords.isEmpty()) throw new BusinessException(422, "keywords不能为空");
        boolean ignoreCase = bool(config.get("ignoreCase"), true);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> record : input) {
            String source = printable(field(record, field));
            String candidate = ignoreCase ? source.toLowerCase(Locale.ROOT) : source;
            List<String> hits = keywords.stream().filter(keyword -> candidate.contains(
                ignoreCase ? keyword.toLowerCase(Locale.ROOT) : keyword)).toList();
            boolean matched = switch (mode) {
                case "any" -> !hits.isEmpty();
                case "all" -> hits.size() == keywords.size();
                case "none" -> hits.isEmpty();
                default -> throw new BusinessException(422, "text_match.mode仅支持any/all/none");
            };
            if (matched) {
                Map<String, Object> copy = copy(record);
                addEvidence(copy, "TEXT_MATCH", "关键词匹配", field, source,
                    mode + "(" + String.join(",", keywords) + ")");
                copy.put("matchedKeywords", hits);
                result.add(copy);
            }
        }
        return result;
    }

    private List<Map<String, Object>> derive(List<Map<String, Object>> input, Map<String, Object> config) {
        String name = required(config, "name");
        if (name.startsWith("__")) throw new BusinessException(422, "派生字段不能使用内部前缀__");
        Object expression = config.get("expression");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> record : input) {
            Map<String, Object> copy = copy(record);
            copy.put(name, evaluate(expression, record, 0));
            result.add(copy);
        }
        return result;
    }

    private List<Map<String, Object>> groupBy(List<Map<String, Object>> input, Map<String, Object> config) {
        List<String> fields = strings(config.get("fields"), "fields");
        if (fields.isEmpty()) throw new BusinessException(422, "group_by.fields不能为空");
        Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> record : input) {
            List<Object> key = fields.stream().map(field -> field(record, field)).toList();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(record);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        groups.forEach((key, items) -> {
            Map<String, Object> group = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) group.put(fields.get(i), key.get(i));
            group.put("__items", items.stream().map(this::copy).toList());
            group.put("groupSize", items.size());
            LineageSupport.mergeInto(group, items);
            result.add(group);
        });
        return result;
    }

    private List<Map<String, Object>> aggregate(List<Map<String, Object>> input, Map<String, Object> config) {
        List<Map<String, Object>> metrics = maps(config.get("metrics"), "metrics");
        if (metrics.isEmpty()) throw new BusinessException(422, "aggregate.metrics不能为空");
        boolean grouped = input.stream().anyMatch(record -> record.get("__items") instanceof List<?>);
        List<Map<String, Object>> sources = grouped ? input : List.of(Map.of("__items", input));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            List<Map<String, Object>> items = maps(source.get("__items"), "__items");
            Map<String, Object> output = copy(source);
            output.remove("__items");
            LineageSupport.mergeInto(output, items);
            for (Map<String, Object> metric : metrics) {
                String function = required(metric, "function").toLowerCase(Locale.ROOT);
                String name = text(metric.get("name"));
                String field = text(metric.get("field"));
                if (name == null) name = function + (field == null ? "" : "_" + field);
                output.put(name, aggregateValue(function, field, items));
            }
            result.add(output);
        }
        return result;
    }

    private Object aggregateValue(String function, String field, List<Map<String, Object>> items) {
        List<Object> values = field == null ? List.of()
            : items.stream().map(item -> field(item, field)).filter(Objects::nonNull).toList();
        return switch (function) {
            case "count" -> (long) items.size();
            case "distinct_count" -> (long) new LinkedHashSet<>(values).size();
            case "sum" -> values.stream().map(this::decimal).reduce(BigDecimal.ZERO, BigDecimal::add);
            case "avg" -> values.isEmpty() ? null : values.stream().map(this::decimal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP).stripTrailingZeros();
            case "min" -> values.stream().min(this::compareValues).orElse(null);
            case "max" -> values.stream().max(this::compareValues).orElse(null);
            default -> throw new BusinessException(422, "不支持的聚合函数: " + function);
        };
    }

    private List<Map<String, Object>> timeWindow(List<Map<String, Object>> input,
                                                  Map<String, Object> config,
                                                  OperatorExecutionContext context) {
        String timeField = required(config, "timeField");
        Duration duration = range(required(config, "range"));
        Instant reference = instant(config.get("referenceTime"));
        if (reference == null && context.runInput() != null) reference = instant(context.runInput().get("referenceTime"));
        if (reference == null) {
            reference = input.stream().map(record -> instant(field(record, timeField)))
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        }
        if (reference == null) throw new BusinessException(422, "时间窗口找不到可解析的参考时间");
        Instant start = reference.minus(duration);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> record : input) {
            Instant value = instant(field(record, timeField));
            if (value != null && !value.isBefore(start) && !value.isAfter(reference)) result.add(copy(record));
        }
        return result;
    }

    private List<Map<String, Object>> lookup(List<Map<String, Object>> input, Map<String, Object> config) {
        if (!(config.get("source") instanceof Map<?, ?> rawSource)) {
            throw new BusinessException(422, "当前lookup仅支持版本内固化的内联source对象；外部数据源需授权执行器");
        }
        Map<String, Object> source = new LinkedHashMap<>();
        rawSource.forEach((key, value) -> source.put(String.valueOf(key), value));
        List<String> keys = strings(config.get("keys"), "keys");
        String target = String.valueOf(config.getOrDefault("targetField", "lookupValue"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> record : input) {
            String key = keys.stream().map(field -> printable(field(record, field)))
                .reduce((left, right) -> left + "|" + right).orElse("");
            Object match = source.get(key);
            if (match == null && bool(config.get("required"), false)) continue;
            Map<String, Object> copy = copy(record);
            if (match instanceof Map<?, ?> map && bool(config.get("merge"), true)) {
                map.forEach((name, value) -> copy.put(String.valueOf(name), value));
            } else {
                copy.put(target, match);
            }
            result.add(copy);
        }
        return result;
    }

    private List<Map<String, Object>> rank(List<Map<String, Object>> input, Map<String, Object> config) {
        List<Map<String, Object>> orders = orderSpecs(config.get("orderBy"));
        List<Map<String, Object>> result = input.stream().map(this::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Comparator<Map<String, Object>> comparator = null;
        for (Map<String, Object> order : orders) {
            String field = required(order, "field");
            boolean descending = !"asc".equalsIgnoreCase(String.valueOf(order.getOrDefault("direction", "desc")));
            Comparator<Map<String, Object>> next = (left, right) -> compareValues(field(left, field), field(right, field));
            if (descending) next = next.reversed();
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        if (comparator == null) throw new BusinessException(422, "rank.orderBy不能为空");
        result.sort(comparator);
        String rankField = String.valueOf(config.getOrDefault("rankField", "rank"));
        for (int i = 0; i < result.size(); i++) result.get(i).put(rankField, i + 1);
        int topN = integer(config.get("topN"), result.size());
        return topN < result.size() ? new ArrayList<>(result.subList(0, Math.max(0, topN))) : result;
    }

    private List<Map<String, Object>> sequence(List<Map<String, Object>> input, Map<String, Object> config) {
        List<String> events = strings(config.get("events"), "events");
        if (events.isEmpty()) throw new BusinessException(422, "sequence.events不能为空");
        String timeField = required(config, "timeField");
        String eventField = String.valueOf(config.getOrDefault("eventField", "event"));
        List<String> groupFields = stringsOrEmpty(config.get("groupFields"));
        Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> record : input) {
            List<Object> key = groupFields.stream().map(field -> field(record, field)).toList();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(record);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        groups.forEach((key, records) -> {
            records.sort(Comparator.comparing(record -> instant(field(record, timeField)),
                Comparator.nullsLast(Comparator.naturalOrder())));
            int cursor = 0;
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> record : records) {
                if (cursor < events.size() && events.get(cursor).equals(printable(field(record, eventField)))) {
                    matched.add(record);
                    cursor++;
                }
            }
            if (cursor == events.size()) {
                Map<String, Object> output = new LinkedHashMap<>();
                for (int i = 0; i < groupFields.size(); i++) output.put(groupFields.get(i), key.get(i));
                output.put("sequenceMatched", true);
                output.put("sequenceEvents", matched.stream().map(this::copy).toList());
                output.put("sequenceCount", matched.size());
                LineageSupport.mergeInto(output, matched);
                result.add(output);
            }
        });
        return result;
    }

    private StepResult leadOutput(List<Map<String, Object>> input, Map<String, Object> config) {
        String leadType = required(config, "leadType");
        Map<String, Object> subject = map(config.get("subjectMapping"));
        Map<String, Object> source = map(config.get("sourceMapping"));
        List<LeadDraft> leads = new ArrayList<>();
        for (Map<String, Object> record : input) {
            Map<String, Object> snapshot = sourceSnapshot(record);
            Map<String, Object> attributes = selectedAttributes(record, config.get("attributeFields"));
            List<LeadDraft.EvidenceDraft> evidence = evidence(record);
            leads.add(new LeadDraft(
                leadType,
                mapped(record, subject.get("subjectType")),
                mapped(record, subject.get("subjectId")),
                mapped(record, subject.get("subjectName")),
                number(mappedValue(record, config.getOrDefault("decisionScore", "$decisionScore"))),
                first(mapped(record, config.getOrDefault("decisionLevel", "$decisionLevel")), "UNSPECIFIED"),
                number(mappedValue(record, config.get("decisionThreshold"))),
                first(mapped(record, config.getOrDefault("decisionResult", "$decisionResult")), "HIT"),
                longNumber(mappedValue(record, source.get("dataSourceId"))),
                mapped(record, source.get("sourceTable")),
                mapped(record, source.get("primaryKeyColumn")),
                mapped(record, source.get("primaryKeyValue")),
                snapshot, attributes, evidence,
                localDateTime(mappedValue(record, config.get("occurredAt")))
            ));
        }
        return new StepResult(input.stream().map(this::copy).toList(), leads);
    }

    private Object evaluate(Object expression, Map<String, Object> record, int depth) {
        if (depth > 12) throw new BusinessException(422, "派生表达式嵌套过深");
        if (expression == null || expression instanceof Number || expression instanceof Boolean) return expression;
        if (expression instanceof String text) return text.startsWith("$") ? field(record, text.substring(1)) : text;
        if (!(expression instanceof Map<?, ?> raw)) throw new BusinessException(422, "派生表达式必须是结构化表达式树");
        Map<String, Object> expressionMap = map(raw);
        if (expressionMap.containsKey("field")) return field(record, String.valueOf(expressionMap.get("field")));
        if (expressionMap.containsKey("value")) return expressionMap.get("value");
        String op = required(expressionMap, "op").toLowerCase(Locale.ROOT);
        Object left = evaluate(expressionMap.get("left"), record, depth + 1);
        Object right = evaluate(expressionMap.get("right"), record, depth + 1);
        return switch (op) {
            case "add" -> decimal(left).add(decimal(right));
            case "subtract" -> decimal(left).subtract(decimal(right));
            case "multiply" -> decimal(left).multiply(decimal(right));
            case "divide" -> {
                BigDecimal divisor = decimal(right);
                if (BigDecimal.ZERO.compareTo(divisor) == 0) throw new BusinessException(422, "派生表达式除数不能为0");
                yield decimal(left).divide(divisor, 8, RoundingMode.HALF_UP).stripTrailingZeros();
            }
            case "concat" -> printable(left) + printable(right);
            case "coalesce" -> left == null ? right : left;
            case "lower" -> printable(left).toLowerCase(Locale.ROOT);
            case "upper" -> printable(left).toUpperCase(Locale.ROOT);
            default -> throw new BusinessException(422, "不支持的派生表达式操作: " + op);
        };
    }

    private ExecutionPlan plan(List<Map<String, Object>> steps) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        String previous = null;
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String id = first(text(step.get("id")), "step_" + (i + 1));
            if (byId.putIfAbsent(id, step) != null) throw new BusinessException(422, "重复的规则步骤ID: " + id);
            List<String> deps = dependencies(step.get("dependsOn"));
            if (deps.isEmpty() && step.get("dependsOn") == null && previous != null) deps = List.of(previous);
            dependencies.put(id, deps);
            previous = id;
        }
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        byId.keySet().forEach(id -> indegree.put(id, 0));
        dependencies.forEach((id, deps) -> deps.forEach(dep -> {
            if (!byId.containsKey(dep)) throw new BusinessException(422, "规则步骤依赖不存在: " + dep);
            indegree.put(id, indegree.get(id) + 1);
            outgoing.computeIfAbsent(dep, ignored -> new ArrayList<>()).add(id);
        }));
        ArrayDeque<String> ready = new ArrayDeque<>();
        byId.keySet().stream().filter(id -> indegree.get(id) == 0).forEach(ready::add);
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            order.add(id);
            for (String target : outgoing.getOrDefault(id, List.of())) {
                int value = indegree.compute(target, (key, old) -> old - 1);
                if (value == 0) ready.addLast(target);
            }
        }
        if (order.size() != byId.size()) throw new BusinessException(422, "规则步骤存在环");
        List<String> terminals = byId.keySet().stream().filter(id -> !outgoing.containsKey(id)).toList();
        return new ExecutionPlan(byId, dependencies, order, terminals);
    }

    private List<Map<String, Object>> inputFor(String id, Map<String, List<String>> dependencies,
                                                Map<String, List<Map<String, Object>>> outputs,
                                                List<Map<String, Object>> base) {
        List<String> deps = dependencies.getOrDefault(id, List.of());
        if (deps.isEmpty()) return base.stream().map(this::copy).toList();
        List<Map<String, Object>> result = new ArrayList<>();
        deps.forEach(dep -> outputs.getOrDefault(dep, List.of()).forEach(record -> result.add(copy(record))));
        return result;
    }

    private List<Map<String, Object>> cleanRecords(List<Map<String, Object>> records) {
        return records.stream().map(record -> {
            Map<String, Object> copy = copy(record);
            copy.remove("__items");
            return copy;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private void addEvidence(Map<String, Object> record, String kind, String name, String field,
                             Object actual, String condition) {
        List<Map<String, Object>> evidence;
        if (record.get(EVIDENCE) instanceof List<?> current) {
            evidence = new ArrayList<>((Collection<? extends Map<String, Object>>) current);
        } else evidence = new ArrayList<>();
        evidence.add(Map.of("kind", kind, "name", name, "field", field,
            "actualValue", printable(actual), "condition", condition));
        record.put(EVIDENCE, evidence);
    }

    private List<LeadDraft.EvidenceDraft> evidence(Map<String, Object> record) {
        if (!(record.get(EVIDENCE) instanceof List<?> list)) return List.of();
        List<LeadDraft.EvidenceDraft> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> value = map(item);
            result.add(new LeadDraft.EvidenceDraft(text(value.get("kind")), text(value.get("name")),
                text(value.get("field")), text(value.get("actualValue")), text(value.get("condition")),
                number(value.get("contribution")), text(value.get("snippet"))));
        }
        return result;
    }

    private Map<String, Object> sourceSnapshot(Map<String, Object> record) {
        if (record.get("__sourceSnapshot") instanceof Map<?, ?> source) return map(source);
        Map<String, Object> copy = copy(record);
        copy.remove(EVIDENCE);
        copy.remove("__items");
        return copy;
    }

    private Map<String, Object> selectedAttributes(Map<String, Object> record, Object rawFields) {
        List<String> fields = stringsOrEmpty(rawFields);
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (fields.isEmpty()) {
            record.forEach((key, value) -> { if (!key.startsWith("__")) attributes.put(key, value); });
        } else fields.forEach(field -> attributes.put(field, field(record, field)));
        return attributes;
    }

    private List<Map<String, Object>> orderSpecs(Object raw) {
        if (raw instanceof String text) return List.of(Map.of("field", text, "direction", "desc"));
        if (raw instanceof Map<?, ?> map) return List.of(map(map));
        return maps(raw, "orderBy");
    }

    private Object mappedValue(Map<String, Object> record, Object mapping) {
        if (mapping == null) return null;
        if (mapping instanceof Map<?, ?> raw) {
            Map<String, Object> map = map(raw);
            if (map.containsKey("field")) return field(record, String.valueOf(map.get("field")));
            return map.get("value");
        }
        if (mapping instanceof String text && text.startsWith("$")) return field(record, text.substring(1));
        if (mapping instanceof String text && record.containsKey(text)) return record.get(text);
        return mapping;
    }

    private String mapped(Map<String, Object> record, Object mapping) {
        Object value = mappedValue(record, mapping);
        return value == null ? null : String.valueOf(value);
    }

    private Object field(Map<String, Object> record, String path) {
        Object value = record;
        for (String part : path.split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) return null;
            value = map.get(part);
        }
        return value;
    }

    private boolean test(Object left, String operator, Object right) {
        if ("==".equals(operator)) return Objects.equals(normalize(left), normalize(right));
        if ("!=".equals(operator)) return !Objects.equals(normalize(left), normalize(right));
        int comparison = compareValues(left, right);
        return switch (operator) {
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            default -> throw new BusinessException(422, "不支持的比较操作: " + operator);
        };
    }

    private int compareValues(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        if (left instanceof Number || right instanceof Number) return decimal(left).compareTo(decimal(right));
        Instant leftTime = instant(left);
        Instant rightTime = instant(right);
        if (leftTime != null && rightTime != null) return leftTime.compareTo(rightTime);
        return printable(left).compareTo(printable(right));
    }

    private Object normalize(Object value) {
        if (value instanceof Number) return decimal(value).stripTrailingZeros();
        return value;
    }

    private Object parseLiteral(String raw) {
        String value = raw.trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        if ("null".equalsIgnoreCase(value)) return null;
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return Boolean.valueOf(value);
        try { return new BigDecimal(value); } catch (NumberFormatException ignored) { return value; }
    }

    private Duration range(String value) {
        Matcher matcher = RANGE.matcher(value.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) throw new BusinessException(422, "时间范围格式应为15m/24h/30d/4w");
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            case "w" -> Duration.ofDays(Math.multiplyExact(amount, 7));
            default -> throw new BusinessException(422, "不支持的时间范围");
        };
    }

    private Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
        if (value instanceof LocalDate date) return date.atStartOfDay().toInstant(ZoneOffset.UTC);
        try { return Instant.parse(String.valueOf(value)); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(String.valueOf(value)).toInstant(); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(String.valueOf(value)).toInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private LocalDateTime localDateTime(Object value) {
        Instant instant = instant(value);
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        try { return value instanceof BigDecimal number ? number : new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException e) { throw new BusinessException(422, "值不是数字: " + value); }
    }

    private Double number(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return decimal(value).doubleValue();
    }

    private Long longNumber(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        return decimal(value).longValueExact();
    }

    private int integer(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException e) { throw new BusinessException(422, "值不是整数: " + value); }
    }

    private boolean bool(Object value, boolean defaultValue) {
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }

    private String required(Map<String, Object> config, String field) {
        String value = text(config.get(field));
        if (value == null) throw new BusinessException(422, field + "不能为空");
        return value;
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String printable(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> config(Map<String, Object> step) {
        return step.get("config") instanceof Map<?, ?> map ? map(map) : step;
    }

    private Map<String, Object> copy(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    private Map<String, Object> map(Object source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source instanceof Map<?, ?> map) map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private List<Map<String, Object>> maps(Object raw, String field) {
        if (!(raw instanceof List<?> list)) throw new BusinessException(422, field + "必须是对象数组");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) throw new BusinessException(422, field + "必须是对象数组");
            result.add(map(item));
        }
        return result;
    }

    private List<String> strings(Object raw, String field) {
        if (!(raw instanceof List<?> list)) throw new BusinessException(422, field + "必须是字符串数组");
        return list.stream().filter(Objects::nonNull).map(String::valueOf).filter(value -> !value.isBlank()).toList();
    }

    private List<String> stringsOrEmpty(Object raw) {
        if (raw == null) return List.of();
        return strings(raw, "字段列表");
    }

    private List<String> dependencies(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?>) return strings(raw, "dependsOn");
        String value = text(raw);
        return value == null ? List.of() : List.of(value);
    }

    private StepResult records(List<Map<String, Object>> records) {
        return new StepResult(records, List.of());
    }

    private record FieldRef(String name) {}
    private record StepResult(List<Map<String, Object>> records, List<LeadDraft> leads) {}
    private record ExecutionPlan(Map<String, Map<String, Object>> steps,
                                 Map<String, List<String>> dependencies,
                                 List<String> order, List<String> terminals) {}
}
