package com.beust.kobalt.internal

import com.beust.kobalt.Args
import com.beust.kobalt.AsciiArt
import com.beust.kobalt.TaskResult
import com.beust.kobalt.api.*
import com.beust.kobalt.misc.Strings
import com.beust.kobalt.misc.kobaltError
import com.beust.kobalt.misc.log
import com.google.common.collect.ListMultimap
import com.google.common.collect.TreeMultimap
import java.util.*

/**
 * Build the projects in parallel.
 *
 * The projects are sorted in topological order and then run by the DynamicGraphExecutor in background threads
 * wherever appropriate. Inside a project, all the tasks are run sequentially.
 */
class ParallelProjectRunner(val tasksByNames: (Project) -> ListMultimap<String, ITask>,
        val dependsOn: TreeMultimap<String, String>,
        val reverseDependsOn: TreeMultimap<String, String>, val runBefore: TreeMultimap<String, String>,
        val runAfter: TreeMultimap<String, String>,
        val alwaysRunAfter: TreeMultimap<String, String>, val args: Args, val pluginInfo: PluginInfo)
            : BaseProjectRunner() {
    override fun runProjects(taskInfos: List<TaskManager.TaskInfo>, projects: List<Project>)
            : TaskManager .RunTargetResult {
        var result = TaskResult()
        val failedProjects = hashSetOf<String>()
        val messages = Collections.synchronizedList(arrayListOf<TaskManager.ProfilerInfo>())

        fun runProjectListeners(project: Project, context: KobaltContext, start: Boolean,
                status: ProjectBuildStatus = ProjectBuildStatus.SUCCESS) {
            context.pluginInfo.buildListeners.forEach {
                if (start) it.projectStart(project, context) else it.projectEnd(project, context, status)
            }
        }

        val context = Kobalt.context!!
        projects.forEach { project ->
            AsciiArt.logBox("Building ${project.name}")

            // Does the current project depend on any failed projects?
            val fp = project.dependsOn.filter {
                failedProjects.contains(it.name)
            }.map {
                it.name
            }

            if (fp.size > 0) {
                log(2, "Marking project ${project.name} as skipped")
                failedProjects.add(project.name)
                runProjectListeners(project, context, false, ProjectBuildStatus.SKIPPED)
                kobaltError("Not building project ${project.name} since it depends on failed "
                        + Strings.pluralize(fp.size, "project")
                        + " " + fp.joinToString(","))
            } else {
                runProjectListeners(project, context, true)

                // There can be multiple tasks by the same name (e.g. PackagingPlugin and AndroidPlugin both
                // define "install"), so use a multimap
                val tasksByNames = tasksByNames(project)

                log(3, "Tasks:")
                tasksByNames.keys().forEach {
                    log(3, "  $it: " + tasksByNames.get(it))
                }

                val graph = createTaskGraph(project.name, taskInfos, tasksByNames,
                        dependsOn, reverseDependsOn, runBefore, runAfter, alwaysRunAfter,
                        { task: ITask -> task.name },
                        { task: ITask -> task.plugin.accept(project) })

                //
                // Now that we have a full graph, run it
                //
                log(2, "About to run graph:\n  ${graph.dump()}  ")

                val factory = object : IThreadWorkerFactory<ITask> {
                    override fun createWorkers(nodes: Collection<ITask>)
                            = nodes.map { TaskWorker(listOf(it), args.dryRun, pluginInfo) }
                }

                val executor = DynamicGraphExecutor(graph, factory, 5)
                val thisResult = executor.run()
                if (! thisResult.success) {
                    log(2, "Marking project ${project.name} as failed")
                    failedProjects.add(project.name)
                }

                runProjectListeners(project, context, false,
                        if (thisResult.success) ProjectBuildStatus.SUCCESS else ProjectBuildStatus.FAILED)

                if (result.success) {
                    result = thisResult
                }
            }
        }

        return TaskManager.RunTargetResult(result, messages)
    }
}
