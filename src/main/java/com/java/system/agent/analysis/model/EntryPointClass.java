package com.java.system.agent.analysis.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE
)
@Data
@SuperBuilder
@NoArgsConstructor
@Accessors(fluent = true)
public class EntryPointClass {
    @JsonPropertyDescription("類別名稱")
    private String className;
    @JsonPropertyDescription("檔案路徑，例如：com/project/module/api/controller/ClassName.java")
    private String packagePath;
    @JsonPropertyDescription("類別功能描述 (Javadoc)")
    private String description;
    @JsonPropertyDescription("層級一：基本 API 路徑 (Controller RequestMapping)")
    private String basePath;
    @JsonPropertyDescription("入口功能列表")
    private List<EntryPointMethod> methods;
}
