package com.java.system.agent.codeintelligence.semantic;

import com.java.system.agent.answering.domain.evidence.SemanticTarget;
import com.java.system.agent.answering.domain.evidence.SemanticTargetKind;
import com.java.system.agent.answering.domain.evidence.SourceRange;
import com.java.system.agent.answering.port.out.CapabilityExecutionContractException;
import com.java.system.agent.codeintelligence.semantic.dto.SemanticDtos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 在 Agent semantic candidate 與 provider target DTO 間可逆轉換的無狀態 mapper
 */
public final class JavaSemanticCandidateTargetMapper {

    private static final String METHOD_TARGET_KEY_VERSION = "mt1";
    private static final String METHOD_TARGET_TERMINATOR = ":!";

    private final JavaSemanticProviderSchemaValidator schemaValidator = new JavaSemanticProviderSchemaValidator();

    public boolean supportsMethodTarget(SemanticTarget target) {
        try {
            methodTarget(target);
            return true;
        } catch (CapabilityExecutionContractException exception) {
            return false;
        }
    }

    public SemanticDtos.MethodTargetPayload methodTarget(SemanticTarget target) {
        SemanticTarget requiredTarget = requireProviderObject(target, "semantic target");
        if (requiredTarget.kind() != SemanticTargetKind.SYMBOL) {
            throw contract("semantic target does not contain an exact method target");
        }
        List<String> parts = decodeMethodTargetKey(requiredTarget.key());
        if (parts.size() < 4) {
            throw contract("semantic target does not contain an exact method target");
        }
        List<String> parameterTypes = new ArrayList<>();
        for (int index = 4; index < parts.size(); index++) {
            parameterTypes.add(parts.get(index));
        }
        SemanticDtos.MethodTarget methodTarget = new SemanticDtos.MethodTarget(parts.get(0), parts.get(1), parts.get(2),
                parts.get(3), List.copyOf(parameterTypes));
        SemanticDtos.MethodTarget requiredMethodTarget = schemaValidator.methodTarget(methodTarget);
        return new SemanticDtos.MethodTargetPayload(new SemanticDtos.SourceTypeIdentityPayload(
                new SemanticDtos.JavaTypeIdentityPayload(requiredMethodTarget.packageName(), requiredMethodTarget.className()),
                requiredMethodTarget.sourceFile()), requiredMethodTarget.methodName(), requiredMethodTarget.parameterTypes());
    }

    public SemanticDtos.SourceTypeIdentityPayload sourceType(SemanticTarget target) {
        return methodTarget(target).sourceType();
    }

    public boolean supportsSourceRange(SemanticTarget target) {
        if (Objects.isNull(target) || target.kind() != SemanticTargetKind.SOURCE_RANGE) {
            return false;
        }
        return target.sourceRange().isPresent() && target.key().equals(target.sourceRange().orElseThrow().sourcePath());
    }

    public SemanticDtos.SourceRangePayload sourceRange(SemanticTarget target) {
        if (!supportsSourceRange(target)) {
            throw contract("semantic target does not contain an exact source range");
        }
        SourceRange sourceRange = target.sourceRange().orElseThrow();
        return new SemanticDtos.SourceRangePayload(sourceRange.sourcePath(), new SemanticDtos.TextRangePayload(
                new SemanticDtos.Position(sourceRange.startLine() - 1, sourceRange.startColumn() - 1),
                new SemanticDtos.Position(sourceRange.endLine() - 1, sourceRange.endColumn() - 1)));
    }

    public SemanticTarget semanticTarget(SemanticDtos.MethodTarget target) {
        SemanticDtos.MethodTarget requiredTarget = schemaValidator.methodTarget(target);
        List<String> parameters = requiredList(requiredTarget.parameterTypes(), "method target parameter type");
        List<String> keyParts = new ArrayList<>();
        keyParts.add(requiredTarget.sourceFile());
        keyParts.add(requiredTarget.packageName());
        keyParts.add(requiredTarget.className());
        keyParts.add(requiredTarget.methodName());
        keyParts.addAll(parameters);
        return new SemanticTarget(SemanticTargetKind.SYMBOL, encodeMethodTargetKey(keyParts), Optional.empty());
    }

    public SemanticTarget semanticTarget(SemanticDtos.SourceRangePayload range) {
        SemanticDtos.SourceRangePayload required = requireProviderObject(range, "source location");
        SemanticDtos.TextRangePayload textRange = requireProviderObject(required.range(), "source location range");
        SourceRange sourceRange = new SourceRange(required.sourceFile(), textRange.start().line() + 1,
                textRange.start().character() + 1, textRange.end().line() + 1, textRange.end().character() + 1);
        return new SemanticTarget(SemanticTargetKind.SOURCE_RANGE, required.sourceFile(), Optional.of(sourceRange));
    }

    private String encodeMethodTargetKey(List<String> components) {
        StringBuilder encoded = new StringBuilder(METHOD_TARGET_KEY_VERSION).append(':').append(components.size());
        for (String component : components) {
            encoded.append(':').append(component.length()).append(':').append(component);
        }
        return encoded.append(METHOD_TARGET_TERMINATOR).toString();
    }

    private List<String> decodeMethodTargetKey(String key) {
        String requiredKey = requireProviderObject(key, "semantic target key");
        String prefix = METHOD_TARGET_KEY_VERSION + ":";
        if (!requiredKey.startsWith(prefix)) {
            throw contract("semantic target key has an unsupported method target version");
        }
        int countEnd = requiredKey.indexOf(':', prefix.length());
        if (countEnd < 0) {
            throw contract("semantic target key is missing a component count delimiter");
        }
        int count = parseCanonicalNumber(requiredKey.substring(prefix.length(), countEnd), "component count");
        if (count < 4) {
            throw contract("semantic target key has too few components");
        }
        int position = countEnd;
        List<String> components = new ArrayList<>();
        for (int componentIndex = 0; componentIndex < count; componentIndex++) {
            if (position >= requiredKey.length() || requiredKey.charAt(position) != ':') {
                throw contract("semantic target key has an invalid component prefix");
            }
            int lengthEnd = requiredKey.indexOf(':', position + 1);
            if (lengthEnd < 0) {
                throw contract("semantic target key is missing a component length delimiter");
            }
            int length = parseCanonicalNumber(requiredKey.substring(position + 1, lengthEnd), "component length");
            int valueStart = lengthEnd + 1;
            if (length > requiredKey.length() - valueStart) {
                throw contract("semantic target key component length exceeds remaining content");
            }
            int valueEnd = valueStart + length;
            components.add(requiredKey.substring(valueStart, valueEnd));
            position = valueEnd;
        }
        if (!requiredKey.startsWith(METHOD_TARGET_TERMINATOR, position)
                || position + METHOD_TARGET_TERMINATOR.length() != requiredKey.length()) {
            throw contract("semantic target key has trailing or missing terminal content");
        }
        return List.copyOf(components);
    }

    private int parseCanonicalNumber(String value, String description) {
        if (value.isEmpty()) {
            throw contract("semantic target key " + description + " is empty");
        }
        if (value.length() > 1 && value.charAt(0) == '0') {
            throw contract("semantic target key " + description + " is noncanonical");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw contract("semantic target key " + description + " is not numeric");
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw contract("semantic target key " + description + " is invalid");
        }
    }

    private static <T> List<T> requiredList(List<T> values, String description) {
        if (Objects.isNull(values)) {
            throw contract(description + " list must not be null");
        }
        List<T> copied = new ArrayList<>();
        for (T value : values) {
            copied.add(requireProviderObject(value, description + " must not contain null values"));
        }
        return List.copyOf(copied);
    }

    private static <T> T requireProviderObject(T value, String description) {
        if (Objects.isNull(value)) {
            throw contract(description + " must not be null");
        }
        return value;
    }

    private static CapabilityExecutionContractException contract(String message) {
        return new CapabilityExecutionContractException(message);
    }
}
