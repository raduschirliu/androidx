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

package androidx.build

import androidx.build.AndroidXRootPlugin.Companion.PREBUILT_OR_SNAPSHOT_EXT_NAME
import androidx.build.AndroidXRootPlugin.Companion.PROJECT_OR_ARTIFACT_EXT_NAME
import androidx.build.gradle.isRoot
import groovy.xml.DOMBuilder
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencySubstitution
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.kotlin.dsl.KotlinClosure1
import org.gradle.kotlin.dsl.extra
import java.net.URI
import java.net.URL

/**
 * This plugin is used in Playground projects and adds functionality like resolving to snapshot
 * artifacts instead of projects or allowing access to public maven repositories.
 */
@Suppress("unused") // used in Playground Projects
class AndroidXPlaygroundRootPlugin : Plugin<Project> {
    private lateinit var rootProject: Project

    /**
     * List of snapshot repositories to fetch AndroidX artifacts
     */
    private lateinit var repos: PlaygroundRepositories

    /**
     * The configuration for the plugin read from the gradle properties
     */
    private lateinit var config: PlaygroundProperties

    private val projectOrArtifactClosure = KotlinClosure1<String, Any>(
        function = {
            projectOrArtifact(this)
        }
    )

    private val prebuiltOrSnapshotClosure = KotlinClosure1<String, String>(
        function = {
            prebuiltOrSnapshot(this)
        }
    )

    override fun apply(target: Project) {
        if (!target.isRoot) {
            throw GradleException("This plugin should only be applied to root project")
        }
        if (!target.plugins.hasPlugin(AndroidXRootPlugin::class.java)) {
            throw GradleException(
                "Must apply AndroidXRootPlugin before applying AndroidXPlaygroundRootPlugin"
            )
        }
        rootProject = target
        config = PlaygroundProperties.load(rootProject)
        repos = PlaygroundRepositories(config)
        rootProject.repositories.addPlaygroundRepositories()
        rootProject.subprojects {
            configureSubProject(it)
        }
    }

    private fun configureSubProject(project: Project) {
        project.repositories.addPlaygroundRepositories()
        project.extra.set(PROJECT_OR_ARTIFACT_EXT_NAME, projectOrArtifactClosure)
        project.extra.set(PREBUILT_OR_SNAPSHOT_EXT_NAME, prebuiltOrSnapshotClosure)
        project.configurations.all { configuration ->
            configuration.resolutionStrategy.dependencySubstitution.all { substitution ->
                substitution.allowAndroidxSnapshotReplacement()
                substitution.replaceIfSnapshot()
            }
        }
    }

    /**
     * Returns a `project` if exists or the latest artifact coordinates if it doesn't.
     *
     * This can be used for optional dependencies in the playground settings.gradle files.
     *
     * @param path The project path
     * @return A Project instance if it exists or coordinates of the artifact if the project is not
     *         included in this build.
     */
    private fun projectOrArtifact(path: String): Any {
        val requested = rootProject.findProject(path)
        if (requested != null) {
            return requested
        } else {
            val sections = path.split(":")

            if (sections[0].isNotEmpty()) {
                throw GradleException(
                    "Expected projectOrArtifact path to start with empty section but got $path"
                )
            }

            // Typically androidx projects have 3 sections, compose has 4.
            if (sections.size >= 3) {
                val group = sections
                    // Filter empty sections as many declarations start with ':'
                    .filter { !it.isBlank() }
                    // Last element is the artifact.
                    .dropLast(1)
                    .joinToString(".")
                return "androidx.$group:${sections.last()}:$SNAPSHOT_MARKER"
            }

            throw GradleException("projectOrArtifact cannot find/replace project $path")
        }
    }

    private fun prebuiltOrSnapshot(path: String): String {
        val sections = path.split(":")

        if (sections.size != 3) {
            throw GradleException(
                "Expected prebuiltOrSnapshot path to be of the form " +
                    "<group>:<artifact>:<version>, but was $path"
            )
        }

        val group = sections[0]
        val artifact = sections[1]
        return "$group:$artifact:$SNAPSHOT_MARKER"
    }

    private fun DependencySubstitution.allowAndroidxSnapshotReplacement() {
        val requested = this.requested
        if (requested is ModuleComponentSelector && requested.group.startsWith("androidx") &&
            requested.version.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$"))
        ) {
            useTarget("${requested.group}:${requested.module}:${requested.version}+")
        }
    }

    private fun DependencySubstitution.replaceIfSnapshot() {
        val requested = this.requested
        if (requested is ModuleComponentSelector && requested.version == SNAPSHOT_MARKER) {
            val snapshotVersion = findSnapshotVersion(requested.group, requested.module)
            useTarget("${requested.group}:${requested.module}:$snapshotVersion")
        }
    }

    /**
     * Finds the snapshot version from the AndroidX snapshot repository.
     *
     * This is initially done by reading the maven-metadata from the snapshot repository.
     * The result of that query is cached in the build file so that subsequent build requests will
     * not need to access the network.
     */
    private fun findSnapshotVersion(group: String, module: String): String {
        val snapshotVersionCache = rootProject.buildDir.resolve(
            "snapshot-version-cache/${config.snapshotBuildId}"
        )
        val groupPath = group.replace('.', '/')
        val modulePath = module.replace('.', '/')
        val metadataCacheFile = snapshotVersionCache.resolve("$groupPath/$modulePath/version.txt")
        return if (metadataCacheFile.exists()) {
            metadataCacheFile.readText(Charsets.UTF_8)
        } else {
            val metadataUrl = "${repos.snapshots}/$groupPath/$modulePath/maven-metadata.xml"
            URL(metadataUrl).openStream().use {
                val parsedMetadata = DOMBuilder.parse(it.reader())
                val versionNodes = parsedMetadata.getElementsByTagName("latest")
                if (versionNodes.length != 1) {
                    throw GradleException(
                        "AndroidXPlaygroundRootPlugin#findSnapshotVersion expected exactly one " +
                            "latest version in $metadataUrl, but got ${versionNodes.length}"
                    )
                }
                val snapshotVersion = versionNodes.item(0).textContent
                metadataCacheFile.parentFile.mkdirs()
                metadataCacheFile.writeText(snapshotVersion, Charsets.UTF_8)
                snapshotVersion
            }
        }
    }

    private fun RepositoryHandler.addPlaygroundRepositories() {
        repos.all.forEach { repoUrl ->
            maven {
                it.url = URI(repoUrl)
                it.metadataSources {
                    it.mavenPom()
                    it.artifact()
                }
            }
        }
        google()
        mavenCentral()
        jcenter()
    }

    private class PlaygroundRepositories(
        props: PlaygroundProperties
    ) {
        val snapshots =
            "https://androidx.dev/snapshots/builds/${props.snapshotBuildId}/artifacts/repository"
        val metalava = "https://androidx.dev/metalava/builds/${props.metalavaBuildId}/artifacts" +
            "/repo/m2repository"
        val doclava = "https://androidx.dev/dokka/builds/${props.dokkaBuildId}/artifacts/repository"
        val all = listOf(snapshots, metalava, doclava)
    }

    private data class PlaygroundProperties(
        val snapshotBuildId: String,
        val metalavaBuildId: String,
        val dokkaBuildId: String
    ) {
        companion object {
            fun load(project: Project): PlaygroundProperties {
                return PlaygroundProperties(
                    snapshotBuildId = project.requireProperty(PLAYGROUND_SNAPSHOT_BUILD_ID),
                    metalavaBuildId = project.requireProperty(PLAYGROUND_METALAVA_BUILD_ID),
                    dokkaBuildId = project.requireProperty(PLAYGROUND_DOKKA_BUILD_ID)
                )
            }

            private fun Project.requireProperty(name: String): String {
                return checkNotNull(findProperty(name)) {
                    "missing $name property. It must be defined in the gradle.properties file"
                }.toString()
            }
        }
    }

    companion object {
        const val SNAPSHOT_MARKER = "REPLACE_WITH_SNAPSHOT"
    }
}
