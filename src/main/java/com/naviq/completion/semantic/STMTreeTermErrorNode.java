package com.naviq.completion.semantic;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ErrorNodeImpl;

/**
 * Mirror STMTreeTermErrorNode của DBeaver.
 *
 * Node terminal ĐƯỢC ANTLR TỰ SINH RA trong lúc error-recovery (single-token
 * insertion/deletion, resync theo FOLLOW set) - KHÔNG phải token thật do người
 * dùng gõ. Chỉ khác ErrorNodeImpl mặc định ở chỗ: là 1 kiểu class RIÊNG BIỆT,
 * để code tiêu thụ cây (SemanticScope) có thể `instanceof` phát hiện ra và
 * KHÔNG TIN vào phần cây chứa nó.
 */
public class STMTreeTermErrorNode extends ErrorNodeImpl {
    public STMTreeTermErrorNode(Token t) {
        super(t);
    }
}
