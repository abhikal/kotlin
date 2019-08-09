/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen;

import com.google.common.collect.Lists;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import kotlin.text.StringsKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.cli.common.modules.ModuleBuilder;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.load.kotlin.ModuleVisibilityManager;
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.test.ConfigurationKind;
import org.jetbrains.kotlin.test.InTextDirectivesUtils;
import org.jetbrains.kotlin.test.KotlinTestUtils;
import org.jetbrains.kotlin.utils.ExceptionUtilsKt;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public abstract class AbstractCompileKotlinAgainstKotlinTest extends CodegenTestCase {
    private File tmpdir;
    private File[] dirs;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        tmpdir = KotlinTestUtils.tmpDirForTest(this);
    }

    @Override
    protected void doMultiFileTest(@NotNull File wholeFile, @NotNull List<TestFile> files) {
        boolean isIgnored = InTextDirectivesUtils.isIgnoredTarget(getBackend(), wholeFile);
        doMultiFileTest(files, !isIgnored);
    }

    @NotNull
    protected List<ClassFileFactory> doMultiFileTest(@NotNull List<TestFile> files, boolean reportProblems) {
        //// Note that it may be beneficial to improve this test to handle many files, compiling them successively against all previous
        //assert files.size() == 2 || (files.size() == 3 && files.get(2).name.equals("CoroutineUtil.kt")) : "There should be exactly two files in this test";

        dirs = new File[files.size()];
        for (int i = 0; i < files.size(); i++) {
            dirs[i] = new File(tmpdir, Integer.toString(i));
            KotlinTestUtils.mkdirs(dirs[i]);
        }

        List<TestFile> filesA;
        List<TestFile> otherFiles;
        // Special case
        if (files.size() == 3 && files.get(2).name.equals("CoroutineUtil.kt")) {
            filesA = Arrays.asList(files.get(0), files.get(2));
            otherFiles = Collections.singletonList(files.get(1));
        } else {
            filesA = Collections.singletonList(files.get(0));
            otherFiles = files.subList(1, files.size());
        }
        List<ClassFileFactory> factories = new ArrayList<ClassFileFactory>();
        factories.add(compileFirst(filesA, files));
        try {
            for (int i = 0; i < otherFiles.size(); i++) {
                TestFile otherFile = otherFiles.get(i);
                factories.add(compileOther(otherFile, files, i + 1));
            }
            invokeBox(PackagePartClassUtils.getFilePartShortName(new File(otherFiles.get(otherFiles.size() - 1).name).getName()));
        }
        catch (Throwable e) {
            if (reportProblems) {
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < factories.size(); i++) {
                    if (i > 0) {
                        result.append("\n\n");
                    }
                    result.append("\n\nFile#" + (i + 1) + ": \n\n" + factories.get(i).createText());
                }
                System.out.println(result);
            }
            throw ExceptionUtilsKt.rethrow(e);
        }
        return factories;
    }

    private void invokeBox(@NotNull String className) throws Exception {
        callBoxMethodAndCheckResult(createGeneratedClassLoader(), className);
    }

    @NotNull
    private URLClassLoader createGeneratedClassLoader() throws Exception {
        URL[] urls = new URL[dirs.length];
        for (int i = 0; i < dirs.length; i++) {
            urls[i] = dirs[i].toURI().toURL();
        }
        return new URLClassLoader(
                urls,
                ForTestCompileRuntime.runtimeAndReflectJarClassLoader()
        );
    }

    @NotNull
    private ClassFileFactory compileFirst(List<TestFile> filesToCompile, List<TestFile> files) {
        Disposable compileDisposable = createDisposable("0");
        CompilerConfiguration configuration = createConfiguration(
                ConfigurationKind.ALL, getJdkKind(files), Collections.singletonList(KotlinTestUtils.getAnnotationsJar()),
                Collections.emptyList(), Collections.singletonList(filesToCompile.get(0))
        );

        configuration.put(CommonConfigurationKeys.MODULE_NAME, "0");

        KotlinCoreEnvironment environment = KotlinCoreEnvironment.createForTests(
                compileDisposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES);

        return compileKotlin(filesToCompile, dirs[0], environment, compileDisposable);
    }

    @NotNull
    private ClassFileFactory compileOther(@NotNull TestFile testFile, List<TestFile> files, int fileNo) {
        File outDir = dirs[fileNo];
        File importedDir = dirs[fileNo - 1];
        String commonHeader = StringsKt.substringBefore(files.get(0).content, "FILE:", "");
        CompilerConfiguration configuration = createConfiguration(
                ConfigurationKind.ALL, getJdkKind(files), Lists.newArrayList(KotlinTestUtils.getAnnotationsJar(), importedDir),
                Collections.emptyList(), Lists.newArrayList(testFile, new TestFile("header", commonHeader))
        );

        configuration.put(CommonConfigurationKeys.MODULE_NAME, Integer.toString(fileNo));

        Disposable compileDisposable = createDisposable(Integer.toString(fileNo));
        KotlinCoreEnvironment environment = KotlinCoreEnvironment.createForTests(
                compileDisposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES
        );

        return compileKotlin(Collections.singletonList(testFile), outDir, environment, compileDisposable);
    }

    private Disposable createDisposable(String debugName) {
        Disposable disposable = Disposer.newDisposable("CompileDisposable" + debugName);
        Disposer.register(getTestRootDisposable(), disposable);
        return disposable;
    }

    @NotNull
    private ClassFileFactory compileKotlin(
            @NotNull List<TestFile> files,
            @NotNull File outputDir,
            @NotNull KotlinCoreEnvironment environment,
            @NotNull Disposable disposable
    ) {

        List<KtFile> ktFiles =
                files.stream().map(file -> KotlinTestUtils.createFile(file.name, file.content, environment.getProject()))
                        .collect(Collectors.toList());

        ModuleVisibilityManager.SERVICE.getInstance(environment.getProject()).addModule(
                new ModuleBuilder("module for test", tmpdir.getAbsolutePath(), "test")
        );

        ClassFileFactory outputFiles = GenerationUtils.compileFilesTo(ktFiles, environment, outputDir);

        Disposer.dispose(disposable);
        return outputFiles;
    }
}
