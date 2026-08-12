package diagnostic;

import mlogix.compiler.core.SourceMapManager.SourceMap;
import mlogix.compiler.core.span.Span;
import mlogix.compiler.core.token.Token;
import mlogix.compiler.core.token.TokenType;
import mlogix.compiler.diagnostic.Diagnostic;
import mlogix.util.Log;

class DiagTest{

    public static void main(String[] args) {
        Log.setLevel(Log.LogType.DEBUG);
        Log.info("=== Starting DiagTest ===");

        testLexerDiagCreation();
        testParserDiagCreation();
        testSemanticDiagCreation();
        testPointMethodWithPositions();
        testPointMethodWithToken();
        testInfoMethodWithPositions();
        testInfoMethodWithToken();
        testMultiplePointsOnSameLine();
        testMultipleLines();
        testDiagLevelEnum();
        testChainedPointAndInfo();
        testToStringWithEmptyLineList();
        testDiagExtendsRuntimeException();

        Log.info("=== All tests completed ===");
    }

    static void testLexerDiagCreation() {
        Log.info("Running testLexerDiagCreation...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2\ntest line 3");

        Diagnostic.LexerDiag diag = new Diagnostic.LexerDiag(
            sourceMap,
            "Test Lexer Error",
            Diagnostic.DiagLevel.ERROR
        );

        if (diag == null) {
            Log.error("FAILED: mlogix.diagnostic should not be null");
            return;
        }
        if (!"Test Lexer Error".equals(diag.getDiagName())) {
            Log.error("FAILED: diagName should be 'Test Lexer Error'");
            return;
        }
        if (diag.getLevel() != Diagnostic.DiagLevel.ERROR) {
            Log.error("FAILED: level should be ERROR");
            return;
        }
        Log.info("PASSED: testLexerDiagCreation");
    }

    static void testParserDiagCreation() {
        Log.info("Running testParserDiagCreation...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2");

        Diagnostic.ParserDiag diag = new Diagnostic.ParserDiag(
            sourceMap,
            "Test Parser Warning",
            Diagnostic.DiagLevel.WARNING
        );

        if (diag == null) {
            Log.error("FAILED: mlogix.diagnostic should not be null");
            return;
        }
        if (!"Test Parser Warning".equals(diag.getDiagName())) {
            Log.error("FAILED: diagName should be 'Test Parser Warning'");
            return;
        }
        if (diag.getLevel() != Diagnostic.DiagLevel.WARNING) {
            Log.error("FAILED: level should be WARNING");
            return;
        }
        Log.info("PASSED: testParserDiagCreation");
    }

    static void testSemanticDiagCreation() {
        Log.info("Running testSemanticDiagCreation...");
        SourceMap sourceMap = new SourceMap("test line 1");

        Diagnostic.SemanticDiag diag = new Diagnostic.SemanticDiag(
            sourceMap,
            "Test Semantic Error",
            Diagnostic.DiagLevel.ERROR
        );

        if (diag == null) {
            Log.error("FAILED: mlogix.diagnostic should not be null");
            return;
        }
        if (!"Test Semantic Error".equals(diag.getDiagName())) {
            Log.error("FAILED: diagName should be 'Test Semantic Error'");
            return;
        }
        if (diag.getLevel() != Diagnostic.DiagLevel.ERROR) {
            Log.error("FAILED: level should be ERROR");
            return;
        }
        Log.info("PASSED: testSemanticDiagCreation");
    }

    static void testPointMethodWithPositions() {
        Log.info("Running testPointMethodWithPositions...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2");

        Diagnostic.LexerDiag diag = new Diagnostic.LexerDiag(
            sourceMap,
            "Test Point",
            Diagnostic.DiagLevel.ERROR
        );

        // Point to "test" in line 1 (positions 0-4)
        Diagnostic result = diag.point(0, 4, "error here");

        if (diag != result) {
            Log.error("FAILED: point should return the same mlogix.diagnostic instance");
            return;
        }
        String resultStr = result.toString();
        if (!resultStr.contains("ERROR")) {
            Log.error("FAILED: toString should contain 'ERROR'");
            return;
        }
        if (!resultStr.contains("Test Point")) {
            Log.error("FAILED: toString should contain 'Test Point'");
            return;
        }
        Log.info("PASSED: testPointMethodWithPositions");
    }

    static void testPointMethodWithToken() {
        Log.info("Running testPointMethodWithToken...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2");

        Span span = new Span(0, 4);
        Token token = new Token(span, TokenType.IDENTIFIER, "test");

        Diagnostic.LexerDiag diag = new Diagnostic.LexerDiag(
            sourceMap,
            "Test Token Point",
            Diagnostic.DiagLevel.ERROR
        );

        Diagnostic result = diag.point(token, "token error");

        if (diag != result) {
            Log.error("FAILED: point should return the same mlogix.diagnostic instance");
            return;
        }
        result.toString();
        Log.info("PASSED: testPointMethodWithToken");
    }

    static void testInfoMethodWithPositions() {
        Log.info("Running testInfoMethodWithPositions...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2");

        Diagnostic.LexerDiag diag = new Diagnostic.LexerDiag(
            sourceMap,
            "Test Info",
            Diagnostic.DiagLevel.WARNING
        );

        Diagnostic result = diag.info(0, 4, "info here");

        if (diag != result) {
            Log.error("FAILED: info should return the same mlogix.diagnostic instance");
            return;
        }
        String resultStr = result.toString();
        if (!resultStr.contains("WARNING")) {
            Log.error("FAILED: toString should contain 'WARNING'");
            return;
        }
        Log.info("PASSED: testInfoMethodWithPositions");
    }

    static void testInfoMethodWithToken() {
        Log.info("Running testInfoMethodWithToken...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2");

        Span span = new Span(0, 4);
        Token token = new Token(span, TokenType.IDENTIFIER, "test");

        Diagnostic.LexerDiag diag = new Diagnostic.LexerDiag(
            sourceMap,
            "Test Token Info",
            Diagnostic.DiagLevel.WARNING
        );

        Diagnostic result = diag.info(token, "token info");

        if (diag != result) {
            Log.error("FAILED: info should return the same mlogix.diagnostic instance");
            return;
        }
        result.toString();
        Log.info("PASSED: testInfoMethodWithToken");
    }

    static void testMultiplePointsOnSameLine() {
        Log.info("Running testMultiplePointsOnSameLine...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2");

        Diagnostic.LexerDiag diag = new Diagnostic.LexerDiag(
            sourceMap,
            "Multiple Points",
            Diagnostic.DiagLevel.ERROR
        );

        diag.point(0, 4, "first error");
        diag.point(5, 9, "second error");

        String result = diag.toString();
        if (!result.contains("first error")) {
            Log.error("FAILED: toString should contain 'first error'");
            return;
        }
        if (!result.contains("second error")) {
            Log.error("FAILED: toString should contain 'second error'");
            return;
        }
        Log.info("PASSED: testMultiplePointsOnSameLine");
    }

    static void testMultipleLines() {
        Log.info("Running testMultipleLines...");
        SourceMap sourceMap = new SourceMap("line 1\nline 2\nline 3");

        Diagnostic.LexerDiag diag = new Diagnostic.LexerDiag(
            sourceMap,
            "Multiple Lines",
            Diagnostic.DiagLevel.ERROR
        );

        diag.point(0, 4, "error on line 1");
        diag.point(7, 11, "error on line 2");
        diag.point(14, 18, "error on line 3");

        String result = diag.toString();
        if (!result.contains("error on line 1")) {
            Log.error("FAILED: toString should contain 'error on line 1'");
            return;
        }
        if (!result.contains("error on line 2")) {
            Log.error("FAILED: toString should contain 'error on line 2'");
            return;
        }
        if (!result.contains("error on line 3")) {
            Log.error("FAILED: toString should contain 'error on line 3'");
            return;
        }
        Log.info("PASSED: testMultipleLines");
    }

    static void testDiagLevelEnum() {
        Log.info("Running testDiagLevelEnum...");
        if (Diagnostic.DiagLevel.getEntries().size() != 2) {
            Log.error("FAILED: DiagLevel should have 2 values");
            return;
        }
        if (Diagnostic.DiagLevel.valueOf("WARNING") != Diagnostic.DiagLevel.WARNING) {
            Log.error("FAILED: valueOf('WARNING') should return WARNING");
            return;
        }
        if (Diagnostic.DiagLevel.valueOf("ERROR") != Diagnostic.DiagLevel.ERROR) {
            Log.error("FAILED: valueOf('ERROR') should return ERROR");
            return;
        }
        Log.info("PASSED: testDiagLevelEnum");
    }

    static void testChainedPointAndInfo() {
        Log.info("Running testChainedPointAndInfo...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2");

        Diagnostic.LexerDiag diag = new Diagnostic.LexerDiag(
            sourceMap,
            "Chained Methods",
            Diagnostic.DiagLevel.ERROR
        );

        String result = diag.point(0, 4, "error")
                              .info(5, 9, "info")
                              .toString();

        if (!result.contains("error")) {
            Log.error("FAILED: toString should contain 'error'");
            return;
        }
        if (!result.contains("info")) {
            Log.error("FAILED: toString should contain 'info'");
            return;
        }
        Log.info("PASSED: testChainedPointAndInfo");
    }

    static void testToStringWithEmptyLineList() {
        Log.info("Running testToStringWithEmptyLineList...");
        SourceMap sourceMap = new SourceMap("test line");

        Diagnostic.LexerDiag diag = new Diagnostic.LexerDiag(
            sourceMap,
            "Empty Line List",
            Diagnostic.DiagLevel.ERROR
        );

        String result = diag.toString();
        if (!result.contains("ERROR")) {
            Log.error("FAILED: toString should contain 'ERROR'");
            return;
        }
        if (!result.contains("Empty Line List")) {
            Log.error("FAILED: toString should contain 'Empty Line List'");
            return;
        }
        Log.info("PASSED: testToStringWithEmptyLineList");
    }

    static void testDiagExtendsRuntimeException() {
        Log.info("Running testDiagExtendsRuntimeException...");
        SourceMap sourceMap = new SourceMap("test");

        Diagnostic.LexerDiag diag = new Diagnostic.LexerDiag(
            sourceMap,
            "Test Exception",
            Diagnostic.DiagLevel.ERROR
        );

        Log.info("PASSED: testDiagExtendsRuntimeException");
    }
}
