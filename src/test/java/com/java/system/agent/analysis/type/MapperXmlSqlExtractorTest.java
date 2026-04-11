package com.java.system.agent.analysis.type;

import com.java.system.agent.analysis.parser.SourceRootResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for MapperXmlSqlExtractor.
 * Uses repos/test which contains OrderMapper.xml as a fixture.
 */
public class MapperXmlSqlExtractorTest {

    private static final String ORDER_MAPPER = "com.example.mybatisplus.OrderMapper";

    private MapperXmlSqlExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new MapperXmlSqlExtractor(new SourceRootResolver());
    }

    @Test
    void should_find_sql_when_mapper_xml_contains_select_statement() {
        // OrderMapper.xml has <select id="findByOrderNo" ...>
        Optional<String> sql = extractor.findSql(ORDER_MAPPER, "findByOrderNo", Paths.get("repos/test"));

        assertTrue(sql.isPresent(), "SQL should be found for findByOrderNo");
        assertTrue(sql.get().contains("orders"), "SQL should reference the table");
        assertTrue(sql.get().contains("order_no"), "SQL should reference the order_no column");
    }

    @Test
    void should_return_empty_when_method_not_in_xml() {
        Optional<String> sql = extractor.findSql(ORDER_MAPPER, "nonExistentMethod", Paths.get("repos/test"));

        assertFalse(sql.isPresent(), "Should return empty for unknown method");
    }

    @Test
    void should_return_empty_when_namespace_not_found() {
        Optional<String> sql = extractor.findSql(
                "com.example.NonExistentMapper", "someMethod", Paths.get("repos/test"));

        assertFalse(sql.isPresent(), "Should return empty for unknown namespace");
    }

    @Test
    void should_normalize_whitespace_in_extracted_sql() {
        // OrderMapper.xml SQL is written with extra indentation and newlines
        Optional<String> sql = extractor.findSql(ORDER_MAPPER, "findByOrderNo", Paths.get("repos/test"));

        assertTrue(sql.isPresent());
        assertFalse(sql.get().contains("\n"), "SQL should not contain newlines after normalization");
        assertFalse(sql.get().contains("  "), "SQL should not contain double spaces after normalization");
    }

    @Test
    void should_use_cache_on_second_call() {
        Optional<String> first = extractor.findSql(ORDER_MAPPER, "findByOrderNo", Paths.get("repos/test"));
        Optional<String> second = extractor.findSql(ORDER_MAPPER, "findByOrderNo", Paths.get("repos/test"));

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertTrue(first.get().equals(second.get()), "Cached result should match original");
    }

    @Test
    void should_clear_cache_after_reload() {
        extractor.findSql(ORDER_MAPPER, "findByOrderNo", Paths.get("repos/test"));

        extractor.invalidate(Paths.get("repos/test"));

        Optional<String> sql = extractor.findSql(ORDER_MAPPER, "findByOrderNo", Paths.get("repos/test"));

        assertTrue(sql.isPresent(), "SQL should still be found after reload");
    }

    @Test
    void should_not_find_annotation_based_mapper_in_xml() {
        // UserMapper uses @Select annotations — no XML file defines it
        Optional<String> sql = extractor.findSql(
                "com.example.repository.UserMapper", "findById", Paths.get("repos/test"));

        assertFalse(sql.isPresent(),
                "UserMapper.findById is annotation-based; XML extractor should return empty");
    }
}
