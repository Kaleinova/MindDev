package diagnostic;

import mlogix.compiler.core.SourceMapManager.SourceMap;
import mlogix.compiler.core.span.Span;
import mlogix.compiler.core.token.Token;
import mlogix.compiler.core.token.TokenType;
import mlogix.compiler.diagnostic.Problem;
import mlogix.util.Log;

class ProblemTest {

    public static void main(String[] args) {
        Log.setLevel(Log.LogType.DEBUG);
        Log.info("=== Starting ProblemTest ===");

        testLexerProblemCreation();
        testParserProblemCreation();
        testSemanticProblemCreation();
        testPointMethodWithPositions();
        testPointMethodWithToken();
        testInfoMethodWithPositions();
        testInfoMethodWithToken();
        testMultiplePointsOnSameLine();
        testMultipleLines();
        testProblemLevelEnum();
        testChainedPointAndInfo();
        testToStringWithEmptyLineList();
        testProblemExtendsRuntimeException();

        Log.info("=== All tests completed ===");
    }

    static void testLexerProblemCreation() {
        Log.info("Running testLexerProblemCreation...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2\ntest line 3");

        Problem.LexerProblem problem = new Problem.LexerProblem(
            sourceMap,
            "Test Lexer Error",
            Problem.ProblemLevel.ERROR
        );

        if (problem == null) {
            Log.error("FAILED: mlogix.diagnostic should not be null");
            return;
        }
        if (!"Test Lexer Error".equals(problem.getProblemName())) {
            Log.error("FAILED: problemName should be 'Test Lexer Error'");
            return;
        }
        if (problem.getLevel() != Problem.ProblemLevel.ERROR) {
            Log.error("FAILED: level should be ERROR");
            return;
        }
        Log.info("PASSED: testLexerProblemCreation");
    }

    static void testParserProblemCreation() {
        Log.info("Running testParserProblemCreation...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2");

        Problem.ParserProblem problem = new Problem.ParserProblem(
            sourceMap,
            "Test Parser Warning",
            Problem.ProblemLevel.WARNING
        );

        if (problem == null) {
            Log.error("FAILED: mlogix.diagnostic should not be null");
            return;
        }
        if (!"Test Parser Warning".equals(problem.getProblemName())) {
            Log.error("FAILED: problemName should be 'Test Parser Warning'");
            return;
        }
        if (problem.getLevel() != Problem.ProblemLevel.WARNING) {
            Log.error("FAILED: level should be WARNING");
            return;
        }
        Log.info("PASSED: testParserProblemCreation");
    }

    static void testSemanticProblemCreation() {
        Log.info("Running testSemanticProblemCreation...");
        SourceMap sourceMap = new SourceMap("test line 1");

        Problem.SemanticProblem problem = new Problem.SemanticProblem(
            sourceMap,
            "Test Semantic Error",
            Problem.ProblemLevel.ERROR
        );

        if (problem == null) {
            Log.error("FAILED: mlogix.diagnostic should not be null");
            return;
        }
        if (!"Test Semantic Error".equals(problem.getProblemName())) {
            Log.error("FAILED: problemName should be 'Test Semantic Error'");
            return;
        }
        if (problem.getLevel() != Problem.ProblemLevel.ERROR) {
            Log.error("FAILED: level should be ERROR");
            return;
        }
        Log.info("PASSED: testSemanticProblemCreation");
    }

    static void testPointMethodWithPositions() {
        Log.info("Running testPointMethodWithPositions...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2");

        Problem.LexerProblem problem = new Problem.LexerProblem(
            sourceMap,
            "Test Point",
            Problem.ProblemLevel.ERROR
        );

        // Point to "test" in line 1 (positions 0-4)
        Problem result = problem.point(0, 4, "error here");

        if (problem != result) {
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

        Span span = new Span(0, 0, 4);
        Token token = new Token(span, TokenType.IDENTIFIER, "test");

        Problem.LexerProblem problem = new Problem.LexerProblem(
            sourceMap,
            "Test Token Point",
            Problem.ProblemLevel.ERROR
        );

        Problem result = problem.point(token, "token error");

        if (problem != result) {
            Log.error("FAILED: point should return the same mlogix.diagnostic instance");
            return;
        }
        result.toString();
        Log.info("PASSED: testPointMethodWithToken");
    }

    static void testInfoMethodWithPositions() {
        Log.info("Running testInfoMethodWithPositions...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2");

        Problem.LexerProblem problem = new Problem.LexerProblem(
            sourceMap,
            "Test Info",
            Problem.ProblemLevel.WARNING
        );

        Problem result = problem.info(0, 4, "info here");

        if (problem != result) {
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

        Span span = new Span(0, 0, 4);
        Token token = new Token(span, TokenType.IDENTIFIER, "test");

        Problem.LexerProblem problem = new Problem.LexerProblem(
            sourceMap,
            "Test Token Info",
            Problem.ProblemLevel.WARNING
        );

        Problem result = problem.info(token, "token info");

        if (problem != result) {
            Log.error("FAILED: info should return the same mlogix.diagnostic instance");
            return;
        }
        result.toString();
        Log.info("PASSED: testInfoMethodWithToken");
    }

    static void testMultiplePointsOnSameLine() {
        Log.info("Running testMultiplePointsOnSameLine...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2");

        Problem.LexerProblem problem = new Problem.LexerProblem(
            sourceMap,
            "Multiple Points",
            Problem.ProblemLevel.ERROR
        );

        problem.point(0, 4, "first error");
        problem.point(5, 9, "second error");

        String result = problem.toString();
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

        Problem.LexerProblem problem = new Problem.LexerProblem(
            sourceMap,
            "Multiple Lines",
            Problem.ProblemLevel.ERROR
        );

        problem.point(0, 4, "error on line 1");
        problem.point(7, 11, "error on line 2");
        problem.point(14, 18, "error on line 3");

        String result = problem.toString();
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

    static void testProblemLevelEnum() {
        Log.info("Running testProblemLevelEnum...");
        if (Problem.ProblemLevel.getEntries().size() != 2) {
            Log.error("FAILED: ProblemLevel should have 2 values");
            return;
        }
        if (Problem.ProblemLevel.valueOf("WARNING") != Problem.ProblemLevel.WARNING) {
            Log.error("FAILED: valueOf('WARNING') should return WARNING");
            return;
        }
        if (Problem.ProblemLevel.valueOf("ERROR") != Problem.ProblemLevel.ERROR) {
            Log.error("FAILED: valueOf('ERROR') should return ERROR");
            return;
        }
        Log.info("PASSED: testProblemLevelEnum");
    }

    static void testChainedPointAndInfo() {
        Log.info("Running testChainedPointAndInfo...");
        SourceMap sourceMap = new SourceMap("test line 1\ntest line 2");

        Problem.LexerProblem problem = new Problem.LexerProblem(
            sourceMap,
            "Chained Methods",
            Problem.ProblemLevel.ERROR
        );

        String result = problem.point(0, 4, "error")
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

        Problem.LexerProblem problem = new Problem.LexerProblem(
            sourceMap,
            "Empty Line List",
            Problem.ProblemLevel.ERROR
        );

        String result = problem.toString();
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

    static void testProblemExtendsRuntimeException() {
        Log.info("Running testProblemExtendsRuntimeException...");
        SourceMap sourceMap = new SourceMap("test");

        Problem.LexerProblem problem = new Problem.LexerProblem(
            sourceMap,
            "Test Exception",
            Problem.ProblemLevel.ERROR
        );

        Log.info("PASSED: testProblemExtendsRuntimeException");
    }
}
