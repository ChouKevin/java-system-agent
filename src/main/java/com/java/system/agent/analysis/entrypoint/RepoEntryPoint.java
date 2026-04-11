package com.java.system.agent.analysis.entrypoint;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.java.system.agent.analysis.model.EntryPointClass;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
@Data
@SuperBuilder
@NoArgsConstructor
@Accessors(fluent = true)
public class RepoEntryPoint {
    @JsonPropertyDescription("Repository name")
    private String repoId;
    @JsonPropertyDescription("List of entry points")
    private List<EntryPointClass> entryPoints;


    public String toFormattedString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("專案：%s\n", repoId()));
        for (EntryPointClass epClass : entryPoints) {
            sb.append(String.format("  類別：%s (%s)\n", epClass.className(), epClass.packagePath()));
            if (epClass.description() != null && !epClass.description().isEmpty()) {
                sb.append(String.format("    描述：%s\n", epClass.description()));
            }
            if (epClass.methods() != null) {
                for (var method : epClass.methods()) {
                    sb.append(String.format("    方法：%s", method.name()));
                        if (method.description() != null && !method.description().isEmpty()) {
                            sb.append(String.format(" - %s", method.description()));
                        }
                        sb.append("\n");
                    }
                }
            }
            sb.append("\n");
        return sb.toString();
    }
}
