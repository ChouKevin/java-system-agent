package com.java.system.agent.runtime.port.out;

import java.util.List;

/**
 * 把使用者原始問題轉譯成 repository 候選與 {@code InformationNeed} 清單
 */
public interface QuestionUnderstandingPort {

    QuestionUnderstanding understand(String question, List<RepositoryDescriptor> candidates);
}
