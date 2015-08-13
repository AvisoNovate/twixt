package io.aviso.twixt.shims;

import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


/**
 * A shim for creating and executing the Google Closure JavaScript compiler.
 */
public class CompilerShim {

    public static List<Object> runCompiler(CompilerOptions options,
                                           CompilationLevel level,
                                           String filePath,
                                           InputStream source) throws IOException {
        Compiler compiler = new Compiler();

        compiler.disableThreads();

        level.setOptionsForCompilationLevel(options);

        SourceFile sourceFile = SourceFile.fromInputStream(filePath, source, StandardCharsets.UTF_8);

        List<SourceFile> externs = Collections.emptyList();
        List<SourceFile> sources = Arrays.asList(sourceFile);

        Result result = compiler.compile(externs, sources, options);

        List<Object> resultTuple = new ArrayList<Object>();

        if (result.success) {
            resultTuple.add(null);
            resultTuple.add(compiler.toSource());
        } else {
            resultTuple.add(result.errors);
        }

        return resultTuple;
    }
}
