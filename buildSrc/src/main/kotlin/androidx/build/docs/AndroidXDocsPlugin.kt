/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.build.docs

import androidx.build.SupportConfig
import androidx.build.addToBuildOnServer
import androidx.build.doclava.DacOptions
import androidx.build.doclava.DoclavaTask
import androidx.build.doclava.GENERATE_DOCS_CONFIG
import androidx.build.doclava.androidJarFile
import androidx.build.doclava.createGenerateSdkApiTask
import androidx.build.dokka.Dokka
import androidx.build.getBuildId
import androidx.build.getCheckoutRoot
import androidx.build.getDistributionDirectory
import androidx.build.gradle.getByType
import com.android.build.api.attributes.BuildTypeAttr
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.LibraryPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.JvmLibrary
import org.gradle.kotlin.dsl.named
import org.gradle.language.base.artifact.SourcesArtifact
import org.jetbrains.dokka.gradle.DokkaAndroidTask
import org.jetbrains.dokka.gradle.PackageOptions
import java.io.File

/**
 * Plugin that allows to build documentation for a given set of prebuilt and tip of tree projects.
 */
class AndroidXDocsPlugin : Plugin<Project> {
    lateinit var project: Project
    lateinit var docsType: String
    lateinit var tipOfTreeSources: Configuration
    lateinit var prebuiltSources: Configuration
    lateinit var tipOfTreeSampleSources: Configuration
    lateinit var prebuiltSampleSources: Configuration
    lateinit var dependencyClasspath: FileCollection

    override fun apply(project: Project) {
        this.project = project
        docsType = project.name.removePrefix("docs-")
        project.plugins.all { plugin ->
            when (plugin) {
                is LibraryPlugin -> {
                    val libraryExtension = project.extensions.getByType<LibraryExtension>()
                    libraryExtension.compileSdkVersion = SupportConfig.COMPILE_SDK_VERSION
                    libraryExtension.buildToolsVersion = SupportConfig.BUILD_TOOLS_VERSION
                }
            }
        }
        disableUnneededTasks()
        createConfigurations()

        val unzippedSamplesSources = File(project.buildDir, "unzippedSampleSources")
        val unzipSamplesTask = configureUnzipTask(
            "unzipSampleSources",
            unzippedSamplesSources,
            tipOfTreeSampleSources,
            prebuiltSampleSources
        )
        val unzippedDocsSources = File(project.buildDir, "unzippedDocsSources")
        val unzipDocsTask = configureUnzipTask(
            "unzipDocsSources",
            unzippedDocsSources,
            tipOfTreeSources,
            prebuiltSources
        )

        configureDokka(
            unzippedDocsSources,
            unzipDocsTask,
            unzippedSamplesSources,
            unzipSamplesTask,
            dependencyClasspath
        )
        configureDoclava(
            unzippedDocsSources,
            unzipDocsTask,
            dependencyClasspath
        )
    }

    /**
     * Creates and configures a task that will build a list of all sources for projects in
     * [tipOfTreeSourcesConfiguration] and [prebuiltSourcesConfiguration] configurations, resolve
     * them and put them to [destinationDirectory].
     */
    private fun configureUnzipTask(
        taskName: String,
        destinationDirectory: File,
        tipOfTreeSourcesConfiguration: Configuration,
        prebuiltSourcesConfiguration: Configuration
    ): TaskProvider<Sync> {
        @Suppress("UnstableApiUsage")
        return project.tasks.register(
            taskName,
            Sync::class.java
        ) { task ->
            val sourcesTipOfTree = tipOfTreeSourcesConfiguration.incoming.artifactView { }.files
            task.from(
                sourcesTipOfTree.elements.map { jars ->
                    jars.map { project.zipTree(it).addFilters() }
                }
            )
            task.dependsOn(prebuiltSourcesConfiguration)
            task.from(
                project.provider {
                    val componentIds =
                        prebuiltSourcesConfiguration.incoming.resolutionResult.allDependencies.map {
                            dependency ->
                            dependency as ResolvedDependencyResult
                            dependency.selected.id
                        }
                    val result = project.dependencies.createArtifactResolutionQuery()
                        .forComponents(componentIds)
                        .withArtifacts(JvmLibrary::class.java, SourcesArtifact::class.java)
                        .execute()
                    val sourcesFromPrebuilts = mutableListOf<File>()
                    for (component in result.resolvedComponents) {
                        component.getArtifacts(SourcesArtifact::class.java).forEach {
                            it as ResolvedArtifactResult
                            sourcesFromPrebuilts.add(it.file)
                        }
                    }
                    sourcesFromPrebuilts.map { project.zipTree(it).addFilters() }
                }
            )
            task.into(destinationDirectory)
            // TODO(123020809) remove this filter once it is no longer necessary to prevent Dokka
            //  from failing
            val regex = Regex("@attr ref ([^*]*)styleable#([^_*]*)_([^*]*)$")
            task.filter { line ->
                regex.replace(line, "{@link $1attr#$3}")
            }
        }
    }

    private fun createConfigurations() {
        // TODO(aurimas): merge tipOfTree/tipOfTreeSamples and prebuilt/prebuiltSamples
        // configurations when Android Lint supports us adding a new variant to the runtimeElements
        // configuration.
        // The following configurations are created to build a list of projects that need to be
        // documented and should be used from build.gradle of docs projects for the following:
        // * tipOfTree(project(":foo:foo") - docs from tip of tree sources
        // * prebuilt("androidx.foo:foo:1.0.0") - docs from prebuilt sources
        // * tipOfTreeSamples(project(":foo:foo-samples") - samples from tip of tree sources
        // * prebuiltSamples("androidx.foo:foo-samples:1.0.0") - samples from prebuilt sources
        // * stubs(project(":foo:foo-stubs")) - stubs needed for a documented library
        val tipOfTreeSourcesConfiguration = project.configurations.create("tipOfTree") {
            it.isCanBeResolved = false
            it.isCanBeConsumed = false
        }
        val prebuiltSourcesConfiguration = project.configurations.create("prebuilt") {
            it.isCanBeResolved = false
            it.isCanBeConsumed = false
        }
        val tipOfTreeSamplesConfiguration = project.configurations.create("tipOfTreeSamples") {
            it.isCanBeResolved = false
            it.isCanBeConsumed = false
        }
        val prebuiltSamplesConfiguration = project.configurations.create("prebuiltSamples") {
            it.isCanBeResolved = false
            it.isCanBeConsumed = false
        }
        val stubsConfiguration = project.configurations.create("stubs") {
            it.isCanBeResolved = false
            it.isCanBeConsumed = false
        }

        tipOfTreeSources = project.configurations.create("tip-of-tree-docs-sources") {
            it.isTransitive = false
            it.isCanBeConsumed = false
            it.attributes {
                it.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
                it.attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling.EXTERNAL))
                it.attribute(
                    Category.CATEGORY_ATTRIBUTE, project.objects.named(Category.DOCUMENTATION)
                )
                it.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, project.objects.named(DocsType.SOURCES))
            }
            it.extendsFrom(tipOfTreeSourcesConfiguration)
        }

        prebuiltSources = project.configurations.create("prebuilt-docs-sources") {
            it.isTransitive = false
            it.isCanBeConsumed = false
            it.attributes {
                it.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
                it.attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling.EXTERNAL))
            }
            it.extendsFrom(prebuiltSourcesConfiguration)
        }

        tipOfTreeSampleSources = project.configurations.create("tip-of-tree-sample-sources") {
            it.isTransitive = false
            it.isCanBeConsumed = false
            it.attributes {
                it.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
                it.attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling.EXTERNAL))
                it.attribute(
                    Category.CATEGORY_ATTRIBUTE, project.objects.named(Category.DOCUMENTATION)
                )
                it.attribute(DocsType.DOCS_TYPE_ATTRIBUTE, project.objects.named(DocsType.SOURCES))
            }
            it.extendsFrom(tipOfTreeSamplesConfiguration)
        }

        prebuiltSampleSources = project.configurations.create("prebuilt-sample-sources") {
            it.isTransitive = false
            it.isCanBeConsumed = false
            it.attributes {
                it.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
                it.attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling.EXTERNAL))
            }
            it.extendsFrom(prebuiltSamplesConfiguration)
        }

        // Build a compile & runtime classpaths for needed for documenting the libraries
        // from the configurations above.
        val docsCompileClasspath = project.configurations.create("docs-compile-classpath") {
            it.isCanBeConsumed = false
            it.attributes {
                it.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_API))
                it.attribute(BuildTypeAttr.ATTRIBUTE, project.objects.named("release"))
            }
            it.extendsFrom(
                tipOfTreeSourcesConfiguration,
                prebuiltSourcesConfiguration,
                tipOfTreeSamplesConfiguration,
                prebuiltSamplesConfiguration,
                stubsConfiguration
            )
        }
        val docsRuntimeClasspath = project.configurations.create("docs-runtime-classpath") {
            it.isCanBeConsumed = false
            it.attributes {
                it.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage.JAVA_RUNTIME))
                it.attribute(BuildTypeAttr.ATTRIBUTE, project.objects.named("release"))
            }
            it.extendsFrom(
                tipOfTreeSourcesConfiguration,
                prebuiltSourcesConfiguration,
                tipOfTreeSamplesConfiguration,
                prebuiltSamplesConfiguration,
                stubsConfiguration
            )
        }
        dependencyClasspath = docsCompileClasspath.incoming.artifactView {
            it.attributes.attribute(
                Attribute.of("artifactType", String::class.java),
                "android-classes"
            )
        }.files + docsRuntimeClasspath.incoming.artifactView {
            it.attributes.attribute(
                Attribute.of("artifactType", String::class.java),
                "android-classes"
            )
        }.files
    }

    /**
     * Filter out files that documentation tools cannot process.
     */
    private fun FileTree.addFilters(): FileTree {
        return matching {
            it.exclude("**/*.MF")
            it.exclude("**/*.aidl")
            it.exclude("**/META-INF/**")
            it.exclude("**/OWNERS")
            it.exclude("**/package.html")
        }
    }

    private fun configureDokka(
        unzippedDocsSources: File,
        unzipDocsTask: TaskProvider<Sync>,
        unzippedSamplesSources: File,
        unzipSamplesTask: TaskProvider<Sync>,
        dependencyClasspath: FileCollection
    ) {
        val dokkaTask = Dokka.createDokkaTask(
            project,
            "", // TODO(aurimas): remove type as that is now embedded in the project name
            hiddenPackages,
            "Kotlin",
            "dac",
            "/reference/kotlin"
        )
        dokkaTask.configure { task ->
            task.sourceDirs += unzippedDocsSources
            task.sourceDirs += unzippedSamplesSources
            task.dependsOn(unzipDocsTask)
            task.dependsOn(unzipSamplesTask)

            val androidJar = androidJarFile(project)
            // DokkaTask tries to resolve DokkaTask#classpath right away for jars that might not
            // be there yet. Delay the setting of this property to before we run the task.
            task.inputs.files(androidJar, dependencyClasspath)
            task.doFirst { dokkaTask ->
                dokkaTask as DokkaAndroidTask
                val packages =
                    unzippedSamplesSources.walkTopDown().filter { it.isFile }.mapNotNull { file ->
                        val lines = file.readLines()
                        lines.find { line ->
                            line.startsWith("package ")
                        }?.replace("package ", "")
                    }.distinct()

                packages.forEach { packageName ->
                    val opts = PackageOptions()
                    opts.prefix = packageName
                    opts.suppress = true
                    dokkaTask.perPackageOptions.add(opts)
                }

                dokkaTask.classpath = project.files(dokkaTask.classpath)
                    .plus(project.files(androidJar))
                    .plus(dependencyClasspath)
            }
        }
        val zipTask = project.tasks.register("zipDokkaDocs", Zip::class.java) {
            it.apply {
                it.dependsOn(dokkaTask)
                from(dokkaTask.map { it.outputDirectory }) { copySpec ->
                    copySpec.into("reference/kotlin")
                }
                val baseName = "dokka-$docsType-docs"
                val buildId = getBuildId()
                archiveBaseName.set(baseName)
                archiveVersion.set(buildId)
                destinationDirectory.set(project.getDistributionDirectory())
                group = JavaBasePlugin.DOCUMENTATION_GROUP
                val filePath = "${project.getDistributionDirectory().canonicalPath}/"
                val fileName = "$baseName-$buildId.zip"
                val destinationFile = filePath + fileName
                description = "Zips Java documentation (generated via Doclava in the " +
                    "style of d.android.com) into $destinationFile"
            }
        }
        project.addToBuildOnServer(zipTask)
    }

    private fun configureDoclava(
        unzippedDocsSources: File,
        unzipDocsTask: TaskProvider<Sync>,
        dependencyClasspath: FileCollection,
    ) {
        // Hack to force tools.jar (required by com.sun.javadoc) to be available on the Doclava
        // run-time classpath. Note this breaks the ability to use JDK 9+ for compilation.
        val doclavaConfiguration = project.configurations.create("doclava")
        doclavaConfiguration.dependencies.add(project.dependencies.create(DOCLAVA_DEPENDENCY))
        doclavaConfiguration.dependencies.add(
            project.dependencies.create(
                project.files(
                    SupportConfig.getJavaToolsJarPath()
                )
            )
        )

        val annotationConfiguration = project.configurations.create("annotation")
        annotationConfiguration.dependencies.add(
            project.dependencies.project(
                mapOf("path" to ":fakeannotations")
            )
        )

        val generatedSdk = File(project.buildDir, "generatedsdk")
        val generateSdkApiTask = createGenerateSdkApiTask(
            project, doclavaConfiguration, annotationConfiguration, generatedSdk
        )

        val destDir = File(project.buildDir, "javadoc")
        val offlineOverride = project.findProject("offlineDocs") as String?
        val offline = if (offlineOverride != null) { offlineOverride == "true" } else false
        val dacOptions = DacOptions("androidx", "ANDROIDX_DATA")

        val doclavaTask = project.tasks.register("doclavaDocs", DoclavaTask::class.java) {
            it.apply {
                dependsOn(unzipDocsTask)
                dependsOn(generateSdkApiTask)
                // Doclava does not know how to parse Kotlin files.
                exclude("**/*.kt")
                group = JavaBasePlugin.DOCUMENTATION_GROUP
                description = "Generates Java documentation in the style of d.android.com. To " +
                    "generate offline docs use \'-PofflineDocs=true\' parameter.  Places the " +
                    "documentation in $destDir"
                dependsOn(doclavaConfiguration)
                setDocletpath(doclavaConfiguration.resolve())
                destinationDir = destDir
                classpath = androidJarFile(project) + dependencyClasspath
                checksConfig = GENERATE_DOCS_CONFIG
                coreJavadocOptions {
                    addStringOption(
                        "templatedir",
                        "${project.getCheckoutRoot()}/external/doclava/res/assets/templates-sdk"
                    )
                    // Note, this is pointing to the root checkout directory.
                    addStringOption(
                        "samplesdir",
                        "${project.rootDir}/samples"
                    )
                    addMultilineMultiValueOption("federate").value = listOf(
                        listOf("Android", "https://developer.android.com")
                    )
                    addMultilineMultiValueOption("federationapi").value = listOf(
                        listOf("Android", generateSdkApiTask.get().apiFile?.absolutePath)
                    )
                    addMultilineMultiValueOption("hdf").value = listOf(
                        listOf("android.whichdoc", "online"),
                        listOf("android.hasSamples", "true"),
                        listOf("dac", "true")
                    )

                    // Specific to reference docs.
                    if (!offline) {
                        addStringOption("toroot", "/")
                        addBooleanOption("devsite", true)
                        addBooleanOption("yamlV2", true)
                        addStringOption("dac_libraryroot", dacOptions.libraryroot)
                        addStringOption("dac_dataname", dacOptions.dataname)
                    }
                }
                it.source(project.fileTree(unzippedDocsSources))
            }
        }
        val zipTask = project.tasks.register("zipDoclavaDocs", Zip::class.java) {
            it.apply {
                it.dependsOn(doclavaTask)
                from(doclavaTask.map { it.destinationDir!! })
                val baseName = "doclava-$docsType-docs"
                val buildId = getBuildId()
                archiveBaseName.set(baseName)
                archiveVersion.set(buildId)
                destinationDirectory.set(project.getDistributionDirectory())
                group = JavaBasePlugin.DOCUMENTATION_GROUP
                val filePath = "${project.getDistributionDirectory().canonicalPath}/"
                val fileName = "$baseName-$buildId.zip"
                val destinationFile = filePath + fileName
                description = "Zips Java documentation (generated via Doclava in the " +
                    "style of d.android.com) into $destinationFile"
            }
        }
        project.addToBuildOnServer(zipTask)
    }

    /**
     * Replace all tests etc with empty task, so we don't run anything
     * it is more effective then task.enabled = false, because we avoid executing deps as well
     */
    private fun disableUnneededTasks() {
        var reentrance = false
        project.tasks.whenTaskAdded { task ->
            if (task is Test || task.name.startsWith("assemble") ||
                task.name == "lint" ||
                task.name == "transformDexArchiveWithExternalLibsDexMergerForPublicDebug" ||
                task.name == "transformResourcesWithMergeJavaResForPublicDebug" ||
                task.name == "checkPublicDebugDuplicateClasses"
            ) {
                if (!reentrance) {
                    reentrance = true
                    project.tasks.named(task.name) {
                        it.actions = emptyList()
                        it.dependsOn(emptyList<Task>())
                    }
                    reentrance = false
                }
            }
        }
    }
}

private const val DOCLAVA_DEPENDENCY = "com.android:doclava:1.0.6"

private val hiddenPackages = listOf(
    "androidx.camera.camera2.impl",
    "androidx.camera.camera2.internal",
    "androidx.camera.camera2.internal.compat",
    "androidx.camera.camera2.internal.compat.params",
    "androidx.camera.core.impl",
    "androidx.camera.core.impl.annotation",
    "androidx.camera.core.impl.utils",
    "androidx.camera.core.impl.utils.executor",
    "androidx.camera.core.impl.utils.futures",
    "androidx.camera.core.internal",
    "androidx.camera.core.internal.utils",
    "androidx.compose.animation.core.samples",
    "androidx.compose.animation.samples",
    "androidx.compose.foundation.layout.samples",
    "androidx.compose.foundation.samples",
    "androidx.compose.foundation.text.samples",
    "androidx.compose.material.icons.samples",
    "androidx.compose.material.samples",
    "androidx.compose.runtime.livedata.samples",
    "androidx.compose.runtime.rxjava2.samples",
    "androidx.compose.runtime.samples",
    "androidx.compose.runtime.savedinstancestate.samples",
    "androidx.compose.ui.graphics.samples",
    "androidx.compose.ui.samples",
    "androidx.compose.ui.text.samples",
    "androidx.compose.ui.unit.samples",
    "androidx.core.internal",
    "androidx.navigation.compose.samples",
    "androidx.paging.samples",
    "androidx.paging.samples.java",
    "androidx.paging.samples.shared",
    "androidx.preference.internal",
    "androidx.wear.internal.widget.drawer",
    "androidx.webkit.internal",
    "androidx.work.impl",
    "androidx.work.impl.background",
    "androidx.work.impl.background.systemalarm",
    "androidx.work.impl.background.systemjob",
    "androidx.work.impl.constraints",
    "androidx.work.impl.constraints.controllers",
    "androidx.work.impl.constraints.trackers",
    "androidx.work.impl.model",
    "androidx.work.impl.utils",
    "androidx.work.impl.utils.futures",
    "androidx.work.impl.utils.taskexecutor",
    "sample",
    "sample.foo"
)