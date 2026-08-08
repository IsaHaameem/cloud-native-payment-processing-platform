package com.paymentflow.sdk.codegen;

import com.paymentflow.openapi.OpenApiYaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the published contract and produces every generated SDK file (M22.1).
 *
 * <p>Since M23.1 it also writes the developer portal's copy of the TypeScript (D189), so the
 * portal reads the contract through the same one reader the SDKs do rather than through a
 * second, ungated description of it.
 *
 * <p>Pure: it takes the document's text and returns a map from repository-relative path to
 * file content. Nothing here touches the filesystem, which is what lets the whole generator be
 * tested by ordinary unit tests rather than only through a Gradle invocation — the convention
 * {@code :openapi-tools}' build file states and this module follows.
 */
public final class SdkCodegen {

    /** The generated output, plus whatever the reader could not describe. */
    public record Result(Map<String, String> files, List<String> unsupported) {

        public Result {
            files = Map.copyOf(files);
            unsupported = List.copyOf(unsupported);
        }
    }

    private SdkCodegen() {
    }

    /**
     * @param specYaml the contents of {@code docs/openapi.yaml}
     * @return every file the SDKs' generated trees should contain, keyed by its path relative
     *         to the repository root
     */
    public static Result generate(String specYaml) {
        SdkSpecReader reader = new SdkSpecReader();
        SdkSpec spec = reader.read(OpenApiYaml.read(specYaml));

        Map<String, String> files = new LinkedHashMap<>();
        files.putAll(TypeScriptEmitter.emit(spec));
        files.putAll(PythonEmitter.emit(spec));
        files.putAll(FixtureEmitter.emit(spec));

        return new Result(files, reader.unsupported());
    }

    /**
     * The directories the generator owns entirely.
     *
     * <p>Owning a directory rather than a file list is what makes deletion detectable: a model
     * removed from the contract leaves an orphaned file behind, and an SDK that still exports
     * a type the API no longer has is a worse lie than one missing a type it does. The write
     * path clears these; the check path fails on anything in them the generator did not
     * produce.
     */
    public static List<String> generatedDirectories() {
        return List.of(TypeScriptEmitter.DIRECTORY, TypeScriptEmitter.PORTAL_DIRECTORY,
                PythonEmitter.DIRECTORY, FixtureEmitter.DIRECTORY);
    }
}
