package com.github.guoyyit.pointeranalysisjava.parser;

import com.github.guoyyit.pointeranalysisjava.ir.JClass;
import com.github.guoyyit.pointeranalysisjava.ir.JMethod;
import com.github.guoyyit.pointeranalysisjava.ir.Program;
import com.github.guoyyit.pointeranalysisjava.ir.Stmt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Parses source files of the supported Java subset into a {@link Program}.
 *
 * <p>Supported forms (one statement per line):
 * <ul>
 *   <li>{@code [Type] lhs = new Type();}</li>
 *   <li>{@code lhs = rhs;}</li>
 *   <li>{@code lhs = base.field;} / {@code base.field = rhs;}</li>
 *   <li>{@code [lhs = ] receiver.ClassName.method(args);} (virtual)</li>
 *   <li>{@code [lhs = ] ClassName.method(args);} (static)</li>
 *   <li>{@code return [value];}</li>
 * </ul>
 */
public final class SourceParser {

    private SourceParser() {
    }

    public static Program parse(Map<String, String> pathToContent) {
        TreeMap<String, JClass> classes = new TreeMap<>();
        TreeMap<String, List<String>> classesByFile = new TreeMap<>();
        TreeMap<String, String> sourceHashByFile = new TreeMap<>();

        List<String> paths = new ArrayList<>(pathToContent.keySet());
        Collections.sort(paths);
        for (String path : paths) {
            String content = pathToContent.get(path);
            sourceHashByFile.put(path, sha256(content));
            List<Token> tokens = Lexer.tokenize(path, content);
            List<JClass> fileClasses = new ClassParser(path, tokens).parseClasses();
            List<String> classNames = new ArrayList<>();
            for (JClass parsed : fileClasses) {
                if (classes.putIfAbsent(parsed.name(), parsed) != null) {
                    throw new ParseException("duplicate class definition: " + parsed.name());
                }
                classNames.add(parsed.name());
            }
            classesByFile.put(path, List.copyOf(classNames));
        }
        return new Program(classes, classesByFile, sourceHashByFile);
    }

    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Token(String text, int line, String file) {
    }

    private static final class Lexer {

        private static final Set<Character> SYMBOLS =
                new HashSet<>(List.of('{', '}', '(', ')', '.', ';', ',', '='));

        static List<Token> tokenize(String file, String content) {
            List<Token> tokens = new ArrayList<>();
            int line = 1;
            int i = 0;
            int n = content.length();
            while (i < n) {
                char c = content.charAt(i);
                if (c == '\n') {
                    line++;
                    i++;
                } else if (c == '\r' || c == '\t' || c == ' ') {
                    i++;
                } else if (c == '/' && i + 1 < n && content.charAt(i + 1) == '/') {
                    while (i < n && content.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < n && content.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < n
                            && !(content.charAt(i) == '*' && content.charAt(i + 1) == '/')) {
                        if (content.charAt(i) == '\n') {
                            line++;
                        }
                        i++;
                    }
                    if (i + 1 >= n) {
                        throw new ParseException(file + ": unterminated block comment");
                    }
                    i += 2;
                } else if (SYMBOLS.contains(c)) {
                    tokens.add(new Token(String.valueOf(c), line, file));
                    i++;
                } else if (Character.isJavaIdentifierStart(c)) {
                    int start = i;
                    while (i < n && Character.isJavaIdentifierPart(content.charAt(i))) {
                        i++;
                    }
                    tokens.add(new Token(content.substring(start, i), line, file));
                } else {
                    throw new ParseException(file + ":" + line + ": unexpected character '"
                            + c + "'");
                }
            }
            return tokens;
        }
    }

    private static final class ClassParser {

        private static final Set<String> METHOD_MODIFIERS =
                Set.of("public", "private", "protected", "static", "final", "abstract");

        private final String file;
        private final List<Token> tokens;
        private int pos;

        ClassParser(String file, List<Token> tokens) {
            this.file = file;
            this.tokens = tokens;
        }

        List<JClass> parseClasses() {
            List<JClass> result = new ArrayList<>();
            while (pos < tokens.size()) {
                skipModifiers();
                expectKeyword("class");
                Token name = consumeIdent();
                String superName = null;
                if (lookingAtKeyword("extends")) {
                    pos++;
                    superName = consumeIdent().text();
                }
                expectSymbol("{");
                List<JMethod> methods = new ArrayList<>();
                Set<String> methodKeys = new TreeSet<>();
                while (!symbolAhead("}")) {
                    skipFieldDeclarationIfPresent();
                    if (symbolAhead("}")) {
                        break;
                    }
                    JMethod method = parseMethod(name.text());
                    if (!methodKeys.add(method.key())) {
                        throw new ParseException(file + ": duplicate method " + method.key());
                    }
                    methods.add(method);
                }
                expectSymbol("}");
                result.add(new JClass(name.text(), superName, methods));
            }
            return result;
        }

        private JMethod parseMethod(String className) {
            int headerStart = pos;
            while (pos < tokens.size() && !symbolAhead("(")) {
                pos++;
            }
            if (pos >= tokens.size() || pos - headerStart < 1) {
                throw new ParseException(file + ": malformed method header");
            }
            boolean isStatic = false;
            for (int i = headerStart; i < pos; i++) {
                if ("static".equals(tokens.get(i).text())) {
                    isStatic = true;
                }
            }
            String methodName = tokens.get(pos - 1).text();
            expectSymbol("(");
            List<String> params = new ArrayList<>();
            if (!symbolAhead(")")) {
                do {
                    consumeIdent();
                    params.add(consumeIdent().text());
                } while (consumeCommaIfPresent());
            }
            expectSymbol(")");
            expectSymbol("{");
            List<Stmt> body = new ArrayList<>();
            while (!symbolAhead("}")) {
                body.addAll(parseStatements(className, methodName));
            }
            expectSymbol("}");
            return new JMethod(className, methodName, params, isStatic, body);
        }

        private List<Stmt> parseStatements(String className, String methodName) {
            int lineNo = tokens.get(pos).line();
            List<Token> line = new ArrayList<>();
            while (pos < tokens.size() && tokens.get(pos).line() == lineNo) {
                line.add(tokens.get(pos));
                pos++;
            }
            String site = file + ":" + lineNo;
            if (line.isEmpty() || !";".equals(line.get(line.size() - 1).text())) {
                throw new ParseException(site + ": statement must end with ';' in "
                        + className + "#" + methodName);
            }
            line.remove(line.size() - 1);

            if ("return".equals(line.get(0).text())) {
                if (line.size() == 1) {
                    return List.of(new Stmt.Return(site, null));
                }
                if (line.size() != 2) {
                    throw new ParseException(site + ": malformed return");
                }
                return List.of(new Stmt.Return(site, line.get(1).text()));
            }

            int eq = indexOfSymbol(line, "=");
            if (eq < 0) {
                InvokeParts invoke = parseInvoke(site, line);
                return List.of(new Stmt.Invoke(site, null, invoke.receiver(), invoke.className(),
                        invoke.method(), invoke.args(), invoke.isStatic()));
            }

            List<Token> lhs = new ArrayList<>(line.subList(0, eq));
            List<Token> rhs = new ArrayList<>(line.subList(eq + 1, line.size()));
            if (rhs.isEmpty()) {
                throw new ParseException(site + ": missing right-hand side");
            }

            if (lhs.size() == 1) {
                return List.of(buildRhs(site, lhs.get(0).text(), rhs));
            } else if (lhs.size() == 2) {
                return List.of(buildRhs(site, lhs.get(1).text(), rhs));
            } else if (lhs.size() == 3 && ".".equals(lhs.get(1).text())) {
                String value;
                List<Stmt> extra = List.of();
                if (rhs.size() == 1) {
                    value = rhs.get(0).text();
                } else {
                    value = "$store$" + lineNo;
                    extra = List.of(buildRhs(site + "@init", value, rhs));
                }
                List<Stmt> result = new ArrayList<>(extra);
                result.add(new Stmt.Store(site + "@store", lhs.get(0).text(),
                        lhs.get(2).text(), value));
                return result;
            } else {
                throw new ParseException(site + ": malformed left-hand side");
            }
        }

        private Stmt buildRhs(String site, String target, List<Token> rhs) {
            if ("new".equals(rhs.get(0).text())) {
                if (rhs.size() != 4
                        || !"(".equals(rhs.get(2).text())
                        || !")".equals(rhs.get(3).text())) {
                    throw new ParseException(site + ": malformed object allocation");
                }
                return new Stmt.New(site, target, rhs.get(1).text(), site + "#" + target);
            }
            if (")".equals(rhs.get(rhs.size() - 1).text())) {
                InvokeParts invoke = parseInvoke(site, rhs);
                return new Stmt.Invoke(site, target, invoke.receiver(), invoke.className(),
                        invoke.method(), invoke.args(), invoke.isStatic());
            }
            if (rhs.size() == 3 && ".".equals(rhs.get(1).text())) {
                return new Stmt.Load(site, target, rhs.get(0).text(), rhs.get(2).text());
            }
            if (rhs.size() == 1) {
                return new Stmt.Assign(site, target, rhs.get(0).text());
            }
            throw new ParseException(site + ": unsupported right-hand side expression");
        }

        private InvokeParts parseInvoke(String site, List<Token> line) {
            int paren = -1;
            for (int i = 0; i < line.size(); i++) {
                if ("(".equals(line.get(i).text())) {
                    paren = i;
                    break;
                }
            }
            if (paren < 0 || !")".equals(line.get(line.size() - 1).text())) {
                throw new ParseException(site + ": malformed invocation");
            }
            List<Token> nameParts = line.subList(0, paren);
            List<String> dotted = new ArrayList<>();
            for (int i = 0; i < nameParts.size(); i++) {
                if (i % 2 == 0) {
                    dotted.add(nameParts.get(i).text());
                } else if (!".".equals(nameParts.get(i).text())) {
                    throw new ParseException(site + ": malformed invocation target");
                }
            }
            if (nameParts.isEmpty() || nameParts.size() % 2 == 0) {
                throw new ParseException(site + ": malformed invocation target");
            }
            List<String> args = new ArrayList<>();
            int argStart = paren + 1;
            while (argStart < line.size() - 1) {
                args.add(line.get(argStart).text());
                argStart++;
                if (argStart < line.size() - 1) {
                    if (!",".equals(line.get(argStart).text())) {
                        throw new ParseException(site + ": malformed argument list");
                    }
                    argStart++;
                }
            }
            String method = dotted.get(dotted.size() - 1);
            if (dotted.size() == 2) {
                String first = dotted.get(0);
                if (isClassName(first)) {
                    return new InvokeParts(null, first, method, args, true);
                }
                return new InvokeParts(first, first, method, args, false);
            }
            if (dotted.size() == 3) {
                return new InvokeParts(dotted.get(0), dotted.get(1), method, args, false);
            }
            throw new ParseException(site + ": invocation must be ClassName.method or "
                    + "receiver.ClassName.method");
        }

        private boolean isClassName(String token) {
            return !token.isEmpty() && Character.isUpperCase(token.charAt(0));
        }

        private record InvokeParts(String receiver, String className, String method,
                                  List<String> args, boolean isStatic) {
        }

        private void skipModifiers() {
            while (pos < tokens.size() && METHOD_MODIFIERS.contains(tokens.get(pos).text())) {
                pos++;
            }
        }

        private void skipFieldDeclarationIfPresent() {
            int lineNo = tokens.get(pos).line();
            int scan = pos;
            boolean isField = false;
            while (scan < tokens.size() && tokens.get(scan).line() == lineNo) {
                String text = tokens.get(scan).text();
                if (";".equals(text)) {
                    isField = true;
                    break;
                }
                if ("(".equals(text) || "{".equals(text)) {
                    return;
                }
                scan++;
            }
            if (isField) {
                pos = scan + 1;
            }
        }

        private boolean consumeCommaIfPresent() {
            if (symbolAhead(",")) {
                pos++;
                return true;
            }
            return false;
        }

        private int indexOfSymbol(List<Token> line, String symbol) {
            for (int i = 0; i < line.size(); i++) {
                if (symbol.equals(line.get(i).text())) {
                    return i;
                }
            }
            return -1;
        }

        private boolean symbolAhead(String symbol) {
            return pos < tokens.size() && symbol.equals(tokens.get(pos).text());
        }

        private boolean lookingAtKeyword(String keyword) {
            return pos < tokens.size() && keyword.equals(tokens.get(pos).text());
        }

        private void expectSymbol(String symbol) {
            if (!symbolAhead(symbol)) {
                throw new ParseException(file + ": expected '" + symbol + "'"
                        + (pos < tokens.size() ? " but found '" + tokens.get(pos).text()
                        + "' at line " + tokens.get(pos).line() : " at end of file"));
            }
            pos++;
        }

        private void expectKeyword(String keyword) {
            if (!lookingAtKeyword(keyword)) {
                throw new ParseException(file + ": expected keyword '" + keyword + "'"
                        + (pos < tokens.size() ? " but found '" + tokens.get(pos).text()
                        + "' at line " + tokens.get(pos).line() : " at end of file"));
            }
            pos++;
        }

        private Token consumeIdent() {
            if (pos >= tokens.size()) {
                throw new ParseException(file + ": expected identifier at end of file");
            }
            Token token = tokens.get(pos);
            if (!Character.isJavaIdentifierStart(token.text().charAt(0))) {
                throw new ParseException(file + ":" + token.line()
                        + ": expected identifier but found '" + token.text() + "'");
            }
            pos++;
            return token;
        }
    }
}
