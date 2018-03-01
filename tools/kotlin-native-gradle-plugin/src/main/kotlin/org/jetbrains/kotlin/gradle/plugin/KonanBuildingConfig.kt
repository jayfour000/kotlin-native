/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.gradle.plugin

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Named
import org.gradle.api.Task
import org.gradle.api.internal.DefaultNamedDomainObjectSet
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.BasePlugin
import org.gradle.internal.reflect.Instantiator
import org.gradle.util.ConfigureUtil
import org.jetbrains.kotlin.gradle.plugin.tasks.KonanBuildingTask
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

/** Base class for all Kotlin/Native artifacts. */
abstract class KonanBuildingConfig<T: KonanBuildingTask>(private val name_: String,
                                                         type: Class<T>,
                                                         val project: ProjectInternal,
                                                         instantiator: Instantiator,
                                                         val targets: Iterable<String>)
    : KonanBuildingSpec, Named, DefaultNamedDomainObjectSet<T>(type, instantiator, { it.konanTarget.visibleName }) {

    override fun getName() = name_

    protected val targetToTask = mutableMapOf<KonanTarget, T>()

    internal val aggregateBuildTask: Task

    private val konanTargets: Iterable<KonanTarget>
        get() = project.hostManager.toKonanTargets(targets).distinct()

    init {
        for (target in konanTargets) {
            if (!project.hostManager.isEnabled(target)) {
                project.logger.warn("The target is not enabled on the current host: ${target.visibleName}")
                continue
            }
            if (!targetIsSupported(target)) {
                project.logger.warn("The target ${target.visibleName} is not supported by the artifact $name")
                continue
            }
            super.add(createTask(target))
        }
        aggregateBuildTask = createAggregateTask()
        createHostTaskIfDeclared()
    }

    protected open fun generateTaskName(target: KonanTarget) =
            "compileKonan${name.capitalize()}${target.visibleName.capitalize()}"

    protected open fun generateAggregateTaskName() =
            "compileKonan${name.capitalize()}"

    protected open fun generateHostTaskName() =
            "compileKonan${name.capitalize()}Host"

    protected abstract fun generateTaskDescription(task: T): String
    protected abstract fun generateAggregateTaskDescription(task: Task): String
    protected abstract fun generateHostTaskDescription(task: Task, hostTarget: KonanTarget): String

    protected abstract val defaultBaseDir: File

    protected open fun targetIsSupported(target: KonanTarget): Boolean = true

    override fun didAdd(toAdd: T) {
        super.didAdd(toAdd)

        assert(toAdd.konanTarget !in targetToTask)
        targetToTask[toAdd.konanTarget] = toAdd
    }

    data class OutputPlacement(val destinationDir: File, val artifactName: String)

    // There are two options for output placement.
    //      1. Gradle's build directory. We use it by default, e.g. if user runs gradle from command line.
    //         In this case all produced files has the same name but are placed in different directories
    //         depending on their targets (e.g. linux/foo.kexe and macbook/foo.kexe).
    //      2. Custom path provided by IDE. In this case CONFIGURATION_BUILD_DIR environment variable should
    //         contain a path to a destination directory. All produced files are placed in this directory and have
    //         different names depending on their target (e.g. foo/bar/baz_linux.kexe and foo/bar/baz_macbook.kexe).
    protected fun determineOutputPlacement(target: KonanTarget): OutputPlacement {
        val configurationBuildDir =  EnvironmentVariables.configurationBuildDir
        return if (configurationBuildDir != null) {
            OutputPlacement(configurationBuildDir,  "${name}_${target.visibleName}")
        } else {
            OutputPlacement(defaultBaseDir.targetSubdir(target), name)
        }
    }

    protected fun createTask(target: KonanTarget): T =
            project.tasks.create(generateTaskName(target), type) {
                val outputDescription = determineOutputPlacement(target)
                it.init(outputDescription.destinationDir, outputDescription.artifactName, target)
                it.group = BasePlugin.BUILD_GROUP
                it.description = generateTaskDescription(it)
            } ?: throw Exception("Cannot create task for target: ${target.visibleName}")

    protected fun createAggregateTask(): Task =
            project.tasks.create(generateAggregateTaskName()) { task ->
                task.group = BasePlugin.BUILD_GROUP
                task.description = generateAggregateTaskDescription(task)
                this.filter {
                    project.targetIsRequested(it.konanTarget)
                }.forEach {
                    task.dependsOn(it)
                }
                project.compileAllTask.dependsOn(task)
            }

    protected fun createHostTaskIfDeclared(): Task? =
            this[HostManager.host]?.let { hostBuild ->
                project.tasks.create(generateHostTaskName()) {
                    it.group = BasePlugin.BUILD_GROUP
                    it.description = generateHostTaskDescription(it, hostBuild.konanTarget)
                    it.dependsOn(hostBuild)
                }
            }

    internal operator fun get(target: KonanTarget) = targetToTask[target]

    fun getByTarget(target: String) = findByTarget(target) ?: throw NoSuchElementException("No such target for artifact $name: ${target}")
    fun findByTarget(target: String) = this[project.hostManager.targetByName(target)]

    fun getArtifactByTarget(target: String) = getByTarget(target).artifact
    fun findArtifactByTarget(target: String) = findByTarget(target)?.artifact

    // Common building DSL.

    override fun artifactName(name: String)  = forEach { it.artifactName(name) }

    fun baseDir(dir: Any) = forEach { it.destinationDir(project.file(dir).targetSubdir(it.konanTarget)) }

    override fun libraries(closure: Closure<Unit>) = forEach { it.libraries(closure) }
    override fun libraries(action: Action<KonanLibrariesSpec>) = forEach { it.libraries(action) }
    override fun libraries(configure: KonanLibrariesSpec.() -> Unit) = forEach { it.libraries(configure) }

    override fun noDefaultLibs(flag: Boolean) = forEach { it.noDefaultLibs(flag) }

    override fun dumpParameters(flag: Boolean) = forEach { it.dumpParameters(flag) }

    override fun extraOpts(vararg values: Any) = forEach { it.extraOpts(*values) }
    override fun extraOpts(values: List<Any>) = forEach { it.extraOpts(values) }

    fun dependsOn(vararg dependencies: Any?) = forEach { it.dependsOn(*dependencies) }

    fun target(targetString: String, configureAction: T.() -> Unit) {
        val target = project.hostManager.targetByName(targetString)

        if (!project.hostManager.isEnabled(target)) {
            project.logger.warn("Target '$targetString' of artifact '$name' is not supported on the current host")
            return
        }

        val task = this[target] ?:
                throw InvalidUserDataException("Target '$targetString' is not declared. Please add it into project.konanTasks list")
        task.configureAction()
    }
    fun target(targetString: String, configureAction: Action<T>) =
            target(targetString) { configureAction.execute(this) }
    fun target(targetString: String, configureAction: Closure<Unit>) =
            target(targetString, ConfigureUtil.configureUsing(configureAction))
}
