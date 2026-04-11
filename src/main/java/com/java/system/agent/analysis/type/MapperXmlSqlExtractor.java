package com.java.system.agent.analysis.type;

import com.java.system.agent.analysis.parser.SourceRootResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** 從 MyBatis XML mapper 提取 SQL，按 namespace + methodId 快取。 */
@Slf4j
@Component
public class MapperXmlSqlExtractor {

    private static final Set<String> SQL_TAGS = Set.of("select", "insert", "update", "delete");

    private final DocumentBuilderFactory xmlFactory;
    private final SourceRootResolver sourceRootResolver;

    // Cache: repoRoot -> (namespace -> (methodId -> sql))
    private final Map<String, Map<String, Map<String, String>>> cache = new ConcurrentHashMap<>();

    public MapperXmlSqlExtractor(SourceRootResolver sourceRootResolver) {
        this.sourceRootResolver = sourceRootResolver;
        try {
            xmlFactory = DocumentBuilderFactory.newInstance();
            xmlFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            xmlFactory.setFeature("http://xml.org/sax/features/validation", false);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure XML parser factory", e);
        }
    }

    public void invalidate(Path repoRoot) {
        cache.remove(repoRoot.toString());
    }

    /** 查詢指定 mapper class + method 的 SQL。 */
    public Optional<String> findSql(String fullyQualifiedMapperClass, String methodId, Path repoRoot) {
        Map<String, Map<String, String>> repoCache = cache.computeIfAbsent(
                repoRoot.toString(), k -> buildCache(repoRoot));

        Map<String, String> methodSqlMap = repoCache.get(fullyQualifiedMapperClass);
        if (methodSqlMap == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(methodSqlMap.get(methodId));
    }

    // --- Internal ---

    private Map<String, Map<String, String>> buildCache(Path repoRoot) {
        Map<String, Map<String, String>> result = new HashMap<>();

        List<Path> resourceRoots = sourceRootResolver.resolveResourceRoots(repoRoot);
        if (CollectionUtils.isEmpty(resourceRoots)) {
            return result;
        }

        for (Path resourcesDir : resourceRoots) {
            try (Stream<Path> walk = Files.walk(resourcesDir)) {
                walk.filter(p -> p.toString().endsWith(".xml"))
                        .forEach(p -> parseXmlFile(p, result));
            } catch (IOException e) {
                log.error("Failed to scan mapper XML files under: {}", resourcesDir, e);
            }
        }

        log.debug("Built XML mapper cache for {} with {} namespaces", repoRoot, result.size());
        return result;
    }

    private void parseXmlFile(Path xmlPath, Map<String, Map<String, String>> result) {
        try {
            DocumentBuilder builder = xmlFactory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));

            Document doc = builder.parse(xmlPath.toFile());
            Element root = doc.getDocumentElement();

            if (!"mapper".equals(root.getNodeName())) {
                return;
            }

            String namespace = root.getAttribute("namespace");
            if (!StringUtils.hasText(namespace)) {
                return;
            }

            Map<String, String> methodSqlMap = result.computeIfAbsent(namespace, k -> new HashMap<>());

            for (String tag : SQL_TAGS) {
                NodeList nodes = root.getElementsByTagName(tag);
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element el = (Element) nodes.item(i);
                    String id = el.getAttribute("id");
                    if (StringUtils.hasText(id)) {
                        String sql = normalizeSql(el.getTextContent());
                        if (StringUtils.hasText(sql)) {
                            methodSqlMap.put(id, sql);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse mapper XML: {}", xmlPath, e);
        }
    }

    private String normalizeSql(String raw) {
        return raw.replaceAll("\\s+", " ").trim();
    }
}
