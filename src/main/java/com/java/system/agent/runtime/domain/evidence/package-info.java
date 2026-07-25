/**
 * 支持回答的證據引用、語意座標與來源範圍
 *
 * <p>只依賴 {@code scope}，不依賴 {@code need}，讓 {@code need} 可以安全地引用本 package
 * 而不產生循環</p>
 */
package com.java.system.agent.runtime.domain.evidence;
