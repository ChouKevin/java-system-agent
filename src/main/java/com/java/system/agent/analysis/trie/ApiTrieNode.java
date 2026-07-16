package com.java.system.agent.analysis.trie;

import java.util.HashMap;
import java.util.Map;

/** API path trie 節點 */
class ApiTrieNode {

    /** Fixed key used to store path-variable segments such as {id}, {userId}, etc. */
    static final String WILDCARD = ApiPathNormalizer.ONE_SEGMENT_WILDCARD;

    /** Fixed key used to store terminal catch-all segments such as {*path}. */
    static final String REST_WILDCARD = ApiPathNormalizer.REST_WILDCARD;

    /** 未指定 HTTP verb 的通用 method key */
    static final String METHOD_ALL = "ALL";

    /** segment → child node */
    final Map<String, ApiTrieNode> children = new HashMap<>();

    /** HTTP method (uppercase) → repo ID → ref — populated only at leaf nodes */
    final Map<String, Map<String, ApiEntryPointRef>> methodMap = new HashMap<>();
}
