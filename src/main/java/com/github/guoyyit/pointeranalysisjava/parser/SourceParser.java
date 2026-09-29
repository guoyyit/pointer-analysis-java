package com.github.guoyyit.pointeranalysisjava.parser;

import com.github.guoyyit.pointeranalysisjava.ast.MethodDecl;
import com.github.guoyyit.pointeranalysisjava.ast.Program;
import com.github.guoyyit.pointeranalysisjava.ast.Stmt;
import com.github.guoyyit.pointeranalysisjava.model.SourceFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the supported pointer-analysis language into a {@link Program}.
 *
 * <p>Grammar (one method per declaration, single type namespace, no classes):</p>
 * <pre>
 * file     := { method }
 * method   := [ "entry" ] "void" IDENT "(" [ IDENT { "," IDENT } ] ")" "{" { stmt } "}"
 * stmt     := "new" IDENT "=" "new" IDENT "(" ")" ";"
 *           | "copy" IDENT "=" IDENT ";"
 *           | "load" IDENT "=" IDENT "." IDENT ";"
 *           | "store" IDENT "." IDENT "=" IDENT ";"
 *           | "call" IDENT "=" IDENT "(" [ IDENT { "," IDENT } ] ")" ";"
 *           | "return" [ IDENT ] ";"
 * </pre>
 * <p>Leading keywords new/copy/load/store/call/return classify the statement so the grammar
 * stays unambiguous and easy to parse deterministically.</p>
 */
public final class SourceParser {

    public Program parse(List<SourceFile> files) {
        Map<String, MethodDecl> methods = new LinkedHashMap<>();
        List<String> fileList = new ArrayList<>();
        for (SourceFile file : files.stream().sorted(java.util.Comparator.comparing(SourceFile::path))
                .toList()) {
            fileList.add(file.path());
            List<MethodDecl> parsed = parseFile(file.path(), file.content());
            for (MethodDecl method : parsed) {
                MethodDecl previous = methods.put(method.name(), method);
                if (previous != null) {
                    throw new ParseException("Duplicate method declaration: " + method.name());
                }
            }
        }
        return new Program(fileList, methods);
    }

    private List<MethodDecl> parseFile(String path, String content) {
        List<Token> tokens = tokenize(path, content);
        List<MethodDecl> methods = new ArrayList<>();
        int index = 0;
        while (index < tokens.size() - 1) {
            boolean entry = false;
            if (tokens.get(index).text.equals("entry")) {
                entry = true;
                index++;
            }
            if (index >= tokens.size() || !tokens.get(index).text.equals("void")) {
                throw error(tokens, index, "expected 'void' to start method declaration");
            }
            index++;
            String name = consumeIdent(tokens, index);
            index++;
            expect(tokens, index, "(");
            index++;
            List<String> params = new ArrayList<>();
            if (!tokens.get(index).text.equals(")")) {
                params.add(consumeIdent(tokens, index));
                index++;
                while (tokens.get(index).text.equals(",")) {
                    index++;
                    params.add(consumeIdent(tokens, index));
                    index++;
                }
            }
            expect(tokens, index, ")");
            index++;
            expect(tokens, index, "{");
            index++;
            List<Stmt> body = new ArrayList<>();
            while (!tokens.get(index).text.equals("}")) {
                Stmt statement;
                Token start = tokens.get(index);
                switch (start.text) {
                    case "new" -> {
                        index++;
                        String lhs = consumeIdent(tokens, index);
                        index++;
                        expect(tokens, index, "=");
                        index++;
                        expectText(tokens, index, "new");
                        index++;
                        String typeName = consumeIdent(tokens, index);
                        index++;
                        expect(tokens, index, "(");
                        index++;
                        expect(tokens, index, ")");
                        index++;
                        expect(tokens, index, ";");
                        index++;
                        statement = new Stmt.New(lhs, typeName, start.line);
                    }
                    case "copy" -> {
                        index++;
                        String lhs = consumeIdent(tokens, index);
                        index++;
                        expect(tokens, index, "=");
                        index++;
                        String rhs = consumeIdent(tokens, index);
                        index++;
                        expect(tokens, index, ";");
                        index++;
                        statement = new Stmt.Assign(lhs, rhs);
                    }
                    case "load" -> {
                        index++;
                        String lhs = consumeIdent(tokens, index);
                        index++;
                        expect(tokens, index, "=");
                        index++;
                        String base = consumeIdent(tokens, index);
                        index++;
                        expect(tokens, index, ".");
                        index++;
                        String field = consumeIdent(tokens, index);
                        index++;
                        expect(tokens, index, ";");
                        index++;
                        statement = new Stmt.Load(lhs, base, field);
                    }
                    case "store" -> {
                        index++;
                        String base = consumeIdent(tokens, index);
                        index++;
                        expect(tokens, index, ".");
                        index++;
                        String field = consumeIdent(tokens, index);
                        index++;
                        expect(tokens, index, "=");
                        index++;
                        String rhs = consumeIdent(tokens, index);
                        index++;
                        expect(tokens, index, ";");
                        index++;
                        statement = new Stmt.Store(base, field, rhs);
                    }
                    case "call" -> {
                        index++;
                        String lhs = consumeIdent(tokens, index);
                        index++;
                        expect(tokens, index, "=");
                        index++;
                        String callee = consumeIdent(tokens, index);
                        index++;
                        expect(tokens, index, "(");
                        index++;
                        List<String> args = new ArrayList<>();
                        if (!tokens.get(index).text.equals(")")) {
                            args.add(consumeIdent(tokens, index));
                            index++;
                            while (tokens.get(index).text.equals(",")) {
                                index++;
                                args.add(consumeIdent(tokens, index));
                                index++;
                            }
                        }
                        expect(tokens, index, ")");
                        index++;
                        expect(tokens, index, ";");
                        index++;
                        statement = new Stmt.Call(lhs, callee, args, start.line);
                    }
                    case "return" -> {
                        index++;
                        if (tokens.get(index).text.equals(";")) {
                            index++;
                            statement = new Stmt.Return(null);
                        } else {
                            String value = consumeIdent(tokens, index);
                            index++;
                            expect(tokens, index, ";");
                            index++;
                            statement = new Stmt.Return(value);
                        }
                    }
                    default -> throw error(tokens, index, "unexpected token in method body: "
                            + start.text);
                }
                body.add(statement);
            }
            index++;
            methods.add(new MethodDecl(path, name, List.copyOf(params), List.copyOf(body), entry));
        }
        return methods;
    }

    private String consumeIdent(List<Token> tokens, int index) {
        Token token = tokens.get(index);
        if (!isIdent(token.text)) {
            throw error(tokens, index, "expected identifier but found: " + token.text);
        }
        return token.text;
    }

    private void expect(List<Token> tokens, int index, String text) {
        if (!tokens.get(index).text.equals(text)) {
            throw error(tokens, index, "expected '" + text + "' but found: " + tokens.get(index).text);
        }
    }

    private void expectText(List<Token> tokens, int index, String text) {
        expect(tokens, index, text);
    }

    private ParseException error(List<Token> tokens, int index, String message) {
        Token token = index < tokens.size() ? tokens.get(index) : null;
        String location = token == null ? "end of file" : token.path + ":" + token.line;
        return new ParseException(message + " (" + location + ")");
    }

    private boolean isIdent(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        if (!Character.isJavaIdentifierStart(first)) {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            if (!Character.isJavaIdentifierPart(text.charAt(i))) {
                return false;
            }
        }
        return !java.util.Set.of("void", "new", "copy", "load", "store", "call", "return", "entry")
                .contains(text);
    }

    private List<Token> tokenize(String path, String content) {
        List<Token> tokens = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String line = lines[lineNumber];
            int column = 0;
            while (column < line.length()) {
                char current = line.charAt(column);
                if (Character.isWhitespace(current)) {
                    column++;
                    continue;
                }
                if (current == '/' && column + 1 < line.length() && line.charAt(column + 1) == '/') {
                    break;
                }
                if ("{}()=,.;".indexOf(current) >= 0) {
                    tokens.add(new Token(path, String.valueOf(current), lineNumber + 1));
                    column++;
                    continue;
                }
                int start = column;
                while (column < line.length()
                        && !Character.isWhitespace(line.charAt(column))
                        && "{}()=,.;".indexOf(line.charAt(column)) < 0) {
                    column++;
                }
                tokens.add(new Token(path, line.substring(start, column), lineNumber + 1));
            }
        }
        tokens.add(new Token(path, "<eof>", lines.length));
        return tokens;
    }

    private record Token(String path, String text, int line) {
    }
}
