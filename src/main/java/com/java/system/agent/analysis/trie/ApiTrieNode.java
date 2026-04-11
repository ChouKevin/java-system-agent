package com.java.system.agent.analysis.trie;

import java.util.HashMap;
import java.util.Map;

/** API path trie 節點。 */
class ApiTrieNode {

    /** Fixed key used to store path-variable segments such as {id}, {userId}, etc. */
    static final String WILDCARD = "{*}";

    /** segment → child node */
    final Map<String, ApiTrieNode> children = new HashMap<>();

    /** HTTP method (uppercase) → ref — populated only at leaf nodes */
    final Map<String, ApiEntryPointRef> methodMap = new HashMap<>();
}
