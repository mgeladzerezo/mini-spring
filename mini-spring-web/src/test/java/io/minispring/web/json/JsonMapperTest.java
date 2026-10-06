package io.minispring.web.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.type.TypeReference;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonMapperTest {

    private final JsonMapper mapper = new JsonMapper();

    enum Status { OPEN, CLOSED }

    record Line(String sku, int quantity) {
    }

    record Order(long id, Status status, List<Line> lines, Optional<String> note, LocalDate placed) {
    }

    /** A generic record: the type variable is bound by the caller, not by the record. */
    record Page<T>(List<T> items, int total) {
    }

    static class Bean {
        private String name;
        private boolean active;
        public int visits;
        private Map<String, List<Integer>> scores;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public Map<String, List<Integer>> getScores() {
            return scores;
        }

        public void setScores(Map<String, List<Integer>> scores) {
            this.scores = scores;
        }
    }

    static class Orders extends ArrayList<Order> {
    }

    static class Box<T> {
        public T content;
    }

    static class LineBox extends Box<Line> {
    }

    // ---- parsing --------------------------------------------------------------------------------------------

    @Test
    void parsesEveryValueKindIntoAPlainTree() {
        Object tree = mapper.readTree("""
                {"s":"x\\n\\u00e9\\ud83d\\ude00","i":42,"big":12345678901234567890,"d":1.50,"e":1e3,"t":true,"n":null,"a":[1,[2]],"o":{}}""");
        Map<?, ?> map = assertInstanceOf(Map.class, tree);
        assertEquals("x\n\u00e9\ud83d\ude00", map.get("s"));
        assertEquals(42L, map.get("i"));
        assertEquals(new BigDecimal("12345678901234567890"), map.get("big"));
        assertEquals(new BigDecimal("1.50"), map.get("d"));
        assertEquals(new BigDecimal("1E+3"), map.get("e"));
        assertEquals(true, map.get("t"));
        assertTrue(map.containsKey("n") && map.get("n") == null);
        assertEquals(List.of(1L, List.of(2L)), map.get("a"));
        assertEquals(Map.of(), map.get("o"));
        assertEquals(List.of("s", "i", "big", "d", "e", "t", "n", "a", "o"), new ArrayList<>(map.keySet()),
                "property order is preserved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[1,]", "{\"a\":1,}", "{'a':1}", "[01]", "[1.]", "[.5]", "[-]", "\"abc", "\"a\nb\"",
            "\"\\x\"", "\"\\u12\"", "tru", "[1] x", "{\"a\" 1}", "nul", "+1", "[1 2]", "{1:2}", "// c\n1"})
    void rejectsMalformedInput(String bad) {
        assertThrows(JsonException.class, () -> mapper.readTree(bad));
    }

    @Test
    void syntaxErrorsReportLineAndColumn() {
        JsonException e = assertThrows(JsonException.class, () -> mapper.readTree("{\n  \"a\": 1,\n  \"b\": ?\n}"));
        assertTrue(e.getMessage().contains("line 3, column 8"), e.getMessage());
    }

    @Test
    void refusesAbsurdNesting() {
        String deep = "[".repeat(1000) + "]".repeat(1000);
        JsonException e = assertThrows(JsonException.class, () -> mapper.readTree(deep));
        assertTrue(e.getMessage().contains("nesting"), e.getMessage());
    }

    // ---- writing --------------------------------------------------------------------------------------------

    @Test
    void writesRecordsInComponentOrderWithEnumsOptionalsAndDates() {
        Order order = new Order(7, Status.OPEN, List.of(new Line("A-1", 2)), Optional.of("fragile"),
                LocalDate.of(2026, 3, 9));
        assertEquals("{\"id\":7,\"status\":\"OPEN\",\"lines\":[{\"sku\":\"A-1\",\"quantity\":2}],"
                + "\"note\":\"fragile\",\"placed\":\"2026-03-09\"}", mapper.writeValueAsString(order));
        assertEquals("null", mapper.writeValueAsString(Optional.empty()));
    }

    @Test
    void writesBeansByGettersMapsCollectionsAndArraysAndEscapesStrings() {
        Bean bean = new Bean();
        bean.setName("quote\" back\\ tab\t ctl\u0001 \u00e9");
        bean.setActive(true);
        bean.setScores(new TreeMap<>(Map.of("b", List.of(1), "a", List.of())));
        assertEquals("{\"active\":true,\"name\":\"quote\\\" back\\\\ tab\\t ctl\\u0001 \u00e9\","
                + "\"scores\":{\"a\":[],\"b\":[1]}}", mapper.writeValueAsString(bean));
        assertEquals("[1,2,3]", mapper.writeValueAsString(new int[] {1, 2, 3}));
        assertEquals("[\"a\",null]", mapper.writeValueAsString(java.util.Arrays.asList("a", null)));
        assertEquals("{\"OPEN\":1}", mapper.writeValueAsString(Map.of(Status.OPEN, 1)));
    }

    @Test
    void writesTimeTypesAsIsoStringsAndBigDecimalWithoutExponent() {
        assertEquals("\"2026-03-09T10:15:30Z\"", mapper.writeValueAsString(Instant.parse("2026-03-09T10:15:30Z")));
        assertEquals("\"PT1H30M\"", mapper.writeValueAsString(Duration.ofMinutes(90)));
        assertEquals("100000000000000000000", mapper.writeValueAsString(new BigDecimal("1E+20")));
        UUID id = UUID.randomUUID();
        assertEquals("\"" + id + "\"", mapper.writeValueAsString(id));
    }

    @Test
    void refusesNanAndCycles() {
        assertThrows(JsonException.class, () -> mapper.writeValueAsString(Double.NaN));
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        JsonException e = assertThrows(JsonException.class, () -> mapper.writeValueAsString(cycle));
        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
    }

    // ---- generic targets ------------------------------------------------------------------------------------

    @Test
    void readsAListOfRecordsFromAGenericTarget() {
        List<Line> lines = mapper.readValue("[{\"sku\":\"a\",\"quantity\":1},{\"sku\":\"b\",\"quantity\":2}]",
                new TypeReference<List<Line>>() {});
        assertEquals(List.of(new Line("a", 1), new Line("b", 2)), lines);
        assertInstanceOf(Line.class, lines.get(0), "elements are Line, not LinkedHashMap");
    }

    @Test
    void bindsATypeVariableOfAGenericRecordFromTheCallersTypeArgument() {
        Page<Line> page = mapper.readValue("{\"items\":[{\"sku\":\"a\",\"quantity\":3}],\"total\":1}",
                new TypeReference<Page<Line>>() {});
        assertEquals(new Page<>(List.of(new Line("a", 3)), 1), page);
        Page<Page<Integer>> nested = mapper.readValue("{\"items\":[{\"items\":[1,2],\"total\":2}],\"total\":1}",
                new TypeReference<Page<Page<Integer>>>() {});
        assertEquals(List.of(1, 2), nested.items().get(0).items());
    }

    @Test
    void bindsTypeArgumentsDeclaredInASuperclass() {
        Orders orders = mapper.readValue("[{\"id\":1,\"status\":\"CLOSED\",\"lines\":[],\"note\":null,\"placed\":null}]",
                Orders.class);
        assertEquals(Status.CLOSED, orders.get(0).status());
        LineBox box = mapper.readValue("{\"content\":{\"sku\":\"z\",\"quantity\":9}}", LineBox.class);
        assertEquals(new Line("z", 9), box.content, "T of Box<T> is bound to Line by 'LineBox extends Box<Line>'");
    }

    @Test
    void readsMapsWithTypedKeysAndNestedGenerics() {
        Map<Integer, List<Status>> map = mapper.readValue("{\"1\":[\"OPEN\"],\"22\":[\"CLOSED\",\"OPEN\"]}",
                new TypeReference<Map<Integer, List<Status>>>() {});
        assertEquals(List.of(Status.CLOSED, Status.OPEN), map.get(22));
        assertInstanceOf(LinkedHashMap.class, map);
        Map<Status, Set<String>> byEnum = mapper.readValue("{\"OPEN\":[\"a\",\"a\",\"b\"]}",
                new TypeReference<Map<Status, Set<String>>>() {});
        assertEquals(new LinkedHashSet<>(List.of("a", "b")), byEnum.get(Status.OPEN));
    }

    @Test
    void readsBeansWithSettersPublicFieldsAndGenericProperties() {
        Bean bean = mapper.readValue("{\"name\":\"n\",\"active\":true,\"visits\":4,\"scores\":{\"x\":[1,2]},\"extra\":1}",
                Bean.class);
        assertEquals("n", bean.getName());
        assertTrue(bean.isActive());
        assertEquals(4, bean.visits);
        assertEquals(List.of(1, 2), bean.getScores().get("x"));
        assertEquals(Integer.class, bean.getScores().get("x").get(0).getClass());
    }

    @Test
    void readsRecordsWithMissingFieldsOptionalsAndTimeTypes() {
        Order order = mapper.readValue("{\"id\":5,\"lines\":[]}", Order.class);
        assertEquals(5, order.id());
        assertNull(order.status());
        assertEquals(Optional.empty(), order.note(), "an absent Optional component is empty, not null");
        Order withNote = mapper.readValue("{\"id\":5,\"note\":\"hi\",\"placed\":\"2026-01-02\"}", Order.class);
        assertEquals(Optional.of("hi"), withNote.note());
        assertEquals(LocalDate.of(2026, 1, 2), withNote.placed());
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4), mapper.readValue("\"2026-01-02T03:04\"", LocalDateTime.class));
        assertEquals(OffsetDateTime.parse("2026-01-02T03:04:05+02:00"),
                mapper.readValue("\"2026-01-02T03:04:05+02:00\"", OffsetDateTime.class));
        assertEquals(Duration.ofSeconds(90), mapper.readValue("\"PT1M30S\"", Duration.class));
    }

    @Test
    void readsOptionalOfAGenericTypeAndArraysAndPrimitives() {
        Optional<List<Integer>> value = mapper.readValue("[1,2]", new TypeReference<Optional<List<Integer>>>() {});
        assertEquals(Optional.of(List.of(1, 2)), value);
        assertEquals(Optional.empty(), mapper.readValue("null", new TypeReference<Optional<Integer>>() {}));
        assertEquals(List.of(1, 2), java.util.Arrays.stream(mapper.readValue("[1,2]", int[].class)).boxed().toList());
        assertEquals(2.5, mapper.readValue("2.5", double.class));
        assertEquals(1.0f, mapper.readValue("1", Float.class));
        assertEquals(new BigDecimal("1.10"), mapper.readValue("1.10", BigDecimal.class));
        assertEquals('x', mapper.readValue("\"x\"", char.class));
    }

    // ---- mapping errors name the path ----------------------------------------------------------------

    @Test
    void errorsPointAtTheOffendingValue() {
        JsonException wrongType = assertThrows(JsonException.class,
                () -> mapper.readValue("[{\"sku\":\"a\",\"quantity\":1},{\"sku\":\"b\",\"quantity\":\"many\"}]",
                        new TypeReference<List<Line>>() {}));
        assertTrue(wrongType.getMessage().startsWith("$[1].quantity: expected"), wrongType.getMessage());

        JsonException badEnum = assertThrows(JsonException.class,
                () -> mapper.readValue("{\"status\":\"NOPE\"}", Order.class));
        assertTrue(badEnum.getMessage().contains("$.status") && badEnum.getMessage().contains("[OPEN, CLOSED]"),
                badEnum.getMessage());

        JsonException badDate = assertThrows(JsonException.class,
                () -> mapper.readValue("{\"placed\":\"yesterday\"}", Order.class));
        assertTrue(badDate.getMessage().contains("$.placed"), badDate.getMessage());
    }

    @Test
    void numbersMustFitTheTarget() {
        assertThrows(JsonException.class, () -> mapper.readValue("1.5", int.class));
        assertThrows(JsonException.class, () -> mapper.readValue("3000000000", int.class));
        assertThrows(JsonException.class, () -> mapper.readValue("300", byte.class));
        assertEquals(3000000000L, mapper.readValue("3000000000", long.class));
        assertEquals(3, mapper.readValue("3.0", int.class), "3.0 is integral and accepted");
        assertThrows(JsonException.class, () -> mapper.readValue("null", int.class), "null into a primitive");
    }

    @Test
    void unknownPropertiesAreIgnoredUnlessConfiguredOtherwise() {
        assertEquals(new Line("a", 1), mapper.readValue("{\"sku\":\"a\",\"quantity\":1,\"extra\":true}", Line.class));
        JsonException e = assertThrows(JsonException.class,
                () -> new JsonMapper(true).readValue("{\"sku\":\"a\",\"quantity\":1,\"extra\":true}", Line.class));
        assertTrue(e.getMessage().contains("$.extra"), e.getMessage());
    }

    @Test
    void cannotBindToInterfacesOrClassesWithoutADefaultConstructor() {
        assertThrows(JsonException.class, () -> mapper.readValue("{}", Runnable.class));
        class NoDefault {
            NoDefault(int x) {
            }
        }
        assertThrows(JsonException.class, () -> mapper.readValue("{}", NoDefault.class));
    }

    @Test
    void roundTripsAnObjectGraph() {
        Order original = new Order(99, Status.CLOSED, List.of(new Line("x", 1), new Line("y", 2)),
                Optional.of("n\"ote"), LocalDate.of(2026, 12, 31));
        Order copy = mapper.readValue(mapper.writeValueAsString(original), Order.class);
        assertEquals(original, copy);
        Page<Order> page = new Page<>(List.of(original), 1);
        assertEquals(page, mapper.readValue(mapper.writeValueAsString(page), new TypeReference<Page<Order>>() {}));
    }
}
