package com.java.system.agent.analysis.type;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScopeTypeResolver 測試
 */
class ScopeTypeResolverTest {

    private ScopeTypeResolver scopeTypeResolver;
    private JavaParser parser;

    @BeforeEach
    void setUp() {
        scopeTypeResolver = new ScopeTypeResolver();
        parser = new JavaParser(new ParserConfiguration());
    }

    @Test
    void should_resolve_baseMapper_type_from_ServiceImpl_generic_parameter() {
        String serviceCode =
                "package com.example.service;\n" +
                "import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;\n" +
                "public class MyServiceImpl extends ServiceImpl<MyMapper, MyEntity> {\n" +
                "    public void doWork() {\n" +
                "        baseMapper.findAll();\n" +
                "    }\n" +
                "}";

        CompilationUnit cu = parser.parse(serviceCode).getResult().get();
        ClassOrInterfaceDeclaration cls = cu.findAll(ClassOrInterfaceDeclaration.class).get(0);
        MethodDeclaration method = cls.getMethods().get(0);

        Optional<String> result = scopeTypeResolver.inferTypeName("baseMapper", method, cls);

        assertTrue(result.isPresent(), "Should resolve baseMapper from ServiceImpl generic type arg");
        assertEquals("MyMapper", result.get(), "Should return the first generic type parameter (M)");
    }

    // ── Static method call ────────────────────────────────────────────────

    @Test
    void should_resolve_static_call_by_uppercase_scope() {
        String code =
                "package com.example;\n" +
                "public class MyService {\n" +
                "    public void process() {\n" +
                "        MyUtils.doSomething();\n" +
                "    }\n" +
                "}";

        CompilationUnit cu = parser.parse(code).getResult().get();
        ClassOrInterfaceDeclaration cls = cu.findAll(ClassOrInterfaceDeclaration.class).get(0);
        MethodDeclaration method = cls.getMethods().get(0);
        MethodCallExpr call = method.findAll(MethodCallExpr.class).get(0);

        Optional<String> result = scopeTypeResolver.inferTypeName(call, method, cls);

        assertTrue(result.isPresent(), "首字母大寫 scope 應視為 static call 回傳類別名");
        assertEquals("MyUtils", result.get());
    }

    @Test
    void should_prefer_field_over_static_fallback() {
        String code =
                "package com.example;\n" +
                "public class MyService {\n" +
                "    private OrderService OrderService;\n" +
                "    public void process() {\n" +
                "        OrderService.save();\n" +
                "    }\n" +
                "}";

        CompilationUnit cu = parser.parse(code).getResult().get();
        ClassOrInterfaceDeclaration cls = cu.findAll(ClassOrInterfaceDeclaration.class).get(0);
        MethodDeclaration method = cls.getMethods().get(0);
        MethodCallExpr call = method.findAll(MethodCallExpr.class).get(0);

        Optional<String> result = scopeTypeResolver.inferTypeName(call, method, cls);

        assertTrue(result.isPresent());
        assertEquals("OrderService", result.get(), "欄位查找應優先於 static fallback");
    }

    @Test
    void should_return_empty_for_lowercase_unresolved_scope() {
        String code =
                "package com.example;\n" +
                "public class MyService {\n" +
                "    public void process() {\n" +
                "        unknown.doSomething();\n" +
                "    }\n" +
                "}";

        CompilationUnit cu = parser.parse(code).getResult().get();
        ClassOrInterfaceDeclaration cls = cu.findAll(ClassOrInterfaceDeclaration.class).get(0);
        MethodDeclaration method = cls.getMethods().get(0);
        MethodCallExpr call = method.findAll(MethodCallExpr.class).get(0);

        Optional<String> result = scopeTypeResolver.inferTypeName(call, method, cls);

        assertTrue(result.isEmpty(), "首字母小寫的未知 scope 不應走 static fallback");
    }

    // ── baseMapper ──────────────────────────────────────────────────────────

    @Test
    void should_return_empty_when_baseMapper_but_no_ServiceImpl_extends() {
        String serviceCode =
                "package com.example.service;\n" +
                "public class PlainClass {\n" +
                "    public void doWork() {}\n" +
                "}";

        CompilationUnit cu = parser.parse(serviceCode).getResult().get();
        ClassOrInterfaceDeclaration cls = cu.findAll(ClassOrInterfaceDeclaration.class).get(0);
        MethodDeclaration method = cls.getMethods().get(0);

        Optional<String> result = scopeTypeResolver.inferTypeName("baseMapper", method, cls);

        assertTrue(result.isEmpty(), "Should return empty when class does not extend ServiceImpl");
    }


}
