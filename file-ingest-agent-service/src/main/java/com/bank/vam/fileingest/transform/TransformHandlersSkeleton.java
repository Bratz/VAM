package com.bank.vam.fileingest.transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The fixed scaffolding for the transform-handlers repo (design doc: "own repo, not inside the
 * vam-portal monorepo" — this writes it fresh into the ingest workspace on first use rather than
 * cloning anything, since there's no existing remote to clone from). The coding agent only ever
 * adds a new generated.&lt;package&gt; package on top of this; it never touches these files.
 */
final class TransformHandlersSkeleton {

    private TransformHandlersSkeleton() {
    }

    static void writeInto(Path repoRoot) throws IOException {
        write(repoRoot.resolve("pom.xml"), POM_XML);
        write(repoRoot.resolve(".gitignore"), "target/\n");
        write(repoRoot.resolve("src/main/java/com/bank/vam/transformhandlers/RowTransform.java"), ROW_TRANSFORM);
        write(repoRoot.resolve("src/main/java/com/bank/vam/transformhandlers/TransformRunnerMain.java"), TRANSFORM_RUNNER_MAIN);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static final String POM_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.bank.vam</groupId>
                <artifactId>transform-handlers</artifactId>
                <version>1.0.0</version>
                <packaging>jar</packaging>

                <properties>
                    <maven.compiler.source>21</maven.compiler.source>
                    <maven.compiler.target>21</maven.compiler.target>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                </properties>

                <dependencies>
                    <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                        <version>2.17.0</version>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.10.2</version>
                        <scope>test</scope>
                    </dependency>
                    <dependency>
                        <groupId>org.assertj</groupId>
                        <artifactId>assertj-core</artifactId>
                        <version>3.25.3</version>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """;

    private static final String ROW_TRANSFORM = """
            package com.bank.vam.transformhandlers;

            import java.nio.file.Path;
            import java.util.List;
            import java.util.Map;

            /**
             * Contract every generated transform implements: read the customer's raw file and
             * produce rows in the canonical shape file-ingest-agent-service expects (mirrors its
             * own TransformedRow — this repo takes no Java dependency on that service; the two are
             * connected only by this field-naming contract and by TransformRunnerMain being run as
             * a separate process).
             *
             * Each row map has exactly these keys: amount (a plain decimal string), currency
             * (3-letter code), viban, debtorName, debtorAccount, remittanceInfo, reference. Use an
             * empty string for any field the source file doesn't actually contain.
             */
            public interface RowTransform {
                List<Map<String, Object>> transform(Path sourceFile) throws Exception;
            }
            """;

    private static final String TRANSFORM_RUNNER_MAIN = """
            package com.bank.vam.transformhandlers;

            import com.fasterxml.jackson.databind.ObjectMapper;

            import java.nio.file.Path;
            import java.util.List;
            import java.util.Map;

            /**
             * Fixed entry point file-ingest-agent-service shells out to for every generated
             * transform — generated code only ever implements RowTransform, never its own main/IO
             * glue. args[0] = fully-qualified RowTransform class name, args[1] = source file path.
             * Prints the row list as JSON to stdout and nothing else, so the caller can parse it.
             */
            public final class TransformRunnerMain {

                private TransformRunnerMain() {
                }

                public static void main(String[] args) throws Exception {
                    if (args.length != 2) {
                        System.err.println("Usage: TransformRunnerMain <fully-qualified RowTransform class> <source file>");
                        System.exit(2);
                    }
                    Class<?> clazz = Class.forName(args[0]);
                    RowTransform transform = (RowTransform) clazz.getDeclaredConstructor().newInstance();
                    List<Map<String, Object>> rows = transform.transform(Path.of(args[1]));
                    new ObjectMapper().writeValue(System.out, rows);
                }
            }
            """;
}
